package org.nodriver4j.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SQLite database manager for the NoDriver4j application.
 *
 * <p>Handles database lifecycle including:</p>
 * <ul>
 *   <li>Connection management</li>
 *   <li>Schema initialization</li>
 *   <li>Version-based migrations</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Initialize on application startup
 * Database.initialize();
 *
 * // Get connection for operations
 * try (Connection conn = Database.connection()) {
 *     // perform queries
 * }
 *
 * // Shutdown on application exit
 * Database.shutdown();
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Manage SQLite connection lifecycle</li>
 *   <li>Initialize and migrate database schema</li>
 *   <li>Provide connections to repositories</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Specific CRUD operations (delegated to repositories)</li>
 *   <li>Business logic</li>
 *   <li>JSON settings management</li>
 * </ul>
 */
public final class Database {

    private static final String DATA_DIRECTORY = "nodriver4j-data";
    private static final String DATABASE_FILE = "data.db";
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static String connectionUrl;

    private Database() {
        // Prevent instantiation
    }

    // ==================== Lifecycle ====================

    /**
     * Initializes the database.
     *
     * <p>Creates the data directory if needed, establishes connection,
     * and runs any pending migrations.</p>
     *
     * <p>This method is idempotent - calling it multiple times has no effect.</p>
     *
     * @throws DatabaseException if initialization fails
     */
    public static void initialize() {
        if (!initialized.compareAndSet(false, true)) {
            return; // Already initialized
        }

        try {
            // Ensure data directory exists
            Path dataDir = Path.of(DATA_DIRECTORY);
            if (!Files.exists(dataDir)) {
                Files.createDirectories(dataDir);
                System.out.println("[Database] Created data directory: " + dataDir.toAbsolutePath());
            }

            // Build connection URL
            Path dbPath = dataDir.resolve(DATABASE_FILE);
            connectionUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

            System.out.println("[Database] Connecting to: " + dbPath.toAbsolutePath());

            // Initialize schema
            try (Connection conn = connection()) {
                initializeSchema(conn);
                runMigrations(conn);
            }

            System.out.println("[Database] Initialized successfully");

        } catch (Exception e) {
            initialized.set(false);
            throw new DatabaseException("Failed to initialize database", e);
        }
    }

    /**
     * Shuts down the database.
     *
     * <p>SQLite doesn't require explicit shutdown, but this method
     * exists for consistency and future-proofing.</p>
     */
    public static void shutdown() {
        if (initialized.compareAndSet(true, false)) {
            System.out.println("[Database] Shutdown complete");
        }
    }

    /**
     * Checks if the database has been initialized.
     *
     * @return true if initialized
     */
    public static boolean isInitialized() {
        return initialized.get();
    }

    // ==================== Connection ====================

    /**
     * Gets a new database connection.
     *
     * <p>Callers are responsible for closing the connection.</p>
     *
     * @return a new Connection instance
     * @throws DatabaseException if not initialized or connection fails
     */
    public static Connection connection() {
        ensureInitialized();

        try {
            Connection conn = DriverManager.getConnection(connectionUrl);
            // Enable foreign keys (disabled by default in SQLite)
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
            return conn;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to get database connection", e);
        }
    }

    // ==================== Schema Management ====================

    /**
     * Initializes the schema version table if it doesn't exist.
     */
    private static void initializeSchema(Connection conn) throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER PRIMARY KEY,
                applied_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """;

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * Gets the current schema version from the database.
     *
     * @return the current version, or 0 if no migrations have run
     */
    private static int currentVersion(Connection conn) throws SQLException {
        String sql = "SELECT COALESCE(MAX(version), 0) FROM schema_version";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.getInt(1);
        }
    }

    /**
     * Records a migration as applied.
     */
    private static void recordMigration(Connection conn, int version) throws SQLException {
        String sql = "INSERT INTO schema_version (version) VALUES (?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, version);
            stmt.executeUpdate();
        }
    }

    /**
     * Runs all pending migrations.
     */
    private static void runMigrations(Connection conn) throws SQLException {
        int currentVersion = currentVersion(conn);

        if (currentVersion >= CURRENT_SCHEMA_VERSION) {
            System.out.println("[Database] Schema is up to date (version " + currentVersion + ")");
            return;
        }

        System.out.println("[Database] Running migrations from version " + currentVersion +
                " to " + CURRENT_SCHEMA_VERSION);

        // Run each migration in order
        for (int version = currentVersion + 1; version <= CURRENT_SCHEMA_VERSION; version++) {
            System.out.println("[Database] Applying migration V" + version + "...");
            applyMigration(conn, version);
            recordMigration(conn, version);
            System.out.println("[Database] Migration V" + version + " complete");
        }
    }

    /**
     * Applies a specific migration version.
     *
     * <p>Add new cases here as the schema evolves.</p>
     */
    private static void applyMigration(Connection conn, int version) throws SQLException {
        switch (version) {
            case 1 -> migrateV1(conn);
            default -> throw new DatabaseException("Unknown migration version: " + version);
        }
    }

    // ==================== Migrations ====================

    /**
     * V1: Initial schema with all core tables.
     */
    private static void migrateV1(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {

            // Profile Groups
            stmt.execute("""
                CREATE TABLE profile_groups (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """);

            // Profiles
            stmt.execute("""
                CREATE TABLE profiles (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    group_id INTEGER NOT NULL,
                    email_address TEXT NOT NULL,
                    profile_name TEXT,
                    only_one_checkout INTEGER NOT NULL DEFAULT 0,
                    name_on_card TEXT,
                    card_type TEXT,
                    card_number TEXT,
                    expiration_month TEXT,
                    expiration_year TEXT,
                    cvv TEXT,
                    same_billing_shipping INTEGER NOT NULL DEFAULT 1,
                    shipping_name TEXT,
                    shipping_phone TEXT,
                    shipping_address TEXT,
                    shipping_address_2 TEXT,
                    shipping_address_3 TEXT,
                    shipping_post_code TEXT,
                    shipping_city TEXT,
                    shipping_state TEXT,
                    shipping_country TEXT,
                    billing_name TEXT,
                    billing_phone TEXT,
                    billing_address TEXT,
                    billing_address_2 TEXT,
                    billing_address_3 TEXT,
                    billing_post_code TEXT,
                    billing_city TEXT,
                    billing_state TEXT,
                    billing_country TEXT,
                    catchall_email TEXT,
                    imap_password TEXT,
                    notes TEXT,
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    FOREIGN KEY (group_id) REFERENCES profile_groups(id) ON DELETE CASCADE
                )
                """);

            // Proxy Groups
            stmt.execute("""
                CREATE TABLE proxy_groups (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """);

            // Proxies
            stmt.execute("""
                CREATE TABLE proxies (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    group_id INTEGER NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    username TEXT NOT NULL,
                    password TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    FOREIGN KEY (group_id) REFERENCES proxy_groups(id) ON DELETE CASCADE
                )
                """);

            // Task Groups
            stmt.execute("""
                CREATE TABLE task_groups (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    script_name TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """);

            // Tasks
            stmt.execute("""
                CREATE TABLE tasks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    group_id INTEGER NOT NULL,
                    profile_id INTEGER NOT NULL,
                    proxy_id INTEGER,
                    status TEXT NOT NULL DEFAULT 'IDLE',
                    userdata_path TEXT,
                    notes TEXT,
                    custom_status TEXT,
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                    FOREIGN KEY (group_id) REFERENCES task_groups(id) ON DELETE CASCADE,
                    FOREIGN KEY (profile_id) REFERENCES profiles(id),
                    FOREIGN KEY (proxy_id) REFERENCES proxies(id)
                )
                """);

            // Indexes for common queries
            stmt.execute("CREATE INDEX idx_profiles_group_id ON profiles(group_id)");
            stmt.execute("CREATE INDEX idx_proxies_group_id ON proxies(group_id)");
            stmt.execute("CREATE INDEX idx_tasks_group_id ON tasks(group_id)");
            stmt.execute("CREATE INDEX idx_tasks_status ON tasks(status)");
        }
    }

    // ==================== Utilities ====================

    /**
     * Ensures the database is initialized before operations.
     *
     * @throws DatabaseException if not initialized
     */
    private static void ensureInitialized() {
        if (!initialized.get()) {
            throw new DatabaseException("Database not initialized. Call Database.initialize() first.");
        }
    }

    /**
     * Gets the path to the data directory.
     *
     * @return the data directory path
     */
    public static Path dataDirectory() {
        return Path.of(DATA_DIRECTORY);
    }

    /**
     * Gets the path to the database file.
     *
     * @return the database file path
     */
    public static Path databasePath() {
        return dataDirectory().resolve(DATABASE_FILE);
    }

    // ==================== Exception ====================

    /**
     * Exception thrown for database-related errors.
     */
    public static class DatabaseException extends RuntimeException {

        public DatabaseException(String message) {
            super(message);
        }

        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}