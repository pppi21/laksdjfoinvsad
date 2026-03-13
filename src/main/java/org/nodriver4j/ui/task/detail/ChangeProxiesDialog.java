package org.nodriver4j.ui.task.detail;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.nodriver4j.persistence.entity.ProxyGroupEntity;
import org.nodriver4j.persistence.repository.ProxyGroupRepository;
import org.nodriver4j.persistence.repository.ProxyRepository;
import org.nodriver4j.ui.util.SmoothScrollHelper;

import java.util.List;

/**
 * Modal dialog for selecting a proxy group to reassign across all tasks
 * in the current task group.
 *
 * <p>Features a proxy group ComboBox with proxy counts and a dynamic hint
 * label that shows how many proxies will be assigned relative to the
 * number of tasks in the group. Selecting "None" clears all proxy
 * assignments from the tasks.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ChangeProxiesDialog dialog = new ChangeProxiesDialog(
 *     ownerWindow,
 *     proxyGroupRepository,
 *     proxyRepository,
 *     20  // number of tasks in the group
 * );
 *
 * Optional<ChangeProxiesDialog.Result> result = dialog.showAndWait();
 * result.ifPresent(r -> {
 *     // r.proxyGroupId() — selected proxy group ID (null = "None")
 * });
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display proxy group ComboBox with counts</li>
 *   <li>Show dynamic hint label for proxy-to-task assignment</li>
 *   <li>Provide optional "None" selection to clear proxies</li>
 *   <li>Return result as immutable record</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Loading proxies from the selected group (controller handles)</li>
 *   <li>Reassigning proxy IDs on tasks (controller handles)</li>
 *   <li>Cleaning up standalone proxies (controller handles)</li>
 *   <li>Database persistence (controller handles)</li>
 * </ul>
 *
 * @see CreateTaskDialog
 * @see EditTaskDialog
 */
public class ChangeProxiesDialog extends Dialog<ChangeProxiesDialog.Result> {

    // ==================== Result Record ====================

    /**
     * Immutable result of a successful dialog submission.
     *
     * @param proxyGroupId the selected proxy group ID (null means "None" — clear all proxies)
     */
    public record Result(Long proxyGroupId) {}

    // ==================== Repositories ====================

    private final ProxyGroupRepository proxyGroupRepository;
    private final ProxyRepository proxyRepository;

    // ==================== UI Components ====================

    /** Dropdown for selecting a proxy group (nullable — first item is "None"). */
    private final ComboBox<ProxyGroupEntity> proxyGroupComboBox;

    /** Hint label showing proxy assignment info below the ComboBox. */
    private final Label proxyHintLabel = new Label();

    // ==================== State ====================

    /** Number of tasks in the current task group. Used for hint computation. */
    private final int taskCount;

    // ==================== Constructor ====================

    /**
     * Creates a new ChangeProxiesDialog.
     *
     * @param owner                  the owner window (dialog will be centered on this)
     * @param proxyGroupRepository   repository for loading proxy groups
     * @param proxyRepository        repository for counting proxies per group
     * @param taskCount              the number of tasks in the current task group
     */
    public ChangeProxiesDialog(
            Window owner,
            ProxyGroupRepository proxyGroupRepository,
            ProxyRepository proxyRepository,
            int taskCount
    ) {
        this.proxyGroupRepository = proxyGroupRepository;
        this.proxyRepository = proxyRepository;
        this.taskCount = taskCount;

        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        initStyle(StageStyle.TRANSPARENT);

        // Create UI components
        proxyGroupComboBox = createProxyGroupComboBox();

        // Build content
        DialogPane dialogPane = getDialogPane();
        dialogPane.setContent(buildContent());
        dialogPane.getStyleClass().add("dialog-pane");

        // Apply stylesheet
        dialogPane.getStylesheets().add(
                getClass().getResource("/org/nodriver4j/ui/css/dark-theme.css").toExternalForm()
        );

        // Add buttons
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType confirmButtonType = new ButtonType("Change Proxies", ButtonBar.ButtonData.OK_DONE);
        dialogPane.getButtonTypes().addAll(cancelButtonType, confirmButtonType);

        // Style buttons
        Button cancelBtn = (Button) dialogPane.lookupButton(cancelButtonType);
        cancelBtn.getStyleClass().add("secondary");

        Button confirmBtn = (Button) dialogPane.lookupButton(confirmButtonType);
        confirmBtn.getStyleClass().add("primary");

        // Set result converter
        setResultConverter(buttonType -> {
            if (buttonType == confirmButtonType) {
                return buildResult();
            }
            return null;
        });

        // Transparent scene for rounded corners
        setOnShown(event -> getDialogPane().getScene().setFill(Color.TRANSPARENT));

        // Load proxy groups into ComboBox
        loadProxyGroups();
        SmoothScrollHelper.apply(proxyGroupComboBox);
    }

    // ==================== UI Building ====================

    /**
     * Builds the main content layout.
     *
     * @return the content VBox
     */
    private VBox buildContent() {
        VBox content = new VBox(4);
        content.setPadding(new Insets(24));
        content.setPrefWidth(420);

        // Title
        Label titleLabel = new Label("Change Proxies");
        titleLabel.getStyleClass().add("dialog-title");

        content.getChildren().addAll(
                titleLabel,
                buildProxyGroupSection()
        );

        return content;
    }

    /**
     * Builds the proxy group selection section with ComboBox and hint label.
     *
     * @return the proxy group section VBox
     */
    private VBox buildProxyGroupSection() {
        Label label = new Label("Proxy Group");
        label.getStyleClass().add("form-label");

        proxyHintLabel.getStyleClass().addAll("label", "muted");
        proxyHintLabel.setStyle("-fx-font-size: 12px;");
        proxyHintLabel.setVisible(false);
        proxyHintLabel.setManaged(false);

        // Update hint when selection changes
        proxyGroupComboBox.valueProperty().addListener((obs, oldVal, newVal) -> refreshProxyHint());

        VBox section = new VBox(6);
        section.getStyleClass().add("form-group");
        section.getChildren().addAll(label, proxyGroupComboBox, proxyHintLabel);

        return section;
    }

    // ==================== Component Creation ====================

    /**
     * Creates the proxy group ComboBox with a custom StringConverter.
     *
     * <p>Uses {@code null} as the first item to represent "None".
     * Each non-null item displays the group name followed by its proxy count
     * (e.g., "Lightning Proxies (50 proxies)").</p>
     *
     * @return the configured ComboBox
     */
    private ComboBox<ProxyGroupEntity> createProxyGroupComboBox() {
        ComboBox<ProxyGroupEntity> comboBox = new ComboBox<>();
        comboBox.setMaxWidth(Double.MAX_VALUE);
        comboBox.setPrefHeight(40);
        comboBox.setPromptText("None");

        comboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(ProxyGroupEntity group) {
                if (group == null) {
                    return "None";
                }
                long count = proxyRepository.countByGroupId(group.id());
                String suffix = (count == 1) ? " proxy" : " proxies";
                return group.name() + " (" + count + suffix + ")";
            }

            @Override
            public ProxyGroupEntity fromString(String string) {
                return null; // Not needed — ComboBox is not editable
            }
        });

        return comboBox;
    }

    // ==================== Data Loading ====================

    /**
     * Loads all proxy groups into the ComboBox.
     *
     * <p>Inserts {@code null} as the first item to represent "None",
     * followed by all proxy groups from the database. Defaults to
     * {@code null} (no proxy group selected).</p>
     */
    private void loadProxyGroups() {
        try {
            List<ProxyGroupEntity> groups = proxyGroupRepository.findAll();

            proxyGroupComboBox.getItems().add(null);
            proxyGroupComboBox.getItems().addAll(groups);
            proxyGroupComboBox.getSelectionModel().selectFirst();

        } catch (Exception e) {
            System.err.println("[ChangeProxiesDialog] Failed to load proxy groups: " + e.getMessage());
        }
    }

    // ==================== Hint Label ====================

    /**
     * Updates the proxy hint label based on the selected proxy group
     * and the number of tasks in the current group.
     *
     * <p>Shows nothing when "None" is selected. Otherwise displays
     * how many proxies will actually be assigned relative to the
     * task count.</p>
     */
    private void refreshProxyHint() {
        ProxyGroupEntity selectedGroup = proxyGroupComboBox.getValue();

        if (selectedGroup == null) {
            proxyHintLabel.setVisible(false);
            proxyHintLabel.setManaged(false);
            return;
        }

        long proxyCount = proxyRepository.countByGroupId(selectedGroup.id());

        String text;
        if (taskCount == 0) {
            text = proxyCount + " proxies available — no tasks to assign";
        } else if (proxyCount >= taskCount) {
            text = "First " + taskCount + " proxies will be assigned to "
                    + taskCount + (taskCount == 1 ? " task" : " tasks");
        } else {
            text = proxyCount + (proxyCount == 1 ? " proxy" : " proxies")
                    + " will be assigned to the first " + proxyCount
                    + (proxyCount == 1 ? " task" : " tasks")
                    + " — " + (taskCount - proxyCount) + " will remain unchanged";
        }

        proxyHintLabel.setText(text);
        proxyHintLabel.setVisible(true);
        proxyHintLabel.setManaged(true);
    }

    // ==================== Result Building ====================

    /**
     * Builds the result from the current form state.
     *
     * @return the Result record
     */
    private Result buildResult() {
        ProxyGroupEntity selectedGroup = proxyGroupComboBox.getValue();
        Long proxyGroupId = (selectedGroup != null) ? selectedGroup.id() : null;
        return new Result(proxyGroupId);
    }
}