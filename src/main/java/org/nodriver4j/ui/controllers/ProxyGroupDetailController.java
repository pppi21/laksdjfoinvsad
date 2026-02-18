package org.nodriver4j.ui.controllers;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
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
 * <p>Displays all proxies belonging to a specific proxy group and allows
 * adding more proxies or deleting existing ones. Unlike the Profile Group
 * Detail page, this page has an add button for importing additional proxies.</p>
 *
 * <p>This controller is paired with {@code proxy-group-detail.fxml}.
 * Navigation to this page is handled by {@link MainController#showProxyGroupDetail(long)},
 * which calls {@link #loadGroup(long)} to populate the page with the
 * specified group's data.</p>
 *
 * <h2>Add Flow</h2>
 * <ol>
 *   <li>User clicks the "+" button</li>
 *   <li>{@link CreateProxyGroupDialog} opens in ADD mode</li>
 *   <li>User provides proxy content (file or paste)</li>
 *   <li>{@link ProxyImporter} parses the content</li>
 *   <li>New proxies are batch-saved with the current group ID</li>
 *   <li>ProxyRow components are created and added to the list</li>
 *   <li>Proxy count is updated</li>
 * </ol>
 *
 * <h2>Delete Flow</h2>
 * <ol>
 *   <li>User clicks delete button on a ProxyRow (no confirmation needed)</li>
 *   <li>Proxy is deleted from the database</li>
 *   <li>ProxyRow is removed from the list</li>
 *   <li>Proxy count is updated</li>
 * </ol>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Load and display proxies for a specific group</li>
 *   <li>Update page header with group name and proxy count</li>
 *   <li>Handle back button navigation</li>
 *   <li>Handle add button → dialog → import → persist → add rows</li>
 *   <li>Handle proxy delete → persist → remove row</li>
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

    // ==================== FXML Injected Fields ====================

    @FXML
    private Button backButton;

    @FXML
    private Label groupNameLabel;

    @FXML
    private Label proxyCountLabel;

    @FXML
    private Button addButton;

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
     * List of ProxyRow components currently displayed.
     */
    private final List<ProxyRow> proxyRows = new ArrayList<>();

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
        SmoothScrollHelper.apply(scrollPane);
    }

    // ==================== Public API ====================

    /**
     * Loads a proxy group and displays its proxies.
     *
     * <p>Called by {@link MainController#showProxyGroupDetail(long)} when
     * navigating to this page. Clears any previously displayed data and
     * loads fresh data from the database.</p>
     *
     * @param groupId the database ID of the proxy group to load
     */
    public void loadGroup(long groupId) {
        System.out.println("[ProxyGroupDetailController] Loading group: " + groupId);

        this.currentGroupId = groupId;

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

        // Load proxies
        try {
            List<ProxyEntity> proxies = proxyRepository.findByGroupId(groupId);

            for (ProxyEntity proxy : proxies) {
                ProxyRow row = buildProxyRow(proxy);
                addProxyRow(row);
            }

            System.out.println("[ProxyGroupDetailController] Loaded " + proxies.size()
                    + " proxies for group '" + currentGroupName + "'");

        } catch (Database.DatabaseException e) {
            System.err.println("[ProxyGroupDetailController] Failed to load proxies: "
                    + e.getMessage());
            showErrorAlert("Failed to Load Proxies",
                    "Could not load proxies from the database.",
                    e.getMessage());
        }

        updateViewState();
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
     * persists any new proxies, adding them to the list.</p>
     */
    @FXML
    private void onAddClicked() {
        System.out.println("[ProxyGroupDetailController] Add button clicked");

        Optional<CreateProxyGroupDialog.Result> result =
                CreateProxyGroupDialog.showAdd(addButton.getScene().getWindow());

        result.ifPresent(this::addProxies);
    }

    // ==================== Proxy Management ====================

    /**
     * Adds proxies from dialog result to the current group.
     *
     * <p>Flow:</p>
     * <ol>
     *   <li>Parse proxy content via ProxyImporter</li>
     *   <li>Set group ID on all parsed entities</li>
     *   <li>Batch-save to database</li>
     *   <li>Create ProxyRow for each and add to list</li>
     *   <li>Update view state and count</li>
     *   <li>Show warnings if any lines failed</li>
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

        // Step 2: Set group ID and batch-save
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

        // Step 3: Create rows and add to list
        for (ProxyEntity proxy : proxies) {
            ProxyRow row = buildProxyRow(proxy);
            addProxyRow(row);
        }

        updateViewState();

        System.out.println("[ProxyGroupDetailController] Added " + proxies.size()
                + " proxies to group '" + currentGroupName + "'");

        // Step 4: Show warnings if any
        if (importResult.hasWarnings()) {
            String warningText = String.join("\n", importResult.warnings());
            showErrorAlert("Import Warnings",
                    importResult.warningCount() + " line(s) could not be parsed.",
                    warningText);
        }
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

    /**
     * Handles proxy deletion from a row's delete button.
     *
     * <p>No confirmation is required — the proxy is deleted immediately.</p>
     *
     * @param row the ProxyRow representing the proxy to delete
     */
    private void onProxyDeleted(ProxyRow row) {
        long proxyId = row.proxyId();

        System.out.println("[ProxyGroupDetailController] Deleting proxy: "
                + row.host() + ":" + row.port() + " (ID " + proxyId + ")");

        try {
            proxyRepository.deleteById(proxyId);
            removeProxyRow(row);

            System.out.println("[ProxyGroupDetailController] Deleted proxy ID " + proxyId);

        } catch (Database.DatabaseException e) {
            System.err.println("[ProxyGroupDetailController] Failed to delete proxy: "
                    + e.getMessage());
            showErrorAlert("Failed to Delete Proxy",
                    "Could not delete the proxy from the database.",
                    e.getMessage());
        }
    }

    // ==================== Row Management ====================

    /**
     * Adds a ProxyRow to the list.
     */
    private void addProxyRow(ProxyRow row) {
        proxyRows.add(row);
        proxyList.getChildren().add(row);
    }

    /**
     * Removes a ProxyRow from the list.
     */
    private void removeProxyRow(ProxyRow row) {
        proxyRows.remove(row);
        proxyList.getChildren().remove(row);
        updateViewState();
    }

    /**
     * Clears all proxy rows from the list.
     */
    private void clearProxies() {
        proxyRows.clear();
        proxyList.getChildren().clear();
    }

    // ==================== View State ====================

    /**
     * Updates the view to show either the empty state or the proxy list,
     * and updates the proxy count label.
     */
    private void updateViewState() {
        int count = proxyRows.size();
        boolean hasProxies = count > 0;

        // Update count label
        proxyCountLabel.setText(count + (count == 1 ? " proxy" : " proxies"));

        // Toggle visibility
        emptyState.setVisible(!hasProxies);
        emptyState.setManaged(!hasProxies);

        scrollPane.setVisible(hasProxies);
        scrollPane.setManaged(hasProxies);
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
     * Gets the current proxy count.
     *
     * @return the number of proxies displayed
     */
    public int proxyCount() {
        return proxyRows.size();
    }
}