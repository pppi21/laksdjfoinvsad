package org.nodriver4j.ui.controllers;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
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
 * <p>Displays individual tasks belonging to a task group as full-width rows.
 * This page is navigated to by clicking a task group card in the Task Manager.
 * The controller is parameterized — {@link #loadGroup(long)} must be called
 * after FXML initialization to populate the page with a specific group's data.</p>
 *
 * <h2>Navigation Pattern</h2>
 * <p>The FXML is loaded and cached once by {@link MainController}. Each time
 * the user navigates to a group, {@code MainController} calls
 * {@link #loadGroup(long)} which clears existing state and repopulates
 * from the database. A back navigation callback is set via
 * {@link #setOnBack(Runnable)} so this controller has no direct dependency
 * on {@code MainController}.</p>
 *
 * <h2>Display Name Assembly</h2>
 * <p>Each task is linked to a profile by {@code profile_id}. The controller
 * resolves profiles from the database and assembles display names in the
 * format {@code "Profile Name (email@example.com)"}. If a profile cannot
 * be found (e.g., deleted), a fallback name is used.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Load task group details and display in the header</li>
 *   <li>Load tasks for the group and create {@link TaskRow} components</li>
 *   <li>Resolve profile display names from {@link ProfileRepository}</li>
 *   <li>Handle back navigation via callback</li>
 *   <li>Show/hide empty state vs task list</li>
 *   <li>Display error alerts on database failures</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Task execution or browser lifecycle (future service layer)</li>
 *   <li>Database connection management (delegated to {@link Database})</li>
 *   <li>SQL queries (delegated to repositories)</li>
 *   <li>Row rendering (delegated to {@link TaskRow})</li>
 *   <li>Task creation dialog (deferred to a future stage)</li>
 *   <li>Page caching or FXML loading (handled by {@link MainController})</li>
 * </ul>
 */
public class TaskGroupDetailController implements Initializable {

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
    private VBox emptyState;

    @FXML
    private ScrollPane scrollPane;

    @FXML
    private VBox taskListContainer;

    @FXML
    private Button startAllButton;

    @FXML
    private Button stopAllButton;

    @FXML
    private Button changeProxiesButton;

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
     * List of task rows currently displayed.
     */
    private final List<TaskRow> taskRows = new ArrayList<>();

    /**
     * Active screencast sessions mapped by task ID.
     * Each entry represents an open view browser window streaming from a running task.
     */
    private final Map<Long, ScreencastSession> screencastSessions = new HashMap<>();

    // ==================== Callbacks ====================

    /**
     * Callback invoked when the back button is clicked.
     * Set by {@link MainController} to navigate back to the Task Manager.
     */
    private Runnable onBack;

    /**
     * Wires all action button callbacks on a TaskRow.
     *
     * <p>Lifecycle callbacks (start, stop, manual browser) delegate to
     * helper methods that coordinate with {@link TaskExecutionService}.
     * View browser callbacks manage screencast sessions directly.
     * Clone, edit, and delete callbacks are wired in Stage 4B.</p>
     *
     * @param row the task row to wire
     */
    private void wireRowCallbacks(TaskRow row) {
        long id = row.taskId();

        // ---- Lifecycle callbacks (Stage 4A) ----

        row.setOnStart(taskId -> requestStart(row));

        row.setOnStop(taskId -> requestStop(row));

        row.setOnOpenManualBrowser(taskId -> openManualBrowser(row));

        // ---- View browser callbacks (already functional) ----

        row.setOnOpenViewBrowser(taskId -> {
            System.out.println("[TaskGroupDetailController] Open view browser — Task #" + taskId);

            Browser browser = TaskExecutionService.instance().browser(taskId);
            if (browser == null || !browser.isOpen()) {
                System.err.println("[TaskGroupDetailController] No active browser for task " + taskId);
                return;
            }

            // Create screencast service and view window
            ScreencastService service = new ScreencastService(browser.cdpClient());
            ViewBrowserWindow window = new ViewBrowserWindow("View Browser — Task #" + taskId);

            // Wire window close (user clicks X) to clean up
            window.setOnClose(() -> {
                closeScreencastSession(taskId);
                findTaskRow(taskId).ifPresent(r -> r.setViewBrowserActive(false));
            });

            // Track the session before starting
            screencastSessions.put(taskId, new ScreencastSession(service, window));

            // Start streaming — frames arrive on WebSocket thread, marshal to FX thread
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

        // ---- Data callbacks (Stage 4B stubs) ----

        row.setOnClone(taskId -> {
            System.out.println("[TaskGroupDetailController] Clone task #" + taskId);

            Optional<TaskEntity> taskOpt = taskRepository.findById(taskId);
            if (taskOpt.isEmpty()) {
                System.err.println("[TaskGroupDetailController] Task not found for clone: " + taskId);
                return;
            }

            TaskEntity original = taskOpt.get();

            // Build a fresh copy — keep profile, proxy, warmSession, notes
            // Reset identity, status, userdata, custom status, and log
            TaskEntity clone = original.toBuilder()
                    .id(0)
                    .status(TaskEntity.STATUS_IDLE)
                    .userdataPath(null)
                    .customStatus(null)
                    .logMessage(null)
                    .logColor(null)
                    .createdAt(null)   // let builder default to now
                    .updatedAt(null)   // let builder default to now
                    .build();

            taskRepository.save(clone);

            // Resolve display name
            String displayName = profileRepository.findById(clone.profileId())
                    .map(profile -> {
                        String name = profile.displayName();
                        String email = profile.emailAddress();
                        return (email != null && !email.isBlank())
                                ? name + " (" + email + ")"
                                : name;
                    })
                    .orElse("Unknown Profile (Task #" + clone.id() + ")");

            // Resolve proxy display
            String proxyDisplay = null;
            if (clone.hasProxy()) {
                proxyDisplay = proxyRepository.findById(clone.proxyId())
                        .map(ProxyEntity::toDisplayString)
                        .orElse(null);
            }

            // Create row and wire it up
            TaskRow cloneRow = new TaskRow(clone.id(), displayName, clone.displayStatus());
            cloneRow.setProxyText(proxyDisplay);

            wireRowCallbacks(cloneRow);
            taskRows.add(cloneRow);
            taskListContainer.getChildren().add(cloneRow);

            System.out.println("[TaskGroupDetailController] Cloned task #" + taskId
                    + " → new task #" + clone.id());
        });

        row.setOnEdit(taskId -> {
            System.out.println("[TaskGroupDetailController] Edit task #" + taskId);

            // Load current task state
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
                // --- Handle proxy change ---
                Long oldProxyId = task.proxyId();
                Long newProxyId;

                if (result.proxyString() != null) {
                    // Create a standalone proxy (null group)
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

                // Clean up old standalone proxy if it was replaced
                proxyRepository.deleteIfStandalone(oldProxyId);

                // Refresh the row
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

                // Clean up standalone proxy (null-group proxies created via edit)
                proxyRepository.deleteIfStandalone(task.proxyId());
            }

            // Remove row from UI
            findTaskRow(taskId).ifPresent(r -> {
                taskRows.remove(r);
                taskListContainer.getChildren().remove(r);
            });

            updateViewState();

            System.out.println("[TaskGroupDetailController] Deleted task #" + taskId);
        });
    }

    // ==================== Task Lifecycle Helpers ====================

    /**
     * Requests a scripted task start for the given row.
     *
     * <p>Sets the row to a transitional "STARTING..." state immediately, then
     * calls {@link TaskExecutionService#startTask(long, java.util.function.BiConsumer,
     * java.util.function.Consumer)} which spawns its own daemon thread and returns
     * immediately. Log and status callbacks are wrapped with {@code Platform.runLater}
     * to marshal updates back to the FX thread.</p>
     *
     * <p>If the service rejects the start (e.g., task already active, service
     * shutdown, missing settings), the row is set to FAILED with an error log.</p>
     *
     * @param row the task row to start
     */
    private void requestStart(TaskRow row) {
        long taskId = row.taskId();
        row.setStatus("STARTING...");

        try {
            TaskExecutionService.instance().startTask(taskId,
                    // Log callback — invoked on script thread, marshalled to FX
                    (msg, color) -> Platform.runLater(() -> row.setLogText(msg, color)),
                    // Status callback — invoked on script thread, marshalled to FX
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
     * calls {@link TaskExecutionService#stopBrowser(long)} on a background
     * thread since it may block briefly while terminating the Chrome process.
     * The row status is set to STOPPED on the FX thread after the browser
     * is closed.</p>
     *
     * <p>For RUNNING tasks that were started via {@link #requestStart(TaskRow)},
     * the script thread's status callback will also fire STOPPED. The duplicate
     * UI update is harmless.</p>
     *
     * @param row the task row to stop
     */
    private void requestStop(TaskRow row) {
        long taskId = row.taskId();

        // Close screencast on FX thread (window.close() requires FX thread)
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
     * <p>Sets the row to a transitional "STARTING..." state immediately, then
     * launches the browser on a background thread since
     * {@link TaskExecutionService#startManualBrowser(long)} blocks until the
     * browser is fully initialized. On success the row transitions to MANUAL;
     * on failure it transitions to FAILED with an error log.</p>
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

    // ==================== Initialization ====================

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        System.out.println("[TaskGroupDetailController] Initialized (awaiting loadGroup call)");
        SmoothScrollHelper.apply(scrollPane);
        updateViewState();
    }

    // ==================== Group Loading ====================

    /**
     * Loads a task group and its tasks into the page.
     *
     * <p>Clears any previously displayed data, then fetches the group
     * entity and its tasks from the database. For each task, the associated
     * profile is resolved to assemble a display name.</p>
     *
     * <p>This method should be called by {@link MainController} every time
     * the user navigates to this page.</p>
     *
     * @param groupId the database ID of the task group to load
     */
    public void loadGroup(long groupId) {
        System.out.println("[TaskGroupDetailController] Loading group ID: " + groupId);

        // Clear previous state
        clearPage();
        this.currentGroupId = groupId;

        // Load group entity for header info
        loadGroupHeader(groupId);

        // Load tasks for this group
        loadTasks(groupId);

        // Update view based on whether tasks exist
        updateViewState();
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

    /**
     * Loads all tasks for the group and creates {@link TaskRow} components.
     *
     * <p>Profiles are bulk-loaded for the group's tasks to avoid N+1 queries.
     * Each task's display name is assembled as
     * {@code "Profile Name (email@example.com)"}.</p>
     *
     * @param groupId the group ID
     */
    private void loadTasks(long groupId) {
        try {
            List<TaskEntity> tasks = taskRepository.findByGroupId(groupId);

            if (tasks.isEmpty()) {
                System.out.println("[TaskGroupDetailController] No tasks found for group " + groupId);
                return;
            }

            // Bulk-load profiles and proxies to avoid N+1 queries
            Map<Long, ProfileEntity> profileMap = buildProfileMap(tasks);
            Map<Long, ProxyEntity> proxyMap = buildProxyMap(tasks);

            // Create a TaskRow for each task
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
                taskRows.add(row);
                taskListContainer.getChildren().add(row);
            }

            System.out.println("[TaskGroupDetailController] Loaded " + tasks.size()
                    + " tasks for group " + groupId);

        } catch (Database.DatabaseException e) {
            System.err.println("[TaskGroupDetailController] Failed to load tasks: " + e.getMessage());
            showErrorAlert("Failed to Load Tasks",
                    "Could not load tasks from the database.",
                    e.getMessage());
        }
    }

    // ==================== Profile Resolution ====================

    /**
     * Builds a map of profile ID → ProfileEntity for the given tasks.
     *
     * <p>Collects unique profile IDs from the task list, then fetches
     * each profile individually. A future optimization could add a
     * {@code findByIds(List<Long>)} method to the repository.</p>
     *
     * @param tasks the tasks to resolve profiles for
     * @return map of profile ID to entity
     */
    private Map<Long, ProfileEntity> buildProfileMap(List<TaskEntity> tasks) {
        Map<Long, ProfileEntity> profileMap = new HashMap<>();

        // Collect unique profile IDs
        Set<Long> profileIds = new HashSet<>();
        for (TaskEntity task : tasks) {
            profileIds.add(task.profileId());
        }

        // Fetch each profile
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
     * <p>Only resolves proxies for tasks that have a non-null proxy ID.
     * Follows the same pattern as {@link #buildProfileMap(List)}.</p>
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
     * <p>Fallback: {@code "Unknown Profile (Task #ID)"} if the profile
     * cannot be found (e.g., deleted after task creation).</p>
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
     * <p>Returns the masked proxy string (e.g., "host:8080:user:***")
     * or null if the task has no proxy assigned.</p>
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

    // ==================== Event Handlers ====================

    /**
     * Handles the back button click.
     * Delegates to the navigation callback set by {@link MainController}.
     */
    @FXML
    private void onBackClicked() {
        System.out.println("[TaskGroupDetailController] Back button clicked");

        if (onBack != null) {
            onBack.run();
        }
    }

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

            // Build profile and proxy maps for display resolution
            Map<Long, ProfileEntity> profileMap = buildProfileMap(tasks);
            Map<Long, ProxyEntity> proxyMap = buildProxyMap(tasks);

            // Create a TaskRow for each saved task
            for (TaskEntity task : tasks) {
                String displayName = resolveDisplayName(task, profileMap);
                String proxyDisplay = resolveProxyDisplay(task, proxyMap);
                TaskRow row = new TaskRow(task.id(), displayName, task.displayStatus());
                row.setProxyText(proxyDisplay);

                // Restore persisted log state (newly created tasks won't have one,
                // but this keeps the pattern consistent)
                if (task.hasLogMessage()) {
                    row.setLogText(task.logMessage(), task.logColor());
                }

                wireRowCallbacks(row);
                taskRows.add(row);
                taskListContainer.getChildren().add(row);
            }

            updateViewState();

            System.out.println("[TaskGroupDetailController] Created " + tasks.size() + " tasks");
        });
    }

    @FXML
    private void onStartAllClicked() {
        System.out.println("[TaskGroupDetailController] Start All clicked for group " + currentGroupId);

        TaskExecutionService service = TaskExecutionService.instance();

        for (TaskRow row : taskRows) {
            if (service.isActive(row.taskId())) {
                continue; // Skip tasks that already have a browser or script thread
            }
            requestStart(row);
        }
    }

    @FXML
    private void onStopAllClicked() {
        System.out.println("[TaskGroupDetailController] Stop All clicked for group " + currentGroupId);

        TaskExecutionService service = TaskExecutionService.instance();

        for (TaskRow row : taskRows) {
            if (service.isActive(row.taskId())) {
                requestStop(row);
            }
        }
    }

    @FXML
    private void onChangeProxiesClicked() {
        System.out.println("[TaskGroupDetailController] Change Proxies clicked for group " + currentGroupId);

        ChangeProxiesDialog dialog = new ChangeProxiesDialog(
                changeProxiesButton.getScene().getWindow(),
                proxyGroupRepository,
                proxyRepository,
                taskRows.size()
        );

        dialog.showAndWait().ifPresent(result -> {
            Long proxyGroupId = result.proxyGroupId();

            // Load proxies from the selected group (empty list if "None")
            List<ProxyEntity> proxies = (proxyGroupId != null)
                    ? proxyRepository.findByGroupId(proxyGroupId)
                    : List.of();

            // Reassign proxies across tasks in row order
            for (int i = 0; i < taskRows.size(); i++) {
                TaskRow row = taskRows.get(i);

                Optional<TaskEntity> taskOpt = taskRepository.findById(row.taskId());
                if (taskOpt.isEmpty()) {
                    continue;
                }

                TaskEntity task = taskOpt.get();
                Long oldProxyId = task.proxyId();

                if (i < proxies.size()) {
                    // Assign proxy from the new group
                    ProxyEntity proxy = proxies.get(i);
                    task.proxyId(proxy.id());
                    task.touchUpdatedAt();
                    taskRepository.save(task);
                    row.setProxyText(proxy.toDisplayString());
                } else if (proxyGroupId == null) {
                    // "None" selected — clear proxy from all tasks
                    task.proxyId(null);
                    task.touchUpdatedAt();
                    taskRepository.save(task);
                    row.setProxyText(null);
                } else {
                    // More tasks than proxies — leave remaining tasks unchanged
                    continue;
                }

                // Clean up old standalone proxy (null-group proxies from edit dialog)
                proxyRepository.deleteIfStandalone(oldProxyId);
            }

            System.out.println("[TaskGroupDetailController] Proxies changed — group "
                    + (proxyGroupId != null ? "#" + proxyGroupId : "None")
                    + ", " + Math.min(proxies.size(), taskRows.size()) + " tasks updated");
        });
    }

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

    // ==================== View State Management ====================

    /**
     * Updates the view to show either empty state or the task list.
     */
    private void updateViewState() {
        boolean hasTasks = !taskRows.isEmpty();

        emptyState.setVisible(!hasTasks);
        emptyState.setManaged(!hasTasks);

        scrollPane.setVisible(hasTasks);
        scrollPane.setManaged(hasTasks);
    }

    /**
     * Clears all displayed data from the page.
     *
     * <p>Called at the start of {@link #loadGroup(long)} to ensure
     * clean state before populating with new data.</p>
     */
    private void clearPage() {
        // Close all active screencast sessions
        for (long taskId : new ArrayList<>(screencastSessions.keySet())) {
            closeScreencastSession(taskId);
        }

        taskRows.clear();
        taskListContainer.getChildren().clear();

        groupNameLabel.setText("Task Group");
        scriptNameLabel.setText("Script");

        currentGroupId = -1;
    }

    // ==================== Error Handling ====================

    /**
     * Shows an error alert dialog to the user.
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

    // ==================== Callbacks ====================

    /**
     * Sets the callback for back navigation.
     *
     * <p>Called by {@link MainController} after loading this controller
     * to wire navigation without tight coupling.</p>
     *
     * @param onBack the callback to invoke when back is clicked
     */
    public void setOnBack(Runnable onBack) {
        this.onBack = onBack;
    }

    // ==================== Public API ====================

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
     * Finds a task row by task ID.
     *
     * @param taskId the task ID to search for
     * @return an Optional containing the row, or empty if not found
     */
    public Optional<TaskRow> findTaskRow(long taskId) {
        return taskRows.stream()
                .filter(row -> row.taskId() == taskId)
                .findFirst();
    }


    /**
     * Bundles the screencast service and view window for an active session.
     * Used to track and clean up view browser sessions per task.
     */
    private record ScreencastSession(ScreencastService service, ViewBrowserWindow window) {}
}