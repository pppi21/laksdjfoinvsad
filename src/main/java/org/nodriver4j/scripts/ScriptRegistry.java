package org.nodriver4j.scripts;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registry that maps script names to {@link AutomationScript} factories
 * and provides bidirectional display name mapping.
 *
 * <p>Script names are stored in the database on {@link
 * org.nodriver4j.persistence.entity.TaskGroupEntity#scriptName()} and
 * resolved at runtime when a task is started. This class provides the
 * lookup mechanism between those stored names and the concrete script
 * implementations.</p>
 *
 * <h2>Display Names</h2>
 * <p>The UI shows user-friendly display names (e.g., "Uber Eats Account
 * Creator") while the backend and database continue using internal names
 * (e.g., "UberGen"). Display names are resolved at render time via
 * {@link #displayName(String)} and {@link #internalName(String)}. The
 * {@link org.nodriver4j.persistence.entity.TaskGroupEntity#scriptName()}
 * always stores the internal name.</p>
 *
 * <h2>Built-in Scripts</h2>
 * <p>Built-in scripts are registered in the static initializer. Currently:</p>
 * <ul>
 *   <li>{@code "UberGen"} → {@link UberGen} — "Uber Eats Account Creator"</li>
 *   <li>{@code "FunkoGen"} → {@link FunkoGen} — "Funko Account Creator"</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Lookup and create (used by TaskExecutionService)
 * AutomationScript script = ScriptRegistry.create("UberGen");
 * script.run(page, profile, logger);
 *
 * // Display name resolution (used by UI)
 * String friendly = ScriptRegistry.displayName("UberGen");
 *     // → "Uber Eats Account Creator"
 * String internal = ScriptRegistry.internalName("Uber Eats Account Creator");
 *     // → "UberGen"
 *
 * // List all display names for dropdowns
 * List<String> names = ScriptRegistry.displayNames();
 *
 * // Check availability (used by UI for validation)
 * boolean valid = ScriptRegistry.isRegistered("UberGen");
 *
 * // Register a custom script at runtime
 * ScriptRegistry.register("CustomScript", "My Custom Script", CustomScript::new);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>The registry uses {@link ConcurrentHashMap} instances and is safe for
 * concurrent reads and writes from any thread.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Map script names to factory functions</li>
 *   <li>Map internal script names to display names (bidirectional)</li>
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
 *   <li>Deciding where to show display vs internal names (UI concern)</li>
 * </ul>
 *
 * @see AutomationScript
 * @see org.nodriver4j.services.TaskExecutionService
 */
public final class ScriptRegistry {

    /**
     * Map of internal script name → factory function.
     *
     * <p>Factories are {@code Supplier<AutomationScript>} because all
     * runtime dependencies (Page, ProfileEntity, TaskLogger) are passed
     * to {@link AutomationScript#run}, not to the constructor.</p>
     */
    private static final Map<String, Supplier<AutomationScript>> REGISTRY = new ConcurrentHashMap<>();

    /**
     * Map of internal script name → user-friendly display name.
     */
    private static final Map<String, String> DISPLAY_NAMES = new ConcurrentHashMap<>();

    /**
     * Reverse map of display name → internal script name.
     */
    private static final Map<String, String> INTERNAL_NAMES = new ConcurrentHashMap<>();

    // ==================== Built-in Registration ====================

    static {
        register("UberGen", "Uber Eats Account Gen", UberGen::new);
        register("FunkoGen", "Funko Account Gen", FunkoGen::new);
        register("SandwichGen", "Ike's Rewards Account Gen", SandwichGen::new);
        register("BrowserScan", "Browser Scan (Test)", BrowserScan::new);
    }

    // ==================== Private Constructor ====================

    private ScriptRegistry() {
        // Static utility class — not instantiable
    }

    // ==================== Registration ====================

    /**
     * Registers a script factory and display name under the given internal name.
     *
     * <p>If a script is already registered under this name, it is replaced.
     * Names are case-sensitive and should match the values stored in
     * {@link org.nodriver4j.persistence.entity.TaskGroupEntity#scriptName()}.</p>
     *
     * @param internalName the internal script name (e.g., "UberGen")
     * @param displayName  the user-friendly display name (e.g., "Uber Eats Account Creator")
     * @param factory      a supplier that creates new instances of the script
     * @throws IllegalArgumentException if any argument is null or blank
     */
    public static void register(String internalName, String displayName, Supplier<AutomationScript> factory) {
        if (internalName == null || internalName.isBlank()) {
            throw new IllegalArgumentException("Script name cannot be null or blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Display name cannot be null or blank");
        }
        if (factory == null) {
            throw new IllegalArgumentException("Script factory cannot be null");
        }

        REGISTRY.put(internalName, factory);
        DISPLAY_NAMES.put(internalName, displayName);
        INTERNAL_NAMES.put(displayName, internalName);
    }

    // ==================== Lookup ====================

    /**
     * Creates a new instance of the script registered under the given internal name.
     *
     * @param name the internal script name (e.g., "UberGen")
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
     * Checks if a script is registered under the given internal name.
     *
     * @param name the internal script name to check
     * @return true if a factory is registered for this name
     */
    public static boolean isRegistered(String name) {
        return name != null && REGISTRY.containsKey(name);
    }

    /**
     * Returns the set of all registered internal script names.
     *
     * @return unmodifiable set of registered internal script names
     */
    public static Set<String> scriptNames() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }

    // ==================== Display Name Resolution ====================

    /**
     * Resolves an internal script name to its user-friendly display name.
     *
     * <p>Falls back to the internal name if no display name is registered.
     * This ensures the UI always has something to show, even for scripts
     * registered without a display name or scripts added after deployment.</p>
     *
     * @param internalName the internal script name (e.g., "UberGen")
     * @return the display name, or the internal name if no mapping exists
     */
    public static String displayName(String internalName) {
        if (internalName == null) {
            return null;
        }
        return DISPLAY_NAMES.getOrDefault(internalName, internalName);
    }

    /**
     * Resolves a display name back to its internal script name.
     *
     * <p>Falls back to the display name itself if no reverse mapping exists.
     * This handles edge cases where a display name is also a valid internal
     * name (e.g., if someone passes an internal name by mistake).</p>
     *
     * @param displayName the user-friendly display name
     * @return the internal script name, or the input if no mapping exists
     */
    public static String internalName(String displayName) {
        if (displayName == null) {
            return null;
        }
        return INTERNAL_NAMES.getOrDefault(displayName, displayName);
    }

    /**
     * Returns a sorted list of all registered display names.
     *
     * <p>Useful for populating UI dropdowns when creating task groups.
     * The list is sorted alphabetically for consistent presentation.</p>
     *
     * @return sorted list of display names
     */
    public static List<String> displayNames() {
        List<String> names = new ArrayList<>(DISPLAY_NAMES.values());
        Collections.sort(names);
        return Collections.unmodifiableList(names);
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