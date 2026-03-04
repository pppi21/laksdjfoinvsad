package org.nodriver4j.persistence.repository;

import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.Repository;
import org.nodriver4j.persistence.entity.TaskEntity;
import org.nodriver4j.services.TaskExecutionService;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link TaskEntity} persistence operations.
 *
 * <p>Handles all CRUD operations for the {@code tasks} table, including
 * nullable proxy references and automatic {@code updated_at} timestamping.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TaskRepository repo = new TaskRepository();
 *
 * // Create
 * TaskEntity task = TaskEntity.builder()
 *     .groupId(1)
 *     .profileId(5)
 *     .proxyId(10L)
 *     .build();
 * task = repo.save(task);
 *
 * // Read
 * Optional<TaskEntity> found = repo.findById(task.id());
 * List<TaskEntity> groupTasks = repo.findByGroupId(1);
 *
 * // Update status (convenience method)
 * repo.updateStatus(task.id(), TaskEntity.STATUS_RUNNING);
 *
 * // Full update
 * task.customStatus("Entering OTP...");
 * repo.save(task);
 *
 * // Delete
 * repo.deleteById(task.id());
 * }</pre>
 *
 * <h2>Nullable Proxy</h2>
 * <p>The {@code proxy_id} column is nullable. This repository uses
 * {@code setObject(i, proxyId, Types.BIGINT)} for inserts/updates and
 * {@code rs.wasNull()} for mapping to correctly handle null proxy references.</p>
 *
 * <h2>Automatic Timestamps</h2>
 * <p>The {@code updated_at} column is automatically set to {@code datetime('now')}
 * on every UPDATE operation, including {@link #updateStatus(long, String)}.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>CRUD operations for tasks table</li>
 *   <li>ResultSet to entity mapping (including nullable proxy_id)</li>
 *   <li>Group-based queries (findByGroupId, countByGroupId, deleteByGroupId)</li>
 *   <li>Status update convenience method</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Managing task groups (use TaskGroupRepository)</li>
 *   <li>Managing referenced profiles or proxies (queried via their own repositories)</li>
 *   <li>Script execution or browser lifecycle</li>
 *   <li>Userdata path generation (use Settings.userdataPathForTask())</li>
 *   <li>Connection lifecycle (uses Database.connection())</li>
 * </ul>
 *
 * @see TaskEntity
 * @see TaskGroupRepository
 * @see Database
 */
public class TaskRepository implements Repository<TaskEntity> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ==================== SQL Statements ====================

    private static final String INSERT_SQL = """
    INSERT INTO tasks (
        group_id, profile_id, proxy_id, status, userdata_path,
        notes, custom_status, log_message, log_color, warm_session,
        fingerprint_id, created_at, updated_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """;


    private static final String UPDATE_SQL = """
    UPDATE tasks SET
        group_id = ?, profile_id = ?, proxy_id = ?, status = ?,
        userdata_path = ?, notes = ?, custom_status = ?,
        log_message = ?, log_color = ?, warm_session = ?,
        fingerprint_id = ?,
        updated_at = datetime('now')
    WHERE id = ?
    """;


    private static final String UPDATE_STATUS_SQL = """
            UPDATE tasks SET status = ?, updated_at = datetime('now')
            WHERE id = ?
            """;

    private static final String UPDATE_LOG_SQL = """
        UPDATE tasks SET log_message = ?, log_color = ?, updated_at = datetime('now')
        WHERE id = ?
        """;

    private static final String SELECT_COLUMNS = """
    id, group_id, profile_id, proxy_id, status, userdata_path,
    notes, custom_status, log_message, log_color, warm_session,
    fingerprint_id, created_at, updated_at
    """;

    private static final String SELECT_BY_ID_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM tasks WHERE id = ?";

    private static final String SELECT_ALL_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM tasks ORDER BY created_at DESC";

    private static final String SELECT_BY_GROUP_ID_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM tasks WHERE group_id = ? ORDER BY id ASC";

    private static final String EXISTS_BY_ID_SQL =
            "SELECT 1 FROM tasks WHERE id = ?";

    private static final String COUNT_SQL =
            "SELECT COUNT(*) FROM tasks";

    private static final String COUNT_BY_GROUP_ID_SQL =
            "SELECT COUNT(*) FROM tasks WHERE group_id = ?";

    private static final String DELETE_BY_ID_SQL =
            "DELETE FROM tasks WHERE id = ?";

    private static final String DELETE_BY_GROUP_ID_SQL =
            "DELETE FROM tasks WHERE group_id = ?";

    private static final String DELETE_ALL_SQL =
            "DELETE FROM tasks";

    private static final String SELECT_IDS_BY_GROUP_ID_SQL =
            "SELECT id FROM tasks WHERE group_id = ? ORDER BY id ASC";

    private static final String SELECT_BY_GROUP_ID_PAGINATED_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM tasks WHERE group_id = ? ORDER BY id ASC LIMIT ? OFFSET ?";

    private static final String SELECT_BY_GROUP_ID_SEARCH_PAGINATED_SQL = """
    SELECT t.id, t.group_id, t.profile_id, t.proxy_id, t.status, t.userdata_path,
           t.notes, t.custom_status, t.log_message, t.log_color, t.warm_session,
           t.fingerprint_id, t.created_at, t.updated_at
        FROM tasks t
        JOIN profiles p ON t.profile_id = p.id
        WHERE t.group_id = ?
          AND (p.email_address LIKE ? OR p.profile_name LIKE ?)
        ORDER BY t.id ASC LIMIT ? OFFSET ?
        """;

    private static final String COUNT_BY_GROUP_ID_SEARCH_SQL = """
        SELECT COUNT(*) FROM tasks t
        JOIN profiles p ON t.profile_id = p.id
        WHERE t.group_id = ?
          AND (p.email_address LIKE ? OR p.profile_name LIKE ?)
        """;

    // ==================== Create / Update ====================

    @Override
    public TaskEntity save(TaskEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }

        if (entity.isPersisted()) {
            return update(entity);
        } else {
            return insert(entity);
        }
    }

    @Override
    public List<TaskEntity> saveAll(List<TaskEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        List<TaskEntity> saved = new ArrayList<>(entities.size());

        try (Connection conn = Database.connection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement updateStmt = conn.prepareStatement(UPDATE_SQL)) {

                for (TaskEntity entity : entities) {
                    if (entity.isPersisted()) {
                        setUpdateParameters(updateStmt, entity);
                        updateStmt.executeUpdate();
                        saved.add(entity);
                    } else {
                        setInsertParameters(insertStmt, entity);
                        insertStmt.executeUpdate();

                        try (ResultSet keys = insertStmt.getGeneratedKeys()) {
                            if (keys.next()) {
                                entity.id(keys.getLong(1));
                            }
                        }
                        saved.add(entity);
                    }
                }

                conn.commit();

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to save tasks", e);
        }

        return saved;
    }

    private TaskEntity insert(TaskEntity entity) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {

            setInsertParameters(stmt, entity);
            stmt.executeUpdate();

            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    entity.id(keys.getLong(1));
                }
            }

            return entity;

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to insert task", e);
        }
    }

    private TaskEntity update(TaskEntity entity) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {

            setUpdateParameters(stmt, entity);
            stmt.executeUpdate();

            return entity;

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to update task", e);
        }
    }

    // ==================== Status Update ====================

    /**
     * Updates only the status of a task.
     *
     * <p>This is a convenience method for the common case of updating
     * a task's status during script execution without loading and saving
     * the entire entity. The {@code updated_at} timestamp is automatically
     * set to the current time.</p>
     *
     * @param id     the task ID
     * @param status the new status (e.g., {@link TaskEntity#STATUS_RUNNING})
     * @return true if the task was found and updated, false if not found
     * @throws Database.DatabaseException if the operation fails
     */
    public boolean updateStatus(long id, String status) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_STATUS_SQL)) {

            stmt.setString(1, status != null ? status : TaskEntity.STATUS_IDLE);
            stmt.setLong(2, id);

            int affected = stmt.executeUpdate();
            return affected > 0;

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to update task status: " + id, e);
        }
    }

    /**
     * Updates only the log message and color of a task.
     *
     * <p>This is a convenience method for the common case of pushing
     * a live log message during script execution without loading and
     * saving the entire entity. The {@code updated_at} timestamp is
     * automatically set to the current time.</p>
     *
     * @param id       the task ID
     * @param message  the log message, or null to clear
     * @param color    the log color class (e.g., {@link TaskEntity#LOG_ERROR}), or null for default
     * @return true if the task was found and updated, false if not found
     * @throws Database.DatabaseException if the operation fails
     */
    public boolean updateLog(long id, String message, String color) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_LOG_SQL)) {

            stmt.setString(1, message);
            stmt.setString(2, color);
            stmt.setLong(3, id);

            int affected = stmt.executeUpdate();
            return affected > 0;

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to update task log: " + id, e);
        }
    }

    // ==================== Read ====================

    @Override
    public Optional<TaskEntity> findById(long id) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_BY_ID_SQL)) {

            stmt.setLong(1, id);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }

            return Optional.empty();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to find task by ID: " + id, e);
        }
    }

    @Override
    public List<TaskEntity> findAll() {
        List<TaskEntity> tasks = new ArrayList<>();

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_ALL_SQL);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                tasks.add(mapRow(rs));
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to find all tasks", e);
        }

        return tasks;
    }

    /**
     * Finds all tasks belonging to a specific group.
     *
     * <p>Results are ordered by ID ascending, preserving the order in which
     * tasks were created. This matches the UI display where tasks appear
     * in rows in their creation order.</p>
     *
     * @param groupId the task group ID
     * @return list of tasks in the group (empty list if none)
     */
    public List<TaskEntity> findByGroupId(long groupId) {
        List<TaskEntity> tasks = new ArrayList<>();

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_BY_GROUP_ID_SQL)) {

            stmt.setLong(1, groupId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    tasks.add(mapRow(rs));
                }
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to find tasks by group ID: " + groupId, e);
        }

        return tasks;
    }

    /**
     * Finds tasks belonging to a specific group with pagination.
     *
     * <p>Results are ordered by ID ascending, preserving the order in which
     * tasks were created. This is used by the UI to display one page of
     * tasks at a time.</p>
     *
     * @param groupId the task group ID
     * @param limit   the maximum number of tasks to return
     * @param offset  the number of tasks to skip
     * @return list of tasks for the requested page (empty list if none)
     */
    public List<TaskEntity> findByGroupId(long groupId, int limit, int offset) {
        List<TaskEntity> tasks = new ArrayList<>();

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_BY_GROUP_ID_PAGINATED_SQL)) {

            stmt.setLong(1, groupId);
            stmt.setInt(2, limit);
            stmt.setInt(3, offset);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    tasks.add(mapRow(rs));
                }
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException(
                    "Failed to find tasks by group ID (paginated): " + groupId, e);
        }

        return tasks;
    }

    /**
     * Finds tasks belonging to a specific group matching a search query, with pagination.
     *
     * <p>The search matches against the linked profile's email address and profile
     * name using a case-insensitive LIKE query. This requires a JOIN against the
     * profiles table. Results are ordered by task ID ascending, preserving
     * creation order.</p>
     *
     * @param groupId the task group ID
     * @param query   the search string to match against email and profile name
     * @param limit   the maximum number of tasks to return
     * @param offset  the number of tasks to skip
     * @return list of matching tasks for the requested page (empty list if none)
     */
    public List<TaskEntity> findByGroupIdAndSearch(long groupId, String query, int limit, int offset) {
        List<TaskEntity> tasks = new ArrayList<>();

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_BY_GROUP_ID_SEARCH_PAGINATED_SQL)) {

            String pattern = "%" + query + "%";
            stmt.setLong(1, groupId);
            stmt.setString(2, pattern);
            stmt.setString(3, pattern);
            stmt.setInt(4, limit);
            stmt.setInt(5, offset);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    tasks.add(mapRow(rs));
                }
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException(
                    "Failed to search tasks by group ID: " + groupId, e);
        }

        return tasks;
    }

    /**
     * Counts tasks in a specific group matching a search query.
     *
     * <p>The search matches against the linked profile's email address and profile
     * name using a case-insensitive LIKE query. This requires a JOIN against the
     * profiles table.</p>
     *
     * @param groupId the task group ID
     * @param query   the search string to match against email and profile name
     * @return the count of matching tasks
     */
    public long countByGroupIdAndSearch(long groupId, String query) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(COUNT_BY_GROUP_ID_SEARCH_SQL)) {

            String pattern = "%" + query + "%";
            stmt.setLong(1, groupId);
            stmt.setString(2, pattern);
            stmt.setString(3, pattern);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
            return 0;

        } catch (SQLException e) {
            throw new Database.DatabaseException(
                    "Failed to count searched tasks by group ID: " + groupId, e);
        }
    }

    /**
     * Finds all task IDs belonging to a specific group.
     *
     * <p>Returns only the IDs, not full entities. This is used by
     * bulk operations like Start All and Stop All where only the
     * task ID is needed to delegate to {@link TaskExecutionService}.</p>
     *
     * @param groupId the task group ID
     * @return list of task IDs in the group, ordered by ID ascending
     */
    public List<Long> findTaskIdsByGroupId(long groupId) {
        List<Long> ids = new ArrayList<>();

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_IDS_BY_GROUP_ID_SQL)) {

            stmt.setLong(1, groupId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException(
                    "Failed to find task IDs by group ID: " + groupId, e);
        }

        return ids;
    }

    @Override
    public boolean existsById(long id) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(EXISTS_BY_ID_SQL)) {

            stmt.setLong(1, id);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to check existence of task: " + id, e);
        }
    }

    @Override
    public long count() {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(COUNT_SQL);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to count tasks", e);
        }
    }

    /**
     * Counts tasks in a specific group.
     *
     * @param groupId the task group ID
     * @return the count of tasks in the group
     */
    public long countByGroupId(long groupId) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(COUNT_BY_GROUP_ID_SQL)) {

            stmt.setLong(1, groupId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
            return 0;

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to count tasks by group ID: " + groupId, e);
        }
    }

    // ==================== Delete ====================

    @Override
    public boolean deleteById(long id) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_BY_ID_SQL)) {

            stmt.setLong(1, id);
            int affected = stmt.executeUpdate();
            return affected > 0;

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete task: " + id, e);
        }
    }

    /**
     * Deletes all tasks in a specific group.
     *
     * <p>Note: This is also handled by ON DELETE CASCADE when deleting the group
     * via {@link TaskGroupRepository#deleteById(long)}.</p>
     *
     * @param groupId the task group ID
     * @return the number of tasks deleted
     */
    public int deleteByGroupId(long groupId) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_BY_GROUP_ID_SQL)) {

            stmt.setLong(1, groupId);
            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete tasks by group ID: " + groupId, e);
        }
    }

    @Override
    public int deleteAllByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        String sql = "DELETE FROM tasks WHERE id IN (" + placeholders + ")";

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < ids.size(); i++) {
                stmt.setLong(i + 1, ids.get(i));
            }

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete tasks by IDs", e);
        }
    }

    @Override
    public int deleteAll() {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_ALL_SQL)) {

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete all tasks", e);
        }
    }

    // ==================== Parameter Setting ====================

    /**
     * Sets parameters for INSERT statement.
     *
     * <p>Uses {@code setObject} with {@code Types.BIGINT} for the nullable
     * {@code proxy_id} column to correctly handle null values.</p>
     */
    private void setInsertParameters(PreparedStatement stmt, TaskEntity e) throws SQLException {
        int i = 1;
        stmt.setLong(i++, e.groupId());
        stmt.setLong(i++, e.profileId());
        stmt.setObject(i++, e.proxyId(), Types.BIGINT);
        stmt.setString(i++, e.status());
        stmt.setString(i++, e.userdataPath());
        stmt.setString(i++, e.notes());
        stmt.setString(i++, e.customStatus());
        stmt.setString(i++, e.logMessage());
        stmt.setString(i++, e.logColor());
        stmt.setInt(i++, e.warmSession() ? 1 : 0);
        stmt.setObject(i++, e.fingerprintId(), Types.BIGINT);
        stmt.setString(i++, e.createdAtString());
        stmt.setString(i, e.updatedAtString());
    }

    /**
     * Sets parameters for UPDATE statement.
     *
     * <p>The {@code updated_at} column is not parameterized here — it is
     * set to {@code datetime('now')} directly in the SQL statement.</p>
     *
     * <p>Uses {@code setObject} with {@code Types.BIGINT} for the nullable
     * {@code proxy_id} column to correctly handle null values.</p>
     */
    private void setUpdateParameters(PreparedStatement stmt, TaskEntity e) throws SQLException {
        int i = 1;
        stmt.setLong(i++, e.groupId());
        stmt.setLong(i++, e.profileId());
        stmt.setObject(i++, e.proxyId(), Types.BIGINT);
        stmt.setString(i++, e.status());
        stmt.setString(i++, e.userdataPath());
        stmt.setString(i++, e.notes());
        stmt.setString(i++, e.customStatus());
        stmt.setString(i++, e.logMessage());
        stmt.setString(i++, e.logColor());
        stmt.setInt(i++, e.warmSession() ? 1 : 0);
        stmt.setObject(i++, e.fingerprintId(), Types.BIGINT);
        // updated_at is set via datetime('now') in SQL
        // WHERE id = ?
        stmt.setLong(i, e.id());
    }

    // ==================== Mapping ====================

    /**
     * Maps a ResultSet row to a TaskEntity.
     *
     * <p>Handles the nullable {@code proxy_id} column by checking
     * {@code rs.wasNull()} after reading the long value. If the column
     * is SQL NULL, {@code proxyId} is set to {@code null} on the entity.</p>
     *
     * @param rs the ResultSet positioned at a valid row
     * @return the mapped entity
     * @throws SQLException if a database access error occurs
     */
    private TaskEntity mapRow(ResultSet rs) throws SQLException {
        // Handle nullable proxy_id
        long proxyIdRaw = rs.getLong("proxy_id");
        Long proxyId = rs.wasNull() ? null : proxyIdRaw;

        // Handle nullable fingerprint_id
        long fingerprintIdRaw = rs.getLong("fingerprint_id");
        Long fingerprintId = rs.wasNull() ? null : fingerprintIdRaw;

        TaskEntity entity = TaskEntity.builder()
                .id(rs.getLong("id"))
                .groupId(rs.getLong("group_id"))
                .profileId(rs.getLong("profile_id"))
                .proxyId(proxyId)
                .status(rs.getString("status"))
                .userdataPath(rs.getString("userdata_path"))
                .notes(rs.getString("notes"))
                .customStatus(rs.getString("custom_status"))
                .logMessage(rs.getString("log_message"))
                .logColor(rs.getString("log_color"))
                .warmSession(rs.getInt("warm_session") == 1)
                .fingerprintId(fingerprintId)
                .build();

        String createdAtStr = rs.getString("created_at");
        if (createdAtStr != null) {
            entity.createdAt(LocalDateTime.parse(createdAtStr.replace(' ', 'T'), FORMATTER));
        }

        String updatedAtStr = rs.getString("updated_at");
        if (updatedAtStr != null) {
            entity.updatedAt(LocalDateTime.parse(updatedAtStr.replace(' ', 'T'), FORMATTER));
        }

        return entity;
    }
}