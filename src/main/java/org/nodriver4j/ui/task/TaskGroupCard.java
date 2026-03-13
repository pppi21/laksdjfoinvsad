package org.nodriver4j.ui.task;

import javafx.scene.control.Label;
import org.nodriver4j.scripts.ScriptRegistry;
import org.nodriver4j.ui.components.GroupCard;

/**
 * A card component that displays task group information.
 *
 * <p>Extends {@link GroupCard} with task-group-specific content:</p>
 * <ul>
 *   <li>Script name (in the content area)</li>
 *   <li>Total task count (in the stats area)</li>
 *   <li>Running task count (in the stats area, color-coded)</li>
 * </ul>
 *
 * <p>Common card functionality — layout, delete confirmation, click handling,
 * group ID and name management — is inherited from {@link GroupCard}.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display script name, task count, and running count</li>
 *   <li>Provide update methods for dynamic data changes</li>
 *   <li>Apply task-group-specific CSS styling</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Card layout skeleton (inherited from {@link GroupCard})</li>
 *   <li>Delete confirmation flow (inherited from {@link GroupCard})</li>
 *   <li>Click handling (inherited from {@link GroupCard})</li>
 *   <li>Database operations (controller handles persistence)</li>
 *   <li>Running tasks (handled by service layer)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TaskGroupCard card = new TaskGroupCard(1L, "Uber Gen", "UberGen", 12, 8);
 * card.setOnClick(() -> System.out.println("Card clicked!"));
 * card.setOnDelete(() -> System.out.println("Delete confirmed!"));
 * flowPane.getChildren().add(card);
 * }</pre>
 *
 * @see GroupCard
 */
public class TaskGroupCard extends GroupCard {

    // ==================== UI Components ====================

    private final Label scriptLabel;
    private final Label taskCountLabel;
    private final Label runningCountLabel;

    // ==================== Data ====================

    private String scriptName;
    private int taskCount;
    private int runningCount;

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
        super(groupId, groupName);

        this.scriptName = scriptName;
        this.taskCount = taskCount;
        this.runningCount = runningCount;

        // Also apply the task-group-specific style class for targeted styling
        getStyleClass().add("task-group-card");

        // ---- Content area: script name ----
        scriptLabel = new Label(ScriptRegistry.displayName(scriptName));
        scriptLabel.getStyleClass().add("group-card-subtitle");
        contentBox().getChildren().add(scriptLabel);

        // ---- Stats area: task count + running count ----
        taskCountLabel = new Label(formatTaskCount());
        taskCountLabel.getStyleClass().add("group-card-stat");

        runningCountLabel = new Label(formatRunningCount());
        runningCountLabel.getStyleClass().add("group-card-stat");
        if (runningCount > 0) {
            runningCountLabel.getStyleClass().add("running");
        }

        statsBox().getChildren().addAll(taskCountLabel, runningCountLabel);
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
     * Updates the script name display.
     *
     * @param script the new script name
     */
    public void setScriptName(String script) {
        this.scriptName = script;
        scriptLabel.setText(ScriptRegistry.displayName(script));
    }

    // ==================== Getters ====================

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
}