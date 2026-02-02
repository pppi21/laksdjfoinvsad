package org.nodriver4j.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * A full-width row component that displays a single task's information.
 *
 * <p>Each row shows:</p>
 * <ul>
 *   <li>Task name (derived from profile name + email by the controller)</li>
 *   <li>Status with color-coded styling</li>
 *   <li>Most recent log message</li>
 * </ul>
 *
 * <p>The row receives only display strings — it has no knowledge of
 * entities, repositories, or the database. The controller is responsible
 * for assembling display data from {@code TaskEntity}, {@code ProfileEntity},
 * and {@code ProxyEntity} before passing it here.</p>
 *
 * <h2>Future Additions</h2>
 * <p>Start/Stop, Clone, and Edit buttons will be added on the right side
 * in a later stage. The layout is structured to accommodate them.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display task name, status, and log text</li>
 *   <li>Hold the database task ID for controller lookups</li>
 *   <li>Apply status-based CSS styling (color-coded)</li>
 *   <li>Provide update methods for dynamic data changes</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Database operations (controller handles persistence)</li>
 *   <li>Profile/proxy lookups (controller resolves display strings)</li>
 *   <li>Task execution or lifecycle (handled by service layer)</li>
 *   <li>Button actions (deferred to a future stage)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TaskRow row = new TaskRow(42L, "John Doe (johndoe@gmail.com)", "IDLE");
 * row.setLogText("Waiting to start...");
 * row.setStatus("RUNNING");
 * taskList.getChildren().add(row);
 * }</pre>
 */
public class TaskRow extends HBox {

    // ==================== UI Components ====================

    private final Label nameLabel;
    private final Label statusLabel;
    private final Label logLabel;

    // ==================== Data ====================

    private final long taskId;
    private String taskName;
    private String statusText;

    // ==================== Constructor ====================

    /**
     * Creates a new TaskRow.
     *
     * @param taskId     the database ID of the task
     * @param taskName   the display name (e.g., "John Doe (johndoe@gmail.com)")
     * @param statusText the initial status text (e.g., "IDLE", "RUNNING")
     */
    public TaskRow(long taskId, String taskName, String statusText) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.statusText = statusText;

        // Apply row styling
        getStyleClass().add("task-row");
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(Double.MAX_VALUE);

        // ---- Left side: info ----
        nameLabel = createNameLabel();
        statusLabel = createStatusLabel();
        logLabel = createLogLabel();

        VBox infoBox = new VBox(4);
        infoBox.setAlignment(Pos.CENTER_LEFT);
        infoBox.getChildren().addAll(nameLabel, statusLabel, logLabel);
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        // ---- Right side: buttons (deferred to future stage) ----
        // HBox buttonBox = createButtonBox();

        // Assemble row
        getChildren().add(infoBox);

        // Apply initial status styling
        applyStatusStyle(statusText);
    }

    // ==================== UI Building ====================

    private Label createNameLabel() {
        Label label = new Label(taskName);
        label.getStyleClass().add("task-row-name");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private Label createStatusLabel() {
        Label label = new Label(statusText);
        label.getStyleClass().add("task-row-status");
        return label;
    }

    private Label createLogLabel() {
        Label label = new Label("");
        label.getStyleClass().add("task-row-log");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    // ==================== Status Styling ====================

    /**
     * Applies the appropriate CSS class based on the status string.
     *
     * <p>Status classes map to colors defined in the dark theme:</p>
     * <ul>
     *   <li>{@code status-idle} — muted/secondary text</li>
     *   <li>{@code status-running} — green (success)</li>
     *   <li>{@code status-completed} — green (success)</li>
     *   <li>{@code status-failed} — red (error)</li>
     *   <li>{@code status-stopped} — yellow (warning)</li>
     * </ul>
     *
     * @param status the status string
     */
    private void applyStatusStyle(String status) {
        statusLabel.getStyleClass().removeAll(
                "status-idle", "status-running", "status-completed",
                "status-failed", "status-stopped"
        );

        String styleClass = switch (status != null ? status : "") {
            case "RUNNING" -> "status-running";
            case "COMPLETED" -> "status-completed";
            case "FAILED" -> "status-failed";
            case "STOPPED" -> "status-stopped";
            default -> "status-idle";
        };

        statusLabel.getStyleClass().add(styleClass);
    }

    // ==================== Data Updates ====================

    /**
     * Updates the task name display.
     *
     * @param name the new display name
     */
    public void setTaskName(String name) {
        this.taskName = name;
        nameLabel.setText(name);
    }

    /**
     * Updates the status display and applies the corresponding style.
     *
     * @param status the new status string
     */
    public void setStatus(String status) {
        this.statusText = status;
        statusLabel.setText(status);
        applyStatusStyle(status);
    }

    /**
     * Updates the log text display.
     *
     * <p>Typically shows the most recent log message from the task.
     * Future: May be upgraded to support multi-line scrolling logs.</p>
     *
     * @param text the log message to display
     */
    public void setLogText(String text) {
        logLabel.setText(text != null ? text : "");
    }

    // ==================== Getters ====================

    /**
     * Gets the database ID of the task.
     *
     * @return the task ID
     */
    public long taskId() {
        return taskId;
    }

    /**
     * Gets the display name of the task.
     *
     * @return the task name
     */
    public String taskName() {
        return taskName;
    }

    /**
     * Gets the current status text.
     *
     * @return the status text
     */
    public String statusText() {
        return statusText;
    }
}