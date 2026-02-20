package org.nodriver4j.ui.controllers;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.nodriver4j.core.Browser;
import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.entity.ProxyEntity;
import org.nodriver4j.persistence.entity.TaskEntity;
import org.nodriver4j.persistence.entity.TaskGroupEntity;
import org.nodriver4j.persistence.repository.*;
import org.nodriver4j.services.ScreencastService;
import org.nodriver4j.services.TaskExecutionService;
import org.nodriver4j.ui.components.TaskRow;
import org.nodriver4j.ui.dialogs.ChangeProxiesDialog;
import org.nodriver4j.ui.dialogs.CreateTaskDialog;
import org.nodriver4j.ui.dialogs.EditTaskDialog;
import org.nodriver4j.ui.util.SmoothScrollHelper;
import org.nodriver4j.ui.windows.ViewBrowserWindow;

import java.net.URL;
import java.nio.file.Path;
import java.util.*;

/**
 * Controller for the Task Group Detail page.
 *
 * <p>Displays tasks belonging to a specific task group using Gmail-style
 * pagination. Only one page of tasks (up to {@value #PAGE_SIZE}) is loaded
 * at a time. Navigation arrows allow moving between pages, and a range label
 * shows the current position (e.g., "1–100 of 847").</p>
 *
 * <p>This controller is paired with {@code task-group-detail.fxml}.
 * Navigation to this page is handled by {@link MainController}, which
 * calls {@link #loadGroup(long)} to populate the page with the specified
 * group's data.</p>
 *
 * <h2>Pagination Behavior</h2>
 * <ul>
 *   <li>Page size is {@value #PAGE_SIZE} tasks per page</li>
 *   <li>Navigating between pages replaces all displayed rows (not appending)</li>
 *   <li>Scroll position resets to the top on each page change</li>
 *   <li>Arrow buttons are disabled (not hidden) when at the first/last page</li>
 *   <li>Pagination controls are visible even for single-page groups (arrows disabled)</li>
 *   <li>Pagination controls are hidden only when the group is empty</li>
 *   <li>Running tasks continue unaffected when navigating between pages</li>
 *   <li>Screencast sessions persist across page navigations</li>
 * </ul>
 *
 * <h2>Callback Re-wiring</h2>
 * <p>When a page is loaded, any task that is currently active (RUNNING)
 * has its UI callbacks replaced on {@link TaskExecutionService} so that
 * live log and status updates target the newly created {@link TaskRow}.
 * This uses the proxy pattern in {@code TaskExecutionService} — see
 * {@link TaskExecutionService#replaceCallbacks(long, java.util.function.BiConsumer,
 * java.util.function.Consumer)}.</p>
 *
 * <h2>Bulk Operations</h2>
 * <p>Start All, Stop All, and Change Proxies operate on <b>all</b> tasks in the
 * group, not just the visible page. This is because the user thinks in terms of
 * the entire group — these buttons are in the page header, not per-row. For
 * visible rows, UI updates happen immediately. For off-screen tasks, the service
 * persists state to the database, and when the user navigates to that page later,
 * rows are rebuilt with the latest state.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Load and display one page of tasks for a specific group</li>
 *   <li>Manage pagination state (current page, total count)</li>
 *   <li>Update page header with group name, script name</li>
 *   <li>Update pagination bar with range label and arrow states</li>
 *   <li>Wire all TaskRow callbacks (start, stop, view, manual, clone, edit, delete)</li>
 *   <li>Re-wire callbacks on {@link TaskExecutionService} for active tasks when rows rebuild</li>
 *   <li>Restore screencast/view browser state when rows rebuild</li>
 *   <li>Handle Start All / Stop All across all group tasks</li>
 *   <li>Handle Change Proxies across all group tasks</li>
 *   <li>Handle Add Task with last-page awareness</li>
 *   <li>Handle Delete with page reload and backfill</li>
 *   <li>Handle Clone with last-page awareness</li>
 *   <li>Manage screencast sessions (open, close, persist across pages)</li>
 *   <li>Toggle between empty state and task list</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Task execution or browser lifecycle ({@link TaskExecutionService})</li>
 *   <li>Row rendering ({@link TaskRow})</li>
 *   <li>Dialog display (CreateTaskDialog, EditTaskDialog, ChangeProxiesDialog)</li>
 *   <li>SQL queries (repositories)</li>
 *   <li>Page caching or FXML loading ({@link MainController})</li>
 * </ul>
 *
 * @see TaskRow
 * @see TaskExecutionService
 * @see CreateTaskDialog
 * @see EditTaskDialog
 * @see ChangeProxiesDialog
 */
public class TaskGroupDetailController implements Initializable {

    // ==================== Constants ====================

    /**
     * Number of tasks displayed per page.
     */
    private static final int PAGE_SIZE = 25;

    // ==================== FXML Injected Fields ====================

    @FXML
    private Button backButton;

    @FXML
    private Label groupNameLabel;

    @FXML
    private Label scriptNameLabel;

    @FXML
    private Button addButton;

    @FXML
    private Button startAllButton;

    @FXML
    private Button stopAllButton;

    @FXML
    private Button changeProxiesButton;

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
    private VBox taskListContainer;

    // ==================== Repositories ====================

    private final TaskGroupRepository taskGroupRepository = new TaskGroupRepository();
    private final TaskRepository taskRepository = new TaskRepository();
    private final ProfileRepository profileRepository = new ProfileRepository();
    private final ProxyGroupRepository proxyGroupRepository = new ProxyGroupRepository();
    private final ProxyRepository proxyRepository = new ProxyRepository();

    // ==================== Internal State ====================

    /**
     * The currently loaded task group ID, or -1 if no group is loaded.
     */
    private long currentGroupId = -1;

    /**
     * List of task rows currently displayed on the current page.
     */
    private final List<TaskRow> taskRows = new ArrayList<>();

    /**
     * Active screencast sessions mapped by task ID.
     * Each entry represents an open view browser window streaming from a running task.
     * These persist across page navigations within the same group.
     */
    private final Map<Long, ScreencastSession> screencastSessions = new HashMap<>();

    // ==================== Pagination State ====================

    /**
     * The current page index (0-based).
     */
    private int currentPage;

    /**
     * The total number of tasks in the current group (from DB).
     */
    private long totalCount;

    // ==================== Callbacks ====================

    /**
     * Callback invoked when the back button is clicked.
     * Set by {@link MainController} to navigate back to the Task Manager.
     */
    private Runnable onBack;

    // ==================== Initialization ====================

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        System.out.println("[TaskGroupDetailController] Initialized (awaiting loadGroup call)");
        SmoothScrollHelper.apply(scrollPane);
        updateViewState();
    }

    // ==================== Public API ====================

    /**
     * Loads a task group and displays the first page of its tasks.
     *
     * <p>Called by {@link MainController} each time the user navigates to
     * this page. Resets pagination to page 0, clears previously displayed
     * data (including screencast sessions), and loads fresh data from the
     * database.</p>
     *
     * @param groupId the database ID of the task group to load
     */
    public void loadGroup(long groupId) {
        System.out.println("[TaskGroupDetailController] Loading group ID: " + groupId);

        clearPage();
        this.currentGroupId = groupId;
        this.currentPage = 0;

        loadGroupHeader(groupId);
        loadCurrentPage();
    }

    /**
     * Sets the callback for back button navigation.
     *
     * @param onBack the callback to invoke when back is clicked
     */
    public void setOnBack(Runnable onBack) {
        this.onBack = onBack;
    }

    // ==================== FXML Actions — Navigation ====================

    /**
     * Handles the back button click.
     */
    @FXML
    private void onBackClicked() {
        System.out.println("[TaskGroupDetailController] Back button clicked");
        if (onBack != null) {
            onBack.run();
        }
    }

    /**
     * Navigates to the previous page of tasks.
     */
    @FXML
    private void onPrevPageClicked() {
        if (currentPage > 0) {
            currentPage--;
            loadCurrentPage();
            System.out.println("[TaskGroupDetailController] Navigated to page " + (currentPage + 1));
        }
    }

    /**
     * Navigates to the next page of tasks.
     */
    @FXML
    private void onNextPageClicked() {
        if (currentPage < totalPages() - 1) {
            currentPage++;
            loadCurrentPage();
            System.out.println("[TaskGroupDetailController] Navigated to page " + (currentPage + 1));
        }
    }

    // ==================== FXML Actions — Task Operations ====================

    /**
     * Opens the Create Task dialog and creates tasks from the selected profiles.
     *
     * <p>New tasks are saved to the database. If the user is on the last page,
     * the page is reloaded so newly created tasks appear (up to page size). If
     * not on the last page, only the total count and pagination controls are
     * refreshed.</p>
     */
    @FXML
    private void onAddTaskClicked() {
        System.out.println("[TaskGroupDetailController] Add task clicked for group " + currentGroupId);

        CreateTaskDialog dialog = new CreateTaskDialog(
                addButton.getScene().getWindow(),
                new ProfileGroupRepository(),
                profileRepository,
                proxyGroupRepository,
                proxyRepository
        );

        dialog.showAndWait().ifPresent(result -> {
            List<Long> profileIds = result.profileIds();
            Long proxyGroupId = result.proxyGroupId();
            boolean warmSession = result.warmSession();

            // Load proxies sequentially if a proxy group was selected
            List<ProxyEntity> proxies = (proxyGroupId != null)
                    ? proxyRepository.findByGroupId(proxyGroupId)
                    : List.of();

            // Check if on last page before saving
            boolean wasOnLastPage = isOnLastPage();

            // Create a TaskEntity for each selected profile
            List<TaskEntity> tasks = new ArrayList<>();
            for (int i = 0; i < profileIds.size(); i++) {
                Long proxyId = (i < proxies.size()) ? proxies.get(i).id() : null;

                TaskEntity task = TaskEntity.builder()
                        .groupId(currentGroupId)
                        .profileId(profileIds.get(i))
                        .proxyId(proxyId)
                        .warmSession(warmSession)
                        .build();

                tasks.add(task);
            }

            // Persist all tasks
            taskRepository.saveAll(tasks);

            // Refresh view
            if (wasOnLastPage) {
                loadCurrentPage();
            } else {
                totalCount = taskRepository.countByGroupId(currentGroupId);
                updateViewState();
            }

            System.out.println("[TaskGroupDetailController] Created " + tasks.size() + " tasks");
        });
    }

    /**
     * Starts all idle tasks in the group.
     *
     * <p>Operates on <b>all</b> tasks in the group, not just the visible page.
     * For visible rows, {@link #requestStart(TaskRow)} is called for immediate
     * UI feedback. For off-screen tasks, the service is called directly with
     * null callbacks — state is persisted to the database and reflected when
     * the user navigates to that page.</p>
     */
    @FXML
    private void onStartAllClicked() {
        System.out.println("[TaskGroupDetailController] Start All clicked for group " + currentGroupId);

        List<Long> allTaskIds = taskRepository.findTaskIdsByGroupId(currentGroupId);
        TaskExecutionService service = TaskExecutionService.instance();

        for (long taskId : allTaskIds) {
            if (service.isActive(taskId)) {
                continue;
            }

            Optional<TaskRow> rowOpt = findTaskRow(taskId);
            if (rowOpt.isPresent()) {
                requestStart(rowOpt.get());
            } else {
                // Off-screen task — start with no UI callbacks.
                // The service persists status/log to DB via TaskLogger.
                try {
                    service.startTask(taskId, null, null);
                } catch (IllegalStateException e) {
                    System.err.println("[TaskGroupDetailController] Failed to start off-screen task #"
                            + taskId + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Stops all active tasks in the group.
     *
     * <p>Operates on <b>all</b> tasks in the group, not just the visible page.
     * Screencast sessions are closed on the FX thread first, then visible rows
     * are optimistically updated to STOPPED. A single background thread handles
     * the actual browser shutdown for all active tasks.</p>
     */
    @FXML
    private void onStopAllClicked() {
        System.out.println("[TaskGroupDetailController] Stop All clicked for group " + currentGroupId);

        List<Long> allTaskIds = taskRepository.findTaskIdsByGroupId(currentGroupId);
        TaskExecutionService service = TaskExecutionService.instance();

        List<Long> activeTaskIds = allTaskIds.stream()
                .filter(service::isActive)
                .toList();

        if (activeTaskIds.isEmpty()) {
            return;
        }

        // Close screencasts and update visible rows on FX thread
        for (long taskId : activeTaskIds) {
            closeScreencastSession(taskId);
            findTaskRow(taskId).ifPresent(row -> row.setStatus(TaskEntity.STATUS_STOPPED));
        }

        // Stop all on single background thread
        Thread thread = new Thread(() -> {
            for (long taskId : activeTaskIds) {
                try {
                    service.stopBrowser(taskId);
                } catch (Exception e) {
                    System.err.println("[TaskGroupDetailController] Error stopping task "
                            + taskId + ": " + e.getMessage());
                }
            }
        }, "StopAll-" + currentGroupId);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Opens the Change Proxies dialog and reassigns proxies across all
     * tasks in the group.
     *
     * <p>Operates on <b>all</b> tasks in the group, not just the visible page.
     * After reassignment, the current page is reloaded to reflect proxy changes
     * on visible rows.</p>
     */
    @FXML
    private void onChangeProxiesClicked() {
        System.out.println("[TaskGroupDetailController] Change Proxies clicked for group " + currentGroupId);

        ChangeProxiesDialog dialog = new ChangeProxiesDialog(
                changeProxiesButton.getScene().getWindow(),
                proxyGroupRepository,
                proxyRepository,
                (int) totalCount
        );

        dialog.showAndWait().ifPresent(result -> {
            Long proxyGroupId = result.proxyGroupId();

            // Load proxies from the selected group (empty list if "None")
            List<ProxyEntity> proxies = (proxyGroupId != null)
                    ? proxyRepository.findByGroupId(proxyGroupId)
                    : List.of();

            // Load ALL tasks in group order for sequential proxy assignment
            List<TaskEntity> allTasks = taskRepository.findByGroupId(currentGroupId);

            for (int i = 0; i < allTasks.size(); i++) {
                TaskEntity task = allTasks.get(i);
                Long oldProxyId = task.proxyId();

                if (i < proxies.size()) {
                    // Assign proxy from the new group
                    task.proxyId(proxies.get(i).id());
                    task.touchUpdatedAt();
                    taskRepository.save(task);
                } else if (proxyGroupId == null) {
                    // "None" selected — clear proxy from all tasks
                    task.proxyId(null);
                    task.touchUpdatedAt();
                    taskRepository.save(task);
                } else {
                    // More tasks than proxies — leave remaining tasks unchanged
                    continue;
                }

                // Clean up old standalone proxy (null-group proxies from edit dialog)
                proxyRepository.deleteIfStandalone(oldProxyId);
            }

            // Reload current page to reflect proxy changes on visible rows
            loadCurrentPage();

            System.out.println("[TaskGroupDetailController] Proxies changed — group "
                    + (proxyGroupId != null ? "#" + proxyGroupId : "None")
                    + ", " + Math.min(proxies.size(), allTasks.size()) + " tasks updated");
        });
    }

    // ==================== Page Loading ====================

    /**
     * Loads the current page of tasks from the database.
     *
     * <p>Clears all displayed rows (but NOT screencast sessions), queries
     * the database for the current page, and rebuilds rows. For any task
     * that is currently active, UI callbacks are re-wired on
     * {@link TaskExecutionService} so live updates target the new row.
     * Screencast view browser state is also restored.</p>
     */
    private void loadCurrentPage() {
        clearRows();

        try {
            totalCount = taskRepository.countByGroupId(currentGroupId);

            if (totalCount == 0) {
                updateViewState();
                return;
            }

            // Clamp page if it has become invalid (e.g., after deletions)
            if (currentPage >= totalPages()) {
                currentPage = Math.max(0, totalPages() - 1);
            }

            int offset = currentPage * PAGE_SIZE;
            List<TaskEntity> tasks = taskRepository.findByGroupId(currentGroupId, PAGE_SIZE, offset);

            // Bulk-load profiles and proxies to avoid N+1 queries
            Map<Long, ProfileEntity> profileMap = buildProfileMap(tasks);
            Map<Long, ProxyEntity> proxyMap = buildProxyMap(tasks);

            TaskExecutionService service = TaskExecutionService.instance();

            for (TaskEntity task : tasks) {
                String displayName = resolveDisplayName(task, profileMap);
                String proxyDisplay = resolveProxyDisplay(task, proxyMap);
                String statusText = task.displayStatus();

                TaskRow row = new TaskRow(task.id(), displayName, statusText);
                row.setProxyText(proxyDisplay);

                // Restore persisted log state
                if (task.hasLogMessage()) {
                    row.setLogText(task.logMessage(), task.logColor());
                }

                wireRowCallbacks(row);

                // Re-wire callbacks for active tasks so live updates target the new row
                if (service.isActive(task.id())) {
                    service.replaceCallbacks(task.id(),
                            (msg, color) -> Platform.runLater(() -> row.setLogText(msg, color)),
                            status -> Platform.runLater(() -> row.setStatus(status))
                    );
                }

                // Restore view browser active state for tasks with open screencasts
                if (screencastSessions.containsKey(task.id())) {
                    row.setViewBrowserActive(true);
                }

                taskRows.add(row);
                taskListContainer.getChildren().add(row);
            }

            System.out.println("[TaskGroupDetailController] Loaded page " + (currentPage + 1)
                    + " (" + tasks.size() + " tasks) for group " + currentGroupId);

        } catch (Database.DatabaseException e) {
            System.err.println("[TaskGroupDetailController] Failed to load tasks: " + e.getMessage());
            showErrorAlert("Failed to Load Tasks",
                    "Could not load tasks from the database.",
                    e.getMessage());
        }

        updateViewState();
        scrollPane.setVvalue(0);
    }

    /**
     * Loads the task group entity and populates the header labels.
     *
     * @param groupId the group ID
     */
    private void loadGroupHeader(long groupId) {
        try {
            Optional<TaskGroupEntity> groupOpt = taskGroupRepository.findById(groupId);

            if (groupOpt.isPresent()) {
                TaskGroupEntity group = groupOpt.get();
                groupNameLabel.setText(group.name());
                scriptNameLabel.setText(group.scriptName());
            } else {
                System.err.println("[TaskGroupDetailController] Group not found: " + groupId);
                groupNameLabel.setText("Unknown Group");
                scriptNameLabel.setText("");
            }

        } catch (Database.DatabaseException e) {
            System.err.println("[TaskGroupDetailController] Failed to load group header: " + e.getMessage());
            groupNameLabel.setText("Error Loading Group");
            scriptNameLabel.setText("");
        }
    }

    // ==================== Row Callback Wiring ====================

    /**
     * Wires all action button callbacks on a TaskRow.
     *
     * @param row the task row to wire
     */
    private void wireRowCallbacks(TaskRow row) {
        long id = row.taskId();

        // ---- Lifecycle callbacks ----

        row.setOnStart(taskId -> requestStart(row));
        row.setOnStop(taskId -> requestStop(row));
        row.setOnOpenManualBrowser(taskId -> openManualBrowser(row));

        // ---- View browser callbacks ----

        row.setOnOpenViewBrowser(taskId -> {
            System.out.println("[TaskGroupDetailController] Open view browser — Task #" + taskId);

            Browser browser = TaskExecutionService.instance().browser(taskId);
            if (browser == null || !browser.isOpen()) {
                System.err.println("[TaskGroupDetailController] No active browser for task " + taskId);
                return;
            }

            ScreencastService service = new ScreencastService(browser.cdpClient());
            ViewBrowserWindow window = new ViewBrowserWindow("View Browser — Task #" + taskId);

            window.setOnClose(() -> {
                closeScreencastSession(taskId);
                findTaskRow(taskId).ifPresent(r -> r.setViewBrowserActive(false));
            });

            screencastSessions.put(taskId, new ScreencastSession(service, window));

            service.start(frameBytes ->
                    Platform.runLater(() -> window.updateFrame(frameBytes))
            );

            window.show();
            row.setViewBrowserActive(true);
        });

        row.setOnCloseViewBrowser(taskId -> {
            System.out.println("[TaskGroupDetailController] Close view browser — Task #" + taskId);
            closeScreencastSession(taskId);
            row.setViewBrowserActive(false);
        });

        // ---- Clone ----

        row.setOnClone(taskId -> {
            System.out.println("[TaskGroupDetailController] Clone task #" + taskId);

            Optional<TaskEntity> taskOpt = taskRepository.findById(taskId);
            if (taskOpt.isEmpty()) {
                System.err.println("[TaskGroupDetailController] Task not found for clone: " + taskId);
                return;
            }

            TaskEntity original = taskOpt.get();

            // Build a fresh copy — keep profile, proxy, warmSession, notes
            // Reset identity, status, userdata, custom status, fingerprint, and log
            TaskEntity clone = original.toBuilder()
                    .id(0)
                    .status(TaskEntity.STATUS_IDLE)
                    .userdataPath(null)
                    .customStatus(null)
                    .logMessage(null)
                    .logColor(null)
                    .fingerprintIndex(null)
                    .createdAt(null)
                    .updatedAt(null)
                    .build();

            taskRepository.save(clone);

            // Check if on last page before refreshing
            boolean wasOnLastPage = isOnLastPage();

            if (wasOnLastPage) {
                loadCurrentPage();
            } else {
                totalCount = taskRepository.countByGroupId(currentGroupId);
                updateViewState();
            }

            System.out.println("[TaskGroupDetailController] Cloned task #" + taskId
                    + " → new task #" + clone.id());
        });

        // ---- Edit ----

        row.setOnEdit(taskId -> {
            System.out.println("[TaskGroupDetailController] Edit task #" + taskId);

            Optional<TaskEntity> taskOpt = taskRepository.findById(taskId);
            if (taskOpt.isEmpty()) {
                System.err.println("[TaskGroupDetailController] Task not found: " + taskId);
                return;
            }

            TaskEntity task = taskOpt.get();

            // Resolve current proxy string for pre-population
            String currentProxyString = null;
            if (task.hasProxy()) {
                Optional<ProxyEntity> proxyOpt = proxyRepository.findById(task.proxyId());
                if (proxyOpt.isPresent()) {
                    currentProxyString = proxyOpt.get().toProxyString();
                }
            }

            // Open edit dialog
            EditTaskDialog dialog = new EditTaskDialog(
                    row.getScene().getWindow(),
                    currentProxyString,
                    task.customStatus()
            );

            dialog.showAndWait().ifPresent(result -> {
                // Handle proxy change
                Long oldProxyId = task.proxyId();
                Long newProxyId;

                if (result.proxyString() != null) {
                    ProxyEntity newProxy = ProxyEntity.builder()
                            .groupId(null)
                            .fromProxyString(result.proxyString())
                            .build();
                    proxyRepository.save(newProxy);
                    newProxyId = newProxy.id();
                } else {
                    newProxyId = null;
                }

                // Update task
                task.proxyId(newProxyId);
                task.customStatus(result.customStatus());
                task.touchUpdatedAt();
                taskRepository.save(task);

                // Clean up old standalone proxy
                proxyRepository.deleteIfStandalone(oldProxyId);

                // Refresh the visible row
                findTaskRow(taskId).ifPresent(r -> {
                    if (newProxyId != null) {
                        proxyRepository.findById(newProxyId).ifPresent(
                                p -> r.setProxyText(p.toDisplayString())
                        );
                    } else {
                        r.setProxyText(null);
                    }

                    r.setStatus(task.displayStatus());
                });

                System.out.println("[TaskGroupDetailController] Task #" + taskId + " updated");
            });
        });

        // ---- Delete ----

        row.setOnDelete(taskId -> {
            System.out.println("[TaskGroupDetailController] Delete task #" + taskId);

            // Defensive: stop browser if somehow still active
            TaskExecutionService service = TaskExecutionService.instance();
            if (service.isActive(taskId)) {
                closeScreencastSession(taskId);
                service.stopBrowser(taskId);
            }

            // Load task for userdata path and proxy info before deletion
            Optional<TaskEntity> taskOpt = taskRepository.findById(taskId);
            if (taskOpt.isPresent()) {
                TaskEntity task = taskOpt.get();

                // Delete userdata directory from disk
                if (task.hasUserdataPath()) {
                    TaskExecutionService.deleteDirectoryWithRetry(
                            Path.of(task.userdataPath()));
                }

                // Delete task from database
                taskRepository.deleteById(taskId);

                // Clean up standalone proxy
                proxyRepository.deleteIfStandalone(task.proxyId());
            }

            // Reload current page (handles backfill and page clamping)
            loadCurrentPage();

            System.out.println("[TaskGroupDetailController] Deleted task #" + taskId);
        });
    }

    // ==================== Task Lifecycle Helpers ====================

    /**
     * Requests a scripted task start for the given row.
     *
     * <p>Sets the row to a transitional "STARTING..." state immediately, then
     * calls {@link TaskExecutionService#startTask} which spawns its own daemon
     * thread and returns immediately. Log and status callbacks are wrapped with
     * {@code Platform.runLater} to marshal updates back to the FX thread.</p>
     *
     * <p>The callbacks are stored in {@link TaskExecutionService} via the proxy
     * pattern and can be replaced later when rows are rebuilt.</p>
     *
     * @param row the task row to start
     */
    private void requestStart(TaskRow row) {
        long taskId = row.taskId();
        row.setStatus("STARTING...");

        try {
            TaskExecutionService.instance().startTask(taskId,
                    (msg, color) -> Platform.runLater(() -> row.setLogText(msg, color)),
                    status -> Platform.runLater(() -> row.setStatus(status))
            );
        } catch (IllegalStateException e) {
            System.err.println("[TaskGroupDetailController] Failed to start task #"
                    + taskId + ": " + e.getMessage());
            row.setStatus(TaskEntity.STATUS_FAILED);
            row.setLogText("Failed to start: " + e.getMessage(), TaskEntity.LOG_ERROR);
        }
    }

    /**
     * Requests a task stop for the given row (handles both RUNNING and MANUAL).
     *
     * <p>Closes any active screencast session on the FX thread first, then
     * stops the browser on a background thread. The row status is set to
     * STOPPED on the FX thread after the browser is closed.</p>
     *
     * @param row the task row to stop
     */
    private void requestStop(TaskRow row) {
        long taskId = row.taskId();

        closeScreencastSession(taskId);

        Thread thread = new Thread(() -> {
            TaskExecutionService.instance().stopBrowser(taskId);
            Platform.runLater(() -> row.setStatus(TaskEntity.STATUS_STOPPED));
        }, "Stop-" + taskId);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Opens a manual (headed) browser session for the given row.
     *
     * <p>Sets the row to "STARTING..." immediately, then launches the browser
     * on a background thread. On success the row transitions to MANUAL;
     * on failure it transitions to FAILED.</p>
     *
     * @param row the task row to open a manual browser for
     */
    private void openManualBrowser(TaskRow row) {
        long taskId = row.taskId();
        row.setStatus("STARTING...");

        Thread thread = new Thread(() -> {
            try {
                TaskExecutionService.instance().startManualBrowser(taskId);
                Platform.runLater(() -> row.setStatus(TaskEntity.STATUS_MANUAL));
            } catch (Exception e) {
                System.err.println("[TaskGroupDetailController] Manual browser failed for task #"
                        + taskId + ": " + e.getMessage());
                Platform.runLater(() -> {
                    row.setStatus(TaskEntity.STATUS_FAILED);
                    row.setLogText("Manual browser failed: " + e.getMessage(), TaskEntity.LOG_ERROR);
                });
            }
        }, "Manual-" + taskId);
        thread.setDaemon(true);
        thread.start();
    }

    // ==================== Screencast Management ====================

    /**
     * Closes and cleans up a screencast session for a task.
     *
     * <p>Stops the screencast service and closes the view window.
     * Safe to call even if no session exists for the given task.</p>
     *
     * @param taskId the task ID
     */
    private void closeScreencastSession(long taskId) {
        ScreencastSession session = screencastSessions.remove(taskId);
        if (session == null) {
            return;
        }

        try {
            session.service().stop();
        } catch (Exception e) {
            System.err.println("[TaskGroupDetailController] Error stopping screencast for task "
                    + taskId + ": " + e.getMessage());
        }

        try {
            session.window().close();
        } catch (Exception e) {
            System.err.println("[TaskGroupDetailController] Error closing view window for task "
                    + taskId + ": " + e.getMessage());
        }
    }

    // ==================== Profile / Proxy Resolution ====================

    /**
     * Builds a map of profile ID → ProfileEntity for the given tasks.
     *
     * @param tasks the tasks to resolve profiles for
     * @return map of profile ID to entity
     */
    private Map<Long, ProfileEntity> buildProfileMap(List<TaskEntity> tasks) {
        Map<Long, ProfileEntity> profileMap = new HashMap<>();

        Set<Long> profileIds = new HashSet<>();
        for (TaskEntity task : tasks) {
            profileIds.add(task.profileId());
        }

        for (long profileId : profileIds) {
            try {
                profileRepository.findById(profileId).ifPresent(
                        profile -> profileMap.put(profileId, profile)
                );
            } catch (Database.DatabaseException e) {
                System.err.println("[TaskGroupDetailController] Failed to load profile "
                        + profileId + ": " + e.getMessage());
            }
        }

        return profileMap;
    }

    /**
     * Builds a map of proxy ID → ProxyEntity for the given tasks.
     *
     * @param tasks the tasks to resolve proxies for
     * @return map of proxy ID to entity
     */
    private Map<Long, ProxyEntity> buildProxyMap(List<TaskEntity> tasks) {
        Map<Long, ProxyEntity> proxyMap = new HashMap<>();

        Set<Long> proxyIds = new HashSet<>();
        for (TaskEntity task : tasks) {
            if (task.hasProxy()) {
                proxyIds.add(task.proxyId());
            }
        }

        for (long proxyId : proxyIds) {
            try {
                proxyRepository.findById(proxyId).ifPresent(
                        proxy -> proxyMap.put(proxyId, proxy)
                );
            } catch (Database.DatabaseException e) {
                System.err.println("[TaskGroupDetailController] Failed to load proxy "
                        + proxyId + ": " + e.getMessage());
            }
        }

        return proxyMap;
    }

    /**
     * Resolves a display name for a task based on its linked profile.
     *
     * <p>Format: {@code "Profile Name (email@example.com)"}</p>
     *
     * @param task       the task entity
     * @param profileMap the pre-loaded profile map
     * @return the display name string
     */
    private String resolveDisplayName(TaskEntity task, Map<Long, ProfileEntity> profileMap) {
        ProfileEntity profile = profileMap.get(task.profileId());

        if (profile == null) {
            return "Unknown Profile (Task #" + task.id() + ")";
        }

        String name = profile.displayName();
        String email = profile.emailAddress();

        if (email != null && !email.isBlank()) {
            return name + " (" + email + ")";
        }

        return name;
    }

    /**
     * Resolves a display string for a task's proxy.
     *
     * @param task     the task entity
     * @param proxyMap the pre-loaded proxy map
     * @return the display string, or null if no proxy
     */
    private String resolveProxyDisplay(TaskEntity task, Map<Long, ProxyEntity> proxyMap) {
        if (!task.hasProxy()) {
            return null;
        }

        ProxyEntity proxy = proxyMap.get(task.proxyId());
        if (proxy == null) {
            return null;
        }

        return proxy.toDisplayString();
    }

    // ==================== Row / Page Management ====================

    /**
     * Clears all displayed task rows from the UI.
     *
     * <p>Does NOT close screencast sessions — they persist across page
     * navigations. Used when navigating between pages within the same group.</p>
     */
    private void clearRows() {
        taskRows.clear();
        taskListContainer.getChildren().clear();
    }

    /**
     * Fully resets the page, including closing all screencast sessions.
     *
     * <p>Called at the start of {@link #loadGroup(long)} when entering
     * the page fresh from the Task Manager. Screencasts from a previous
     * group visit are cleaned up.</p>
     */
    private void clearPage() {
        // Close all active screencast sessions
        for (long taskId : new ArrayList<>(screencastSessions.keySet())) {
            closeScreencastSession(taskId);
        }

        clearRows();

        groupNameLabel.setText("Task Group");
        scriptNameLabel.setText("Script");

        currentGroupId = -1;
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
     * @return true if on the last page or if the group is empty
     */
    private boolean isOnLastPage() {
        return totalPages() == 0 || currentPage >= totalPages() - 1;
    }

    // ==================== View State ====================

    /**
     * Updates the view to show either empty state or the task list,
     * and updates the pagination controls.
     */
    private void updateViewState() {
        boolean hasTasks = totalCount > 0;

        emptyState.setVisible(!hasTasks);
        emptyState.setManaged(!hasTasks);

        scrollPane.setVisible(hasTasks);
        scrollPane.setManaged(hasTasks);

        paginationBar.setVisible(hasTasks);
        paginationBar.setManaged(hasTasks);

        if (hasTasks) {
            updatePaginationControls();
        }
    }

    /**
     * Updates the pagination range label and arrow button states.
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
            System.err.println("[TaskGroupDetailController] Could not apply dark theme to alert: "
                    + e.getMessage());
        }

        alert.showAndWait();
    }

    // ==================== Getters ====================

    /**
     * Gets the currently loaded group ID.
     *
     * @return the group ID, or -1 if no group is loaded
     */
    public long currentGroupId() {
        return currentGroupId;
    }

    /**
     * Gets the number of task rows currently displayed.
     *
     * @return the task row count
     */
    public int taskRowCount() {
        return taskRows.size();
    }

    /**
     * Gets all task rows currently displayed.
     *
     * @return unmodifiable list of task rows
     */
    public List<TaskRow> taskRows() {
        return List.copyOf(taskRows);
    }

    /**
     * Finds a task row by task ID on the current page.
     *
     * @param taskId the task ID to search for
     * @return an Optional containing the row, or empty if not on this page
     */
    public Optional<TaskRow> findTaskRow(long taskId) {
        return taskRows.stream()
                .filter(row -> row.taskId() == taskId)
                .findFirst();
    }

    /**
     * Bundles the screencast service and view window for an active session.
     */
    private record ScreencastSession(ScreencastService service, ViewBrowserWindow window) {}
}