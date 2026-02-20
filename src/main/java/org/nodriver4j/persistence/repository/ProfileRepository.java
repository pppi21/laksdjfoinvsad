package org.nodriver4j.persistence.repository;

import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.Repository;
import org.nodriver4j.persistence.entity.ProfileEntity;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ProfileEntity} persistence operations.
 *
 * <p>Handles all CRUD operations for the {@code profiles} table.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProfileRepository repo = new ProfileRepository();
 *
 * // Create
 * ProfileEntity profile = ProfileEntity.builder()
 *     .groupId(1)
 *     .emailAddress("user@example.com")
 *     .shippingName("John Doe")
 *     .build();
 * profile = repo.save(profile);
 *
 * // Read
 * Optional<ProfileEntity> found = repo.findById(profile.id());
 * List<ProfileEntity> groupProfiles = repo.findByGroupId(1);
 *
 * // Update
 * profile.notes("Updated notes");
 * repo.save(profile);
 *
 * // Delete
 * repo.deleteById(profile.id());
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>CRUD operations for profiles table</li>
 *   <li>ResultSet to entity mapping</li>
 *   <li>Group-based queries (findByGroupId)</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Managing profile groups (use ProfileGroupRepository)</li>
 *   <li>CSV parsing (use ProfileImporter)</li>
 *   <li>Connection lifecycle (uses Database.connection())</li>
 * </ul>
 *
 * @see ProfileEntity
 * @see ProfileGroupRepository
 * @see Database
 */
public class ProfileRepository implements Repository<ProfileEntity> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ==================== SQL Statements ====================

    private static final String INSERT_SQL = """
            INSERT INTO profiles (
                group_id, email_address, profile_name, only_one_checkout,
                name_on_card, card_type, card_number, expiration_month, expiration_year, cvv,
                same_billing_shipping,
                shipping_name, shipping_phone, shipping_address, shipping_address_2, shipping_address_3,
                shipping_post_code, shipping_city, shipping_state, shipping_country,
                billing_name, billing_phone, billing_address, billing_address_2, billing_address_3,
                billing_post_code, billing_city, billing_state, billing_country,
                catchall_email, imap_password, notes, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_SQL = """
            UPDATE profiles SET
                group_id = ?, email_address = ?, profile_name = ?, only_one_checkout = ?,
                name_on_card = ?, card_type = ?, card_number = ?, expiration_month = ?, expiration_year = ?, cvv = ?,
                same_billing_shipping = ?,
                shipping_name = ?, shipping_phone = ?, shipping_address = ?, shipping_address_2 = ?, shipping_address_3 = ?,
                shipping_post_code = ?, shipping_city = ?, shipping_state = ?, shipping_country = ?,
                billing_name = ?, billing_phone = ?, billing_address = ?, billing_address_2 = ?, billing_address_3 = ?,
                billing_post_code = ?, billing_city = ?, billing_state = ?, billing_country = ?,
                catchall_email = ?, imap_password = ?, notes = ?
            WHERE id = ?
            """;

    private static final String SELECT_COLUMNS = """
            id, group_id, email_address, profile_name, only_one_checkout,
            name_on_card, card_type, card_number, expiration_month, expiration_year, cvv,
            same_billing_shipping,
            shipping_name, shipping_phone, shipping_address, shipping_address_2, shipping_address_3,
            shipping_post_code, shipping_city, shipping_state, shipping_country,
            billing_name, billing_phone, billing_address, billing_address_2, billing_address_3,
            billing_post_code, billing_city, billing_state, billing_country,
            catchall_email, imap_password, notes, created_at
            """;

    private static final String SELECT_BY_ID_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM profiles WHERE id = ?";

    private static final String SELECT_ALL_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM profiles ORDER BY created_at DESC";

    private static final String SELECT_BY_GROUP_ID_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM profiles WHERE group_id = ? ORDER BY created_at DESC";

    private static final String EXISTS_BY_ID_SQL =
            "SELECT 1 FROM profiles WHERE id = ?";

    private static final String COUNT_SQL =
            "SELECT COUNT(*) FROM profiles";

    private static final String COUNT_BY_GROUP_ID_SQL =
            "SELECT COUNT(*) FROM profiles WHERE group_id = ?";

    private static final String DELETE_BY_ID_SQL =
            "DELETE FROM profiles WHERE id = ?";

    private static final String DELETE_BY_GROUP_ID_SQL =
            "DELETE FROM profiles WHERE group_id = ?";

    private static final String DELETE_ALL_SQL =
            "DELETE FROM profiles";

    private static final String SELECT_BY_GROUP_ID_PAGINATED_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM profiles WHERE group_id = ? ORDER BY id ASC LIMIT ? OFFSET ?";

    // ==================== Create / Update ====================

    @Override
    public ProfileEntity save(ProfileEntity entity) {
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
    public List<ProfileEntity> saveAll(List<ProfileEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        List<ProfileEntity> saved = new ArrayList<>(entities.size());

        try (Connection conn = Database.connection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement updateStmt = conn.prepareStatement(UPDATE_SQL)) {

                for (ProfileEntity entity : entities) {
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
            throw new Database.DatabaseException("Failed to save profiles", e);
        }

        return saved;
    }

    private ProfileEntity insert(ProfileEntity entity) {
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
            throw new Database.DatabaseException("Failed to insert profile", e);
        }
    }

    private ProfileEntity update(ProfileEntity entity) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {

            setUpdateParameters(stmt, entity);
            stmt.executeUpdate();

            return entity;

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to update profile", e);
        }
    }

    // ==================== Read ====================

    @Override
    public Optional<ProfileEntity> findById(long id) {
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
            throw new Database.DatabaseException("Failed to find profile by ID: " + id, e);
        }
    }

    @Override
    public List<ProfileEntity> findAll() {
        List<ProfileEntity> profiles = new ArrayList<>();

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_ALL_SQL);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                profiles.add(mapRow(rs));
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to find all profiles", e);
        }

        return profiles;
    }

    /**
     * Finds all profiles belonging to a specific group.
     *
     * @param groupId the profile group ID
     * @return list of profiles in the group (empty list if none)
     */
    public List<ProfileEntity> findByGroupId(long groupId) {
        List<ProfileEntity> profiles = new ArrayList<>();

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_BY_GROUP_ID_SQL)) {

            stmt.setLong(1, groupId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    profiles.add(mapRow(rs));
                }
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to find profiles by group ID: " + groupId, e);
        }

        return profiles;
    }

    /**
     * Finds profiles belonging to a specific group with pagination.
     *
     * <p>Results are ordered by ID ascending, preserving the original
     * import order. This is used by the UI to display one page of profiles
     * at a time.</p>
     *
     * @param groupId the profile group ID
     * @param limit   the maximum number of profiles to return
     * @param offset  the number of profiles to skip
     * @return list of profiles for the requested page (empty list if none)
     */
    public List<ProfileEntity> findByGroupId(long groupId, int limit, int offset) {
        List<ProfileEntity> profiles = new ArrayList<>();

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_BY_GROUP_ID_PAGINATED_SQL)) {

            stmt.setLong(1, groupId);
            stmt.setInt(2, limit);
            stmt.setInt(3, offset);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    profiles.add(mapRow(rs));
                }
            }

        } catch (SQLException e) {
            throw new Database.DatabaseException(
                    "Failed to find profiles by group ID (paginated): " + groupId, e);
        }

        return profiles;
    }

    /**
     * Finds a profile by email address.
     *
     * @param email the email address
     * @return an Optional containing the profile, or empty if not found
     */
    public Optional<ProfileEntity> findByEmail(String email) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM profiles WHERE email_address = ?";

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, email);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }

            return Optional.empty();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to find profile by email: " + email, e);
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
            throw new Database.DatabaseException("Failed to check existence of profile: " + id, e);
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
            throw new Database.DatabaseException("Failed to count profiles", e);
        }
    }

    /**
     * Counts profiles in a specific group.
     *
     * @param groupId the profile group ID
     * @return the count of profiles in the group
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
            throw new Database.DatabaseException("Failed to count profiles by group ID: " + groupId, e);
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
            throw new Database.DatabaseException("Failed to delete profile: " + id, e);
        }
    }

    /**
     * Deletes all profiles in a specific group.
     *
     * <p>Note: This is also handled by ON DELETE CASCADE when deleting the group.</p>
     *
     * @param groupId the profile group ID
     * @return the number of profiles deleted
     */
    public int deleteByGroupId(long groupId) {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_BY_GROUP_ID_SQL)) {

            stmt.setLong(1, groupId);
            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete profiles by group ID: " + groupId, e);
        }
    }

    @Override
    public int deleteAllByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        String sql = "DELETE FROM profiles WHERE id IN (" + placeholders + ")";

        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < ids.size(); i++) {
                stmt.setLong(i + 1, ids.get(i));
            }

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete profiles by IDs", e);
        }
    }

    @Override
    public int deleteAll() {
        try (Connection conn = Database.connection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_ALL_SQL)) {

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new Database.DatabaseException("Failed to delete all profiles", e);
        }
    }

    // ==================== Parameter Setting ====================

    /**
     * Sets parameters for INSERT statement.
     */
    private void setInsertParameters(PreparedStatement stmt, ProfileEntity e) throws SQLException {
        int i = 1;
        stmt.setLong(i++, e.groupId());
        stmt.setString(i++, e.emailAddress());
        stmt.setString(i++, e.profileName());
        stmt.setInt(i++, e.onlyOneCheckout() ? 1 : 0);
        stmt.setString(i++, e.nameOnCard());
        stmt.setString(i++, e.cardType());
        stmt.setString(i++, e.cardNumber());
        stmt.setString(i++, e.expirationMonth());
        stmt.setString(i++, e.expirationYear());
        stmt.setString(i++, e.cvv());
        stmt.setInt(i++, e.sameBillingShipping() ? 1 : 0);
        stmt.setString(i++, e.shippingName());
        stmt.setString(i++, e.shippingPhone());
        stmt.setString(i++, e.shippingAddress());
        stmt.setString(i++, e.shippingAddress2());
        stmt.setString(i++, e.shippingAddress3());
        stmt.setString(i++, e.shippingPostCode());
        stmt.setString(i++, e.shippingCity());
        stmt.setString(i++, e.shippingState());
        stmt.setString(i++, e.shippingCountry());
        stmt.setString(i++, e.billingName());
        stmt.setString(i++, e.billingPhone());
        stmt.setString(i++, e.billingAddress());
        stmt.setString(i++, e.billingAddress2());
        stmt.setString(i++, e.billingAddress3());
        stmt.setString(i++, e.billingPostCode());
        stmt.setString(i++, e.billingCity());
        stmt.setString(i++, e.billingState());
        stmt.setString(i++, e.billingCountry());
        stmt.setString(i++, e.catchallEmail());
        stmt.setString(i++, e.imapPassword());
        stmt.setString(i++, e.notes());
        stmt.setString(i, e.createdAtString());
    }

    /**
     * Sets parameters for UPDATE statement.
     */
    private void setUpdateParameters(PreparedStatement stmt, ProfileEntity e) throws SQLException {
        int i = 1;
        stmt.setLong(i++, e.groupId());
        stmt.setString(i++, e.emailAddress());
        stmt.setString(i++, e.profileName());
        stmt.setInt(i++, e.onlyOneCheckout() ? 1 : 0);
        stmt.setString(i++, e.nameOnCard());
        stmt.setString(i++, e.cardType());
        stmt.setString(i++, e.cardNumber());
        stmt.setString(i++, e.expirationMonth());
        stmt.setString(i++, e.expirationYear());
        stmt.setString(i++, e.cvv());
        stmt.setInt(i++, e.sameBillingShipping() ? 1 : 0);
        stmt.setString(i++, e.shippingName());
        stmt.setString(i++, e.shippingPhone());
        stmt.setString(i++, e.shippingAddress());
        stmt.setString(i++, e.shippingAddress2());
        stmt.setString(i++, e.shippingAddress3());
        stmt.setString(i++, e.shippingPostCode());
        stmt.setString(i++, e.shippingCity());
        stmt.setString(i++, e.shippingState());
        stmt.setString(i++, e.shippingCountry());
        stmt.setString(i++, e.billingName());
        stmt.setString(i++, e.billingPhone());
        stmt.setString(i++, e.billingAddress());
        stmt.setString(i++, e.billingAddress2());
        stmt.setString(i++, e.billingAddress3());
        stmt.setString(i++, e.billingPostCode());
        stmt.setString(i++, e.billingCity());
        stmt.setString(i++, e.billingState());
        stmt.setString(i++, e.billingCountry());
        stmt.setString(i++, e.catchallEmail());
        stmt.setString(i++, e.imapPassword());
        stmt.setString(i++, e.notes());
        // WHERE id = ?
        stmt.setLong(i, e.id());
    }

    // ==================== Mapping ====================

    /**
     * Maps a ResultSet row to a ProfileEntity.
     *
     * @param rs the ResultSet positioned at a valid row
     * @return the mapped entity
     * @throws SQLException if a database access error occurs
     */
    private ProfileEntity mapRow(ResultSet rs) throws SQLException {
        ProfileEntity entity = ProfileEntity.builder()
                .id(rs.getLong("id"))
                .groupId(rs.getLong("group_id"))
                .emailAddress(rs.getString("email_address"))
                .profileName(rs.getString("profile_name"))
                .onlyOneCheckout(rs.getInt("only_one_checkout") == 1)
                .nameOnCard(rs.getString("name_on_card"))
                .cardType(rs.getString("card_type"))
                .cardNumber(rs.getString("card_number"))
                .expirationMonth(rs.getString("expiration_month"))
                .expirationYear(rs.getString("expiration_year"))
                .cvv(rs.getString("cvv"))
                .sameBillingShipping(rs.getInt("same_billing_shipping") == 1)
                .shippingName(rs.getString("shipping_name"))
                .shippingPhone(rs.getString("shipping_phone"))
                .shippingAddress(rs.getString("shipping_address"))
                .shippingAddress2(rs.getString("shipping_address_2"))
                .shippingAddress3(rs.getString("shipping_address_3"))
                .shippingPostCode(rs.getString("shipping_post_code"))
                .shippingCity(rs.getString("shipping_city"))
                .shippingState(rs.getString("shipping_state"))
                .shippingCountry(rs.getString("shipping_country"))
                .billingName(rs.getString("billing_name"))
                .billingPhone(rs.getString("billing_phone"))
                .billingAddress(rs.getString("billing_address"))
                .billingAddress2(rs.getString("billing_address_2"))
                .billingAddress3(rs.getString("billing_address_3"))
                .billingPostCode(rs.getString("billing_post_code"))
                .billingCity(rs.getString("billing_city"))
                .billingState(rs.getString("billing_state"))
                .billingCountry(rs.getString("billing_country"))
                .catchallEmail(rs.getString("catchall_email"))
                .imapPassword(rs.getString("imap_password"))
                .notes(rs.getString("notes"))
                .build();

        String createdAtStr = rs.getString("created_at");
        if (createdAtStr != null) {
            entity.createdAt(LocalDateTime.parse(createdAtStr, FORMATTER));
        }

        return entity;
    }
}