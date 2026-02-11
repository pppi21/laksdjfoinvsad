package org.nodriver4j.scripts;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registry that maps script names to {@link AutomationScript} factories.
 *
 * <p>Script names are stored in the database on {@link
 * org.nodriver4j.persistence.entity.TaskGroupEntity#scriptName()} and
 * resolved at runtime when a task is started. This class provides the
 * lookup mechanism between those stored names and the concrete script
 * implementations.</p>
 *
 * <h2>Built-in Scripts</h2>
 * <p>Built-in scripts are registered in the static initializer. Currently:</p>
 * <ul>
 *   <li>{@code "UberGen"} → {@link UberGen}</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Lookup and create (used by TaskExecutionService)
 * AutomationScript script = ScriptRegistry.create("UberGen");
 * script.run(page, profile, logger);
 *
 * // Check availability (used by UI for validation)
 * boolean valid = ScriptRegistry.isRegistered("UberGen");
 *
 * // List all registered script names (used by UI dropdowns)
 * Set<String> names = ScriptRegistry.scriptNames();
 *
 * // Register a custom script at runtime
 * ScriptRegistry.register("CustomScript", CustomScript::new);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>The registry uses a {@link ConcurrentHashMap} and is safe for
 * concurrent reads and writes from any thread.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Map script names to factory functions</li>
 *   <li>Create new {@link AutomationScript} instances on demand</li>
 *   <li>Register built-in scripts at class load time</li>
 *   <li>Provide lookup and validation for the rest of the system</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Script execution or lifecycle (managed by
 *       {@link org.nodriver4j.services.TaskExecutionService})</li>
 *   <li>Thread management (managed by
 *       {@link org.nodriver4j.services.TaskExecutionService})</li>
 *   <li>Persisting script names (stored on
 *       {@link org.nodriver4j.persistence.entity.TaskGroupEntity})</li>
 * </ul>
 *
 * @see AutomationScript
 * @see org.nodriver4j.services.TaskExecutionService
 */
public final class ScriptRegistry {

    /**
     * Map of script name → factory function.
     *
     * <p>Factories are {@code Supplier<AutomationScript>} because all
     * runtime dependencies (Page, ProfileEntity, TaskLogger) are passed
     * to {@link AutomationScript#run}, not to the constructor.</p>
     */
    private static final Map<String, Supplier<AutomationScript>> REGISTRY = new ConcurrentHashMap<>();

    // ==================== Built-in Registration ====================

    static {
        register("UberGen", UberGen::new);
    }

    // ==================== Private Constructor ====================

    private ScriptRegistry() {
        // Static utility class — not instantiable
    }

    // ==================== Registration ====================

    /**
     * Registers a script factory under the given name.
     *
     * <p>If a script is already registered under this name, it is replaced.
     * Names are case-sensitive and should match the values stored in
     * {@link org.nodriver4j.persistence.entity.TaskGroupEntity#scriptName()}.</p>
     *
     * @param name    the script name (e.g., "UberGen")
     * @param factory a supplier that creates new instances of the script
     * @throws IllegalArgumentException if name is null/blank or factory is null
     */
    public static void register(String name, Supplier<AutomationScript> factory) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Script name cannot be null or blank");
        }
        if (factory == null) {
            throw new IllegalArgumentException("Script factory cannot be null");
        }

        REGISTRY.put(name, factory);
    }

    // ==================== Lookup ====================

    /**
     * Creates a new instance of the script registered under the given name.
     *
     * @param name the script name (e.g., "UberGen")
     * @return a new {@link AutomationScript} instance
     * @throws IllegalArgumentException if name is null or blank
     * @throws UnknownScriptException   if no script is registered under this name
     */
    public static AutomationScript create(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Script name cannot be null or blank");
        }

        Supplier<AutomationScript> factory = REGISTRY.get(name);
        if (factory == null) {
            throw new UnknownScriptException(name);
        }

        return factory.get();
    }

    /**
     * Checks if a script is registered under the given name.
     *
     * @param name the script name to check
     * @return true if a factory is registered for this name
     */
    public static boolean isRegistered(String name) {
        return name != null && REGISTRY.containsKey(name);
    }

    /**
     * Returns the set of all registered script names.
     *
     * <p>Useful for populating UI dropdowns when creating task groups.</p>
     *
     * @return unmodifiable set of registered script names
     */
    public static Set<String> scriptNames() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }

    /**
     * Returns the number of registered scripts.
     *
     * @return the count of registered scripts
     */
    public static int count() {
        return REGISTRY.size();
    }

    // ==================== Exception ====================

    /**
     * Thrown when a script name cannot be resolved to a registered factory.
     *
     * <p>This typically indicates a mismatch between the script name stored
     * in the database and the scripts registered at startup. Check that the
     * script name in the task group matches a registered entry.</p>
     */
    public static class UnknownScriptException extends RuntimeException {

        private final String scriptName;

        /**
         * Creates an exception for an unregistered script name.
         *
         * @param scriptName the name that was not found
         */
        public UnknownScriptException(String scriptName) {
            super("No script registered with name: " + scriptName);
            this.scriptName = scriptName;
        }

        /**
         * Gets the script name that was not found.
         *
         * @return the unregistered script name
         */
        public String scriptName() {
            return scriptName;
        }
    }
}