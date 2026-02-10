package org.nodriver4j.persistence.entity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Entity representing a task in the database.
 *
 * <p>A task links a profile to a task group and optionally a proxy.
 * Each task represents a single browser automation session that can
 * be started, stopped, cloned, and edited. Tasks track their execution
 * status, userdata directory for browser persistence, and support
 * custom status strings set by scripts.</p>
 *
 * <h2>Database Table</h2>
 * <pre>
 * tasks (
 *     id INTEGER PRIMARY KEY,
 *     group_id INTEGER NOT NULL,
 *     profile_id INTEGER NOT NULL,
 *     proxy_id INTEGER,
 *     status TEXT NOT NULL DEFAULT 'IDLE',
 *     userdata_path TEXT,
 *     notes TEXT,
 *     custom_status TEXT,
 *     log_message TEXT,
 *     log_color TEXT,
 *     created_at TEXT NOT NULL,
 *     updated_at TEXT NOT NULL,
 *     FOREIGN KEY (group_id) REFERENCES task_groups(id) ON DELETE CASCADE,
 *     FOREIGN KEY (profile_id) REFERENCES profiles(id),
 *     FOREIGN KEY (proxy_id) REFERENCES proxies(id)
 * )
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TaskEntity task = TaskEntity.builder()
 *     .groupId(1)
 *     .profileId(5)
 *     .proxyId(10)
 *     .build();
 *
 * task = repository.save(task);
 *
 * // Update status during execution
 * task.status(TaskEntity.STATUS_RUNNING);
 * task.touchUpdatedAt();
 * repository.save(task);
 *
 * // Scripts can set custom status
 * task.customStatus("Entering OTP...");
 * repository.save(task);
 *
 * // Scripts can push log messages with color
 * task.logMessage("Navigating to checkout...");
 * task.logColor(TaskEntity.LOG_DEFAULT);
 * repository.save(task);
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Hold task data fields</li>
 *   <li>Represent a row in the tasks table</li>
 *   <li>Define standard status and log color constants</li>
 *   <li>Provide convenience methods for status checks</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Database operations (use TaskRepository)</li>
 *   <li>Managing referenced profiles or proxies (queried separately)</li>
 *   <li>Script execution or browser lifecycle</li>
 *   <li>Generating userdata paths (use {@link org.nodriver4j.persistence.Settings#userdataPathForTask(long)})</li>
 * </ul>
 *
 * @see TaskGroupEntity
 * @see ProfileEntity
 * @see ProxyEntity
 */
public class TaskEntity {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ==================== Status Constants ====================

    /** Task has been created but not yet started. */
    public static final String STATUS_IDLE = "IDLE";

    /** Task is currently running. */
    public static final String STATUS_RUNNING = "RUNNING";

    /** Task completed successfully. */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** Task failed during execution. */
    public static final String STATUS_FAILED = "FAILED";

    /** Task was manually stopped by the user. */
    public static final String STATUS_STOPPED = "STOPPED";

    /** Task is in manual browser mode (headed, no script). */
    public static final String STATUS_MANUAL = "MANUAL";

    // ==================== Log Color Constants ====================

    /** Default log color — white text. */
    public static final String LOG_DEFAULT = "log-default";

    /** Error/bad outcome log color — red (#c20000). */
    public static final String LOG_ERROR = "log-error";

    /** Final success log color — green (#0e8f00). */
    public static final String LOG_SUCCESS = "log-success";

    // ==================== Identity ====================

    private long id;
    private long groupId;

    // ==================== References ====================

    private long profileId;
    private Long proxyId;  // Nullable — not all tasks require a proxy

    // ==================== Status ====================

    private String status;
    private String customStatus;

    // ==================== Live Log ====================

    private String logMessage;
    private String logColor;

    // ==================== Paths ====================

    private String userdataPath;

    // ==================== Session Options ====================

    private boolean warmSession;

    // ==================== Metadata ====================

    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ==================== Constructors ====================

    /**
     * Default constructor for repository mapping.
     */
    public TaskEntity() {
        this.status = STATUS_IDLE;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Private constructor for builder.
     */
    private TaskEntity(Builder builder) {
        this.id = builder.id;
        this.groupId = builder.groupId;
        this.profileId = builder.profileId;
        this.proxyId = builder.proxyId;
        this.status = builder.status;
        this.customStatus = builder.customStatus;
        this.logMessage = builder.logMessage;
        this.logColor = builder.logColor;
        this.userdataPath = builder.userdataPath;
        this.warmSession = builder.warmSession;
        this.notes = builder.notes;
        this.createdAt = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
        this.updatedAt = builder.updatedAt != null ? builder.updatedAt : LocalDateTime.now();
    }

    // ==================== Builder Factory ====================

    /**
     * Creates a new builder for TaskEntity.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder initialized with this entity's values.
     *
     * @return a Builder with current values
     */
    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .groupId(groupId)
                .profileId(profileId)
                .proxyId(proxyId)
                .status(status)
                .customStatus(customStatus)
                .logMessage(logMessage)
                .logColor(logColor)
                .userdataPath(userdataPath)
                .warmSession(warmSession)
                .notes(notes)
                .createdAt(createdAt)
                .updatedAt(updatedAt);
    }

    // ==================== Getters ====================

    /**
     * Gets the task ID.
     *
     * @return the ID, or 0 if not yet persisted
     */
    public long id() {
        return id;
    }

    /**
     * Gets the parent task group ID.
     *
     * @return the group ID
     */
    public long groupId() {
        return groupId;
    }

    /**
     * Gets the associated profile ID.
     *
     * @return the profile ID
     */
    public long profileId() {
        return profileId;
    }

    /**
     * Gets the associated proxy ID, if any.
     *
     * @return the proxy ID, or null if no proxy is assigned
     */
    public Long proxyId() {
        return proxyId;
    }

    /**
     * Gets the current task status.
     *
     * @return the status string (one of the STATUS_* constants or a custom value)
     */
    public String status() {
        return status;
    }

    /**
     * Gets the custom status string set by the script.
     *
     * <p>This is a user/script-defined status message displayed in the UI
     * alongside the standard status. For example: "Entering OTP...",
     * "Waiting for email...", "Account created".</p>
     *
     * @return the custom status, or null if not set
     */
    public String customStatus() {
        return customStatus;
    }

    /**
     * Gets the most recent log message from the automation script.
     *
     * <p>This is a short message pushed by the script to indicate
     * what the task is currently doing. Only the most recent message
     * is stored — there is no history.</p>
     *
     * @return the log message, or null if not set
     */
    public String logMessage() {
        return logMessage;
    }

    /**
     * Gets the CSS color class for the current log message.
     *
     * <p>One of {@link #LOG_DEFAULT} (white), {@link #LOG_ERROR} (red),
     * or {@link #LOG_SUCCESS} (green). Null is treated as LOG_DEFAULT
     * by the UI layer.</p>
     *
     * @return the log color class, or null
     */
    public String logColor() {
        return logColor;
    }

    /**
     * Gets the browser userdata directory path.
     *
     * <p>This is the persistent Chrome user data directory for this task,
     * allowing session data (cookies, localStorage) to survive across runs.</p>
     *
     * @return the userdata path, or null if not yet assigned
     */
    public String userdataPath() {
        return userdataPath;
    }

    /**
     * Gets whether the session should be warmed with activity.
     *
     * @return true if the session should be warmed before automation
     */
    public boolean warmSession() {
        return warmSession;
    }

    /**
     * Gets the task notes.
     *
     * @return the notes, or null if not set
     */
    public String notes() {
        return notes;
    }

    /**
     * Gets the creation timestamp.
     *
     * @return the creation time
     */
    public LocalDateTime createdAt() {
        return createdAt;
    }

    /**
     * Gets the creation timestamp as a string for database storage.
     *
     * @return ISO-formatted datetime string
     */
    public String createdAtString() {
        return createdAt != null ? createdAt.format(FORMATTER) : null;
    }

    /**
     * Gets the last update timestamp.
     *
     * @return the last update time
     */
    public LocalDateTime updatedAt() {
        return updatedAt;
    }

    /**
     * Gets the last update timestamp as a string for database storage.
     *
     * @return ISO-formatted datetime string
     */
    public String updatedAtString() {
        return updatedAt != null ? updatedAt.format(FORMATTER) : null;
    }

    // ==================== Setters (for repository mapping and runtime updates) ====================

    /**
     * Sets the task ID.
     *
     * @param id the ID
     * @return this entity for chaining
     */
    public TaskEntity id(long id) {
        this.id = id;
        return this;
    }

    /**
     * Sets the parent task group ID.
     *
     * @param groupId the group ID
     * @return this entity for chaining
     */
    public TaskEntity groupId(long groupId) {
        this.groupId = groupId;
        return this;
    }

    /**
     * Sets the associated profile ID.
     *
     * @param profileId the profile ID
     * @return this entity for chaining
     */
    public TaskEntity profileId(long profileId) {
        this.profileId = profileId;
        return this;
    }

    /**
     * Sets the associated proxy ID.
     *
     * @param proxyId the proxy ID, or null to clear
     * @return this entity for chaining
     */
    public TaskEntity proxyId(Long proxyId) {
        this.proxyId = proxyId;
        return this;
    }

    /**
     * Sets the task status.
     *
     * @param status the status string
     * @return this entity for chaining
     */
    public TaskEntity status(String status) {
        this.status = status != null ? status : STATUS_IDLE;
        return this;
    }

    /**
     * Sets the custom status string.
     *
     * @param customStatus the custom status, or null to clear
     * @return this entity for chaining
     */
    public TaskEntity customStatus(String customStatus) {
        this.customStatus = customStatus;
        return this;
    }

    /**
     * Sets the most recent log message.
     *
     * @param logMessage the log message, or null to clear
     * @return this entity for chaining
     */
    public TaskEntity logMessage(String logMessage) {
        this.logMessage = logMessage;
        return this;
    }

    /**
     * Sets the CSS color class for the log message.
     *
     * @param logColor one of LOG_DEFAULT, LOG_ERROR, LOG_SUCCESS, or null
     * @return this entity for chaining
     */
    public TaskEntity logColor(String logColor) {
        this.logColor = logColor;
        return this;
    }

    /**
     * Clears the log message and resets the color to default.
     *
     * <p>Convenience method for clearing the log when a task
     * transitions to IDLE.</p>
     *
     * @return this entity for chaining
     */
    public TaskEntity clearLog() {
        this.logMessage = null;
        this.logColor = null;
        return this;
    }

    /**
     * Sets the browser userdata directory path.
     *
     * @param userdataPath the userdata path, or null to clear
     * @return this entity for chaining
     */
    public TaskEntity userdataPath(String userdataPath) {
        this.userdataPath = userdataPath;
        return this;
    }

    /**
     * Sets whether the session should be warmed with activity.
     *
     * @param warmSession true to warm the session before automation
     * @return this entity for chaining
     */
    public TaskEntity warmSession(boolean warmSession) {
        this.warmSession = warmSession;
        return this;
    }

    /**
     * Sets the task notes.
     *
     * @param notes the notes, or null to clear
     * @return this entity for chaining
     */
    public TaskEntity notes(String notes) {
        this.notes = notes;
        return this;
    }

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt the creation time
     * @return this entity for chaining
     */
    public TaskEntity createdAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    /**
     * Sets the creation timestamp from a string.
     *
     * @param createdAt ISO-formatted datetime string
     * @return this entity for chaining
     */
    public TaskEntity createdAtString(String createdAt) {
        this.createdAt = createdAt != null ? LocalDateTime.parse(createdAt, FORMATTER) : null;
        return this;
    }

    /**
     * Sets the last update timestamp.
     *
     * @param updatedAt the update time
     * @return this entity for chaining
     */
    public TaskEntity updatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
        return this;
    }

    /**
     * Sets the last update timestamp from a string.
     *
     * @param updatedAt ISO-formatted datetime string
     * @return this entity for chaining
     */
    public TaskEntity updatedAtString(String updatedAt) {
        this.updatedAt = updatedAt != null ? LocalDateTime.parse(updatedAt, FORMATTER) : null;
        return this;
    }

    /**
     * Updates the updatedAt timestamp to the current time.
     *
     * <p>Call this before saving when the task's state has changed.</p>
     *
     * @return this entity for chaining
     */
    public TaskEntity touchUpdatedAt() {
        this.updatedAt = LocalDateTime.now();
        return this;
    }

    // ==================== Convenience Methods ====================

    /**
     * Checks if a proxy is assigned to this task.
     *
     * @return true if a proxy ID is set
     */
    public boolean hasProxy() {
        return proxyId != null && proxyId > 0;
    }

    /**
     * Checks if a userdata path is assigned.
     *
     * @return true if a userdata path is set
     */
    public boolean hasUserdataPath() {
        return userdataPath != null && !userdataPath.isBlank();
    }

    /**
     * Checks if a log message is present.
     *
     * @return true if a log message is set and non-blank
     */
    public boolean hasLogMessage() {
        return logMessage != null && !logMessage.isBlank();
    }

    /**
     * Checks if the task is currently idle.
     *
     * @return true if status is IDLE
     */
    public boolean isIdle() {
        return STATUS_IDLE.equals(status);
    }

    /**
     * Checks if the task is currently running.
     *
     * @return true if status is RUNNING
     */
    public boolean isRunning() {
        return STATUS_RUNNING.equals(status);
    }

    /**
     * Checks if the task has completed successfully.
     *
     * @return true if status is COMPLETED
     */
    public boolean isCompleted() {
        return STATUS_COMPLETED.equals(status);
    }

    /**
     * Checks if the task has failed.
     *
     * @return true if status is FAILED
     */
    public boolean isFailed() {
        return STATUS_FAILED.equals(status);
    }

    /**
     * Checks if the task was manually stopped.
     *
     * @return true if status is STOPPED
     */
    public boolean isStopped() {
        return STATUS_STOPPED.equals(status);
    }

    public boolean isManual() {
        return STATUS_MANUAL.equals(status);
    }

    /**
     * Checks if the task has finished (completed, failed, or stopped).
     *
     * @return true if the task is no longer active
     */
    public boolean isFinished() {
        return isCompleted() || isFailed() || isStopped();
        // MANUAL is NOT finished — it's an active session
    }

    /**
     * Gets the display status for the UI.
     *
     * <p>Returns the custom status if set, otherwise the standard status.</p>
     *
     * @return the status string to display
     */
    public String displayStatus() {
        if (customStatus != null && !customStatus.isBlank()) {
            return customStatus;
        }
        return status != null ? status : STATUS_IDLE;
    }

    /**
     * Checks if this entity has been persisted.
     *
     * @return true if ID is set (greater than 0)
     */
    public boolean isPersisted() {
        return id > 0;
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format("TaskEntity{id=%d, groupId=%d, profileId=%d, proxyId=%s, status=%s}",
                id, groupId, profileId, proxyId != null ? proxyId : "none", displayStatus());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskEntity that = (TaskEntity) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    // ==================== Builder ====================

    /**
     * Builder for creating TaskEntity instances.
     */
    public static class Builder {

        private long id;
        private long groupId;
        private long profileId;
        private Long proxyId;
        private String status = STATUS_IDLE;
        private String customStatus;
        private String logMessage;
        private String logColor;
        private String userdataPath;
        private boolean warmSession = false;
        private String notes;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        private Builder() {}

        public Builder id(long id) {
            this.id = id;
            return this;
        }

        public Builder groupId(long groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder profileId(long profileId) {
            this.profileId = profileId;
            return this;
        }

        public Builder proxyId(Long proxyId) {
            this.proxyId = proxyId;
            return this;
        }

        public Builder status(String status) {
            this.status = status != null ? status : STATUS_IDLE;
            return this;
        }

        public Builder customStatus(String customStatus) {
            this.customStatus = customStatus;
            return this;
        }

        public Builder logMessage(String logMessage) {
            this.logMessage = logMessage;
            return this;
        }

        public Builder logColor(String logColor) {
            this.logColor = logColor;
            return this;
        }

        public Builder userdataPath(String userdataPath) {
            this.userdataPath = userdataPath;
            return this;
        }

        public Builder warmSession(boolean warmSession) {
            this.warmSession = warmSession;
            return this;
        }

        public Builder notes(String notes) {
            this.notes = notes;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        /**
         * Builds the TaskEntity instance.
         *
         * @return a new TaskEntity
         */
        public TaskEntity build() {
            return new TaskEntity(this);
        }
    }
}