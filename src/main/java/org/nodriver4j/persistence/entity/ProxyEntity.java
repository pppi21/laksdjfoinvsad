package org.nodriver4j.persistence.entity;

import org.nodriver4j.core.ProxyConfig;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Entity representing a proxy in the database.
 *
 * <p>A proxy belongs to a {@link ProxyGroupEntity} and contains connection
 * details for HTTP proxy authentication. Unlike the legacy consumption model
 * where proxies were deleted after use, proxies in this system are persistent
 * and reusable - they remain in their groups until explicitly edited or deleted.</p>
 *
 * <h2>Database Table</h2>
 * <pre>
 * proxies (
 *     id INTEGER PRIMARY KEY,
 *     group_id INTEGER NOT NULL,
 *     host TEXT NOT NULL,
 *     port INTEGER NOT NULL,
 *     username TEXT NOT NULL,
 *     password TEXT NOT NULL,
 *     created_at TEXT NOT NULL,
 *     FOREIGN KEY (group_id) REFERENCES proxy_groups(id) ON DELETE CASCADE
 * )
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create via builder
 * ProxyEntity proxy = ProxyEntity.builder()
 *     .groupId(1)
 *     .host("proxy.example.com")
 *     .port(8080)
 *     .username("user")
 *     .password("pass")
 *     .build();
 *
 * proxy = repository.save(proxy);
 *
 * // Convert to ProxyConfig for browser use
 * ProxyConfig config = proxy.toProxyConfig();
 * }</pre>
 *
 * <h2>Proxy String Format</h2>
 * <p>Standard format: {@code host:port:username:password}</p>
 * <p>Example: {@code res-us.lightningproxies.net:9999:user-zone-abc:secretpass}</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Hold proxy connection data</li>
 *   <li>Represent a row in the proxies table</li>
 *   <li>Convert to ProxyConfig for runtime use</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Database operations (use ProxyRepository)</li>
 *   <li>File-based proxy parsing (use ProxyImporter)</li>
 *   <li>Browser proxy authentication (use ProxyConfig)</li>
 * </ul>
 *
 * @see ProxyGroupEntity
 * @see ProxyConfig
 */
public class ProxyEntity {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ==================== Identity ====================

    private long id;
    private long groupId;

    // ==================== Connection Details ====================

    private String host;
    private int port;
    private String username;
    private String password;

    // ==================== Metadata ====================

    private LocalDateTime createdAt;

    // ==================== Constructors ====================

    /**
     * Default constructor for repository mapping.
     */
    public ProxyEntity() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Private constructor for builder.
     */
    private ProxyEntity(Builder builder) {
        this.id = builder.id;
        this.groupId = builder.groupId;
        this.host = builder.host;
        this.port = builder.port;
        this.username = builder.username;
        this.password = builder.password;
        this.createdAt = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
    }

    // ==================== Builder Factory ====================

    /**
     * Creates a new builder for ProxyEntity.
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
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .createdAt(createdAt);
    }

    // ==================== Getters ====================

    /**
     * Gets the proxy ID.
     *
     * @return the ID, or 0 if not yet persisted
     */
    public long id() {
        return id;
    }

    /**
     * Gets the parent group ID.
     *
     * @return the group ID
     */
    public long groupId() {
        return groupId;
    }

    /**
     * Gets the proxy hostname.
     *
     * @return the host
     */
    public String host() {
        return host;
    }

    /**
     * Gets the proxy port.
     *
     * @return the port (1-65535)
     */
    public int port() {
        return port;
    }

    /**
     * Gets the authentication username.
     *
     * @return the username
     */
    public String username() {
        return username;
    }

    /**
     * Gets the authentication password.
     *
     * @return the password
     */
    public String password() {
        return password;
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

    // ==================== Setters (for repository mapping) ====================

    /**
     * Sets the proxy ID.
     *
     * @param id the ID
     * @return this entity for chaining
     */
    public ProxyEntity id(long id) {
        this.id = id;
        return this;
    }

    /**
     * Sets the parent group ID.
     *
     * @param groupId the group ID
     * @return this entity for chaining
     */
    public ProxyEntity groupId(long groupId) {
        this.groupId = groupId;
        return this;
    }

    /**
     * Sets the proxy hostname.
     *
     * @param host the host
     * @return this entity for chaining
     */
    public ProxyEntity host(String host) {
        this.host = host;
        return this;
    }

    /**
     * Sets the proxy port.
     *
     * @param port the port (1-65535)
     * @return this entity for chaining
     */
    public ProxyEntity port(int port) {
        this.port = port;
        return this;
    }

    /**
     * Sets the authentication username.
     *
     * @param username the username
     * @return this entity for chaining
     */
    public ProxyEntity username(String username) {
        this.username = username;
        return this;
    }

    /**
     * Sets the authentication password.
     *
     * @param password the password
     * @return this entity for chaining
     */
    public ProxyEntity password(String password) {
        this.password = password;
        return this;
    }

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt the creation time
     * @return this entity for chaining
     */
    public ProxyEntity createdAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    /**
     * Sets the creation timestamp from a string.
     *
     * @param createdAt ISO-formatted datetime string
     * @return this entity for chaining
     */
    public ProxyEntity createdAtString(String createdAt) {
        this.createdAt = createdAt != null ? LocalDateTime.parse(createdAt, FORMATTER) : null;
        return this;
    }

    // ==================== Conversion Methods ====================

    /**
     * Converts this entity to a ProxyConfig for browser use.
     *
     * <p>ProxyConfig is the runtime configuration used by Browser
     * for proxy authentication via CDP.</p>
     *
     * @return a new ProxyConfig instance
     */
    public ProxyConfig toProxyConfig() {
        return new ProxyConfig(host, port, username, password);
    }

    /**
     * Returns the proxy in standard string format.
     *
     * <p>Format: {@code host:port:username:password}</p>
     *
     * @return the proxy string
     */
    public String toProxyString() {
        return String.format("%s:%d:%s:%s", host, port, username, password);
    }

    /**
     * Returns a display-safe string (password masked).
     *
     * <p>Format: {@code host:port:username:***}</p>
     *
     * @return the masked proxy string for display
     */
    public String toDisplayString() {
        return String.format("%s:%d:%s:***", host, port, username);
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

    /**
     * Validates that all required fields are set.
     *
     * @return true if host, port, username, and password are all valid
     */
    public boolean isValid() {
        return host != null && !host.isBlank()
                && port >= 1 && port <= 65535
                && username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format("ProxyEntity{id=%d, groupId=%d, proxy=%s}",
                id, groupId, toDisplayString());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProxyEntity that = (ProxyEntity) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    // ==================== Builder ====================

    /**
     * Builder for creating ProxyEntity instances.
     */
    public static class Builder {

        private long id;
        private long groupId;
        private String host = "";
        private int port;
        private String username = "";
        private String password = "";
        private LocalDateTime createdAt;

        private Builder() {}

        public Builder id(long id) {
            this.id = id;
            return this;
        }

        public Builder groupId(long groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder host(String host) {
            this.host = host != null ? host : "";
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder username(String username) {
            this.username = username != null ? username : "";
            return this;
        }

        public Builder password(String password) {
            this.password = password != null ? password : "";
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /**
         * Parses a proxy string and sets host, port, username, password.
         *
         * <p>Format: {@code host:port:username:password}</p>
         *
         * @param proxyString the proxy string to parse
         * @return this builder
         * @throws IllegalArgumentException if format is invalid
         */
        public Builder fromProxyString(String proxyString) {
            if (proxyString == null || proxyString.isBlank()) {
                throw new IllegalArgumentException("Proxy string cannot be null or blank");
            }

            // Split with limit of 4 to handle passwords containing colons
            String[] parts = proxyString.trim().split(":", 4);

            if (parts.length != 4) {
                throw new IllegalArgumentException(
                        "Invalid proxy format. Expected host:port:username:password, got: " + proxyString);
            }

            this.host = parts[0];
            try {
                this.port = Integer.parseInt(parts[1].trim());
                if (this.port < 1 || this.port > 65535) {
                    throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + this.port);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port number: " + parts[1]);
            }
            this.username = parts[2];
            this.password = parts[3];

            return this;
        }

        /**
         * Builds the ProxyEntity instance.
         *
         * @return a new ProxyEntity
         */
        public ProxyEntity build() {
            return new ProxyEntity(this);
        }
    }
}