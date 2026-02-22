package org.nodriver4j.ui.controllers;

import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.entity.ProxyEntity;
import org.nodriver4j.persistence.entity.ProxyGroupEntity;
import org.nodriver4j.persistence.importer.ProxyImporter;
import org.nodriver4j.persistence.repository.ProxyGroupRepository;
import org.nodriver4j.persistence.repository.ProxyRepository;
import org.nodriver4j.ui.components.ProxyRow;
import org.nodriver4j.ui.dialogs.CreateProxyGroupDialog;
import org.nodriver4j.ui.util.SmoothScrollHelper;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Controller for the Proxy Group Detail page.
 *
 * <p>Displays proxies belonging to a specific proxy group using Gmail-style
 * pagination. Only one page of proxies (up to {@value #PAGE_SIZE}) is loaded
 * at a time. Navigation arrows allow moving between pages, and a range label
 * shows the current position (e.g., "1–50 of 847").</p>
 *
 * <p>Includes a live search bar with 500ms debounce that filters proxies by
 * matching against the full proxy string ({@code host:port:username:password}).
 * Search is performed at the database level using SQL LIKE queries.</p>
 *
 * <p>This controller is paired with {@code proxy-group-detail.fxml}.
 * Navigation to this page is handled by {@link MainController#showProxyGroupDetail(long)},
 * which calls {@link #loadGroup(long)} to populate the page with the
 * specified group's data.</p>
 *
 * <h2>Pagination Behavior</h2>
 * <ul>
 *   <li>Page size is {@value #PAGE_SIZE} proxies per page</li>
 *   <li>Navigating between pages replaces all displayed rows (not appending)</li>
 *   <li>Scroll position resets to the top on each page change</li>
 *   <li>Arrow buttons are disabled (not hidden) when at the first/last page</li>
 *   <li>Pagination controls are visible even for single-page groups (arrows disabled)</li>
 *   <li>Pagination controls are hidden only when the group is empty</li>
 * </ul>
 *
 * <h2>Search Behavior</h2>
 * <ul>
 *   <li>Live search with 500ms debounce — queries fire after the user stops typing</li>
 *   <li>Searches against the full proxy string (host:port:username:password)</li>
 *   <li>Entering a search term resets pagination to page 0</li>
 *   <li>Clearing the search field restores the unfiltered view</li>
 *   <li>The proxy count label shows filtered context (e.g., "12 of 847 proxies")</li>
 *   <li>Search state is reset when navigating to a different group</li>
 * </ul>
 *
 * <h2>Add Proxy Behavior</h2>
 * <ul>
 *   <li>New proxies are saved to the database with auto-incrementing IDs</li>
 *   <li>If the user is on the last page, the current page is reloaded so
 *       newly added proxies appear (up to the page size limit)</li>
 *   <li>If the user is NOT on the last page, only the total count is
 *       refreshed — no row changes occur</li>
 * </ul>
 *
 * <h2>Delete Proxy Behavior</h2>
 * <ul>
 *   <li>The proxy is deleted from the database and the current page is
 *       reloaded to backfill from subsequent items</li>
 *   <li>If deleting the last proxy on the current page, the controller
 *       navigates back to the previous page</li>
 *   <li>If the group becomes empty, the empty state is displayed</li>
 * </ul>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Load and display one page of proxies for a specific group</li>
 *   <li>Manage pagination state (current page, total count)</li>
 *   <li>Manage search state (query string, debounce timer, filtered count)</li>
 *   <li>Update page header with group name and total proxy count</li>
 *   <li>Update pagination bar with range label and arrow states</li>
 *   <li>Handle back button navigation</li>
 *   <li>Handle add button → dialog → import → persist → reload page</li>
 *   <li>Handle proxy delete → persist → reload page</li>
 *   <li>Toggle between empty state and proxy list</li>
 *   <li>Build ProxyRow components with wired callbacks</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Proxy parsing (delegated to {@link ProxyImporter})</li>
 *   <li>SQL queries (delegated to repositories)</li>
 *   <li>Page caching/FXML loading (MainController handles)</li>
 *   <li>ProxyRow internal layout (component handles)</li>
 *   <li>Dialog display logic (CreateProxyGroupDialog handles)</li>
 * </ul>
 *
 * @see ProxyRow
 * @see CreateProxyGroupDialog
 * @see ProxyImporter
 * @see MainController#showProxyGroupDetail(long)
 */
public class ProxyGroupDetailController implements Initializable {

    // ==================== Constants ====================

    /**
     * Number of proxies displayed per page.
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
    private Label proxyCountLabel;

    @FXML
    private HBox searchBar;

    @FXML
    private TextField searchField;

    @FXML
    private Button addButton;

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
    private VBox proxyList;

    // ==================== Repositories ====================

    private final ProxyGroupRepository proxyGroupRepository = new ProxyGroupRepository();
    private final ProxyRepository proxyRepository = new ProxyRepository();

    // ==================== Services ====================

    private final ProxyImporter proxyImporter = new ProxyImporter();

    // ==================== State ====================

    /**
     * The currently loaded proxy group ID.
     */
    private long currentGroupId;

    /**
     * The currently loaded proxy group name.
     */
    private String currentGroupName;

    /**
     * List of ProxyRow components currently displayed on the current page.
     */
    private final List<ProxyRow> proxyRows = new ArrayList<>();

    // ==================== Pagination State ====================

    /**
     * The current page index (0-based).
     */
    private int currentPage;

    /**
     * The total number of proxies matching the current view (filtered or unfiltered).
     */
    private long totalCount;

    /**
     * The total number of proxies in the group (always unfiltered).
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
     * Set by MainController to navigate back to the Proxy Manager.
     */
    private Runnable onBack;

    // ==================== Initialization ====================

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        System.out.println("[ProxyGroupDetailController] Initialized");
        SmoothScrollHelper.apply(scrollPane, 0.2, 1.2);

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
     * Loads a proxy group and displays the first page of its proxies.
     *
     * <p>Called by {@link MainController#showProxyGroupDetail(long)} when
     * navigating to this page. Resets pagination and search state to defaults,
     * clears any previously displayed data, and loads fresh data from the
     * database.</p>
     *
     * @param groupId the database ID of the proxy group to load
     */
    public void loadGroup(long groupId) {
        System.out.println("[ProxyGroupDetailController] Loading group: " + groupId);

        this.currentGroupId = groupId;
        this.currentPage = 0;
        this.currentSearchQuery = "";

        // Clear search field without triggering the debounce
        searchDebounce.stop();
        searchField.setText("");

        // Clear previous data
        clearProxies();

        // Load group info
        Optional<ProxyGroupEntity> groupOpt = proxyGroupRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            System.err.println("[ProxyGroupDetailController] Group not found: " + groupId);
            showErrorAlert("Group Not Found",
                    "The proxy group could not be found.",
                    "It may have been deleted.");
            return;
        }

        ProxyGroupEntity group = groupOpt.get();
        this.currentGroupName = group.name();
        groupNameLabel.setText(currentGroupName);

        // Load first page
        loadCurrentPage();
    }

    /**
     * Sets the callback for back button navigation.
     *
     * <p>Called by MainController to wire navigation back to Proxy Manager.</p>
     *
     * @param onBack the callback
     */
    public void setOnBack(Runnable onBack) {
        this.onBack = onBack;
    }

    // ==================== FXML Actions ====================

    /**
     * Handles the back button click.
     *
     * <p>Invokes the onBack callback to navigate to the Proxy Manager.</p>
     */
    @FXML
    private void onBackClicked() {
        System.out.println("[ProxyGroupDetailController] Back button clicked");
        if (onBack != null) {
            onBack.run();
        }
    }

    /**
     * Handles the add button click.
     *
     * <p>Opens the proxy import dialog in ADD mode, then imports and
     * persists any new proxies, refreshing the view as needed.</p>
     */
    @FXML
    private void onAddClicked() {
        System.out.println("[ProxyGroupDetailController] Add button clicked");

        Optional<CreateProxyGroupDialog.Result> result =
                CreateProxyGroupDialog.showAdd(addButton.getScene().getWindow());

        result.ifPresent(this::addProxies);
    }

    /**
     * Navigates to the previous page of proxies.
     *
     * <p>Does nothing if already on the first page.</p>
     */
    @FXML
    private void onPrevPageClicked() {
        if (currentPage > 0) {
            currentPage--;
            loadCurrentPage();
            System.out.println("[ProxyGroupDetailController] Navigated to page " + (currentPage + 1));
        }
    }

    /**
     * Navigates to the next page of proxies.
     *
     * <p>Does nothing if already on the last page.</p>
     */
    @FXML
    private void onNextPageClicked() {
        if (currentPage < totalPages() - 1) {
            currentPage++;
            loadCurrentPage();
            System.out.println("[ProxyGroupDetailController] Navigated to page " + (currentPage + 1));
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

        System.out.println("[ProxyGroupDetailController] Search executed: '"
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
     * Loads the current page of proxies from the database.
     *
     * <p>Clears all currently displayed rows, queries the database for
     * the current page's worth of proxies (filtered by search if active),
     * and rebuilds the row list. Also refreshes the total count and updates
     * all view state including the pagination controls.</p>
     *
     * <p>If the current page index has become invalid (e.g., due to
     * deletions reducing the total page count), the page is clamped
     * to the last valid page before loading.</p>
     */
    private void loadCurrentPage() {
        clearProxies();

        try {
            // Always fetch unfiltered count for the subtitle
            unfilteredCount = proxyRepository.countByGroupId(currentGroupId);

            // Fetch filtered count (same as unfiltered when no search is active)
            if (isSearchActive()) {
                totalCount = proxyRepository.countByGroupIdAndSearch(currentGroupId, currentSearchQuery);
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
            List<ProxyEntity> proxies;

            if (isSearchActive()) {
                proxies = proxyRepository.findByGroupIdAndSearch(
                        currentGroupId, currentSearchQuery, PAGE_SIZE, offset);
            } else {
                proxies = proxyRepository.findByGroupId(currentGroupId, PAGE_SIZE, offset);
            }

            for (ProxyEntity proxy : proxies) {
                ProxyRow row = buildProxyRow(proxy);
                addProxyRow(row);
            }

            System.out.println("[ProxyGroupDetailController] Loaded page " + (currentPage + 1)
                    + " (" + proxies.size() + " proxies) for group '" + currentGroupName + "'"
                    + (isSearchActive() ? " [search: '" + currentSearchQuery + "']" : ""));

        } catch (Database.DatabaseException e) {
            System.err.println("[ProxyGroupDetailController] Failed to load proxies: "
                    + e.getMessage());
            showErrorAlert("Failed to Load Proxies",
                    "Could not load proxies from the database.",
                    e.getMessage());
        }

        updateViewState();
        scrollPane.setVvalue(0);
    }

    // ==================== Proxy Management ====================

    /**
     * Adds proxies from dialog result to the current group.
     *
     * <p>Flow:</p>
     * <ol>
     *   <li>Parse proxy content via ProxyImporter</li>
     *   <li>Check whether the user is currently on the last page</li>
     *   <li>Set group ID on all parsed entities and batch-save to database</li>
     *   <li>If the user was on the last page, reload the page so new proxies
     *       appear (up to page size). Otherwise, just refresh the total count.</li>
     *   <li>Update pagination controls</li>
     *   <li>Show warnings if any lines failed to parse</li>
     * </ol>
     *
     * @param data the dialog result containing proxy content
     */
    private void addProxies(CreateProxyGroupDialog.Result data) {
        System.out.println("[ProxyGroupDetailController] Adding proxies to group: " + currentGroupName);

        // Step 1: Parse content
        ProxyImporter.ImportResult importResult =
                proxyImporter.importFromContent(data.proxyContent(), currentGroupName);

        if (importResult.proxyCount() == 0 && !importResult.hasWarnings()) {
            showErrorAlert("No Proxies Found",
                    "The input did not contain any valid proxies.",
                    "Ensure each line is in the format: host:port:username:password");
            return;
        }

        // Step 2: Check if on last page before saving
        boolean wasOnLastPage = isOnLastPage();

        // Step 3: Set group ID and batch-save
        List<ProxyEntity> proxies = importResult.proxies();

        try {
            for (ProxyEntity proxy : proxies) {
                proxy.groupId(currentGroupId);
            }
            proxyRepository.saveAll(proxies);
        } catch (Database.DatabaseException e) {
            System.err.println("[ProxyGroupDetailController] Failed to save proxies: "
                    + e.getMessage());
            showErrorAlert("Failed to Save Proxies",
                    "Could not save proxies to the database.",
                    e.getMessage());
            return;
        }

        // Step 4: Refresh view
        if (wasOnLastPage) {
            // Reload page so new proxies appear at the end
            loadCurrentPage();
        } else {
            // Just refresh the total count and pagination controls
            unfilteredCount = proxyRepository.countByGroupId(currentGroupId);
            if (isSearchActive()) {
                totalCount = proxyRepository.countByGroupIdAndSearch(currentGroupId, currentSearchQuery);
            } else {
                totalCount = unfilteredCount;
            }
            updateViewState();
        }

        System.out.println("[ProxyGroupDetailController] Added " + proxies.size()
                + " proxies to group '" + currentGroupName + "'");

        // Step 5: Show warnings if any
        if (importResult.hasWarnings()) {
            String warningText = String.join("\n", importResult.warnings());
            showErrorAlert("Import Warnings",
                    importResult.warningCount() + " line(s) could not be parsed.",
                    warningText);
        }
    }

    /**
     * Handles proxy deletion from a row's delete button.
     *
     * <p>No confirmation is required — the proxy is deleted immediately.
     * After deletion, the current page is reloaded from the database to
     * backfill the gap. If the current page becomes empty (e.g., the last
     * proxy on the last page was deleted), the controller navigates to the
     * previous page automatically.</p>
     *
     * @param row the ProxyRow representing the proxy to delete
     */
    private void onProxyDeleted(ProxyRow row) {
        long proxyId = row.proxyId();

        System.out.println("[ProxyGroupDetailController] Deleting proxy: "
                + row.host() + ":" + row.port() + " (ID " + proxyId + ")");

        try {
            proxyRepository.deleteById(proxyId);

            System.out.println("[ProxyGroupDetailController] Deleted proxy ID " + proxyId);

        } catch (Database.DatabaseException e) {
            System.err.println("[ProxyGroupDetailController] Failed to delete proxy: "
                    + e.getMessage());
            showErrorAlert("Failed to Delete Proxy",
                    "Could not delete the proxy from the database.",
                    e.getMessage());
            return;
        }

        // Reload current page (handles backfill and page clamping)
        loadCurrentPage();
    }

    /**
     * Builds a ProxyRow with the delete callback wired.
     *
     * @param proxy the proxy entity
     * @return a configured ProxyRow
     */
    private ProxyRow buildProxyRow(ProxyEntity proxy) {
        ProxyRow row = new ProxyRow(
                proxy.id(),
                proxy.host(),
                proxy.port(),
                proxy.username(),
                proxy.password()
        );

        row.setOnDelete(() -> onProxyDeleted(row));

        return row;
    }

    // ==================== Row Management ====================

    /**
     * Adds a ProxyRow to the current page's list.
     */
    private void addProxyRow(ProxyRow row) {
        proxyRows.add(row);
        proxyList.getChildren().add(row);
    }

    /**
     * Clears all proxy rows from the current page.
     */
    private void clearProxies() {
        proxyRows.clear();
        proxyList.getChildren().clear();
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

    /**
     * Checks whether the current page is the last page.
     *
     * <p>Also returns {@code true} if the group is empty (no pages).</p>
     *
     * @return true if on the last page or if the group is empty
     */
    private boolean isOnLastPage() {
        return totalPages() == 0 || currentPage >= totalPages() - 1;
    }

    // ==================== View State ====================

    /**
     * Updates the view to show either the empty state or the proxy list,
     * and updates the proxy count label and pagination controls.
     *
     * <p>When a search is active, the proxy count label shows the filtered
     * count in context (e.g., "12 of 847 proxies"). When no search is active,
     * it shows just the total (e.g., "847 proxies").</p>
     */
    private void updateViewState() {
        boolean hasProxies = totalCount > 0;

        // Update subtitle with count
        if (isSearchActive()) {
            String proxyWord = unfilteredCount == 1 ? " proxy" : " proxies";
            proxyCountLabel.setText(totalCount + " of " + unfilteredCount + proxyWord);
        } else {
            proxyCountLabel.setText(totalCount + (totalCount == 1 ? " proxy" : " proxies"));
        }

        // Toggle empty state vs list
        emptyState.setVisible(!hasProxies);
        emptyState.setManaged(!hasProxies);

        scrollPane.setVisible(hasProxies);
        scrollPane.setManaged(hasProxies);

        // Toggle pagination bar
        paginationBar.setVisible(hasProxies);
        paginationBar.setManaged(hasProxies);

        if (hasProxies) {
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
     * @param header  the header text
     * @param content the detailed message
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
            System.err.println("[ProxyGroupDetailController] Could not apply dark theme to alert: "
                    + e.getMessage());
        }

        alert.showAndWait();
    }

    // ==================== Getters ====================

    /**
     * Gets the currently loaded group ID.
     *
     * @return the group ID, or 0 if no group is loaded
     */
    public long currentGroupId() {
        return currentGroupId;
    }

    /**
     * Gets the currently loaded group name.
     *
     * @return the group name, or null if no group is loaded
     */
    public String currentGroupName() {
        return currentGroupName;
    }

    /**
     * Gets the number of proxies currently displayed on the page.
     *
     * @return the number of proxy rows on the current page
     */
    public int proxyCount() {
        return proxyRows.size();
    }
}