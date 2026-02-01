package org.nodriver4j.ui.controllers;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.entity.TaskGroupEntity;
import org.nodriver4j.persistence.repository.TaskGroupRepository;
import org.nodriver4j.persistence.repository.TaskRepository;
import org.nodriver4j.ui.components.TaskGroupCard;
import org.nodriver4j.ui.dialogs.CreateTaskGroupDialog;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Controller for the Task Manager page.
 *
 * <p>Manages task group creation, display, and interaction. Task groups
 * are persisted to the database and loaded on startup so they survive
 * application restarts.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Load persisted task groups on initialization</li>
 *   <li>Handle "Add Task Group" button click</li>
 *   <li>Persist new task groups to the database</li>
 *   <li>Delete task groups from the database</li>
 *   <li>Show/hide empty state vs grid</li>
 *   <li>Create and manage TaskGroupCard components</li>
 *   <li>Handle card click events (navigate to group detail)</li>
 *   <li>Display error alerts on database failures</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Actually running automation scripts (delegated to service layer)</li>
 *   <li>Profile/proxy management (separate controllers)</li>
 *   <li>Database connection management (delegated to {@link Database})</li>
 *   <li>SQL queries (delegated to repositories)</li>
 *   <li>Card rendering (delegated to {@link TaskGroupCard})</li>
 * </ul>
 */
public class TaskManagerController implements Initializable {

    // ==================== FXML Injected Fields ====================

    @FXML
    private Button addButton;

    @FXML
    private VBox emptyState;

    @FXML
    private ScrollPane scrollPane;

    @FXML
    private FlowPane taskGroupGrid;

    // ==================== Repositories ====================

    private final TaskGroupRepository taskGroupRepository = new TaskGroupRepository();
    private final TaskRepository taskRepository = new TaskRepository();

    // ==================== Internal State ====================

    /**
     * List of task group cards currently displayed.
     */
    private final List<TaskGroupCard> taskGroupCards = new ArrayList<>();

    // ==================== Initialization ====================

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        System.out.println("[TaskManagerController] Initializing...");

        // Load persisted task groups from database
        loadTaskGroups();

        // Update view based on whether groups exist
        updateViewState();

        System.out.println("[TaskManagerController] Initialized successfully");
    }

    /**
     * Loads all task groups from the database and creates cards for them.
     *
     * <p>Groups are returned newest-first from the repository, which matches
     * the desired grid display order. Task counts are queried per group;
     * running counts default to 0 until task execution is wired.</p>
     */
    private void loadTaskGroups() {
        try {
            List<TaskGroupEntity> groups = taskGroupRepository.findAll();

            for (TaskGroupEntity group : groups) {
                int taskCount = (int) taskRepository.countByGroupId(group.id());

                TaskGroupCard card = buildCard(group.id(), group.name(), group.scriptName(), taskCount);
                taskGroupCards.add(card);
                taskGroupGrid.getChildren().add(card);
            }

            System.out.println("[TaskManagerController] Loaded " + groups.size() + " task groups from database");

        } catch (Database.DatabaseException e) {
            System.err.println("[TaskManagerController] Failed to load task groups: " + e.getMessage());
            showErrorAlert("Failed to Load Task Groups",
                    "Could not load task groups from the database.",
                    e.getMessage());
        }
    }

    // ==================== Event Handlers ====================

    /**
     * Handles the "Add Task Group" button click.
     * Opens a dialog to create a new task group.
     */
    @FXML
    private void onAddTaskGroupClicked() {
        System.out.println("[TaskManagerController] Add button clicked");

        // Get the owner window from the button's scene
        Window owner = addButton.getScene().getWindow();

        // Show the create dialog (centered on owner, moves with owner)
        CreateTaskGroupDialog dialog = new CreateTaskGroupDialog(owner);
        Optional<CreateTaskGroupDialog.TaskGroupData> result = dialog.showAndWait();

        // Handle the result
        result.ifPresent(this::createTaskGroup);
    }

    // ==================== Task Group Management ====================

    /**
     * Creates a new task group from dialog data.
     *
     * <p>Persists the group to the database first. If persistence succeeds,
     * builds a card and adds it to the grid. If persistence fails, shows
     * an error alert and does not create the card.</p>
     *
     * @param data the task group data from the dialog
     */
    private void createTaskGroup(CreateTaskGroupDialog.TaskGroupData data) {
        System.out.println("[TaskManagerController] Creating task group: " + data.name() + " (" + data.script() + ")");

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

        // Build card with the persisted entity's ID
        TaskGroupCard card = buildCard(entity.id(), entity.name(), entity.scriptName(), 0);

        // Add to the beginning of the list and grid (newest first)
        taskGroupCards.addFirst(card);
        taskGroupGrid.getChildren().addFirst(card);

        // Update view state (show grid, hide empty state)
        updateViewState();

        System.out.println("[TaskManagerController] Task group created with ID " + entity.id()
                + ". Total groups: " + taskGroupCards.size());
    }

    /**
     * Handles a task group card being clicked.
     * Future: Navigate to group detail page.
     *
     * @param card the clicked card
     */
    private void onTaskGroupClicked(TaskGroupCard card) {
        System.out.println("[TaskManagerController] Task group clicked: "
                + card.groupName() + " (ID: " + card.groupId() + ")");

        // Future: Navigate to group detail page
    }

    /**
     * Handles a task group card being deleted.
     *
     * <p>Deletes from the database first. If deletion succeeds, removes the
     * card from the UI. If deletion fails, shows an error alert and leaves
     * the card in place.</p>
     *
     * @param card the card to delete
     */
    private void onTaskGroupDeleted(TaskGroupCard card) {
        System.out.println("[TaskManagerController] Deleting task group: "
                + card.groupName() + " (ID: " + card.groupId() + ")");

        // Delete from database first (CASCADE will remove child tasks)
        try {
            taskGroupRepository.deleteById(card.groupId());
        } catch (Database.DatabaseException e) {
            System.err.println("[TaskManagerController] Failed to delete task group: " + e.getMessage());
            showErrorAlert("Failed to Delete Task Group",
                    "Could not delete the task group from the database.",
                    e.getMessage());
            return;
        }

        // Remove from tracking list and grid
        taskGroupCards.remove(card);
        taskGroupGrid.getChildren().remove(card);

        // Update view state
        updateViewState();

        System.out.println("[TaskManagerController] Task group deleted. Total groups: " + taskGroupCards.size());
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

    // ==================== View State Management ====================

    /**
     * Updates the view to show either empty state or the task group grid.
     */
    private void updateViewState() {
        boolean hasGroups = !taskGroupCards.isEmpty();

        // Toggle visibility
        emptyState.setVisible(!hasGroups);
        emptyState.setManaged(!hasGroups);

        scrollPane.setVisible(hasGroups);
        scrollPane.setManaged(hasGroups);
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

        // Style the alert with our dark theme if possible
        try {
            alert.getDialogPane().getStylesheets().add(
                    getClass().getResource("../css/dark-theme.css").toExternalForm()
            );
            alert.getDialogPane().getStyleClass().add("dialog-pane");
        } catch (Exception e) {
            // Fallback to default styling if CSS can't be loaded
            System.err.println("[TaskManagerController] Could not apply dark theme to alert: " + e.getMessage());
        }

        alert.showAndWait();
    }

    // ==================== Public API ====================

    /**
     * Gets the number of task groups.
     *
     * @return the count of task groups
     */
    public int taskGroupCount() {
        return taskGroupCards.size();
    }

    /**
     * Gets all task group cards.
     *
     * @return unmodifiable list of cards
     */
    public List<TaskGroupCard> taskGroupCards() {
        return List.copyOf(taskGroupCards);
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

        taskGroupCards.clear();
        taskGroupGrid.getChildren().clear();
        updateViewState();

        System.out.println("[TaskManagerController] All task groups cleared");
    }
}