package org.nodriver4j.ui.dialogs;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.entity.ProfileGroupEntity;
import org.nodriver4j.persistence.entity.ProxyGroupEntity;
import org.nodriver4j.persistence.repository.ProfileGroupRepository;
import org.nodriver4j.persistence.repository.ProfileRepository;
import org.nodriver4j.persistence.repository.ProxyGroupRepository;
import org.nodriver4j.persistence.repository.ProxyRepository;

import java.util.*;

/**
 * Modal dialog for creating new tasks within a task group.
 *
 * <p>Features a unified profile selector with two navigable views:</p>
 * <ul>
 *   <li><strong>Group view</strong> — lists profile groups; click to drill in</li>
 *   <li><strong>Profile view</strong> — shows profiles in a group with checkboxes
 *       and a back button to return to the group list</li>
 * </ul>
 *
 * <p>Selected profiles appear as chips above the selector panel. Selections
 * persist when navigating between groups, enabling multi-group selection.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * CreateTaskDialog dialog = new CreateTaskDialog(
 *     ownerWindow,
 *     profileGroupRepository,
 *     profileRepository,
 *     proxyGroupRepository,
 *     proxyRepository
 * );
 *
 * Optional<CreateTaskDialog.Result> result = dialog.showAndWait();
 * result.ifPresent(r -> {
 *     // r.profileIds() - selected profile IDs
 *     // r.proxyGroupId() - selected proxy group ID (nullable)
 *     // r.warmSession() - whether to warm session
 * });
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display form for task creation options</li>
 *   <li>Unified profile selector with group/profile navigation</li>
 *   <li>Chips display of selected profiles (max 2 rows + overflow)</li>
 *   <li>Load profile groups and profiles from repositories</li>
 *   <li>Load proxy groups with counts from repositories</li>
 *   <li>Provide optional proxy group selection</li>
 *   <li>Validate that at least one profile is selected</li>
 *   <li>Return result as immutable record</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Creating TaskEntity objects (controller handles)</li>
 *   <li>Proxy assignment logic (controller handles)</li>
 *   <li>Database persistence (controller handles)</li>
 * </ul>
 */
public class CreateTaskDialog extends Dialog<CreateTaskDialog.Result> {

    // ==================== Result Record ====================

    /**
     * Immutable result of a successful dialog submission.
     *
     * @param profileIds   the selected profile IDs to create tasks for
     * @param proxyGroupId the proxy group ID to assign from (null if none selected)
     * @param warmSession  whether to warm the session with activity
     */
    public record Result(
            List<Long> profileIds,
            Long proxyGroupId,
            boolean warmSession
    ) {}

    // ==================== Profile List Item Types ====================

    /**
     * Sealed interface for items in the profile ListView.
     * Distinguishes between "Select All" and individual profiles.
     */
    private sealed interface ProfileListItem permits SelectAllItem, ProfileItem {
        String displayText();
    }

    /**
     * Represents the "Select All" item at the top of the list.
     */
    private record SelectAllItem() implements ProfileListItem {
        @Override
        public String displayText() {
            return "Select All";
        }
    }

    /**
     * Represents an individual profile in the list.
     */
    private record ProfileItem(ProfileEntity profile) implements ProfileListItem {
        @Override
        public String displayText() {
            String name = profile.displayName();
            String email = profile.emailAddress();
            if (email != null && !email.isBlank()) {
                return name + " (" + email + ")";
            }
            return name;
        }
    }

    // ==================== Repositories ====================

    private final ProfileGroupRepository profileGroupRepository;
    private final ProfileRepository profileRepository;
    private final ProxyGroupRepository proxyGroupRepository;
    private final ProxyRepository proxyRepository;

    // ==================== UI Components — Profile Selector ====================

    /** Chips display showing selected profiles above the selector panel. */
    private final FlowPane chipsContainer;

    /** Container that swaps between group view and profile view. */
    private final StackPane selectorPanel;

    /** Scrollable list of profile groups. */
    private final VBox groupListContainer;

    /** Container for profile view: header + profile ListView. */
    private final VBox profileViewContainer;

    /** The profile ListView with checkboxes (reused across group navigations). */
    private final ListView<ProfileListItem> profileListView;

    /** Label showing the current group name in profile view header. */
    private final Label profileViewGroupName;

    // ==================== UI Components — Proxy Group ====================

    /** Dropdown for selecting a proxy group (nullable — first item is "None"). */
    private final ComboBox<ProxyGroupEntity> proxyGroupComboBox;

    // TODO: Warm session checkbox

    // ==================== Selection State ====================

    /**
     * Tracks selection state for each profile by ID.
     * Persists across group navigations to support multi-group selection.
     */
    private final Map<Long, BooleanProperty> profileSelectionMap = new LinkedHashMap<>();

    /**
     * Maps profile IDs to their display text for chip rendering.
     * Populated alongside profileSelectionMap so chips can render without re-querying.
     */
    private final Map<Long, String> profileDisplayNames = new LinkedHashMap<>();

    /**
     * Selection state for the "Select All" checkbox within the current group.
     */
    private final BooleanProperty selectAllProperty = new SimpleBooleanProperty(false);

    /**
     * Flag to prevent recursive updates when programmatically changing selections.
     */
    private boolean updatingSelections = false;

    /**
     * The profile group currently being viewed in the profile list, or null
     * if the group list is showing.
     */
    private ProfileGroupEntity currentProfileGroup;

    /**
     * Cached profiles for the currently viewed group.
     * Used to check "Select All" state without re-querying.
     */
    private List<ProfileEntity> currentGroupProfiles = List.of();

    /**
     * Reference to the Create button for validation updates.
     */
    private Button createButton;

    // ==================== Constants ====================

    /** Maximum visible rows of chips before showing "+N more". */
    private static final int MAX_CHIP_ROWS = 2;

    /** Approximate chip height used to compute row cutoff. */
    private static final double CHIP_ROW_HEIGHT = 30.0;

    // ==================== Constructor ====================

    /**
     * Creates a new CreateTaskDialog.
     *
     * @param owner                  the owner window (dialog will be centered on this)
     * @param profileGroupRepository repository for loading profile groups
     * @param profileRepository      repository for loading profiles
     * @param proxyGroupRepository   repository for loading proxy groups
     * @param proxyRepository        repository for counting proxies per group
     */
    public CreateTaskDialog(
            Window owner,
            ProfileGroupRepository profileGroupRepository,
            ProfileRepository profileRepository,
            ProxyGroupRepository proxyGroupRepository,
            ProxyRepository proxyRepository
    ) {
        this.profileGroupRepository = profileGroupRepository;
        this.profileRepository = profileRepository;
        this.proxyGroupRepository = proxyGroupRepository;
        this.proxyRepository = proxyRepository;

        // Set owner for proper positioning
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        initStyle(StageStyle.TRANSPARENT);

        // Create UI components
        chipsContainer = createChipsContainer();
        groupListContainer = new VBox();
        profileListView = createProfileListView();
        profileViewGroupName = new Label();
        profileViewContainer = buildProfileViewContainer();
        selectorPanel = buildSelectorPanel();
        proxyGroupComboBox = createProxyGroupComboBox();

        // Build content
        DialogPane dialogPane = getDialogPane();
        dialogPane.setContent(buildContent());
        dialogPane.getStyleClass().add("dialog-pane");

        // Apply stylesheet
        dialogPane.getStylesheets().add(
                getClass().getResource("../css/dark-theme.css").toExternalForm()
        );

        // Add buttons
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType createButtonType = new ButtonType("Create Tasks", ButtonBar.ButtonData.OK_DONE);
        dialogPane.getButtonTypes().addAll(cancelButtonType, createButtonType);

        // Style the buttons
        Button cancelBtn = (Button) dialogPane.lookupButton(cancelButtonType);
        cancelBtn.getStyleClass().add("secondary");

        createButton = (Button) dialogPane.lookupButton(createButtonType);
        createButton.getStyleClass().add("primary");

        // Disable create button initially (no profiles selected)
        createButton.setDisable(true);

        // Set result converter
        setResultConverter(buttonType -> {
            if (buttonType == createButtonType) {
                return buildResult();
            }
            return null;
        });

        // Transparent scene for rounded corners
        setOnShown(event -> getDialogPane().getScene().setFill(Color.TRANSPARENT));

        // Load initial data and show group view
        loadGroupList();
        loadProxyGroups();
        showGroupView();
    }

    // ==================== UI Building ====================

    /**
     * Builds the main content layout wrapped in a ScrollPane.
     *
     * @return the scrollable content container
     */
    private ScrollPane buildContent() {
        VBox content = new VBox(4);
        content.setPadding(new Insets(24));
        content.setPrefWidth(460);

        // Title
        Label titleLabel = new Label("Create Tasks");
        titleLabel.getStyleClass().add("dialog-title");

        // Profile selector section
        VBox profileSection = buildProfileSelectorSection();

        // Proxy group section
        VBox proxySection = buildProxyGroupSection();

        // TODO: Add warm session checkbox

        content.getChildren().addAll(
                titleLabel,
                profileSection,
                proxySection
        );

        // Wrap in ScrollPane
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPrefViewportWidth(480);
        scrollPane.setMaxHeight(500);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        return scrollPane;
    }

    /**
     * Builds the unified profile selector section containing the chips
     * display and the navigable group/profile panel.
     *
     * @return the profile selector VBox
     */
    private VBox buildProfileSelectorSection() {
        Label label = new Label("Profiles");
        label.getStyleClass().add("form-label");

        VBox section = new VBox(6);
        section.getStyleClass().add("form-group");
        section.getChildren().addAll(label, chipsContainer, selectorPanel);

        return section;
    }

    /**
     * Builds the proxy group selection section with a ComboBox and hint label.
     *
     * <p>The ComboBox has a null first item representing "None". Each proxy group
     * is displayed with its proxy count (e.g., "Lightning Proxies (50 proxies)").</p>
     *
     * @return the proxy group section VBox
     */
    private VBox buildProxyGroupSection() {
        Label label = new Label("Proxy Group (Optional)");
        label.getStyleClass().add("form-label");

        Label hintLabel = new Label("First N proxies will be assigned to N tasks");
        hintLabel.getStyleClass().addAll("label", "muted");
        hintLabel.setStyle("-fx-font-size: 12px;");

        // Only show hint when a proxy group is actually selected
        hintLabel.setVisible(false);
        hintLabel.setManaged(false);

        proxyGroupComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            boolean hasSelection = newVal != null;
            hintLabel.setVisible(hasSelection);
            hintLabel.setManaged(hasSelection);
        });

        VBox section = new VBox(6);
        section.getStyleClass().add("form-group");
        section.getChildren().addAll(label, proxyGroupComboBox, hintLabel);

        return section;
    }

    /**
     * Builds the selector panel that swaps between group view and profile view.
     *
     * @return the configured StackPane
     */
    private StackPane buildSelectorPanel() {
        // Wrap the group list in a ScrollPane for long lists
        ScrollPane groupScrollPane = new ScrollPane(groupListContainer);
        groupScrollPane.setFitToWidth(true);
        groupScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        groupScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        groupScrollPane.setPrefViewportHeight(200);
        groupScrollPane.setMaxHeight(200);
        groupScrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        StackPane panel = new StackPane();
        panel.getChildren().addAll(groupScrollPane, profileViewContainer);

        // Style the panel with a border to look like a form field
        panel.setStyle(
                "-fx-border-color: -fx-border; " +
                        "-fx-border-width: 1px; " +
                        "-fx-border-radius: 8px; " +
                        "-fx-background-color: -fx-primary-dark; " +
                        "-fx-background-radius: 8px;"
        );

        return panel;
    }

    /**
     * Builds the profile view container with a back-button header
     * and the profile ListView below it.
     *
     * @return the profile view VBox
     */
    private VBox buildProfileViewContainer() {
        // Header: back button + group name
        Button backButton = new Button("←");
        backButton.getStyleClass().add("back-button");
        backButton.setStyle(
                "-fx-min-width: 28px; -fx-max-width: 28px; " +
                        "-fx-min-height: 28px; -fx-max-height: 28px; " +
                        "-fx-font-size: 14px; -fx-padding: 0;"
        );
        backButton.setOnAction(event -> showGroupView());

        profileViewGroupName.getStyleClass().add("label");
        profileViewGroupName.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 12, 4, 12));
        header.getChildren().addAll(backButton, profileViewGroupName);

        VBox container = new VBox();
        container.getChildren().addAll(header, profileListView);

        // Initially hidden (group view shows first)
        container.setVisible(false);
        container.setManaged(false);

        return container;
    }

    // ==================== Component Creation ====================

    /**
     * Creates the chips container for displaying selected profiles.
     *
     * <p>Uses a FlowPane that wraps children. Displays up to 2 rows of chips,
     * then a "+N more" label for overflow.</p>
     *
     * @return the configured FlowPane
     */
    private FlowPane createChipsContainer() {
        FlowPane pane = new FlowPane();
        pane.setHgap(6);
        pane.setVgap(4);
        pane.setPadding(new Insets(4, 0, 4, 0));
        pane.setMaxWidth(Double.MAX_VALUE);
        // Will be populated dynamically
        return pane;
    }

    /**
     * Creates the profile ListView with checkbox cells.
     *
     * <p>Uses a sealed interface pattern to distinguish "Select All" from
     * individual profile items, preventing the bug where all checkboxes
     * act like Select All.</p>
     *
     * @return the configured ListView
     */
    private ListView<ProfileListItem> createProfileListView() {
        ListView<ProfileListItem> listView = new ListView<>();
        listView.setPrefHeight(200);
        listView.setMaxWidth(Double.MAX_VALUE);
        listView.setStyle("-fx-background-color: transparent;");

        // Custom cell factory for checkboxes
        listView.setCellFactory(lv -> new ListCell<>() {
            private final CheckBox checkBox = new CheckBox();

            {
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                checkBox.setMaxWidth(Double.MAX_VALUE);
                setStyle("-fx-background-color: transparent;");
            }

            @Override
            protected void updateItem(ProfileListItem item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setGraphic(null);
                    checkBox.setOnAction(null);
                    return;
                }

                checkBox.setText(item.displayText());

                if (item instanceof SelectAllItem) {
                    checkBox.setStyle("-fx-font-weight: bold;");
                    checkBox.setSelected(selectAllProperty.get());
                    checkBox.setOnAction(event -> onSelectAllToggled(checkBox.isSelected()));
                } else if (item instanceof ProfileItem profileItem) {
                    checkBox.setStyle("");
                    long profileId = profileItem.profile().id();
                    BooleanProperty selectedProp = profileSelectionMap.get(profileId);
                    if (selectedProp != null) {
                        checkBox.setSelected(selectedProp.get());
                        checkBox.setOnAction(event -> onProfileToggled(profileId, checkBox.isSelected()));
                    }
                }

                setGraphic(checkBox);
            }
        });

        return listView;
    }

    /**
     * Creates the proxy group ComboBox with a custom StringConverter.
     *
     * <p>The ComboBox uses {@code null} as its first item to represent "None".
     * Each non-null item displays the group name followed by its proxy count
     * (e.g., "Lightning Proxies (50 proxies)").</p>
     *
     * <p>Proxy counts are queried from {@link ProxyRepository#countByGroupId(long)}
     * during display. This is acceptable since the converter is only called
     * when the dropdown is rendered, which happens infrequently.</p>
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
                // Not needed — ComboBox is not editable
                return null;
            }
        });

        return comboBox;
    }

    // ==================== Data Loading ====================

    /**
     * Loads all proxy groups into the ComboBox.
     *
     * <p>Inserts {@code null} as the first item to represent "None",
     * followed by all proxy groups from the database. The ComboBox
     * defaults to {@code null} (no proxy group selected).</p>
     */
    private void loadProxyGroups() {
        try {
            List<ProxyGroupEntity> groups = proxyGroupRepository.findAll();

            // null represents "None" — rendered by the StringConverter
            proxyGroupComboBox.getItems().add(null);
            proxyGroupComboBox.getItems().addAll(groups);

            // Default to "None"
            proxyGroupComboBox.getSelectionModel().selectFirst();

        } catch (Exception e) {
            System.err.println("[CreateTaskDialog] Failed to load proxy groups: " + e.getMessage());
            // ComboBox stays empty — user can still create tasks without proxies
        }
    }

    // ==================== Navigation ====================

    /**
     * Switches the selector panel to show the group list view.
     *
     * <p>Hides the profile view and shows the group list. The group list
     * is refreshed to show updated selection counts.</p>
     */
    private void showGroupView() {
        currentProfileGroup = null;
        currentGroupProfiles = List.of();

        profileViewContainer.setVisible(false);
        profileViewContainer.setManaged(false);

        // The group scroll pane is the first child of selectorPanel
        selectorPanel.getChildren().getFirst().setVisible(true);
        ((Region) selectorPanel.getChildren().getFirst()).setManaged(true);

        // Refresh group rows to show updated selection counts
        refreshGroupRows();
    }

    /**
     * Switches the selector panel to show profiles for the given group.
     *
     * <p>Loads profiles from the repository and populates the ListView.
     * Existing selections for profiles in this group are preserved.</p>
     *
     * @param group the profile group to display
     */
    private void showProfileView(ProfileGroupEntity group) {
        currentProfileGroup = group;
        profileViewGroupName.setText(group.name());

        // Load profiles for this group
        try {
            currentGroupProfiles = profileRepository.findByGroupId(group.id());
        } catch (Exception e) {
            System.err.println("[CreateTaskDialog] Failed to load profiles: " + e.getMessage());
            currentGroupProfiles = List.of();
        }

        // Ensure each profile has a selection entry (preserves existing selections)
        for (ProfileEntity profile : currentGroupProfiles) {
            profileSelectionMap.computeIfAbsent(profile.id(), k -> {
                BooleanProperty prop = new SimpleBooleanProperty(false);
                return prop;
            });
            profileDisplayNames.putIfAbsent(profile.id(), formatProfileDisplay(profile));
        }

        // Update Select All state based on current selections for THIS group
        updateSelectAllState();

        // Build list items
        profileListView.getItems().clear();
        if (!currentGroupProfiles.isEmpty()) {
            profileListView.getItems().add(new SelectAllItem());
            for (ProfileEntity profile : currentGroupProfiles) {
                profileListView.getItems().add(new ProfileItem(profile));
            }
        }

        // Swap views
        selectorPanel.getChildren().getFirst().setVisible(false);
        ((Region) selectorPanel.getChildren().getFirst()).setManaged(false);

        profileViewContainer.setVisible(true);
        profileViewContainer.setManaged(true);
    }

    // ==================== Group List ====================

    /**
     * Loads all profile groups from the repository and populates the group list.
     */
    private void loadGroupList() {
        groupListContainer.getChildren().clear();
        groupListContainer.setSpacing(2);
        groupListContainer.setPadding(new Insets(4));

        try {
            List<ProfileGroupEntity> groups = profileGroupRepository.findAll();

            if (groups.isEmpty()) {
                Label emptyLabel = new Label("No profile groups found");
                emptyLabel.getStyleClass().addAll("label", "muted");
                emptyLabel.setPadding(new Insets(16));
                emptyLabel.setMaxWidth(Double.MAX_VALUE);
                emptyLabel.setAlignment(Pos.CENTER);
                groupListContainer.getChildren().add(emptyLabel);
                return;
            }

            for (ProfileGroupEntity group : groups) {
                groupListContainer.getChildren().add(buildGroupRow(group));
            }

        } catch (Exception e) {
            System.err.println("[CreateTaskDialog] Failed to load profile groups: " + e.getMessage());
            Label errorLabel = new Label("Error loading groups");
            errorLabel.getStyleClass().addAll("label", "muted");
            errorLabel.setPadding(new Insets(16));
            groupListContainer.getChildren().add(errorLabel);
        }
    }

    /**
     * Builds a clickable row for a profile group in the group list.
     *
     * <p>Shows the group name, profile count, and number of selected profiles.
     * Clicking navigates to the profile view for that group.</p>
     *
     * @param group the profile group
     * @return the configured HBox row
     */
    private HBox buildGroupRow(ProfileGroupEntity group) {
        long profileCount = profileRepository.countByGroupId(group.id());

        Label nameLabel = new Label(group.name());
        nameLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: -fx-text-primary;");

        Label countLabel = new Label(profileCount + " profiles");
        countLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -fx-text-muted;");

        VBox textBox = new VBox(2);
        textBox.getChildren().addAll(nameLabel, countLabel);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Label arrow = new Label("›");
        arrow.setStyle("-fx-font-size: 18px; -fx-text-fill: -fx-text-muted;");

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));
        row.getChildren().addAll(textBox, arrow);
        row.setStyle(
                "-fx-cursor: hand; " +
                        "-fx-background-radius: 6px;"
        );

        // Hover effect
        row.setOnMouseEntered(e -> row.setStyle(
                "-fx-cursor: hand; " +
                        "-fx-background-color: -fx-hover; " +
                        "-fx-background-radius: 6px;"
        ));
        row.setOnMouseExited(e -> row.setStyle(
                "-fx-cursor: hand; " +
                        "-fx-background-radius: 6px;"
        ));

        // Click to navigate to profile view
        row.setOnMouseClicked(event -> showProfileView(group));

        // Store reference for refresh
        row.setUserData(group);

        return row;
    }

    /**
     * Refreshes group rows to show updated selection counts.
     *
     * <p>Called when returning to group view to reflect any selection
     * changes made in the profile view.</p>
     */
    private void refreshGroupRows() {
        for (var node : groupListContainer.getChildren()) {
            if (node instanceof HBox row && row.getUserData() instanceof ProfileGroupEntity group) {
                // Find the count label (second child in the VBox)
                VBox textBox = (VBox) row.getChildren().getFirst();
                if (textBox.getChildren().size() >= 2) {
                    Label countLabel = (Label) textBox.getChildren().get(1);

                    long totalCount = profileRepository.countByGroupId(group.id());
                    long selectedCount = countSelectedInGroup(group.id());

                    if (selectedCount > 0) {
                        countLabel.setText(selectedCount + " / " + totalCount + " selected");
                        countLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -fx-success;");
                    } else {
                        countLabel.setText(totalCount + " profiles");
                        countLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -fx-text-muted;");
                    }
                }
            }
        }
    }

    /**
     * Counts how many profiles from a given group are currently selected.
     *
     * @param groupId the profile group ID
     * @return the number of selected profiles in that group
     */
    private long countSelectedInGroup(long groupId) {
        try {
            List<ProfileEntity> profiles = profileRepository.findByGroupId(groupId);
            return profiles.stream()
                    .filter(p -> {
                        BooleanProperty prop = profileSelectionMap.get(p.id());
                        return prop != null && prop.get();
                    })
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== Chips Display ====================

    /**
     * Rebuilds the chips container to reflect current selections.
     *
     * <p>Shows up to 2 rows of profile chips with "×" remove buttons.
     * If selections overflow, a "+N more" label is shown at the start
     * of a 3rd row. If nothing is selected, the container is empty
     * (placeholder text could be added here if desired).</p>
     */
    private void refreshChips() {
        chipsContainer.getChildren().clear();

        // Collect selected profile IDs in insertion order
        List<Long> selectedIds = profileSelectionMap.entrySet().stream()
                .filter(e -> e.getValue().get())
                .map(Map.Entry::getKey)
                .toList();

        if (selectedIds.isEmpty()) {
            Label placeholder = new Label("No profiles selected");
            placeholder.setStyle("-fx-text-fill: -fx-text-muted; -fx-font-size: 12px;");
            chipsContainer.getChildren().add(placeholder);
            return;
        }

        // Estimate how many chips fit in 2 rows
        // Approximate: each chip ~150px wide, container ~430px wide → ~2-3 per row → ~5-6 in 2 rows
        double containerWidth = 420;
        double avgChipWidth = 160;
        int chipsPerRow = Math.max(1, (int) (containerWidth / avgChipWidth));
        int maxVisible = chipsPerRow * MAX_CHIP_ROWS;

        int visibleCount = Math.min(selectedIds.size(), maxVisible);
        int overflowCount = selectedIds.size() - visibleCount;

        for (int i = 0; i < visibleCount; i++) {
            long profileId = selectedIds.get(i);
            String displayName = profileDisplayNames.getOrDefault(profileId, "Profile #" + profileId);
            chipsContainer.getChildren().add(buildChip(profileId, displayName));
        }

        if (overflowCount > 0) {
            Label overflow = new Label("+" + overflowCount + " more");
            overflow.setStyle(
                    "-fx-text-fill: -fx-text-muted; " +
                            "-fx-font-size: 11px; " +
                            "-fx-padding: 4px 8px;"
            );
            chipsContainer.getChildren().add(overflow);
        }
    }

    /**
     * Builds a single chip for a selected profile.
     *
     * <p>Shows a truncated display name and a "×" button to deselect.</p>
     *
     * @param profileId   the profile ID
     * @param displayName the profile display text
     * @return the chip HBox
     */
    private HBox buildChip(long profileId, String displayName) {
        // Truncate long names
        String truncated = displayName.length() > 25
                ? displayName.substring(0, 22) + "..."
                : displayName;

        Label nameLabel = new Label(truncated);
        nameLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-primary;");

        Label removeBtn = new Label("×");
        removeBtn.setStyle(
                "-fx-font-size: 13px; -fx-text-fill: -fx-text-muted; " +
                        "-fx-cursor: hand; -fx-padding: 0 0 0 4px;"
        );
        removeBtn.setOnMouseClicked(event -> {
            BooleanProperty prop = profileSelectionMap.get(profileId);
            if (prop != null) {
                prop.set(false);
                refreshChips();
                updateSelectAllState();
                profileListView.refresh();
                validateAndUpdateButton();
            }
        });

        HBox chip = new HBox(2);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setPadding(new Insets(3, 8, 3, 8));
        chip.getChildren().addAll(nameLabel, removeBtn);
        chip.setStyle(
                "-fx-background-color: -fx-primary-light; " +
                        "-fx-background-radius: 12px; " +
                        "-fx-border-color: -fx-border; " +
                        "-fx-border-radius: 12px; " +
                        "-fx-border-width: 1px;"
        );

        return chip;
    }

    // ==================== Selection Handlers ====================

    /**
     * Called when the "Select All" checkbox is toggled.
     *
     * <p>Only affects profiles in the currently viewed group.</p>
     *
     * @param selected true if Select All is now checked
     */
    private void onSelectAllToggled(boolean selected) {
        if (updatingSelections) {
            return;
        }

        updatingSelections = true;
        try {
            selectAllProperty.set(selected);

            // Update selections for all profiles in the CURRENT group only
            for (ProfileEntity profile : currentGroupProfiles) {
                BooleanProperty prop = profileSelectionMap.get(profile.id());
                if (prop != null) {
                    prop.set(selected);
                }
            }

            profileListView.refresh();
            refreshChips();
            validateAndUpdateButton();
        } finally {
            updatingSelections = false;
        }
    }

    /**
     * Called when an individual profile checkbox is toggled.
     *
     * @param profileId the profile ID
     * @param selected  true if the profile is now checked
     */
    private void onProfileToggled(long profileId, boolean selected) {
        if (updatingSelections) {
            return;
        }

        updatingSelections = true;
        try {
            BooleanProperty prop = profileSelectionMap.get(profileId);
            if (prop != null) {
                prop.set(selected);
            }

            // Update "Select All" state for the current group
            updateSelectAllState();

            profileListView.refresh();
            refreshChips();
            validateAndUpdateButton();
        } finally {
            updatingSelections = false;
        }
    }

    /**
     * Updates the Select All property based on whether all profiles
     * in the current group are selected.
     */
    private void updateSelectAllState() {
        if (currentGroupProfiles.isEmpty()) {
            selectAllProperty.set(false);
            return;
        }

        boolean allSelected = currentGroupProfiles.stream()
                .allMatch(p -> {
                    BooleanProperty prop = profileSelectionMap.get(p.id());
                    return prop != null && prop.get();
                });
        selectAllProperty.set(allSelected);
    }

    // ==================== Validation ====================

    /**
     * Validates the form and enables/disables the create button.
     *
     * <p>The create button is enabled when at least one profile is selected.</p>
     */
    private void validateAndUpdateButton() {
        if (createButton == null) {
            return;
        }

        boolean hasSelection = profileSelectionMap.values().stream()
                .anyMatch(BooleanProperty::get);

        createButton.setDisable(!hasSelection);
    }

    // ==================== Result Building ====================

    /**
     * Builds the result from the current form state.
     *
     * @return the Result record, or null if no profiles selected
     */
    private Result buildResult() {
        List<Long> profileIds = profileSelectionMap.entrySet().stream()
                .filter(entry -> entry.getValue().get())
                .map(Map.Entry::getKey)
                .toList();

        if (profileIds.isEmpty()) {
            return null;
        }

        // Read selected proxy group (null means "None")
        ProxyGroupEntity selectedProxyGroup = proxyGroupComboBox.getValue();
        Long proxyGroupId = (selectedProxyGroup != null) ? selectedProxyGroup.id() : null;

        // TODO: Get warm session checkbox state
        boolean warmSession = false;

        return new Result(profileIds, proxyGroupId, warmSession);
    }

    // ==================== Utility ====================

    /**
     * Formats a profile for display as "Name (email)" or just "Name".
     *
     * @param profile the profile entity
     * @return the formatted display string
     */
    private String formatProfileDisplay(ProfileEntity profile) {
        String name = profile.displayName();
        String email = profile.emailAddress();
        if (email != null && !email.isBlank()) {
            return name + " (" + email + ")";
        }
        return name;
    }
}