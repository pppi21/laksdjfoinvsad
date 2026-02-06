package org.nodriver4j.ui.controllers;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.entity.ProxyEntity;
import org.nodriver4j.persistence.entity.TaskEntity;
import org.nodriver4j.persistence.entity.TaskGroupEntity;
import org.nodriver4j.persistence.repository.*;
import org.nodriver4j.ui.components.TaskRow;
import org.nodriver4j.ui.dialogs.CreateTaskDialog;

import java.net.URL;
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

    // ==================== Callbacks ====================

    /**
     * Callback invoked when the back button is clicked.
     * Set by {@link MainController} to navigate back to the Task Manager.
     */
    private Runnable onBack;

    /**
     * Wires all action button callbacks on a TaskRow.
     *
     * <p>All callbacks are stubs for now — they log the action and task ID.
     * Execution logic will be wired in a future stage when the service
     * layer is implemented.</p>
     *
     * @param row the task row to wire
     */
    private void wireRowCallbacks(TaskRow row) {
        long id = row.taskId();

        row.setOnStart(taskId ->
                System.out.println("[TaskGroupDetailController] Start task #" + taskId));

        row.setOnStop(taskId ->
                System.out.println("[TaskGroupDetailController] Stop task #" + taskId));

        row.setOnOpenViewBrowser(taskId ->
                System.out.println("[TaskGroupDetailController] Open view browser — Task #" + taskId));

        row.setOnCloseViewBrowser(taskId ->
                System.out.println("[TaskGroupDetailController] Close view browser — Task #" + taskId));

        row.setOnOpenManualBrowser(taskId ->
                System.out.println("[TaskGroupDetailController] Open manual browser — Task #" + taskId));

        row.setOnCloseManualBrowser(taskId ->
                System.out.println("[TaskGroupDetailController] Close manual browser — Task #" + taskId));

        row.setOnClone(taskId -> {
            System.out.println("[TaskGroupDetailController] Clone task #" + taskId);
            // TODO: Duplicate task entity and append new row
        });

        row.setOnEdit(taskId -> {
            System.out.println("[TaskGroupDetailController] Edit task #" + taskId);
            // TODO: Open edit dialog with single profile + proxy string
        });

        row.setOnDelete(taskId -> {
            System.out.println("[TaskGroupDetailController] Delete task #" + taskId);
            // TODO: Delete from DB, remove userdata, remove row from list
        });
    }

    // ==================== Initialization ====================

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        System.out.println("[TaskGroupDetailController] Initialized (awaiting loadGroup call)");

        // Page starts in empty state until loadGroup is called
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

            // Bulk-load profiles to avoid N+1 queries
            Map<Long, ProfileEntity> profileMap = buildProfileMap(tasks);

            // Create a TaskRow for each task
            for (TaskEntity task : tasks) {
                String displayName = resolveDisplayName(task, profileMap);
                String statusText = task.displayStatus();

                TaskRow row = new TaskRow(task.id(), displayName, statusText);
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

            // Build profile map for display name resolution
            Map<Long, ProfileEntity> profileMap = buildProfileMap(tasks);

            // Create a TaskRow for each saved task
            for (TaskEntity task : tasks) {
                String displayName = resolveDisplayName(task, profileMap);
                TaskRow row = new TaskRow(task.id(), displayName, task.displayStatus());
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
        // TODO: Iterate taskRows and start each task via service layer
    }

    @FXML
    private void onStopAllClicked() {
        System.out.println("[TaskGroupDetailController] Stop All clicked for group " + currentGroupId);
        // TODO: Iterate taskRows and stop each running task via service layer
    }

    @FXML
    private void onChangeProxiesClicked() {
        System.out.println("[TaskGroupDetailController] Change Proxies clicked for group " + currentGroupId);
        // TODO: Open proxy group selection dialog and reassign proxies
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
}