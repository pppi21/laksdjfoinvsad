package org.nodriver4j.persistence.repository;

import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.Repository;
import org.nodriver4j.persistence.entity.TaskGroupEntity;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link TaskGroupEntity} persistence operations.
 *
 * <p>Handles all CRUD operations for the {@code task_groups} table.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TaskGroupRepository repo = new TaskGroupRepository();
 *
 * // Create
 * TaskGroupEntity group = new TaskGroupEntity("Uber Batch 1", "UberGen");
 * group = repo.save(group);
 * System.out.println("Created with ID: " + group.id());
 *
 * // Read
 * Optional<TaskGroupEntity> found = repo.findById(group.id());
 * List<TaskGroupEntity> all = repo.findAll();
 *
 * // Update
 * group.name("Uber Batch 2");
 * repo.save(group);
 *
 * // Delete (cascades to child tasks)
 * repo.deleteById(group.id());
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>CRUD operations for task_groups table</li>
 *   <li>ResultSet to entity mapping</li>
 *   <li>SQL query execution</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Managing child tasks (use TaskRepository)</li>
 *   <li>Connection lifecycle (uses Database.connection())</li>
 *   <li>Script execution logic</li>
 * </ul>
 *
 * @see TaskGroupEntity
 * @see Database
 */
public class TaskGroupRepository implements Repository<TaskGroupEntity> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ==================== SQL Statements ====================

    private static final String INSERT_SQL = """
            INSERT INTO task_groups (name, script_name, created_at)
            VALUES (?, ?, ?)
            """;

    private static final String UPDATE_SQL = """
            UPDATE task_groups
            SET name = ?, script_name = ?
            WHERE id = ?
            """;

    private static final String SELECT_COLUMNS = """
            id, name, script_name, created_at
            """;

    private static final String SELECT_BY_ID_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM task_groups WHERE id = ?";

    private static final String SELECT_ALL_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM task_groups ORDER BY created_at DESC";

    private static final String SELECT_BY_NAME_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM task_groups WHERE name = ?";

    private static final String EXISTS_BY_ID_SQL =
            "SELECT 1 FROM task_groups WHERE id = ?";

    private static final String COUNT_SQL =
            "SELECT COUNT(*) FROM task_groups";

    private static final String DELETE_BY_ID_SQL =
            "DELETE FROM task_groups WHERE id = ?";

    private static final String DELETE_ALL_SQL =
            "DELETE FROM task_groups";

    // ==================== Create / Update ====================

    @Override
    public TaskGroupEntity save(TaskGroupEntity entity) {
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
    public List<TaskGroupEntity> saveAll(List<TaskGroupEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        List<TaskGroupEntity> saved = new ArrayList<>(entities.size());

        try (Connection conn = Database.connection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement updateStmt = conn.prepareStatement(UPDATE_SQL)) {

                for (TaskGroupEntity entity : entities) {
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
            throw new Database.DatabaseException("Failed to save task groups", e);
        }

        return saved;
    }

    private TaskGroupEntity insert(TaskGroupEntity entity) {
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
            throw new Database.DatabaseException("Failed to insert task group", e);
        }
    }

    private TaskGroupEntity update(TaskGroupEntity entity) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {

            setUpdateParameters(stmt, entity);
            stmt.executeUpdate();

            return entity;

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to update task group", e);
        }
    }

    // ==================== Read ====================

    @Override
    public Optional<TaskGroupEntity> findById(long id) {
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
            throw new Database.DatabaseException("Failed to find task group by ID: " + id, e);
        }
    }

    @Override
    public List<TaskGroupEntity> findAll() {
        List<TaskGroupEntity> groups = new ArrayList<>();

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_ALL_SQL);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                groups.add(mapRow(rs));
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to find all task groups", e);
        }

        return groups;
    }

    /**
     * Finds a task group by name.
     *
     * @param name the group name
     * @return an Optional containing the group, or empty if not found
     */
    public Optional<TaskGroupEntity> findByName(String name) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_BY_NAME_SQL)) {

            stmt.setString(1, name);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }

            return Optional.empty();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to find task group by name: " + name, e);
        }
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
            throw new Database.DatabaseException("Failed to check existence of task group: " + id, e);
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
            throw new Database.DatabaseException("Failed to count task groups", e);
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
            throw new Database.DatabaseException("Failed to delete task group: " + id, e);
        }
    }

    @Override
    public int deleteAllByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        String sql = "DELETE FROM task_groups WHERE id IN (" + placeholders + ")";

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < ids.size(); i++) {
                stmt.setLong(i + 1, ids.get(i));
            }

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete task groups by IDs", e);
        }
    }

    @Override
    public int deleteAll() {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_ALL_SQL)) {

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete all task groups", e);
        }
    }

    // ==================== Parameter Setting ====================

    /**
     * Sets parameters for INSERT statement.
     */
    private void setInsertParameters(PreparedStatement stmt, TaskGroupEntity e) throws SQLException {
        int i = 1;
        stmt.setString(i++, e.name());
        stmt.setString(i++, e.scriptName());
        stmt.setString(i, e.createdAtString());
    }

    /**
     * Sets parameters for UPDATE statement.
     */
    private void setUpdateParameters(PreparedStatement stmt, TaskGroupEntity e) throws SQLException {
        int i = 1;
        stmt.setString(i++, e.name());
        stmt.setString(i++, e.scriptName());
        // WHERE id = ?
        stmt.setLong(i, e.id());
    }

    // ==================== Mapping ====================

    /**
     * Maps a ResultSet row to a TaskGroupEntity.
     *
     * @param rs the ResultSet positioned at a valid row
     * @return the mapped entity
     * @throws SQLException if a database access error occurs
     */
    private TaskGroupEntity mapRow(ResultSet rs) throws SQLException {
        TaskGroupEntity entity = new TaskGroupEntity();
        entity.id(rs.getLong("id"));
        entity.name(rs.getString("name"));
        entity.scriptName(rs.getString("script_name"));

        String createdAtStr = rs.getString("created_at");
        if (createdAtStr != null) {
            entity.createdAt(LocalDateTime.parse(createdAtStr, FORMATTER));
        }

        return entity;
    }
}