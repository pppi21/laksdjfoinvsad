package org.nodriver4j.services;

import org.nodriver4j.persistence.entity.TaskEntity;
import org.nodriver4j.persistence.repository.TaskRepository;
import org.nodriver4j.ui.task.detail.TaskRow;

import java.util.function.BiConsumer;

/**
 * Processes log messages from automation scripts.
 *
 * <p>Each running task gets its own {@code TaskLogger} instance, created by
 * {@link TaskExecutionService} when a script is launched. The logger handles
 * two aspects of the same responsibility — processing script log output:</p>
 * <ol>
 *   <li><b>Persistence:</b> Writes the message and color to the database via
 *       {@link TaskRepository#updateLog(long, String, String)} so that log
 *       state survives app restarts.</li>
 *   <li><b>UI notification:</b> Fires an optional callback so the controller
 *       can update the {@link TaskRow} in real time.</li>
 * </ol>
 *
 * <h2>Usage in Scripts</h2>
 * <pre>{@code
 * // Informational (white text)
 * logger.log("Navigating to checkout...");
 *
 * // Error (red text)
 * logger.error("Payment declined");
 *
 * // Success (green text)
 * logger.success("Account created");
 *
 * // Clear the log
 * logger.clear();
 * }</pre>
 *
 * <h2>Usage in TaskExecutionService</h2>
 * <pre>{@code
 * TaskLogger logger = new TaskLogger(taskId, taskRepository);
 *
 * // Controller registers UI callback when wiring the task row
 * logger.setOnLogUpdate((message, color) ->
 *     Platform.runLater(() -> row.setLogText(message, color))
 * );
 *
 * // Pass to script
 * script.run(page, profile, logger);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Scripts run on background threads, so all operations in this class
 * are safe to call from any thread. The UI callback is responsible for
 * marshalling to the JavaFX application thread (e.g., via
 * {@code Platform.runLater()}) — this class does not impose any
 * threading model on the callback.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Process log messages from automation scripts</li>
 *   <li>Persist log state to the database</li>
 *   <li>Notify the UI layer of log updates</li>
 *   <li>Provide convenience methods for log severities</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Task status transitions (managed by {@link TaskExecutionService})</li>
 *   <li>UI rendering (managed by controller layer)</li>
 *   <li>Script execution or thread management (managed by {@link TaskExecutionService})</li>
 *   <li>Defining log color constants (defined on {@link TaskEntity})</li>
 * </ul>
 *
 * @see TaskEntity#LOG_DEFAULT
 * @see TaskEntity#LOG_ERROR
 * @see TaskEntity#LOG_SUCCESS
 * @see TaskRepository#updateLog(long, String, String)
 */
public class TaskLogger {

    private final long taskId;
    private final TaskRepository taskRepository;

    /**
     * Optional callback for live UI updates.
     *
     * <p>First parameter is the message, second is the color class
     * (one of the {@code TaskEntity.LOG_*} constants, or null for default).</p>
     */
    private volatile BiConsumer<String, String> onLogUpdate;

    // ==================== Constructor ====================

    /**
     * Creates a logger for a specific task.
     *
     * @param taskId         the task ID to persist logs against
     * @param taskRepository the repository for database writes
     * @throws IllegalArgumentException if taskRepository is null
     */
    public TaskLogger(long taskId, TaskRepository taskRepository) {
        if (taskRepository == null) {
            throw new IllegalArgumentException("TaskRepository cannot be null");
        }
        this.taskId = taskId;
        this.taskRepository = taskRepository;
    }

    // ==================== Callback Registration ====================

    /**
     * Sets the UI notification callback.
     *
     * <p>Called by the controller layer when wiring a task row. The callback
     * receives {@code (message, colorClass)} and is responsible for marshalling
     * to the JavaFX application thread if needed.</p>
     *
     * @param onLogUpdate the callback, or null to disable UI notifications
     */
    public void setOnLogUpdate(BiConsumer<String, String> onLogUpdate) {
        this.onLogUpdate = onLogUpdate;
    }

    // ==================== Logging Methods ====================

    /**
     * Logs an informational message (default/white color).
     *
     * @param message the message to display
     */
    public void log(String message) {
        System.out.println("[" + taskId + "] " + message);
        write(message, TaskEntity.LOG_DEFAULT);
    }

    /**
     * Logs an error message (red color).
     *
     * @param message the error message to display
     */
    public void error(String message) {
        System.out.println("[" + taskId + "] " + message);
        write(message, TaskEntity.LOG_ERROR);
    }

    /**
     * Logs a success message (green color).
     *
     * @param message the success message to display
     */
    public void success(String message) {
        System.out.println("[" + taskId + "] " + message);
        write(message, TaskEntity.LOG_SUCCESS);
    }

    /**
     * Clears the log message.
     *
     * <p>Persists null to the database and notifies the UI to clear
     * the log label. Typically called when a task transitions to IDLE.</p>
     */
    public void clear() {
        write(null, null);
    }

    // ==================== Core Write ====================

    /**
     * Writes a log message with the given color to both the database and the UI.
     *
     * <p>Database write failures are logged to stderr but do not propagate —
     * a failed DB write should not crash the running script. UI callback
     * failures are similarly contained.</p>
     *
     * @param message the log message, or null to clear
     * @param color   the color class (one of the {@code TaskEntity.LOG_*} constants), or null
     */
    private void write(String message, String color) {
        // Persist to database
        try {
            taskRepository.updateLog(taskId, message, color);
        } catch (Exception e) {
            System.err.println("[TaskLogger] Failed to persist log for task " +
                    taskId + ": " + e.getMessage());
        }

        // Notify UI
        BiConsumer<String, String> callback = onLogUpdate;
        if (callback != null) {
            try {
                callback.accept(message, color);
            } catch (Exception e) {
                System.err.println("[TaskLogger] UI callback failed for task " +
                        taskId + ": " + e.getMessage());
            }
        }
    }

    // ==================== Accessors ====================

    /**
     * Gets the task ID this logger writes to.
     *
     * @return the task ID
     */
    public long taskId() {
        return taskId;
    }
}