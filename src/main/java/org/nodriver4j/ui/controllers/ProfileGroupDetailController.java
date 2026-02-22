package org.nodriver4j.ui.controllers;

import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.entity.ProfileGroupEntity;
import org.nodriver4j.persistence.repository.ProfileGroupRepository;
import org.nodriver4j.persistence.repository.ProfileRepository;
import org.nodriver4j.ui.components.ProfileCard;
import org.nodriver4j.ui.dialogs.ProfileDetailDialog;
import org.nodriver4j.ui.util.SmoothScrollHelper;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Controller for the Profile Group Detail page.
 *
 * <p>Displays profiles belonging to a specific profile group using Gmail-style
 * pagination. Only one page of profiles (up to {@value #PAGE_SIZE}) is loaded
 * at a time. Navigation arrows allow moving between pages, and a range label
 * shows the current position (e.g., "1–50 of 847").</p>
 *
 * <p>Includes a live search bar with 500ms debounce that filters profiles by
 * matching against the email address and profile name. Search is performed at
 * the database level using SQL LIKE queries.</p>
 *
 * <p>This controller is paired with {@code profile-group-detail.fxml}.
 * Navigation to this page is handled by {@link MainController#showProfileGroupDetail(long)},
 * which calls {@link #loadGroup(long)} to populate the page with the
 * specified group's data.</p>
 *
 * <h2>Pagination Behavior</h2>
 * <ul>
 *   <li>Page size is {@value #PAGE_SIZE} profiles per page</li>
 *   <li>Navigating between pages replaces all displayed cards (not appending)</li>
 *   <li>Scroll position resets to the top on each page change</li>
 *   <li>Arrow buttons are disabled (not hidden) when at the first/last page</li>
 *   <li>Pagination controls are visible even for single-page groups (arrows disabled)</li>
 *   <li>Pagination controls are hidden only when the group is empty</li>
 * </ul>
 *
 * <h2>Search Behavior</h2>
 * <ul>
 *   <li>Live search with 500ms debounce — queries fire after the user stops typing</li>
 *   <li>Searches against email address and profile name (OR match)</li>
 *   <li>Entering a search term resets pagination to page 0</li>
 *   <li>Clearing the search field restores the unfiltered view</li>
 *   <li>The profile count label shows filtered context (e.g., "12 of 847 profiles")</li>
 *   <li>Search state is reset when navigating to a different group</li>
 * </ul>
 *
 * <h2>Delete Behavior</h2>
 * <ul>
 *   <li>Each ProfileCard has a delete button with inline confirmation</li>
 *   <li>After deletion, the current page is reloaded to backfill from
 *       subsequent items</li>
 *   <li>If the current page becomes empty (e.g., last profile on the last
 *       page was deleted), the controller navigates to the previous page</li>
 *   <li>If the group becomes empty, the empty state is displayed</li>
 * </ul>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Load and display one page of profiles for a specific group</li>
 *   <li>Manage pagination state (current page, total count)</li>
 *   <li>Manage search state (query string, debounce timer, filtered count)</li>
 *   <li>Update page header with group name and total profile count</li>
 *   <li>Update pagination bar with range label and arrow states</li>
 *   <li>Handle back button navigation</li>
 *   <li>Handle card click → open ProfileDetailDialog</li>
 *   <li>Handle card delete → persist → reload page</li>
 *   <li>Persist notes updates returned from the detail dialog</li>
 *   <li>Toggle between empty state and card grid</li>
 *   <li>Build ProfileCard components with wired callbacks</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Profile card layout/styling (delegated to {@link ProfileCard})</li>
 *   <li>Profile detail display/editing/copy-to-clipboard (delegated to {@link ProfileDetailDialog})</li>
 *   <li>Page navigation mechanics (delegated to {@link MainController})</li>
 *   <li>CSV import (handled by {@link ProfileManagerController} + ProfileImporter)</li>
 *   <li>Grid layout management (FXML + CSS)</li>
 * </ul>
 *
 * @see ProfileCard
 * @see ProfileDetailDialog
 * @see ProfileManagerController
 * @see MainController
 */
public class ProfileGroupDetailController implements Initializable {

    // ==================== Constants ====================

    /**
     * Number of profiles displayed per page.
     */
    private static final int PAGE_SIZE = 50;

    /**
     * Debounce delay for live search in milliseconds.
     */
    private static final double SEARCH_DEBOUNCE_MS = 500;

    // ==================== FXML Injected Fields ====================

    @FXML
    private Button backButton;

    @FXML
    private Label groupNameLabel;

    @FXML
    private Label profileCountLabel;

    @FXML
    private HBox searchBar;

    @FXML
    private TextField searchField;

    @FXML
    private HBox paginationBar;

    @FXML
    private Label pageRangeLabel;

    @FXML
    private Button prevPageButton;

    @FXML
    private Button nextPageButton;

    @FXML
    private VBox emptyState;

    @FXML
    private ScrollPane scrollPane;

    @FXML
    private FlowPane cardGrid;

    // ==================== Repositories ====================

    private final ProfileGroupRepository profileGroupRepository = new ProfileGroupRepository();
    private final ProfileRepository profileRepository = new ProfileRepository();

    // ==================== State ====================

    /**
     * The database ID of the currently displayed profile group.
     */
    private long currentGroupId;

    /**
     * The name of the currently displayed profile group.
     */
    private String currentGroupName;

    /**
     * List of profile cards currently displayed on the current page.
     * Maintained in sync with {@code cardGrid.getChildren()}.
     */
    private final List<ProfileCard> cards = new ArrayList<>();

    // ==================== Pagination State ====================

    /**
     * The current page index (0-based).
     */
    private int currentPage;

    /**
     * The total number of profiles matching the current view (filtered or unfiltered).
     */
    private long totalCount;

    /**
     * The total number of profiles in the group (always unfiltered).
     * Used for the subtitle label when a search is active.
     */
    private long unfilteredCount;

    // ==================== Search State ====================

    /**
     * The current search query. Empty string means no filter.
     */
    private String currentSearchQuery = "";

    /**
     * Debounce timer for live search. Restarted on each keystroke.
     */
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(SEARCH_DEBOUNCE_MS));

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
        SmoothScrollHelper.apply(scrollPane);

        setupSearchBar();
    }

    /**
     * Configures the search bar: adds the search icon, wires the debounce
     * listener, and sets up focus forwarding from the container HBox.
     */
    private void setupSearchBar() {
        // Add search icon to the right side of the search bar
        FontIcon searchIcon = new FontIcon(FontAwesomeSolid.SEARCH);
        searchIcon.setIconSize(14);
        searchIcon.setIconColor(Color.web("#737373"));
        searchBar.getChildren().add(searchIcon);

        // Forward clicks on the HBox container to the TextField
        searchBar.setOnMouseClicked(event -> searchField.requestFocus());

        // Toggle focused style class on the parent HBox when TextField gains/loses focus
        searchField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                searchBar.getStyleClass().add("search-bar-focused");
            } else {
                searchBar.getStyleClass().remove("search-bar-focused");
            }
        });

        // Wire debounce: restart timer on each keystroke, fire search on completion
        searchDebounce.setOnFinished(event -> executeSearch());

        searchField.textProperty().addListener((obs, oldText, newText) -> {
            searchDebounce.playFromStart();
        });
    }

    // ==================== Public API ====================

    /**
     * Loads a profile group and displays the first page of its profiles.
     *
     * <p>Called by {@link MainController#showProfileGroupDetail(long)} each
     * time this page is displayed. Resets pagination and search state to
     * defaults, clears any previously loaded data, and reloads from the
     * database.</p>
     *
     * @param groupId the database ID of the profile group to display
     */
    public void loadGroup(long groupId) {
        this.currentGroupId = groupId;
        this.currentPage = 0;
        this.currentSearchQuery = "";

        // Clear search field without triggering the debounce
        searchDebounce.stop();
        searchField.setText("");

        System.out.println("[ProfileGroupDetailController] Loading group " + groupId);

        // Clear previous state
        clearCards();

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
            this.currentGroupName = group.name();
            groupNameLabel.setText(currentGroupName);

            // Load first page
            loadCurrentPage();

        } catch (Database.DatabaseException e) {
            System.err.println("[ProfileGroupDetailController] Failed to load group: "
                    + e.getMessage());
            showErrorAlert("Failed to Load Profiles",
                    "Could not load profiles for this group.",
                    e.getMessage());
        }
    }

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

    // ==================== FXML Actions ====================

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

    /**
     * Navigates to the previous page of profiles.
     *
     * <p>Does nothing if already on the first page.</p>
     */
    @FXML
    private void onPrevPageClicked() {
        if (currentPage > 0) {
            currentPage--;
            loadCurrentPage();
            System.out.println("[ProfileGroupDetailController] Navigated to page " + (currentPage + 1));
        }
    }

    /**
     * Navigates to the next page of profiles.
     *
     * <p>Does nothing if already on the last page.</p>
     */
    @FXML
    private void onNextPageClicked() {
        if (currentPage < totalPages() - 1) {
            currentPage++;
            loadCurrentPage();
            System.out.println("[ProfileGroupDetailController] Navigated to page " + (currentPage + 1));
        }
    }

    // ==================== Search ====================

    /**
     * Executes a search based on the current text in the search field.
     *
     * <p>Called by the debounce timer after the user stops typing. Resets
     * pagination to page 0 and reloads with the new search filter. If the
     * search text hasn't changed since the last execution, the reload is
     * skipped to avoid unnecessary database queries.</p>
     */
    private void executeSearch() {
        String query = searchField.getText().trim();

        // Skip if query hasn't changed
        if (query.equals(currentSearchQuery)) {
            return;
        }

        currentSearchQuery = query;
        currentPage = 0;
        loadCurrentPage();

        System.out.println("[ProfileGroupDetailController] Search executed: '"
                + (query.isEmpty() ? "(cleared)" : query) + "'");
    }

    /**
     * Checks whether a search filter is currently active.
     *
     * @return true if the current search query is non-empty
     */
    private boolean isSearchActive() {
        return !currentSearchQuery.isEmpty();
    }

    // ==================== Page Loading ====================

    /**
     * Loads the current page of profiles from the database.
     *
     * <p>Clears all currently displayed cards, queries the database for
     * the current page's worth of profiles (filtered by search if active),
     * and rebuilds the card list. Also refreshes the total count and updates
     * all view state including the pagination controls.</p>
     *
     * <p>If the current page index has become invalid (e.g., due to
     * deletions reducing the total page count), the page is clamped
     * to the last valid page before loading.</p>
     */
    private void loadCurrentPage() {
        clearCards();

        try {
            // Always fetch unfiltered count for the subtitle
            unfilteredCount = profileRepository.countByGroupId(currentGroupId);

            // Fetch filtered count (same as unfiltered when no search is active)
            if (isSearchActive()) {
                totalCount = profileRepository.countByGroupIdAndSearch(currentGroupId, currentSearchQuery);
            } else {
                totalCount = unfilteredCount;
            }

            if (totalCount == 0) {
                updateViewState();
                return;
            }

            // Clamp page if it has become invalid (e.g., after deletions)
            if (currentPage >= totalPages()) {
                currentPage = Math.max(0, totalPages() - 1);
            }

            int offset = currentPage * PAGE_SIZE;
            List<ProfileEntity> profiles;

            if (isSearchActive()) {
                profiles = profileRepository.findByGroupIdAndSearch(
                        currentGroupId, currentSearchQuery, PAGE_SIZE, offset);
            } else {
                profiles = profileRepository.findByGroupId(currentGroupId, PAGE_SIZE, offset);
            }

            for (ProfileEntity profile : profiles) {
                ProfileCard card = buildCard(profile);
                cards.add(card);
                cardGrid.getChildren().add(card);
            }

            System.out.println("[ProfileGroupDetailController] Loaded page " + (currentPage + 1)
                    + " (" + profiles.size() + " profiles) for group '" + currentGroupName + "'"
                    + (isSearchActive() ? " [search: '" + currentSearchQuery + "']" : ""));

        } catch (Database.DatabaseException e) {
            System.err.println("[ProfileGroupDetailController] Failed to load profiles: "
                    + e.getMessage());
            showErrorAlert("Failed to Load Profiles",
                    "Could not load profiles from the database.",
                    e.getMessage());
        }

        updateViewState();
        scrollPane.setVvalue(0);
    }

    // ==================== Card Building ====================

    /**
     * Builds a {@link ProfileCard} from a profile entity with click
     * and delete callbacks wired.
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
        card.setOnDelete(() -> onProfileDeleted(profile.id()));

        return card;
    }

    // ==================== Profile Detail ====================

    /**
     * Opens the profile detail dialog for a specific profile.
     *
     * <p>Loads the full profile entity from the database and opens
     * {@link ProfileDetailDialog}. If the user edits the notes field,
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

    // ==================== Profile Deletion ====================

    /**
     * Handles profile deletion from a card's delete button.
     *
     * <p>Deletes the profile from the database and reloads the current
     * page to backfill the gap. If the current page becomes empty after
     * deletion (e.g., the last profile on the last page), the controller
     * navigates to the previous page. If the group becomes entirely
     * empty, the empty state is displayed.</p>
     *
     * @param profileId the database ID of the profile to delete
     */
    private void onProfileDeleted(long profileId) {
        System.out.println("[ProfileGroupDetailController] Deleting profile ID " + profileId);

        try {
            profileRepository.deleteById(profileId);

            System.out.println("[ProfileGroupDetailController] Deleted profile ID " + profileId);

        } catch (Database.DatabaseException e) {
            System.err.println("[ProfileGroupDetailController] Failed to delete profile: "
                    + e.getMessage());
            showErrorAlert("Failed to Delete Profile",
                    "Could not delete the profile from the database.",
                    e.getMessage());
            return;
        }

        // Reload current page (handles backfill and page clamping)
        loadCurrentPage();
    }

    // ==================== Card Management ====================

    /**
     * Clears all cards from the grid and internal list.
     */
    private void clearCards() {
        cards.clear();
        cardGrid.getChildren().clear();
    }

    // ==================== Pagination Helpers ====================

    /**
     * Calculates the total number of pages based on the current total count.
     *
     * @return the total page count, or 0 if the group is empty
     */
    private int totalPages() {
        if (totalCount <= 0) return 0;
        return (int) Math.ceil((double) totalCount / PAGE_SIZE);
    }

    // ==================== View State ====================

    /**
     * Updates the view to show either the empty state or the card grid,
     * and updates the profile count label and pagination controls.
     *
     * <p>When a search is active, the profile count label shows the filtered
     * count in context (e.g., "12 of 847 profiles"). When no search is active,
     * it shows just the total (e.g., "847 profiles").</p>
     */
    private void updateViewState() {
        boolean hasProfiles = totalCount > 0;

        // Update subtitle with count
        if (isSearchActive()) {
            String profileWord = unfilteredCount == 1 ? " profile" : " profiles";
            profileCountLabel.setText(totalCount + " of " + unfilteredCount + profileWord);
        } else {
            profileCountLabel.setText(totalCount + (totalCount == 1 ? " profile" : " profiles"));
        }

        // Toggle empty state vs grid
        emptyState.setVisible(!hasProfiles);
        emptyState.setManaged(!hasProfiles);

        scrollPane.setVisible(hasProfiles);
        scrollPane.setManaged(hasProfiles);

        // Toggle pagination bar
        paginationBar.setVisible(hasProfiles);
        paginationBar.setManaged(hasProfiles);

        if (hasProfiles) {
            updatePaginationControls();
        }
    }

    /**
     * Updates the pagination range label and arrow button states.
     *
     * <p>The range label shows the current range and total, e.g., "1–50 of 847".
     * The previous arrow is disabled on the first page, and the next arrow is
     * disabled on the last page.</p>
     */
    private void updatePaginationControls() {
        long start = (long) currentPage * PAGE_SIZE + 1;
        long end = Math.min((long) (currentPage + 1) * PAGE_SIZE, totalCount);

        pageRangeLabel.setText(start + "–" + end + " of " + totalCount);

        prevPageButton.setDisable(currentPage <= 0);
        nextPageButton.setDisable(currentPage >= totalPages() - 1);
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
     * Gets the number of profile cards currently displayed on the page.
     *
     * @return the card count
     */
    public int cardCount() {
        return cards.size();
    }
}