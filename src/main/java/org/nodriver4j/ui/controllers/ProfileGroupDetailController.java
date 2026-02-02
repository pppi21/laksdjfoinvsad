package org.nodriver4j.ui.controllers;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.entity.ProfileGroupEntity;
import org.nodriver4j.persistence.repository.ProfileGroupRepository;
import org.nodriver4j.persistence.repository.ProfileRepository;
import org.nodriver4j.ui.components.ProfileCard;
import org.nodriver4j.ui.dialogs.ProfileDetailDialog;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Controller for the Profile Group Detail page.
 *
 * <p>Displays all profiles belonging to a specific profile group as
 * clickable ID-card-shaped cards in a grid layout. Navigated to by
 * clicking a profile group card in the Profile Manager.</p>
 *
 * <p>This controller is paired with {@code profile-group-detail.fxml},
 * which declares this controller via {@code fx:controller}. Navigation
 * is managed by {@link MainController}, which calls
 * {@link #loadGroup(long)} each time the page is shown.</p>
 *
 * <h2>Page Features</h2>
 * <ul>
 *   <li>Back button → returns to Profile Manager</li>
 *   <li>Header showing group name and profile count</li>
 *   <li>Scrollable FlowPane grid of {@link ProfileCard} instances</li>
 *   <li>Empty state fallback when the group has no profiles</li>
 *   <li>Card click → opens {@code ProfileDetailDialog} (full detail view)</li>
 * </ul>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Load profile group header info (name + count)</li>
 *   <li>Load profiles and create {@link ProfileCard} instances</li>
 *   <li>Handle back navigation via {@link Runnable} callback</li>
 *   <li>Open {@code ProfileDetailDialog} when a card is clicked</li>
 *   <li>Persist notes updates returned from the detail dialog</li>
 *   <li>Toggle empty state vs grid visibility</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Profile card layout/styling (delegated to {@link ProfileCard})</li>
 *   <li>Profile detail display/editing/copy-to-clipboard (delegated to {@code ProfileDetailDialog})</li>
 *   <li>Page navigation mechanics (delegated to {@link MainController})</li>
 *   <li>CSV import (handled by {@link ProfileManagerController} + {@code ProfileImporter})</li>
 *   <li>Grid layout management (FXML + CSS)</li>
 * </ul>
 *
 * @see ProfileCard
 * @see ProfileManagerController
 * @see MainController
 */
public class ProfileGroupDetailController implements Initializable {

    // ==================== FXML Injected Fields ====================

    @FXML
    private Button backButton;

    @FXML
    private Label groupNameLabel;

    @FXML
    private Label profileCountLabel;

    @FXML
    private VBox emptyState;

    @FXML
    private ScrollPane scrollPane;

    @FXML
    private FlowPane cardGrid;

    // ==================== Repositories ====================

    private final ProfileGroupRepository profileGroupRepository = new ProfileGroupRepository();
    private final ProfileRepository profileRepository = new ProfileRepository();

    // ==================== Internal State ====================

    /**
     * The database ID of the currently displayed profile group.
     */
    private long currentGroupId;

    /**
     * List of profile cards currently displayed in the grid.
     * Maintained in sync with {@code cardGrid.getChildren()}.
     */
    private final List<ProfileCard> cards = new ArrayList<>();

    // ==================== Callbacks ====================

    /**
     * Callback invoked when the back button is clicked.
     * Set by {@link MainController} to navigate back to the Profile Manager.
     */
    private Runnable onBack;

    // ==================== Initialization ====================

    /**
     * Initializes the controller.
     *
     * <p>Actual data loading happens in {@link #loadGroup(long)}, which is
     * called by {@link MainController} each time this page is navigated to.
     * This method only handles one-time setup.</p>
     */
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        System.out.println("[ProfileGroupDetailController] Initialized");
    }

    // ==================== Group Loading ====================

    /**
     * Loads a profile group and populates the page.
     *
     * <p>Called by {@link MainController#showProfileGroupDetail(long)} each
     * time this page is displayed. Clears any previously loaded data and
     * reloads from the database.</p>
     *
     * <p>Flow:</p>
     * <ol>
     *   <li>Clear existing cards</li>
     *   <li>Load group entity for header info</li>
     *   <li>Load all profiles for the group</li>
     *   <li>Build {@link ProfileCard} instances</li>
     *   <li>Update header labels and view state</li>
     * </ol>
     *
     * @param groupId the database ID of the profile group to display
     */
    public void loadGroup(long groupId) {
        this.currentGroupId = groupId;

        System.out.println("[ProfileGroupDetailController] Loading group " + groupId);

        // Clear previous state
        clearCards();
        resetScrollPosition();

        try {
            // Load group info for header
            Optional<ProfileGroupEntity> groupOpt = profileGroupRepository.findById(groupId);
            if (groupOpt.isEmpty()) {
                System.err.println("[ProfileGroupDetailController] Group not found: " + groupId);
                groupNameLabel.setText("Unknown Group");
                profileCountLabel.setText("Group not found");
                updateViewState();
                return;
            }

            ProfileGroupEntity group = groupOpt.get();

            // Load profiles
            List<ProfileEntity> profiles = profileRepository.findByGroupId(groupId);

            // Update header
            groupNameLabel.setText(group.name());
            profileCountLabel.setText(formatProfileCount(profiles.size()));

            // Build and add cards
            for (ProfileEntity profile : profiles) {
                ProfileCard card = buildCard(profile);
                cards.add(card);
                cardGrid.getChildren().add(card);
            }

            updateViewState();

            System.out.println("[ProfileGroupDetailController] Loaded " + profiles.size()
                    + " profiles for group '" + group.name() + "'");

        } catch (Database.DatabaseException e) {
            System.err.println("[ProfileGroupDetailController] Failed to load group: "
                    + e.getMessage());
            showErrorAlert("Failed to Load Profiles",
                    "Could not load profiles for this group.",
                    e.getMessage());
        }
    }

    // ==================== Card Building ====================

    /**
     * Builds a {@link ProfileCard} from a profile entity with the click
     * callback wired to open the detail dialog.
     *
     * @param profile the profile entity to build a card for
     * @return the configured ProfileCard
     */
    private ProfileCard buildCard(ProfileEntity profile) {
        ProfileCard card = new ProfileCard(
                profile.id(),
                profile.displayName(),
                profile.emailAddress(),
                profile.shippingPhone(),
                profile.cardNumber(),
                profile.expirationMonth(),
                profile.expirationYear(),
                profile.cvv()
        );

        card.setOnClick(() -> openProfileDetail(profile.id()));

        return card;
    }

    // ==================== Profile Detail ====================

    /**
     * Opens the profile detail dialog for a specific profile.
     *
     * <p>Loads the full profile entity from the database and opens
     * {@code ProfileDetailDialog}. If the user edits the notes field,
     * the updated notes are persisted back to the database.</p>
     *
     * @param profileId the database ID of the profile to display
     */
    private void openProfileDetail(long profileId) {
        System.out.println("[ProfileGroupDetailController] Opening detail for profile " + profileId);

        try {
            Optional<ProfileEntity> profileOpt = profileRepository.findById(profileId);
            if (profileOpt.isEmpty()) {
                System.err.println("[ProfileGroupDetailController] Profile not found: " + profileId);
                return;
            }

            ProfileEntity profile = profileOpt.get();

            Optional<String> updatedNotes = ProfileDetailDialog.show(
                    backButton.getScene().getWindow(), profile);
            updatedNotes.ifPresent(notes -> saveProfileNotes(profile, notes));
            System.out.println("[ProfileGroupDetailController] Detail dialog not yet implemented for: "
                    + profile.displayName());

        } catch (Database.DatabaseException e) {
            System.err.println("[ProfileGroupDetailController] Failed to load profile: "
                    + e.getMessage());
            showErrorAlert("Failed to Load Profile",
                    "Could not load profile details.",
                    e.getMessage());
        }
    }

    /**
     * Saves updated notes for a profile.
     *
     * <p>Called after the detail dialog returns with modified notes.
     * Only the notes field is editable; all other profile data is read-only.</p>
     *
     * @param profile the profile entity to update
     * @param notes   the new notes text
     */
    private void saveProfileNotes(ProfileEntity profile, String notes) {
        try {
            profile.notes(notes);
            profileRepository.save(profile);

            System.out.println("[ProfileGroupDetailController] Saved notes for profile "
                    + profile.id());

        } catch (Database.DatabaseException e) {
            System.err.println("[ProfileGroupDetailController] Failed to save notes: "
                    + e.getMessage());
            showErrorAlert("Failed to Save Notes",
                    "Could not save the updated notes.",
                    e.getMessage());
        }
    }

    // ==================== Navigation ====================

    /**
     * Handles the back button click.
     *
     * <p>Invokes the {@link #onBack} callback, which is wired by
     * {@link MainController} to return to the Profile Manager page.</p>
     */
    @FXML
    private void onBackClicked() {
        if (onBack != null) {
            onBack.run();
        }
    }

    // ==================== View State ====================

    /**
     * Updates the view to show either the empty state or the card grid.
     */
    private void updateViewState() {
        boolean hasCards = !cards.isEmpty();

        emptyState.setVisible(!hasCards);
        emptyState.setManaged(!hasCards);

        scrollPane.setVisible(hasCards);
        scrollPane.setManaged(hasCards);
    }

    /**
     * Clears all cards from the grid and internal list.
     */
    private void clearCards() {
        cards.clear();
        cardGrid.getChildren().clear();
        updateViewState();
    }

    /**
     * Resets the scroll position to the top.
     *
     * <p>Called when loading a new group to ensure the user starts
     * at the top of the card grid.</p>
     */
    private void resetScrollPosition() {
        scrollPane.setVvalue(0);
    }

    // ==================== Formatting ====================

    /**
     * Formats a profile count as a human-readable string.
     *
     * @param count the number of profiles
     * @return formatted string (e.g., "12 profiles", "1 profile")
     */
    private String formatProfileCount(int count) {
        return count + (count == 1 ? " profile" : " profiles");
    }

    // ==================== Error Handling ====================

    /**
     * Shows an error alert dialog styled with the dark theme.
     *
     * @param title   the alert title
     * @param header  the header text describing what went wrong
     * @param content the detailed error message
     */
    private void showErrorAlert(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);

        try {
            alert.getDialogPane().getStylesheets().add(
                    getClass().getResource("../css/dark-theme.css").toExternalForm()
            );
            alert.getDialogPane().getStyleClass().add("dialog-pane");
        } catch (Exception e) {
            System.err.println("[ProfileGroupDetailController] Could not apply dark theme to alert: "
                    + e.getMessage());
        }

        alert.showAndWait();
    }

    // ==================== Callback Setters ====================

    /**
     * Sets the callback for back navigation.
     *
     * <p>Called by {@link MainController} to wire the back button
     * to return to the Profile Manager page.</p>
     *
     * @param onBack the callback to invoke on back click
     */
    public void setOnBack(Runnable onBack) {
        this.onBack = onBack;
    }

    // ==================== Getters ====================

    /**
     * Gets the database ID of the currently displayed profile group.
     *
     * @return the current group ID, or 0 if no group is loaded
     */
    public long currentGroupId() {
        return currentGroupId;
    }

    /**
     * Gets the number of profile cards currently displayed.
     *
     * @return the card count
     */
    public int cardCount() {
        return cards.size();
    }
}