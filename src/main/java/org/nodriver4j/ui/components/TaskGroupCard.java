package org.nodriver4j.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * A card component that displays task group information.
 *
 * <p>The card shows:</p>
 * <ul>
 *   <li>Group name</li>
 *   <li>Script name</li>
 *   <li>Total task count</li>
 *   <li>Running task count</li>
 *   <li>Delete button with confirmation flow (bottom-right)</li>
 * </ul>
 *
 * <p>Each card holds a {@code groupId} that maps back to the persisted
 * {@code TaskGroupEntity} in the database. The controller uses this ID
 * for deletion, navigation, and task count queries.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display task group data visually</li>
 *   <li>Hold the database ID for controller lookups</li>
 *   <li>Handle click events (delegates to callback)</li>
 *   <li>Handle delete with inline confirmation</li>
 *   <li>Update displayed stats when data changes</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Managing task group data (just displays it)</li>
 *   <li>Database operations (controller handles persistence)</li>
 *   <li>Running tasks (handled by service layer)</li>
 *   <li>Persistence (handled by service layer)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TaskGroupCard card = new TaskGroupCard(1L, "Uber Gen", "UberGen", 12, 8);
 * card.setOnClick(() -> System.out.println("Card clicked!"));
 * card.setOnDelete(() -> System.out.println("Delete confirmed!"));
 * flowPane.getChildren().add(card);
 * }</pre>
 */
public class TaskGroupCard extends VBox {

    // ==================== UI Components ====================

    private final Label nameLabel;
    private final Label scriptLabel;
    private final Label taskCountLabel;
    private final Label runningCountLabel;

    // Delete flow components
    private final StackPane deleteContainer;
    private final Button deleteButton;
    private final HBox confirmCancelContainer;

    // ==================== Data ====================

    private final long groupId;
    private String groupName;
    private String scriptName;
    private int taskCount;
    private int runningCount;

    // ==================== State ====================

    private boolean isConfirmingDelete = false;

    // ==================== Callbacks ====================

    private Runnable onClick;
    private Runnable onDelete;

    // ==================== Constructor ====================

    /**
     * Creates a new TaskGroupCard.
     *
     * @param groupId      the database ID of the task group
     * @param groupName    the name of the task group
     * @param scriptName   the name of the script this group runs
     * @param taskCount    the total number of tasks in this group
     * @param runningCount the number of currently running tasks
     */
    public TaskGroupCard(long groupId, String groupName, String scriptName, int taskCount, int runningCount) {
        this.groupId = groupId;
        this.groupName = groupName;
        this.scriptName = scriptName;
        this.taskCount = taskCount;
        this.runningCount = runningCount;

        // Apply card styling
        getStyleClass().add("task-group-card");

        // Build the UI components
        nameLabel = createNameLabel();
        scriptLabel = createScriptLabel();
        taskCountLabel = createStatLabel(formatTaskCount());
        runningCountLabel = createRunningLabel(formatRunningCount());

        // Build delete flow components
        deleteButton = createDeleteButton();
        confirmCancelContainer = createConfirmCancelContainer();
        deleteContainer = createDeleteContainer();

        // Build bottom row with stats on left, delete on right
        HBox bottomRow = createBottomRow();

        // Add spacer to push bottom row down
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(nameLabel, scriptLabel, spacer, bottomRow);

        // Set up click handler (only when not confirming delete)
        setOnMouseClicked(event -> {
            if (event.getButton().name().equals("PRIMARY") && !isConfirmingDelete && onClick != null) {
                onClick.run();
            }
        });
    }

    // ==================== UI Building ====================

    private Label createNameLabel() {
        Label label = new Label(groupName);
        label.getStyleClass().add("task-group-card-title");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private Label createScriptLabel() {
        Label label = new Label(scriptName);
        label.getStyleClass().add("task-group-card-script");
        return label;
    }

    private Label createStatLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("task-group-card-stat");
        return label;
    }

    private Label createRunningLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll("task-group-card-stat", "running");
        return label;
    }

    private Button createDeleteButton() {
        Button button = new Button();
        button.getStyleClass().add("delete-button");

        // Trash icon (outline style)
        FontIcon trashIcon = new FontIcon(FontAwesomeSolid.TRASH_ALT);
        trashIcon.setIconSize(16);
        trashIcon.setIconColor(Color.web("#d15252"));
        button.setGraphic(trashIcon);

        button.setOnAction(event -> {
            event.consume();
            showDeleteConfirmation();
        });

        return button;
    }

    private HBox createConfirmCancelContainer() {
        HBox container = new HBox();
        container.getStyleClass().add("delete-confirm-buttons");
        container.setAlignment(Pos.CENTER_RIGHT);
        container.setSpacing(6);

        // Confirm button FIRST (left), then Cancel (right)
        Button confirmButton = new Button("Delete");
        confirmButton.getStyleClass().add("confirm-delete-button");
        confirmButton.setOnAction(event -> {
            event.consume();
            confirmDelete();
        });

        Button cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().add("cancel-delete-button");
        cancelButton.setOnAction(event -> {
            event.consume();
            hideDeleteConfirmation();
        });

        container.getChildren().addAll(confirmButton, cancelButton);
        container.setVisible(false);
        container.setManaged(false);

        return container;
    }

    private StackPane createDeleteContainer() {
        StackPane container = new StackPane();
        container.setAlignment(Pos.BOTTOM_RIGHT);
        container.getChildren().addAll(deleteButton, confirmCancelContainer);
        return container;
    }

    private HBox createBottomRow() {
        // Stats column (left side)
        VBox statsBox = new VBox();
        statsBox.getStyleClass().add("task-group-card-stats");
        statsBox.setSpacing(4);
        statsBox.getChildren().addAll(taskCountLabel, runningCountLabel);

        // Spacer to push delete to the right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Bottom row: stats left, delete right
        HBox row = new HBox();
        row.setAlignment(Pos.BOTTOM_LEFT);
        row.getChildren().addAll(statsBox, spacer, deleteContainer);

        return row;
    }

    // ==================== Delete Flow ====================

    /**
     * Shows the delete confirmation buttons.
     */
    private void showDeleteConfirmation() {
        isConfirmingDelete = true;

        // Hide trash icon, show confirm/cancel
        deleteButton.setVisible(false);
        deleteButton.setManaged(false);

        confirmCancelContainer.setVisible(true);
        confirmCancelContainer.setManaged(true);
    }

    /**
     * Hides the delete confirmation buttons.
     */
    private void hideDeleteConfirmation() {
        isConfirmingDelete = false;

        // Show trash icon, hide confirm/cancel
        deleteButton.setVisible(true);
        deleteButton.setManaged(true);

        confirmCancelContainer.setVisible(false);
        confirmCancelContainer.setManaged(false);
    }

    /**
     * Confirms the delete action.
     */
    private void confirmDelete() {
        isConfirmingDelete = false;

        if (onDelete != null) {
            onDelete.run();
        }
    }

    // ==================== Formatting ====================

    private String formatTaskCount() {
        return taskCount + (taskCount == 1 ? " task" : " tasks");
    }

    private String formatRunningCount() {
        return runningCount + " running";
    }

    // ==================== Data Updates ====================

    /**
     * Updates the task count display.
     *
     * @param count the new task count
     */
    public void setTaskCount(int count) {
        this.taskCount = count;
        taskCountLabel.setText(formatTaskCount());
    }

    /**
     * Updates the running count display.
     *
     * @param count the new running count
     */
    public void setRunningCount(int count) {
        this.runningCount = count;
        runningCountLabel.setText(formatRunningCount());

        // Update styling based on running state
        if (count > 0) {
            if (!runningCountLabel.getStyleClass().contains("running")) {
                runningCountLabel.getStyleClass().add("running");
            }
        } else {
            runningCountLabel.getStyleClass().remove("running");
        }
    }

    /**
     * Updates the group name.
     *
     * @param name the new name
     */
    public void setGroupName(String name) {
        this.groupName = name;
        nameLabel.setText(name);
    }

    /**
     * Updates the script name.
     *
     * @param script the new script name
     */
    public void setScriptName(String script) {
        this.scriptName = script;
        scriptLabel.setText(script);
    }

    // ==================== Getters ====================

    /**
     * Gets the database ID of the task group.
     *
     * @return the group ID
     */
    public long groupId() {
        return groupId;
    }

    /**
     * Gets the group name.
     *
     * @return the group name
     */
    public String groupName() {
        return groupName;
    }

    /**
     * Gets the script name.
     *
     * @return the script name
     */
    public String scriptName() {
        return scriptName;
    }

    /**
     * Gets the task count.
     *
     * @return the task count
     */
    public int taskCount() {
        return taskCount;
    }

    /**
     * Gets the running count.
     *
     * @return the running count
     */
    public int runningCount() {
        return runningCount;
    }

    /**
     * Checks if the card is currently showing delete confirmation.
     *
     * @return true if confirming delete
     */
    public boolean isConfirmingDelete() {
        return isConfirmingDelete;
    }

    // ==================== Callbacks ====================

    /**
     * Sets the callback for when the card is clicked.
     *
     * @param onClick the callback
     */
    public void setOnClick(Runnable onClick) {
        this.onClick = onClick;
    }

    /**
     * Sets the callback for when delete is confirmed.
     *
     * @param onDelete the callback
     */
    public void setOnDelete(Runnable onDelete) {
        this.onDelete = onDelete;
    }
}