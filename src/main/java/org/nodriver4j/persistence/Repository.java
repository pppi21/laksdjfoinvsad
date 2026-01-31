package org.nodriver4j.persistence;

import java.util.List;
import java.util.Optional;

/**
 * Generic repository interface defining standard CRUD operations.
 *
 * <p>All entity repositories should implement this interface to ensure
 * consistent API across the persistence layer.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class ProfileRepository implements Repository<ProfileEntity> {
 *     @Override
 *     public ProfileEntity save(ProfileEntity entity) {
 *         // implementation
 *     }
 *     // ... other methods
 * }
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Define standard CRUD contract</li>
 *   <li>Establish consistent method naming</li>
 *   <li>Provide type safety via generics</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Actual database operations (concrete implementations)</li>
 *   <li>Connection management (use {@link Database})</li>
 *   <li>Entity-specific queries (add to concrete repositories)</li>
 * </ul>
 *
 * @param <T> the entity type this repository manages
 */
public interface Repository<T> {

    // ==================== Create / Update ====================

    /**
     * Saves an entity (insert or update).
     *
     * <p>If the entity has no ID (or ID is 0), inserts a new record.
     * If the entity has an ID, updates the existing record.</p>
     *
     * @param entity the entity to save
     * @return the saved entity with ID populated
     * @throws Database.DatabaseException if the operation fails
     */
    T save(T entity);

    /**
     * Saves multiple entities in a batch operation.
     *
     * <p>More efficient than calling {@link #save(Object)} in a loop
     * as it can use batch inserts.</p>
     *
     * @param entities the entities to save
     * @return list of saved entities with IDs populated
     * @throws Database.DatabaseException if the operation fails
     */
    List<T> saveAll(List<T> entities);

    // ==================== Read ====================

    /**
     * Finds an entity by its ID.
     *
     * @param id the entity ID
     * @return an Optional containing the entity, or empty if not found
     * @throws Database.DatabaseException if the operation fails
     */
    Optional<T> findById(long id);

    /**
     * Retrieves all entities.
     *
     * <p><strong>Warning:</strong> Use with caution on large tables.
     * Consider using paginated queries for large datasets.</p>
     *
     * @return list of all entities (empty list if none exist)
     * @throws Database.DatabaseException if the operation fails
     */
    List<T> findAll();

    /**
     * Checks if an entity with the given ID exists.
     *
     * @param id the entity ID
     * @return true if an entity with the ID exists
     * @throws Database.DatabaseException if the operation fails
     */
    boolean existsById(long id);

    /**
     * Counts the total number of entities.
     *
     * @return the count of entities
     * @throws Database.DatabaseException if the operation fails
     */
    long count();

    // ==================== Delete ====================

    /**
     * Deletes an entity by its ID.
     *
     * <p>Does nothing if no entity with the ID exists.</p>
     *
     * @param id the entity ID
     * @return true if an entity was deleted, false if not found
     * @throws Database.DatabaseException if the operation fails
     */
    boolean deleteById(long id);

    /**
     * Deletes multiple entities by their IDs in a batch operation.
     *
     * <p>More efficient than calling {@link #deleteById(long)} in a loop.</p>
     *
     * @param ids the entity IDs to delete
     * @return the number of entities actually deleted
     * @throws Database.DatabaseException if the operation fails
     */
    int deleteAllByIds(List<Long> ids);

    /**
     * Deletes all entities managed by this repository.
     *
     * <p><strong>Warning:</strong> This is a destructive operation.
     * Use with extreme caution.</p>
     *
     * @return the number of entities deleted
     * @throws Database.DatabaseException if the operation fails
     */
    int deleteAll();
}