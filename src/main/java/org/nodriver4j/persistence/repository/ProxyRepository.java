package org.nodriver4j.persistence.repository;

import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.Repository;
import org.nodriver4j.persistence.entity.ProxyEntity;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ProxyEntity} persistence operations.
 *
 * <p>Handles all CRUD operations for the {@code proxies} table.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProxyRepository repo = new ProxyRepository();
 *
 * // Create
 * ProxyEntity proxy = ProxyEntity.builder()
 *     .groupId(1)
 *     .host("proxy.example.com")
 *     .port(8080)
 *     .username("user")
 *     .password("pass")
 *     .build();
 * proxy = repo.save(proxy);
 *
 * // Read
 * Optional<ProxyEntity> found = repo.findById(proxy.id());
 * List<ProxyEntity> groupProxies = repo.findByGroupId(1);
 *
 * // Update
 * proxy.host("new-proxy.example.com");
 * repo.save(proxy);
 *
 * // Delete
 * repo.deleteById(proxy.id());
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>CRUD operations for proxies table</li>
 *   <li>ResultSet to entity mapping</li>
 *   <li>Group-based queries (findByGroupId, countByGroupId, deleteByGroupId)</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Managing proxy groups (use ProxyGroupRepository)</li>
 *   <li>Proxy string parsing from files (use ProxyImporter)</li>
 *   <li>Connection lifecycle (uses Database.connection())</li>
 *   <li>Runtime proxy authentication (use ProxyConfig)</li>
 * </ul>
 *
 * @see ProxyEntity
 * @see ProxyGroupRepository
 * @see Database
 */
public class ProxyRepository implements Repository<ProxyEntity> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ==================== SQL Statements ====================

    private static final String INSERT_SQL = """
            INSERT INTO proxies (group_id, host, port, username, password, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_SQL = """
            UPDATE proxies
            SET group_id = ?, host = ?, port = ?, username = ?, password = ?
            WHERE id = ?
            """;

    private static final String SELECT_COLUMNS = """
            id, group_id, host, port, username, password, created_at
            """;

    private static final String SELECT_BY_ID_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM proxies WHERE id = ?";

    private static final String SELECT_ALL_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM proxies ORDER BY created_at DESC";

    private static final String SELECT_BY_GROUP_ID_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM proxies WHERE group_id = ? ORDER BY id ASC";

    private static final String EXISTS_BY_ID_SQL =
            "SELECT 1 FROM proxies WHERE id = ?";

    private static final String COUNT_SQL =
            "SELECT COUNT(*) FROM proxies";

    private static final String COUNT_BY_GROUP_ID_SQL =
            "SELECT COUNT(*) FROM proxies WHERE group_id = ?";

    private static final String DELETE_BY_ID_SQL =
            "DELETE FROM proxies WHERE id = ?";

    private static final String DELETE_BY_GROUP_ID_SQL =
            "DELETE FROM proxies WHERE group_id = ?";

    private static final String DELETE_ALL_SQL =
            "DELETE FROM proxies";

    // ==================== Create / Update ====================

    @Override
    public ProxyEntity save(ProxyEntity entity) {
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
    public List<ProxyEntity> saveAll(List<ProxyEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        List<ProxyEntity> saved = new ArrayList<>(entities.size());

        try (Connection conn = Database.connection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement updateStmt = conn.prepareStatement(UPDATE_SQL)) {

                for (ProxyEntity entity : entities) {
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
            throw new Database.DatabaseException("Failed to save proxies", e);
        }

        return saved;
    }

    private ProxyEntity insert(ProxyEntity entity) {
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
            throw new Database.DatabaseException("Failed to insert proxy", e);
        }
    }

    private ProxyEntity update(ProxyEntity entity) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {

            setUpdateParameters(stmt, entity);
            stmt.executeUpdate();

            return entity;

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to update proxy", e);
        }
    }

    // ==================== Read ====================

    @Override
    public Optional<ProxyEntity> findById(long id) {
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
            throw new Database.DatabaseException("Failed to find proxy by ID: " + id, e);
        }
    }

    @Override
    public List<ProxyEntity> findAll() {
        List<ProxyEntity> proxies = new ArrayList<>();

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_ALL_SQL);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                proxies.add(mapRow(rs));
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to find all proxies", e);
        }

        return proxies;
    }

    /**
     * Finds all proxies belonging to a specific group.
     *
     * <p>Results are ordered by ID ascending, which preserves the original
     * import order. This is important for proxy assignment where the first
     * N proxies from a group are assigned to N tasks.</p>
     *
     * @param groupId the proxy group ID
     * @return list of proxies in the group (empty list if none)
     */
    public List<ProxyEntity> findByGroupId(long groupId) {
        List<ProxyEntity> proxies = new ArrayList<>();

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_BY_GROUP_ID_SQL)) {

            stmt.setLong(1, groupId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    proxies.add(mapRow(rs));
                }
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to find proxies by group ID: " + groupId, e);
        }

        return proxies;
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
            throw new Database.DatabaseException("Failed to check existence of proxy: " + id, e);
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
            throw new Database.DatabaseException("Failed to count proxies", e);
        }
    }

    /**
     * Counts proxies in a specific group.
     *
     * @param groupId the proxy group ID
     * @return the count of proxies in the group
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
            throw new Database.DatabaseException("Failed to count proxies by group ID: " + groupId, e);
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
            throw new Database.DatabaseException("Failed to delete proxy: " + id, e);
        }
    }

    /**
     * Deletes all proxies in a specific group.
     *
     * <p>Note: This is also handled by ON DELETE CASCADE when deleting the group.</p>
     *
     * @param groupId the proxy group ID
     * @return the number of proxies deleted
     */
    public int deleteByGroupId(long groupId) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_BY_GROUP_ID_SQL)) {

            stmt.setLong(1, groupId);
            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete proxies by group ID: " + groupId, e);
        }
    }

    @Override
    public int deleteAllByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        String sql = "DELETE FROM proxies WHERE id IN (" + placeholders + ")";

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < ids.size(); i++) {
                stmt.setLong(i + 1, ids.get(i));
            }

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete proxies by IDs", e);
        }
    }

    @Override
    public int deleteAll() {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_ALL_SQL)) {

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete all proxies", e);
        }
    }

    // ==================== Parameter Setting ====================

    /**
     * Sets parameters for INSERT statement.
     */
    private void setInsertParameters(PreparedStatement stmt, ProxyEntity e) throws SQLException {
        int i = 1;
        stmt.setObject(i++, e.groupId(), Types.BIGINT);
        stmt.setString(i++, e.host());
        stmt.setInt(i++, e.port());
        stmt.setString(i++, e.username());
        stmt.setString(i++, e.password());
        stmt.setString(i, e.createdAtString());
    }

    /**
     * Sets parameters for UPDATE statement.
     */
    private void setUpdateParameters(PreparedStatement stmt, ProxyEntity e) throws SQLException {
        int i = 1;
        stmt.setObject(i++, e.groupId(), Types.BIGINT);
        stmt.setString(i++, e.host());
        stmt.setInt(i++, e.port());
        stmt.setString(i++, e.username());
        stmt.setString(i++, e.password());
        // WHERE id = ?
        stmt.setLong(i, e.id());
    }

    // ==================== Mapping ====================

    /**
     * Maps a ResultSet row to a ProxyEntity.
     *
     * @param rs the ResultSet positioned at a valid row
     * @return the mapped entity
     * @throws SQLException if a database access error occurs
     */
    private ProxyEntity mapRow(ResultSet rs) throws SQLException {
        // Handle nullable group_id
        long groupIdRaw = rs.getLong("group_id");
        Long groupId = rs.wasNull() ? null : groupIdRaw;

        ProxyEntity entity = ProxyEntity.builder()
                .id(rs.getLong("id"))
                .groupId(groupId)
                .host(rs.getString("host"))
                .port(rs.getInt("port"))
                .username(rs.getString("username"))
                .password(rs.getString("password"))
                .build();

        String createdAtStr = rs.getString("created_at");
        if (createdAtStr != null) {
            entity.createdAt(LocalDateTime.parse(createdAtStr, FORMATTER));
        }

        return entity;
    }
}