package org.nodriver4j.persistence.entity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Entity representing a task group in the database.
 *
 * <p>A task group is a named collection of tasks that all run the same
 * automation script. The distinction between task groups is their name
 * and the script they execute.</p>
 *
 * <h2>Database Table</h2>
 * <pre>
 * task_groups (
 *     id INTEGER PRIMARY KEY,
 *     name TEXT NOT NULL,
 *     script_name TEXT NOT NULL,
 *     created_at TEXT NOT NULL
 * )
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TaskGroupEntity group = new TaskGroupEntity("Uber Batch 1", "UberGen");
 * group = repository.save(group); // ID populated after save
 *
 * // Tasks are queried separately
 * List<TaskEntity> tasks = taskRepo.findByGroupId(group.id());
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Hold task group data</li>
 *   <li>Represent a row in the task_groups table</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Database operations (use TaskGroupRepository)</li>
 *   <li>Managing child tasks (queried separately)</li>
 *   <li>Script execution logic</li>
 * </ul>
 *
 * @see TaskEntity
 */
public class TaskGroupEntity {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private long id;
    private String name;
    private String scriptName;
    private LocalDateTime createdAt;

    // ==================== Constructors ====================

    /**
     * Default constructor for repository mapping.
     */
    public TaskGroupEntity() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Creates a new task group with the given name and script.
     *
     * @param name       the group name
     * @param scriptName the automation script to run
     */
    public TaskGroupEntity(String name, String scriptName) {
        this();
        this.name = name;
        this.scriptName = scriptName;
    }

    // ==================== Getters ====================

    /**
     * Gets the group ID.
     *
     * @return the ID, or 0 if not yet persisted
     */
    public long id() {
        return id;
    }

    /**
     * Gets the group name.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * Gets the automation script name.
     *
     * @return the script name (e.g., "UberGen")
     */
    public String scriptName() {
        return scriptName;
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

    // ==================== Setters ====================

    /**
     * Sets the group ID.
     *
     * <p>Typically called by the repository after insert.</p>
     *
     * @param id the ID
     * @return this entity for chaining
     */
    public TaskGroupEntity id(long id) {
        this.id = id;
        return this;
    }

    /**
     * Sets the group name.
     *
     * @param name the name
     * @return this entity for chaining
     */
    public TaskGroupEntity name(String name) {
        this.name = name;
        return this;
    }

    /**
     * Sets the automation script name.
     *
     * @param scriptName the script name
     * @return this entity for chaining
     */
    public TaskGroupEntity scriptName(String scriptName) {
        this.scriptName = scriptName;
        return this;
    }

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt the creation time
     * @return this entity for chaining
     */
    public TaskGroupEntity createdAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    /**
     * Sets the creation timestamp from a string.
     *
     * @param createdAt ISO-formatted datetime string
     * @return this entity for chaining
     */
    public TaskGroupEntity createdAtString(String createdAt) {
        this.createdAt = createdAt != null ? LocalDateTime.parse(createdAt, FORMATTER) : null;
        return this;
    }

    // ==================== Utility ====================

    /**
     * Checks if this entity has been persisted.
     *
     * @return true if ID is set (greater than 0)
     */
    public boolean isPersisted() {
        return id > 0;
    }

    @Override
    public String toString() {
        return String.format("TaskGroupEntity{id=%d, name='%s', script='%s', createdAt=%s}",
                id, name, scriptName, createdAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskGroupEntity that = (TaskGroupEntity) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }
}