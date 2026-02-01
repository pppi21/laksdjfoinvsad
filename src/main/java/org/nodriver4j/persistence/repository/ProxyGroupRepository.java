package org.nodriver4j.persistence.repository;

import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.Repository;
import org.nodriver4j.persistence.entity.ProxyGroupEntity;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ProxyGroupEntity} persistence operations.
 *
 * <p>Handles all CRUD operations for the {@code proxy_groups} table.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProxyGroupRepository repo = new ProxyGroupRepository();
 *
 * // Create
 * ProxyGroupEntity group = new ProxyGroupEntity("Lightning Proxies");
 * group = repo.save(group);
 * System.out.println("Created with ID: " + group.id());
 *
 * // Read
 * Optional<ProxyGroupEntity> found = repo.findById(group.id());
 * List<ProxyGroupEntity> all = repo.findAll();
 *
 * // Update
 * group.name("Updated Name");
 * repo.save(group);
 *
 * // Delete (cascades to child proxies)
 * repo.deleteById(group.id());
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>CRUD operations for proxy_groups table</li>
 *   <li>ResultSet to entity mapping</li>
 *   <li>SQL query execution</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Managing child proxies (use ProxyRepository)</li>
 *   <li>Connection lifecycle (uses Database.connection())</li>
 *   <li>Proxy string parsing (use ProxyImporter)</li>
 * </ul>
 *
 * @see ProxyGroupEntity
 * @see ProxyRepository
 * @see Database
 */
public class ProxyGroupRepository implements Repository<ProxyGroupEntity> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ==================== SQL Statements ====================

    private static final String INSERT_SQL = """
            INSERT INTO proxy_groups (name, created_at)
            VALUES (?, ?)
            """;

    private static final String UPDATE_SQL = """
            UPDATE proxy_groups
            SET name = ?
            WHERE id = ?
            """;

    private static final String SELECT_BY_ID_SQL = """
            SELECT id, name, created_at
            FROM proxy_groups
            WHERE id = ?
            """;

    private static final String SELECT_ALL_SQL = """
            SELECT id, name, created_at
            FROM proxy_groups
            ORDER BY created_at DESC
            """;

    private static final String SELECT_BY_NAME_SQL = """
            SELECT id, name, created_at
            FROM proxy_groups
            WHERE name = ?
            """;

    private static final String EXISTS_BY_ID_SQL = """
            SELECT 1 FROM proxy_groups WHERE id = ?
            """;

    private static final String COUNT_SQL = """
            SELECT COUNT(*) FROM proxy_groups
            """;

    private static final String DELETE_BY_ID_SQL = """
            DELETE FROM proxy_groups WHERE id = ?
            """;

    private static final String DELETE_ALL_SQL = """
            DELETE FROM proxy_groups
            """;

    // ==================== Create / Update ====================

    @Override
    public ProxyGroupEntity save(ProxyGroupEntity entity) {
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
    public List<ProxyGroupEntity> saveAll(List<ProxyGroupEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        List<ProxyGroupEntity> saved = new ArrayList<>(entities.size());

        try (Connection conn = Database.connection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement updateStmt = conn.prepareStatement(UPDATE_SQL)) {

                for (ProxyGroupEntity entity : entities) {
                    if (entity.isPersisted()) {
                        updateStmt.setString(1, entity.name());
                        updateStmt.setLong(2, entity.id());
                        updateStmt.executeUpdate();
                        saved.add(entity);
                    } else {
                        insertStmt.setString(1, entity.name());
                        insertStmt.setString(2, entity.createdAtString());
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
            throw new Database.DatabaseException("Failed to save proxy groups", e);
        }

        return saved;
    }

    private ProxyGroupEntity insert(ProxyGroupEntity entity) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, entity.name());
            stmt.setString(2, entity.createdAtString());

            stmt.executeUpdate();

            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    entity.id(keys.getLong(1));
                }
            }

            return entity;

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to insert proxy group", e);
        }
    }

    private ProxyGroupEntity update(ProxyGroupEntity entity) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {

            stmt.setString(1, entity.name());
            stmt.setLong(2, entity.id());

            stmt.executeUpdate();

            return entity;

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to update proxy group", e);
        }
    }

    // ==================== Read ====================

    @Override
    public Optional<ProxyGroupEntity> findById(long id) {
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
            throw new Database.DatabaseException("Failed to find proxy group by ID: " + id, e);
        }
    }

    @Override
    public List<ProxyGroupEntity> findAll() {
        List<ProxyGroupEntity> groups = new ArrayList<>();

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_ALL_SQL);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                groups.add(mapRow(rs));
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to find all proxy groups", e);
        }

        return groups;
    }

    /**
     * Finds a proxy group by name.
     *
     * @param name the group name
     * @return an Optional containing the group, or empty if not found
     */
    public Optional<ProxyGroupEntity> findByName(String name) {
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
            throw new Database.DatabaseException("Failed to find proxy group by name: " + name, e);
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
            throw new Database.DatabaseException("Failed to check existence of proxy group: " + id, e);
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
            throw new Database.DatabaseException("Failed to count proxy groups", e);
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
            throw new Database.DatabaseException("Failed to delete proxy group: " + id, e);
        }
    }

    @Override
    public int deleteAllByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        String sql = "DELETE FROM proxy_groups WHERE id IN (" + placeholders + ")";

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < ids.size(); i++) {
                stmt.setLong(i + 1, ids.get(i));
            }

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete proxy groups by IDs", e);
        }
    }

    @Override
    public int deleteAll() {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_ALL_SQL)) {

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete all proxy groups", e);
        }
    }

    // ==================== Mapping ====================

    /**
     * Maps a ResultSet row to a ProxyGroupEntity.
     *
     * @param rs the ResultSet positioned at a valid row
     * @return the mapped entity
     * @throws SQLException if a database access error occurs
     */
    private ProxyGroupEntity mapRow(ResultSet rs) throws SQLException {
        ProxyGroupEntity entity = new ProxyGroupEntity();
        entity.id(rs.getLong("id"));
        entity.name(rs.getString("name"));

        String createdAtStr = rs.getString("created_at");
        if (createdAtStr != null) {
            entity.createdAt(LocalDateTime.parse(createdAtStr, FORMATTER));
        }

        return entity;
    }
}