package org.nodriver4j.persistence.entity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Entity representing a proxy group in the database.
 *
 * <p>A proxy group is a named collection of proxies. Unlike the legacy
 * consumption model, proxies remain in their groups permanently until
 * explicitly edited or deleted by the user. Tasks reference proxies
 * but do not remove them from groups.</p>
 *
 * <h2>Database Table</h2>
 * <pre>
 * proxy_groups (
 *     id INTEGER PRIMARY KEY,
 *     name TEXT NOT NULL,
 *     created_at TEXT NOT NULL
 * )
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProxyGroupEntity group = new ProxyGroupEntity("Lightning Proxies");
 * group = repository.save(group); // ID populated after save
 *
 * // Proxies are queried separately
 * List<ProxyEntity> proxies = proxyRepo.findByGroupId(group.id());
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Hold proxy group data</li>
 *   <li>Represent a row in proxy_groups table</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Database operations (use ProxyGroupRepository)</li>
 *   <li>Managing child proxies (use ProxyRepository)</li>
 *   <li>Proxy string parsing (use ProxyImporter)</li>
 * </ul>
 *
 * @see ProxyEntity
 */
public class ProxyGroupEntity {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private long id;
    private String name;
    private LocalDateTime createdAt;

    // ==================== Constructors ====================

    /**
     * Default constructor for repository mapping.
     */
    public ProxyGroupEntity() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Creates a new proxy group with the given name.
     *
     * @param name the group name
     */
    public ProxyGroupEntity(String name) {
        this();
        this.name = name;
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
    public ProxyGroupEntity id(long id) {
        this.id = id;
        return this;
    }

    /**
     * Sets the group name.
     *
     * @param name the name
     * @return this entity for chaining
     */
    public ProxyGroupEntity name(String name) {
        this.name = name;
        return this;
    }

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt the creation time
     * @return this entity for chaining
     */
    public ProxyGroupEntity createdAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    /**
     * Sets the creation timestamp from a string.
     *
     * @param createdAt ISO-formatted datetime string
     * @return this entity for chaining
     */
    public ProxyGroupEntity createdAtString(String createdAt) {
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
        return String.format("ProxyGroupEntity{id=%d, name='%s', createdAt=%s}",
                id, name, createdAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProxyGroupEntity that = (ProxyGroupEntity) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }
}