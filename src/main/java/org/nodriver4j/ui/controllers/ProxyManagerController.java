package org.nodriver4j.ui.controllers;

import javafx.fxml.FXML;
import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.entity.ProxyEntity;
import org.nodriver4j.persistence.entity.ProxyGroupEntity;
import org.nodriver4j.persistence.importer.ProxyImporter;
import org.nodriver4j.persistence.repository.ProxyGroupRepository;
import org.nodriver4j.persistence.repository.ProxyRepository;
import org.nodriver4j.ui.components.ProxyGroupCard;
import org.nodriver4j.ui.dialogs.CreateProxyGroupDialog;

import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * Controller for the Proxy Manager page.
 *
 * <p>Manages proxy group creation, display, and interaction. Extends
 * {@link GroupManagerController} which provides the shared page layout,
 * card management, empty state toggle, and error alert infrastructure.</p>
 *
 * <p>This controller is paired with {@code group-manager.fxml} (shared layout).
 * {@link MainController} assigns this controller programmatically via
 * {@code loader.setController(new ProxyManagerController())} before loading.</p>
 *
 * <h2>Creation Flow</h2>
 * <ol>
 *   <li>User clicks the "+" button</li>
 *   <li>{@link CreateProxyGroupDialog} collects group name + proxy content
 *       (via file upload or paste)</li>
 *   <li>{@link ProxyImporter} parses the content into entities</li>
 *   <li>Group is persisted first (persist-first pattern)</li>
 *   <li>All parsed proxies are batch-saved with the group ID</li>
 *   <li>A {@link ProxyGroupCard} is inserted at the beginning of the grid</li>
 *   <li>If any proxy lines failed to parse, a warning alert is shown</li>
 * </ol>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Provide proxy-manager-specific page text</li>
 *   <li>Load persisted proxy groups on initialization</li>
 *   <li>Handle "Add" button → dialog → import → persist → card</li>
 *   <li>Handle card click → navigation to proxy group detail</li>
 *   <li>Handle card delete → persist → remove card</li>
 *   <li>Build {@link ProxyGroupCard} instances with wired callbacks</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>FXML injection for shared layout (inherited from {@link GroupManagerController})</li>
 *   <li>Card list / grid synchronization (inherited)</li>
 *   <li>Empty state toggle (inherited)</li>
 *   <li>Error alert display (inherited)</li>
 *   <li>Proxy line parsing logic (delegated to {@link ProxyImporter})</li>
 *   <li>SQL queries (delegated to repositories)</li>
 *   <li>Proxy group detail page (separate controller)</li>
 * </ul>
 *
 * @see GroupManagerController
 * @see ProxyGroupCard
 * @see CreateProxyGroupDialog
 * @see ProxyImporter
 */
public class ProxyManagerController extends GroupManagerController<ProxyGroupCard> {

    // ==================== Repositories ====================

    private final ProxyGroupRepository proxyGroupRepository = new ProxyGroupRepository();
    private final ProxyRepository proxyRepository = new ProxyRepository();

    // ==================== Services ====================

    private final ProxyImporter proxyImporter = new ProxyImporter();

    // ==================== Callbacks ====================

    /**
     * Callback invoked when a proxy group card is clicked.
     * Set by {@link MainController} to navigate to the proxy group detail page.
     * Accepts the group ID as a parameter.
     */
    private LongConsumer onNavigateToGroup;

    // ==================== Page Text ====================

    @Override
    protected String pageTitle() {
        return "Proxies";
    }

    @Override
    protected String pageSubtitle() {
        return "Import and manage your proxy groups";
    }

    @Override
    protected String emptyStateIcon() {
        return "🌐";
    }

    @Override
    protected String emptyStateTitle() {
        return "No Proxy Groups";
    }

    @Override
    protected String emptyStateDescription() {
        return "Click the + button to import proxies from a text file or paste them directly";
    }

    // ==================== Data Loading ====================

    /**
     * Loads all proxy groups from the database and creates cards for them.
     *
     * <p>Groups are returned newest-first from the repository, which matches
     * the desired grid display order. Proxy counts are queried per group.</p>
     */
    @Override
    protected void loadGroups() {
        try {
            List<ProxyGroupEntity> groups = proxyGroupRepository.findAll();

            for (ProxyGroupEntity group : groups) {
                int proxyCount = (int) proxyRepository.countByGroupId(group.id());
                ProxyGroupCard card = buildCard(group.id(), group.name(), proxyCount);
                addCard(card);
            }

            System.out.println("[ProxyManagerController] Loaded " + groups.size()
                    + " proxy groups from database");

        } catch (Database.DatabaseException e) {
            System.err.println("[ProxyManagerController] Failed to load proxy groups: "
                    + e.getMessage());
            showErrorAlert("Failed to Load Proxy Groups",
                    "Could not load proxy groups from the database.",
                    e.getMessage());
        }
    }

    // ==================== Add Button ====================

    /**
     * Handles the "Add" button click.
     *
     * <p>Opens the proxy import dialog in CREATE mode, then orchestrates
     * the import flow: parse content → persist group → batch-persist proxies
     * → create card. Shows a warning alert if any proxy lines failed to parse.</p>
     */
    @FXML
    @Override
    protected void onAddClicked() {
        System.out.println("[ProxyManagerController] Add button clicked");

        Optional<CreateProxyGroupDialog.Result> result =
                CreateProxyGroupDialog.showCreate(ownerWindow());

        result.ifPresent(this::createProxyGroup);
    }

    // ==================== Proxy Group Management ====================

    /**
     * Creates a new proxy group from dialog data.
     *
     * <p>Flow:</p>
     * <ol>
     *   <li>Import proxy content via {@link ProxyImporter}</li>
     *   <li>Persist the group entity (persist-first pattern)</li>
     *   <li>Set group ID on all parsed proxy entities</li>
     *   <li>Batch-save all proxies</li>
     *   <li>Build card and insert at beginning of grid</li>
     *   <li>Show warning alert if any lines failed to parse</li>
     * </ol>
     *
     * @param data the dialog result containing group name and proxy content
     */
    private void createProxyGroup(CreateProxyGroupDialog.Result data) {
        System.out.println("[ProxyManagerController] Creating proxy group: " + data.groupName());

        // Step 1: Parse proxy content
        ProxyImporter.ImportResult importResult =
                proxyImporter.importFromContent(data.proxyContent(), data.groupName());

        if (importResult.proxyCount() == 0 && !importResult.hasWarnings()) {
            showErrorAlert("No Proxies Found",
                    "The input did not contain any valid proxies.",
                    "Ensure each line is in the format: host:port:username:password");
            return;
        }

        // Step 2: Persist group first
        ProxyGroupEntity groupEntity;
        try {
            groupEntity = importResult.group();
            groupEntity = proxyGroupRepository.save(groupEntity);
        } catch (Database.DatabaseException e) {
            System.err.println("[ProxyManagerController] Failed to save proxy group: "
                    + e.getMessage());
            showErrorAlert("Failed to Create Proxy Group",
                    "Could not save the proxy group to the database.",
                    e.getMessage());
            return;
        }

        // Step 3: Set group ID on all proxies and batch-save
        long groupId = groupEntity.id();
        List<ProxyEntity> proxies = importResult.proxies();

        try {
            for (ProxyEntity proxy : proxies) {
                proxy.groupId(groupId);
            }
            proxyRepository.saveAll(proxies);
        } catch (Database.DatabaseException e) {
            System.err.println("[ProxyManagerController] Failed to save proxies: "
                    + e.getMessage());
            // Group was created but proxies failed — clean up the empty group
            try {
                proxyGroupRepository.deleteById(groupId);
            } catch (Database.DatabaseException cleanupEx) {
                System.err.println("[ProxyManagerController] Failed to clean up group: "
                        + cleanupEx.getMessage());
            }
            showErrorAlert("Failed to Save Proxies",
                    "The proxy group was created but proxies could not be saved.",
                    e.getMessage());
            return;
        }

        // Step 4: Build card and add to grid (newest-first)
        int proxyCount = proxies.size();
        ProxyGroupCard card = buildCard(groupId, data.groupName(), proxyCount);
        addCardFirst(card);

        System.out.println("[ProxyManagerController] Created proxy group '"
                + data.groupName() + "' with " + proxyCount + " proxies");

        // Step 5: Show warnings if any lines failed
        if (importResult.hasWarnings()) {
            String warningText = String.join("\n", importResult.warnings());
            showErrorAlert("Import Warnings",
                    importResult.warningCount() + " line(s) could not be parsed.",
                    warningText);
        }
    }

    // ==================== Card Building ====================

    /**
     * Builds a {@link ProxyGroupCard} with click and delete callbacks wired.
     *
     * @param groupId    the database ID
     * @param groupName  the group name
     * @param proxyCount the number of proxies in the group
     * @return a fully configured card
     */
    private ProxyGroupCard buildCard(long groupId, String groupName, int proxyCount) {
        ProxyGroupCard card = new ProxyGroupCard(groupId, groupName, proxyCount);

        // Click → navigate to proxy group detail
        card.setOnClick(() -> {
            if (onNavigateToGroup != null) {
                onNavigateToGroup.accept(groupId);
            }
        });

        // Delete confirmed → remove from DB and grid
        card.setOnDelete(() -> onProxyGroupDeleted(card));

        return card;
    }

    // ==================== Delete ====================

    /**
     * Handles a confirmed proxy group deletion.
     *
     * <p>Deletes the group from the database (CASCADE deletes child proxies),
     * then removes the card from the grid.</p>
     *
     * @param card the card representing the group to delete
     */
    private void onProxyGroupDeleted(ProxyGroupCard card) {
        long groupId = card.groupId();
        String groupName = card.groupName();

        System.out.println("[ProxyManagerController] Deleting proxy group: "
                + groupName + " (ID " + groupId + ")");

        try {
            proxyGroupRepository.deleteById(groupId);
            removeCard(card);

            System.out.println("[ProxyManagerController] Deleted proxy group: " + groupName);

        } catch (Database.DatabaseException e) {
            System.err.println("[ProxyManagerController] Failed to delete proxy group: "
                    + e.getMessage());
            showErrorAlert("Failed to Delete Proxy Group",
                    "Could not delete '" + groupName + "' from the database.",
                    e.getMessage());
        }
    }

    // ==================== Navigation Callback ====================

    /**
     * Sets the callback for navigating to a proxy group detail page.
     *
     * <p>Called by {@link MainController} during initialization to wire
     * cross-page navigation.</p>
     *
     * @param callback a consumer that accepts the group ID
     */
    public void setOnNavigateToGroup(LongConsumer callback) {
        this.onNavigateToGroup = callback;
    }
}