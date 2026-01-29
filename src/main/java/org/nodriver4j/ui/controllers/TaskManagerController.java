package org.nodriver4j.ui.controllers;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
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
 * <p>Manages task group creation, display, and interaction.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Handle "Add Task Group" button click</li>
 *   <li>Show/hide empty state vs grid</li>
 *   <li>Create and manage TaskGroupCard components</li>
 *   <li>Handle card click events (navigate to group detail)</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Actually running automation scripts (delegated to service layer)</li>
 *   <li>Profile/proxy management (separate controllers)</li>
 *   <li>Persistence (delegated to service layer)</li>
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

    // ==================== Internal State ====================

    /**
     * List of task group cards currently displayed.
     */
    private final List<TaskGroupCard> taskGroupCards = new ArrayList<>();

    // ==================== Initialization ====================

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        System.out.println("[TaskManagerController] Initializing...");

        // Initial state is empty - already set in FXML
        updateViewState();

        System.out.println("[TaskManagerController] Initialized successfully");
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
     * @param data the task group data from the dialog
     */
    private void createTaskGroup(CreateTaskGroupDialog.TaskGroupData data) {
        System.out.println("[TaskManagerController] Creating task group: " + data.name() + " (" + data.script() + ")");

        // Create the card component
        TaskGroupCard card = new TaskGroupCard(
                data.name(),
                data.script(),
                0,  // Initial task count
                0   // Initial running count
        );

        // Set up card click handler
        card.setOnClick(() -> onTaskGroupClicked(card));

        // Set up card delete handler
        card.setOnDelete(() -> onTaskGroupDeleted(card));

        // Add to tracking list and grid
        taskGroupCards.add(card);
        taskGroupGrid.getChildren().add(card);

        // Update view state (show grid, hide empty state)
        updateViewState();

        System.out.println("[TaskManagerController] Task group created. Total groups: " + taskGroupCards.size());
    }

    /**
     * Handles a task group card being clicked.
     * Future: Navigate to group detail page.
     *
     * @param card the clicked card
     */
    private void onTaskGroupClicked(TaskGroupCard card) {
        System.out.println("[TaskManagerController] Task group clicked: " + card.groupName());

        // Future: Navigate to group detail page
        // For now, just log
    }

    /**
     * Handles a task group card being deleted.
     *
     * @param card the card to delete
     */
    private void onTaskGroupDeleted(TaskGroupCard card) {
        System.out.println("[TaskManagerController] Deleting task group: " + card.groupName());

        // Remove from tracking list and grid
        taskGroupCards.remove(card);
        taskGroupGrid.getChildren().remove(card);

        // Update view state
        updateViewState();

        System.out.println("[TaskManagerController] Task group deleted. Total groups: " + taskGroupCards.size());
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
     * Clears all task groups.
     * Future: Add confirmation dialog.
     */
    public void clearAllTaskGroups() {
        taskGroupCards.clear();
        taskGroupGrid.getChildren().clear();
        updateViewState();

        System.out.println("[TaskManagerController] All task groups cleared");
    }
}