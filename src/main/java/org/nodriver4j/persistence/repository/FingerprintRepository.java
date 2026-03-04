package org.nodriver4j.persistence.repository;

import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.Repository;
import org.nodriver4j.persistence.entity.FingerprintEntity;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link FingerprintEntity} persistence operations.
 *
 * <p>Handles all CRUD operations for the {@code fingerprints} table.
 * Each fingerprint represents a stable browser identity extracted once
 * from a JSONL profile and reused across browser sessions.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * FingerprintRepository repo = new FingerprintRepository();
 *
 * // Create
 * FingerprintEntity fp = FingerprintEntity.builder()
 *     .browserBrand("Chrome")
 *     .browserMajorVersion(145)
 *     .brandVersionLong("145.0.7632.117")
 *     .platform("Windows")
 *     .platformVersion("10.0.0")
 *     .gpuVendor("Google Inc. (NVIDIA)")
 *     .gpuRenderer("ANGLE (NVIDIA, ...)")
 *     .seed(123456)
 *     .hardwareConcurrency(8)
 *     .deviceMemory(16)
 *     // ... other fields
 *     .build();
 * fp = repo.save(fp);
 *
 * // Read
 * Optional<FingerprintEntity> found = repo.findById(fp.id());
 *
 * // Update
 * fp.deviceMemory(8);
 * repo.save(fp);
 *
 * // Delete
 * repo.deleteById(fp.id());
 * }</pre>
 *
 * <h2>Nullable Columns</h2>
 * <p>The following columns are nullable and handled with
 * {@code setObject}/{@code rs.wasNull()} patterns:</p>
 * <ul>
 *   <li>{@code jsonl_line_index} — null if not sourced from JSONL</li>
 *   <li>{@code device_pixel_ratio} — null until DPR switch is implemented</li>
 *   <li>{@code extra_switches} — null when no extra switches are configured</li>
 * </ul>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>CRUD operations for fingerprints table</li>
 *   <li>ResultSet to entity mapping (including nullable columns)</li>
 *   <li>Batch save operations with transaction support</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Extracting fingerprint values from JSONL (use Fingerprint / extraction service)</li>
 *   <li>Managing tasks that reference fingerprints (use TaskRepository)</li>
 *   <li>Building Chrome command-line arguments (use Browser / BrowserConfig)</li>
 *   <li>Connection lifecycle (uses Database.connection())</li>
 * </ul>
 *
 * @see FingerprintEntity
 * @see Database
 */
public class FingerprintRepository implements Repository<FingerprintEntity> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ==================== SQL Statements ====================

    private static final String SELECT_COLUMNS = """
        id, jsonl_line_index, browser_brand, browser_major_version, brand_version_long,
        platform, platform_version, gpu_vendor, gpu_renderer, device_type,
        seed, hardware_concurrency, device_memory,
        screen_width, screen_height, avail_width, avail_height, avail_top, color_depth,
        device_pixel_ratio,
        audio_sample_rate, audio_base_latency, audio_output_latency, audio_max_channel_count,
        media_mics, media_webcams, media_speakers,
        extra_switches, created_at, updated_at
        """;

    private static final String INSERT_SQL = """
        INSERT INTO fingerprints (
            jsonl_line_index, browser_brand, browser_major_version, brand_version_long,
            platform, platform_version, gpu_vendor, gpu_renderer, device_type,
            seed, hardware_concurrency, device_memory,
            screen_width, screen_height, avail_width, avail_height, avail_top, color_depth,
            device_pixel_ratio,
            audio_sample_rate, audio_base_latency, audio_output_latency, audio_max_channel_count,
            media_mics, media_webcams, media_speakers,
            extra_switches, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPDATE_SQL = """
        UPDATE fingerprints SET
            jsonl_line_index = ?, browser_brand = ?, browser_major_version = ?,
            brand_version_long = ?, platform = ?, platform_version = ?,
            gpu_vendor = ?, gpu_renderer = ?, device_type = ?,
            seed = ?, hardware_concurrency = ?, device_memory = ?,
            screen_width = ?, screen_height = ?, avail_width = ?, avail_height = ?,
            avail_top = ?, color_depth = ?, device_pixel_ratio = ?,
            audio_sample_rate = ?, audio_base_latency = ?, audio_output_latency = ?,
            audio_max_channel_count = ?,
            media_mics = ?, media_webcams = ?, media_speakers = ?,
            extra_switches = ?,
            updated_at = datetime('now')
        WHERE id = ?
        """;

    private static final String SELECT_BY_ID_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM fingerprints WHERE id = ?";

    private static final String SELECT_ALL_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM fingerprints ORDER BY created_at DESC";

    private static final String EXISTS_BY_ID_SQL =
            "SELECT 1 FROM fingerprints WHERE id = ?";

    private static final String COUNT_SQL =
            "SELECT COUNT(*) FROM fingerprints";

    private static final String DELETE_BY_ID_SQL =
            "DELETE FROM fingerprints WHERE id = ?";

    private static final String DELETE_ALL_SQL =
            "DELETE FROM fingerprints";

    // ==================== Create / Update ====================

    @Override
    public FingerprintEntity save(FingerprintEntity entity) {
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
    public List<FingerprintEntity> saveAll(List<FingerprintEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        List<FingerprintEntity> saved = new ArrayList<>(entities.size());

        try (Connection conn = Database.connection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement updateStmt = conn.prepareStatement(UPDATE_SQL)) {

                for (FingerprintEntity entity : entities) {
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
            throw new Database.DatabaseException("Failed to save fingerprints", e);
        }

        return saved;
    }

    private FingerprintEntity insert(FingerprintEntity entity) {
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
            throw new Database.DatabaseException("Failed to insert fingerprint", e);
        }
    }

    private FingerprintEntity update(FingerprintEntity entity) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {

            setUpdateParameters(stmt, entity);
            stmt.executeUpdate();

            return entity;

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to update fingerprint", e);
        }
    }

    // ==================== Read ====================

    @Override
    public Optional<FingerprintEntity> findById(long id) {
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
            throw new Database.DatabaseException("Failed to find fingerprint by ID: " + id, e);
        }
    }

    @Override
    public List<FingerprintEntity> findAll() {
        List<FingerprintEntity> fingerprints = new ArrayList<>();

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_ALL_SQL);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                fingerprints.add(mapRow(rs));
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to find all fingerprints", e);
        }

        return fingerprints;
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
            throw new Database.DatabaseException("Failed to check existence of fingerprint: " + id, e);
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
            throw new Database.DatabaseException("Failed to count fingerprints", e);
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
            throw new Database.DatabaseException("Failed to delete fingerprint: " + id, e);
        }
    }

    @Override
    public int deleteAllByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        String sql = "DELETE FROM fingerprints WHERE id IN (" + placeholders + ")";

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < ids.size(); i++) {
                stmt.setLong(i + 1, ids.get(i));
            }

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete fingerprints by IDs", e);
        }
    }

    @Override
    public int deleteAll() {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_ALL_SQL)) {

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete all fingerprints", e);
        }
    }

    // ==================== Parameter Setting ====================

    /**
     * Sets parameters for INSERT statement.
     *
     * <p>Uses {@code setObject} with appropriate {@code Types} for nullable
     * columns: {@code jsonl_line_index}, {@code device_pixel_ratio}, and
     * {@code extra_switches}.</p>
     */
    private void setInsertParameters(PreparedStatement stmt, FingerprintEntity e) throws SQLException {
        int i = 1;
        stmt.setObject(i++, e.jsonlLineIndex(), Types.INTEGER);
        stmt.setString(i++, e.browserBrand());
        stmt.setInt(i++, e.browserMajorVersion());
        stmt.setString(i++, e.brandVersionLong());
        stmt.setString(i++, e.platform());
        stmt.setString(i++, e.platformVersion());
        stmt.setString(i++, e.gpuVendor());
        stmt.setString(i++, e.gpuRenderer());
        stmt.setString(i++, e.deviceType());
        stmt.setInt(i++, e.seed());
        stmt.setInt(i++, e.hardwareConcurrency());
        stmt.setInt(i++, e.deviceMemory());
        stmt.setInt(i++, e.screenWidth());
        stmt.setInt(i++, e.screenHeight());
        stmt.setInt(i++, e.availWidth());
        stmt.setInt(i++, e.availHeight());
        stmt.setInt(i++, e.availTop());
        stmt.setInt(i++, e.colorDepth());
        stmt.setObject(i++, e.devicePixelRatio(), Types.DOUBLE);
        stmt.setInt(i++, e.audioSampleRate());
        stmt.setDouble(i++, e.audioBaseLatency());
        stmt.setDouble(i++, e.audioOutputLatency());
        stmt.setInt(i++, e.audioMaxChannelCount());
        stmt.setInt(i++, e.mediaMics());
        stmt.setInt(i++, e.mediaWebcams());
        stmt.setInt(i++, e.mediaSpeakers());
        stmt.setString(i++, e.extraSwitches());
        stmt.setString(i++, e.createdAtString());
        stmt.setString(i, e.updatedAtString());
    }

    /**
     * Sets parameters for UPDATE statement.
     *
     * <p>The {@code updated_at} column is not parameterized here — it is
     * set to {@code datetime('now')} directly in the SQL statement.</p>
     */
    private void setUpdateParameters(PreparedStatement stmt, FingerprintEntity e) throws SQLException {
        int i = 1;
        stmt.setObject(i++, e.jsonlLineIndex(), Types.INTEGER);
        stmt.setString(i++, e.browserBrand());
        stmt.setInt(i++, e.browserMajorVersion());
        stmt.setString(i++, e.brandVersionLong());
        stmt.setString(i++, e.platform());
        stmt.setString(i++, e.platformVersion());
        stmt.setString(i++, e.gpuVendor());
        stmt.setString(i++, e.gpuRenderer());
        stmt.setString(i++, e.deviceType());
        stmt.setInt(i++, e.seed());
        stmt.setInt(i++, e.hardwareConcurrency());
        stmt.setInt(i++, e.deviceMemory());
        stmt.setInt(i++, e.screenWidth());
        stmt.setInt(i++, e.screenHeight());
        stmt.setInt(i++, e.availWidth());
        stmt.setInt(i++, e.availHeight());
        stmt.setInt(i++, e.availTop());
        stmt.setInt(i++, e.colorDepth());
        stmt.setObject(i++, e.devicePixelRatio(), Types.DOUBLE);
        stmt.setInt(i++, e.audioSampleRate());
        stmt.setDouble(i++, e.audioBaseLatency());
        stmt.setDouble(i++, e.audioOutputLatency());
        stmt.setInt(i++, e.audioMaxChannelCount());
        stmt.setInt(i++, e.mediaMics());
        stmt.setInt(i++, e.mediaWebcams());
        stmt.setInt(i++, e.mediaSpeakers());
        stmt.setString(i++, e.extraSwitches());
        // updated_at is set via datetime('now') in SQL
        // WHERE id = ?
        stmt.setLong(i, e.id());
    }

    // ==================== Mapping ====================

    /**
     * Maps a ResultSet row to a FingerprintEntity.
     *
     * <p>Handles nullable columns ({@code jsonl_line_index},
     * {@code device_pixel_ratio}, {@code extra_switches}) by checking
     * {@code rs.wasNull()} after reading primitive values.</p>
     *
     * @param rs the ResultSet positioned at a valid row
     * @return the mapped entity
     * @throws SQLException if a database access error occurs
     */
    private FingerprintEntity mapRow(ResultSet rs) throws SQLException {
        // Handle nullable jsonl_line_index
        int lineIndexRaw = rs.getInt("jsonl_line_index");
        Integer jsonlLineIndex = rs.wasNull() ? null : lineIndexRaw;

        // Handle nullable device_pixel_ratio
        double dprRaw = rs.getDouble("device_pixel_ratio");
        Double devicePixelRatio = rs.wasNull() ? null : dprRaw;

        FingerprintEntity entity = FingerprintEntity.builder()
                .id(rs.getLong("id"))
                .jsonlLineIndex(jsonlLineIndex)
                .browserBrand(rs.getString("browser_brand"))
                .browserMajorVersion(rs.getInt("browser_major_version"))
                .brandVersionLong(rs.getString("brand_version_long"))
                .platform(rs.getString("platform"))
                .platformVersion(rs.getString("platform_version"))
                .gpuVendor(rs.getString("gpu_vendor"))
                .gpuRenderer(rs.getString("gpu_renderer"))
                .deviceType(rs.getString("device_type"))
                .seed(rs.getInt("seed"))
                .hardwareConcurrency(rs.getInt("hardware_concurrency"))
                .deviceMemory(rs.getInt("device_memory"))
                .screenWidth(rs.getInt("screen_width"))
                .screenHeight(rs.getInt("screen_height"))
                .availWidth(rs.getInt("avail_width"))
                .availHeight(rs.getInt("avail_height"))
                .availTop(rs.getInt("avail_top"))
                .colorDepth(rs.getInt("color_depth"))
                .devicePixelRatio(devicePixelRatio)
                .audioSampleRate(rs.getInt("audio_sample_rate"))
                .audioBaseLatency(rs.getDouble("audio_base_latency"))
                .audioOutputLatency(rs.getDouble("audio_output_latency"))
                .audioMaxChannelCount(rs.getInt("audio_max_channel_count"))
                .mediaMics(rs.getInt("media_mics"))
                .mediaWebcams(rs.getInt("media_webcams"))
                .mediaSpeakers(rs.getInt("media_speakers"))
                .extraSwitches(rs.getString("extra_switches"))
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