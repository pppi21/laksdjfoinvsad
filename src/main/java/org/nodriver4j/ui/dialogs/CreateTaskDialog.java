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
import javafx.stage.Popup;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.entity.ProfileGroupEntity;
import org.nodriver4j.persistence.repository.ProfileGroupRepository;
import org.nodriver4j.persistence.repository.ProfileRepository;
import org.nodriver4j.persistence.repository.ProxyGroupRepository;
import org.nodriver4j.persistence.repository.ProxyRepository;

import java.util.*;

/**
 * Modal dialog for creating new tasks within a task group.
 *
 * <p>Allows users to:</p>
 * <ul>
 *   <li>Select a profile group, then select individual profiles via checkboxes</li>
 *   <li>Optionally select a proxy group (proxies assigned sequentially)</li>
 *   <li>Enable "warm session" option for browser activity before automation</li>
 * </ul>
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
 *   <li>Load profile groups and profiles from repositories</li>
 *   <li>Load proxy groups with counts from repositories</li>
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

    // ==================== UI Components ====================

    private final ComboBox<ProfileGroupEntity> profileGroupComboBox;
    private final ListView<ProfileListItem> profileListView;
    private final Label profileListPlaceholder;

    // ==================== Selection State ====================

    /**
     * Tracks selection state for each profile by ID.
     * Key = profile ID, Value = selected property.
     */
    private final Map<Long, BooleanProperty> profileSelectionMap = new HashMap<>();

    /**
     * Selection state for the "Select All" checkbox.
     */
    private final BooleanProperty selectAllProperty = new SimpleBooleanProperty(false);

    /**
     * Flag to prevent recursive updates when programmatically changing selections.
     */
    private boolean updatingSelections = false;

    /**
     * Reference to the Create button for validation updates.
     */
    private Button createButton;

    // TODO: Proxy Group dropdown (after profile list)
    // TODO: Warm session checkbox (after proxy dropdown)

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
        profileGroupComboBox = createProfileGroupComboBox();
        profileListView = createProfileListView();
        profileListPlaceholder = createProfileListPlaceholder();

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

        // Add validation listeners
        profileGroupComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            onProfileGroupSelected(newVal);
            validateAndUpdateButton();
        });

        // Set result converter
        setResultConverter(buttonType -> {
            if (buttonType == createButtonType) {
                return buildResult();
            }
            return null;
        });

        // Transparent scene for rounded corners
        setOnShown(event -> getDialogPane().getScene().setFill(Color.TRANSPARENT));

        // Focus profile group dropdown when dialog opens
        Platform.runLater(() -> profileGroupComboBox.requestFocus());

        // Load initial data
        loadProfileGroups();
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

        // Profile Group dropdown
        VBox profileGroupSection = buildProfileGroupSection();

        // Profile ListView with checkboxes
        VBox profileListSection = buildProfileListSection();

        // TODO: Add proxy group section
        // TODO: Add warm session checkbox

        content.getChildren().addAll(
                titleLabel,
                profileGroupSection,
                profileListSection
                // More sections will be added here
        );

        // Wrap in ScrollPane for scrollable dialog
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
     * Builds the profile group dropdown section.
     *
     * @return the profile group VBox
     */
    private VBox buildProfileGroupSection() {
        Label label = new Label("Profile Group");
        label.getStyleClass().add("form-label");

        VBox section = new VBox();
        section.getStyleClass().add("form-group");
        section.getChildren().addAll(label, profileGroupComboBox);

        return section;
    }

    /**
     * Creates the profile group ComboBox.
     *
     * @return the configured ComboBox
     */
    private ComboBox<ProfileGroupEntity> createProfileGroupComboBox() {
        ComboBox<ProfileGroupEntity> comboBox = new ComboBox<>();
        comboBox.setPromptText("Select a profile group...");
        comboBox.setPrefHeight(40);
        comboBox.setMaxWidth(Double.MAX_VALUE);

        // Custom converter to display group name with profile count
        comboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(ProfileGroupEntity group) {
                if (group == null) {
                    return null;
                }
                long count = profileRepository.countByGroupId(group.id());
                return group.name() + " (" + count + " profiles)";
            }

            @Override
            public ProfileGroupEntity fromString(String string) {
                // Not needed for display-only
                return null;
            }
        });

        return comboBox;
    }

    /**
     * Builds the profile list section with ListView.
     *
     * @return the profile list VBox
     */
    private VBox buildProfileListSection() {
        Label label = new Label("Profiles");
        label.getStyleClass().add("form-label");

        // Stack the ListView and placeholder (only one visible at a time)
        VBox listContainer = new VBox();
        listContainer.getChildren().addAll(profileListView, profileListPlaceholder);

        // Initially show placeholder, hide list
        profileListView.setVisible(false);
        profileListView.setManaged(false);
        profileListPlaceholder.setVisible(true);
        profileListPlaceholder.setManaged(true);

        VBox section = new VBox();
        section.getStyleClass().add("form-group");
        section.getChildren().addAll(label, listContainer);

        return section;
    }

    /**
     * Creates the profile ListView with checkbox cells.
     *
     * @return the configured ListView
     */
    private ListView<ProfileListItem> createProfileListView() {
        ListView<ProfileListItem> listView = new ListView<>();
        listView.setPrefHeight(180);
        listView.setMaxWidth(Double.MAX_VALUE);

        // Custom cell factory for checkboxes
        listView.setCellFactory(lv -> new ListCell<>() {
            private final CheckBox checkBox = new CheckBox();

            {
                // Prevent the cell itself from being selectable
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                checkBox.setMaxWidth(Double.MAX_VALUE);
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

                // Bind checkbox behavior based on item type
                if (item instanceof SelectAllItem) {
                    // "Select All" checkbox
                    checkBox.setStyle("-fx-font-weight: bold;");
                    checkBox.setSelected(selectAllProperty.get());
                    checkBox.setOnAction(event -> onSelectAllToggled(checkBox.isSelected()));
                } else if (item instanceof ProfileItem profileItem) {
                    // Individual profile checkbox
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
     * Creates the placeholder label shown when no profile group is selected.
     *
     * @return the configured Label
     */
    private Label createProfileListPlaceholder() {
        Label label = new Label("Select a profile group first");
        label.getStyleClass().addAll("label", "muted");
        label.setStyle("-fx-padding: 20px; -fx-alignment: center;");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    // ==================== Data Loading ====================

    /**
     * Loads profile groups into the dropdown.
     */
    private void loadProfileGroups() {
        try {
            List<ProfileGroupEntity> groups = profileGroupRepository.findAll();
            profileGroupComboBox.getItems().clear();
            profileGroupComboBox.getItems().addAll(groups);

            if (groups.isEmpty()) {
                profileGroupComboBox.setPromptText("No profile groups found");
                profileGroupComboBox.setDisable(true);
            }
        } catch (Exception e) {
            System.err.println("[CreateTaskDialog] Failed to load profile groups: " + e.getMessage());
            profileGroupComboBox.setPromptText("Error loading groups");
            profileGroupComboBox.setDisable(true);
        }
    }

    /**
     * Called when a profile group is selected.
     * Loads profiles for the selected group into the profile list.
     *
     * @param group the selected profile group (may be null)
     */
    private void onProfileGroupSelected(ProfileGroupEntity group) {
        // Clear previous state
        profileListView.getItems().clear();
        profileSelectionMap.clear();
        selectAllProperty.set(false);

        if (group == null) {
            showProfilePlaceholder("Select a profile group first");
            return;
        }

        try {
            List<ProfileEntity> profiles = profileRepository.findByGroupId(group.id());

            if (profiles.isEmpty()) {
                showProfilePlaceholder("No profiles found in this group");
                return;
            }

            // Populate selection map for each profile
            for (ProfileEntity profile : profiles) {
                BooleanProperty selectedProp = new SimpleBooleanProperty(false);
                profileSelectionMap.put(profile.id(), selectedProp);
            }

            // Build list items: "Select All" first, then profiles
            List<ProfileListItem> items = new ArrayList<>();
            items.add(new SelectAllItem());
            for (ProfileEntity profile : profiles) {
                items.add(new ProfileItem(profile));
            }

            profileListView.getItems().addAll(items);

            // Show ListView, hide placeholder
            profileListView.setVisible(true);
            profileListView.setManaged(true);
            profileListPlaceholder.setVisible(false);
            profileListPlaceholder.setManaged(false);

            System.out.println("[CreateTaskDialog] Loaded " + profiles.size() + " profiles for group: " + group.name());

        } catch (Exception e) {
            System.err.println("[CreateTaskDialog] Failed to load profiles: " + e.getMessage());
            showProfilePlaceholder("Error loading profiles");
        }
    }

    /**
     * Shows the placeholder label with a custom message.
     *
     * @param message the message to display
     */
    private void showProfilePlaceholder(String message) {
        profileListPlaceholder.setText(message);
        profileListView.setVisible(false);
        profileListView.setManaged(false);
        profileListPlaceholder.setVisible(true);
        profileListPlaceholder.setManaged(true);
    }

    // ==================== Selection Handlers ====================

    /**
     * Called when the "Select All" checkbox is toggled.
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

            // Update all profile selections to match
            for (BooleanProperty prop : profileSelectionMap.values()) {
                prop.set(selected);
            }

            // Refresh the ListView to update checkbox visuals
            profileListView.refresh();

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
            // Update the profile's selection state
            BooleanProperty prop = profileSelectionMap.get(profileId);
            if (prop != null) {
                prop.set(selected);
            }

            // Update "Select All" state based on whether all profiles are selected
            boolean allSelected = profileSelectionMap.values().stream()
                    .allMatch(BooleanProperty::get);
            selectAllProperty.set(allSelected);

            // Refresh only the first item (Select All) to update its visual
            profileListView.refresh();

            validateAndUpdateButton();
        } finally {
            updatingSelections = false;
        }
    }

    // ==================== Validation ====================

    /**
     * Validates the form and enables/disables the create button.
     *
     * <p>Validation rules:</p>
     * <ul>
     *   <li>At least one profile must be selected</li>
     * </ul>
     */
    private void validateAndUpdateButton() {
        if (createButton == null) {
            return;
        }

        // Check if at least one profile is selected
        boolean hasSelection = profileSelectionMap.values().stream()
                .anyMatch(BooleanProperty::get);

        createButton.setDisable(!hasSelection);
    }

    // ==================== Result Building ====================

    /**
     * Builds the result from the current form state.
     *
     * @return the Result record, or null if invalid
     */
    private Result buildResult() {
        // Get selected profile IDs
        List<Long> profileIds = profileSelectionMap.entrySet().stream()
                .filter(entry -> entry.getValue().get())
                .map(Map.Entry::getKey)
                .toList();

        if (profileIds.isEmpty()) {
            return null;
        }

        // TODO: Get selected proxy group ID
        Long proxyGroupId = null; // Placeholder

        // TODO: Get warm session checkbox state
        boolean warmSession = false; // Placeholder

        return new Result(profileIds, proxyGroupId, warmSession);
    }
}