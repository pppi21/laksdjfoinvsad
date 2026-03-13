package org.nodriver4j.ui.task;

import javafx.fxml.FXML;
import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.entity.TaskEntity;
import org.nodriver4j.persistence.entity.TaskGroupEntity;
import org.nodriver4j.persistence.repository.ProxyRepository;
import org.nodriver4j.persistence.repository.TaskGroupRepository;
import org.nodriver4j.persistence.repository.TaskRepository;
import org.nodriver4j.services.TaskExecutionService;
import org.nodriver4j.ui.controllers.GroupManagerController;
import org.nodriver4j.ui.controllers.MainController;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * Controller for the Task Manager page.
 *
 * <p>Manages task group creation, display, and interaction. Extends
 * {@link GroupManagerController} which provides the shared page layout,
 * card management, empty state toggle, and error alert infrastructure.</p>
 *
 * <p>This controller is paired with {@code group-manager.fxml} (shared layout).
 * {@link MainController} assigns this controller programmatically via
 * {@code loader.setController(new TaskManagerController())} before loading.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Provide task-manager-specific page text</li>
 *   <li>Load persisted task groups on initialization</li>
 *   <li>Handle "Add Task Group" button → dialog → persist → card</li>
 *   <li>Handle card click → navigation to group detail</li>
 *   <li>Handle card delete → persist → remove card</li>
 *   <li>Build {@link TaskGroupCard} instances with wired callbacks</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>FXML injection for shared layout (inherited from {@link GroupManagerController})</li>
 *   <li>Card list / grid synchronization (inherited)</li>
 *   <li>Empty state toggle (inherited)</li>
 *   <li>Error alert display (inherited)</li>
 *   <li>Running automation scripts (service layer)</li>
 *   <li>Profile/proxy management (separate controllers)</li>
 *   <li>SQL queries (delegated to repositories)</li>
 * </ul>
 *
 * @see GroupManagerController
 * @see TaskGroupCard
 * @see CreateTaskGroupDialog
 */
public class TaskManagerController extends GroupManagerController<TaskGroupCard> {

    // ==================== Repositories ====================

    private final TaskGroupRepository taskGroupRepository = new TaskGroupRepository();
    private final TaskRepository taskRepository = new TaskRepository();
    private final ProxyRepository proxyRepository = new ProxyRepository();

    // ==================== Callbacks ====================

    /**
     * Callback invoked when a task group card is clicked.
     * Set by {@link MainController} to navigate to the group detail page.
     * Accepts the group ID as a parameter.
     */
    private LongConsumer onNavigateToGroup;

    // ==================== Page Text ====================

    @Override
    protected String pageTitle() {
        return "Task Manager";
    }

    @Override
    protected String pageSubtitle() {
        return "Create and manage your automation task groups";
    }

    @Override
    protected String emptyStateIcon() {
        return "📋";
    }

    @Override
    protected String emptyStateTitle() {
        return "No Task Groups";
    }

    @Override
    protected String emptyStateDescription() {
        return "Click the + button to create your first task group";
    }

    // ==================== Data Loading ====================

    /**
     * Loads all task groups from the database and creates cards for them.
     *
     * <p>Groups are returned newest-first from the repository, which matches
     * the desired grid display order. Task counts are queried per group;
     * running counts default to 0 until task execution is wired.</p>
     */
    @Override
    protected void loadGroups() {
        try {
            List<TaskGroupEntity> groups = taskGroupRepository.findAll();

            for (TaskGroupEntity group : groups) {
                int taskCount = (int) taskRepository.countByGroupId(group.id());
                TaskGroupCard card = buildCard(group.id(), group.name(), group.scriptName(), taskCount);
                addCard(card);
            }

            System.out.println("[TaskManagerController] Loaded " + groups.size()
                    + " task groups from database");

        } catch (Database.DatabaseException e) {
            System.err.println("[TaskManagerController] Failed to load task groups: " + e.getMessage());
            showErrorAlert("Failed to Load Task Groups",
                    "Could not load task groups from the database.",
                    e.getMessage());
        }
    }

    // ==================== Add Button ====================

    /**
     * Handles the "Add Task Group" button click.
     * Opens a dialog to create a new task group.
     */
    @FXML
    @Override
    protected void onAddClicked() {
        System.out.println("[TaskManagerController] Add button clicked");

        CreateTaskGroupDialog dialog = new CreateTaskGroupDialog(ownerWindow());
        Optional<CreateTaskGroupDialog.TaskGroupData> result = dialog.showAndWait();

        result.ifPresent(this::createTaskGroup);
    }

    // ==================== Task Group Management ====================

    /**
     * Creates a new task group from dialog data.
     *
     * <p>Persists the group to the database first. If persistence succeeds,
     * builds a card and inserts it at the beginning of the grid (newest-first).
     * If persistence fails, shows an error alert and does not create the card.</p>
     *
     * @param data the task group data from the dialog
     */
    private void createTaskGroup(CreateTaskGroupDialog.TaskGroupData data) {
        System.out.println("[TaskManagerController] Creating task group: "
                + data.name() + " (" + data.script() + ")");

        // Persist to database first
        TaskGroupEntity entity;
        try {
            entity = new TaskGroupEntity(data.name(), data.script());
            entity = taskGroupRepository.save(entity);
        } catch (Database.DatabaseException e) {
            System.err.println("[TaskManagerController] Failed to save task group: " + e.getMessage());
            showErrorAlert("Failed to Create Task Group",
                    "Could not save the task group to the database.",
                    e.getMessage());
            return;
        }

        // Build card with the persisted entity's ID and insert at beginning
        TaskGroupCard card = buildCard(entity.id(), entity.name(), entity.scriptName(), 0);
        addCardFirst(card);

        System.out.println("[TaskManagerController] Task group created with ID " + entity.id()
                + ". Total groups: " + cardCount());
    }

    /**
     * Handles a task group card being clicked.
     * Navigates to the group detail page via the callback set by MainController.
     *
     * @param card the clicked card
     */
    private void onTaskGroupClicked(TaskGroupCard card) {
        System.out.println("[TaskManagerController] Task group clicked: "
                + card.groupName() + " (ID: " + card.groupId() + ")");

        if (onNavigateToGroup != null) {
            onNavigateToGroup.accept(card.groupId());
        }
    }

    /**
     * Handles a task group card being deleted.
     *
     * <p>Performs a full cascading cleanup before removing the group:</p>
     * <ol>
     *   <li>Stop all active browsers in the group</li>
     *   <li>Delete userdata directories from disk</li>
     *   <li>Clean up standalone proxies (null-group proxies from edit dialog)</li>
     *   <li>Delete the group from the database (CASCADE removes child tasks)</li>
     *   <li>Remove the card from the UI</li>
     * </ol>
     *
     * <p>If the database deletion fails, an error alert is shown and the
     * card remains in place. Browser and disk cleanup are best-effort —
     * failures are logged but do not prevent group deletion.</p>
     *
     * @param card the card to delete
     */
    private void onTaskGroupDeleted(TaskGroupCard card) {
        long groupId = card.groupId();
        System.out.println("[TaskManagerController] Deleting task group: "
                + card.groupName() + " (ID: " + groupId + ")");

        try {
            List<TaskEntity> tasks = taskRepository.findByGroupId(groupId);
            TaskExecutionService service = TaskExecutionService.instance();

            // Stop all active browsers in the group
            for (TaskEntity task : tasks) {
                if (service.isActive(task.id())) {
                    service.stopBrowser(task.id());
                }
            }

            // Clean up userdata directories and standalone proxies
            for (TaskEntity task : tasks) {
                if (task.hasUserdataPath()) {
                    TaskExecutionService.deleteDirectoryWithRetry(Path.of(task.userdataPath()));
                }
                proxyRepository.deleteIfStandalone(task.proxyId());
            }

            // Delete the group — CASCADE removes child task rows from the database
            taskGroupRepository.deleteById(groupId);

        } catch (Database.DatabaseException e) {
            System.err.println("[TaskManagerController] Failed to delete task group: " + e.getMessage());
            showErrorAlert("Failed to Delete Task Group",
                    "Could not delete the task group from the database.",
                    e.getMessage());
            return;
        }

        removeCard(card);

        System.out.println("[TaskManagerController] Task group deleted. Total groups: " + cardCount());
    }

    /**
     * Refreshes task count and running count on all task group cards.
     *
     * <p>For each card, loads all tasks in the group and checks the active
     * state via {@link TaskExecutionService#isActive(long)}. This gives an
     * accurate count based on actual browser/thread state rather than
     * potentially stale database status values.</p>
     *
     * <p>Intended to be called when navigating back to the Task Manager page
     * so that cards reflect any changes made on the detail page.</p>
     */
    public void refreshCardStats() {
        TaskExecutionService service = TaskExecutionService.instance();

        for (TaskGroupCard card : cards()) {
            long groupId = card.groupId();

            try {
                List<TaskEntity> tasks = taskRepository.findByGroupId(groupId);
                card.setTaskCount(tasks.size());

                int runningCount = 0;
                for (TaskEntity task : tasks) {
                    if (service.isActive(task.id())) {
                        runningCount++;
                    }
                }
                card.setRunningCount(runningCount);

            } catch (Database.DatabaseException e) {
                System.err.println("[TaskManagerController] Failed to refresh stats for group "
                        + groupId + ": " + e.getMessage());
            }
        }
    }

    // ==================== Card Building ====================

    /**
     * Builds a {@link TaskGroupCard} with standard click and delete handlers.
     *
     * @param groupId    the database ID
     * @param name       the group name
     * @param scriptName the script name
     * @param taskCount  the current task count
     * @return the configured card
     */
    private TaskGroupCard buildCard(long groupId, String name, String scriptName, int taskCount) {
        TaskGroupCard card = new TaskGroupCard(
                groupId,
                name,
                scriptName,
                taskCount,
                0  // Running count — not wired until task execution is implemented
        );

        card.setOnClick(() -> onTaskGroupClicked(card));
        card.setOnDelete(() -> onTaskGroupDeleted(card));

        return card;
    }

    // ==================== Public API ====================

    /**
     * Gets the number of task groups.
     *
     * @return the count of task groups
     */
    public int taskGroupCount() {
        return cardCount();
    }

    /**
     * Gets all task group cards.
     *
     * @return unmodifiable list of cards
     */
    public List<TaskGroupCard> taskGroupCards() {
        return cards();
    }

    /**
     * Clears all task groups from the UI and database.
     * Future: Add confirmation dialog.
     */
    public void clearAllTaskGroups() {
        try {
            taskGroupRepository.deleteAll();
        } catch (Database.DatabaseException e) {
            System.err.println("[TaskManagerController] Failed to clear all task groups: " + e.getMessage());
            showErrorAlert("Failed to Clear Task Groups",
                    "Could not delete all task groups from the database.",
                    e.getMessage());
            return;
        }

        clearCards();

        System.out.println("[TaskManagerController] All task groups cleared");
    }

    /**
     * Sets the callback for navigating to a task group detail page.
     *
     * <p>Called by {@link MainController} after loading this controller
     * to wire navigation without tight coupling.</p>
     *
     * @param onNavigateToGroup callback that accepts the group ID
     */
    public void setOnNavigateToGroup(LongConsumer onNavigateToGroup) {
        this.onNavigateToGroup = onNavigateToGroup;
    }
}