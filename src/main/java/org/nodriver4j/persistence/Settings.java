package org.nodriver4j.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JSON-based application settings manager.
 *
 * <p>Manages persistent application settings that rarely change, such as:</p>
 * <ul>
 *   <li>Chrome executable path</li>
 *   <li>Default browser options (headless, fingerprinting, etc.)</li>
 *   <li>API keys for external services (captcha solvers, SMS providers)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Load settings on startup
 * Settings.load();
 *
 * // Read settings
 * String chromePath = Settings.get().chromePath();
 *
 * // Modify and save
 * Settings.get().chromePath("/path/to/chrome");
 * Settings.save();
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Load/save settings from JSON file</li>
 *   <li>Provide typed access to settings</li>
 *   <li>Supply sensible defaults</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Database operations</li>
 *   <li>Runtime browser configuration (use BrowserConfig)</li>
 *   <li>Validation of setting values</li>
 * </ul>
 */
public final class Settings {

    private static final String DATA_DIRECTORY = "nodriver4j-data";
    private static final String SETTINGS_FILE = "settings.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    private static final AtomicReference<Settings> instance = new AtomicReference<>();

    // ==================== Settings Fields ====================

    // Browser defaults
    private String chromePath;
    private boolean defaultHeadless;
    private boolean defaultFingerprintEnabled;
    private boolean fingerprintMonitoringEnabled;

    // Warming
    private boolean defaultWarmProfile;

    // Captcha API Keys
    private String autoSolveApiKey;
    private String capsolverApiKey;
    private String twoCaptchaApiKey;

    // SMS Provider API Keys
    private String textVerifiedApiKey;
    private String textVerifiedEmail;
    private String smsManApiKey;
    private String daisySmsApiKey;

    // Userdata management
    private String userdataBasePath;

    // Fingerprint profiles
    private String fingerprintsPath;

    // ==================== Constructor ====================

    /**
     * Creates Settings with default values.
     */
    public Settings() {
        // Browser defaults
        this.defaultHeadless = false;
        this.defaultFingerprintEnabled = true;
        this.fingerprintMonitoringEnabled = false;

        // Warming
        this.defaultWarmProfile = false;

        // Captcha API Keys
        this.autoSolveApiKey = "";
        this.capsolverApiKey = "";
        this.twoCaptchaApiKey = "";

        // SMS Provider API Keys
        this.textVerifiedApiKey = "";
        this.textVerifiedEmail = "";
        this.smsManApiKey = "";
        this.daisySmsApiKey = "";

        // Userdata - default to data directory
        this.userdataBasePath = Path.of(DATA_DIRECTORY, "userdata").toString();

        // Fingerprints - default to data directory
        this.fingerprintsPath = Path.of(DATA_DIRECTORY, "fingerprints.jsonl").toString();
    }

    // ==================== Static Access ====================

    /**
     * Gets the current settings instance.
     *
     * <p>If settings haven't been loaded, loads them first.</p>
     *
     * @return the Settings instance
     */
    public static Settings get() {
        Settings settings = instance.get();
        if (settings == null) {
            load();
            settings = instance.get();
        }
        return settings;
    }

    /**
     * Loads settings from the JSON file.
     *
     * <p>If the file doesn't exist, creates default settings.</p>
     * <p>This method is idempotent if settings are already loaded.</p>
     */
    public static void load() {
        Path settingsPath = settingsPath();

        try {
            if (Files.exists(settingsPath)) {
                String json = Files.readString(settingsPath);
                Settings loaded = GSON.fromJson(json, Settings.class);

                if (loaded != null) {
                    instance.set(loaded);
                    System.out.println("[Settings] Loaded from: " + settingsPath);
                    return;
                }
            }

            // File doesn't exist or was empty/invalid - use defaults
            Settings defaults = new Settings();
            instance.set(defaults);
            System.out.println("[Settings] Using default settings");

            // Save defaults to create the file
            save();

        } catch (IOException e) {
            System.err.println("[Settings] Failed to load settings: " + e.getMessage());
            instance.set(new Settings());
        }
    }

    /**
     * Saves current settings to the JSON file.
     *
     * @throws SettingsException if save fails
     */
    public static void save() {
        Settings current = instance.get();
        if (current == null) {
            current = new Settings();
            instance.set(current);
        }

        try {
            Path settingsPath = settingsPath();

            // Ensure directory exists
            Path parent = settingsPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            String json = GSON.toJson(current);
            Files.writeString(settingsPath, json);

            System.out.println("[Settings] Saved to: " + settingsPath);

        } catch (IOException e) {
            throw new SettingsException("Failed to save settings", e);
        }
    }

    /**
     * Reloads settings from disk, discarding any unsaved changes.
     */
    public static void reload() {
        instance.set(null);
        load();
    }

    /**
     * Resets settings to defaults and saves.
     */
    public static void reset() {
        instance.set(new Settings());
        save();
        System.out.println("[Settings] Reset to defaults");
    }

    // ==================== Path Utilities ====================

    /**
     * Gets the path to the settings file.
     *
     * @return the settings file path
     */
    public static Path settingsPath() {
        return Path.of(DATA_DIRECTORY, SETTINGS_FILE);
    }

    // ==================== Browser Defaults ====================

    /**
     * Gets the Chrome executable path.
     *
     * @return the path, or empty string if not set
     */
    public String chromePath() {
        return chromePath;
    }

    /**
     * Sets the Chrome executable path.
     *
     * @param path the path to Chrome executable
     * @return this Settings for chaining
     */
    public Settings chromePath(String path) {
        this.chromePath = path != null ? path : "";
        return this;
    }

    /**
     * Checks if Chrome path is configured.
     *
     * @return true if a non-empty path is set
     */
    public boolean hasChromePath() {
        return chromePath != null && !chromePath.isBlank();
    }

    /**
     * Gets the default headless mode setting.
     *
     * @return true if headless by default
     */
    public boolean defaultHeadless() {
        return defaultHeadless;
    }

    /**
     * Sets the default headless mode.
     *
     * @param headless true for headless by default
     * @return this Settings for chaining
     */
    public Settings defaultHeadless(boolean headless) {
        this.defaultHeadless = headless;
        return this;
    }

    /**
     * Gets the default fingerprint enabled setting.
     *
     * @return true if fingerprinting enabled by default
     */
    public boolean defaultFingerprintEnabled() {
        return defaultFingerprintEnabled;
    }

    /**
     * Sets the default fingerprint enabled setting.
     *
     * @param enabled true to enable fingerprinting by default
     * @return this Settings for chaining
     */
    public Settings defaultFingerprintEnabled(boolean enabled) {
        this.defaultFingerprintEnabled = enabled;
        return this;
    }

    /**
     * Gets the fingerprint monitoring enabled setting.
     *
     * <p>When enabled, task execution injects a diagnostic script
     * that records which fingerprint-related APIs a website accesses.
     * Results are exported to JSON files in {@code nodriver4j-data/fp-reports/}.</p>
     *
     * @return true if fingerprint monitoring is enabled
     */
    public boolean fingerprintMonitoringEnabled() {
        return fingerprintMonitoringEnabled;
    }

    /**
     * Sets the fingerprint monitoring enabled setting.
     *
     * @param enabled true to enable fingerprint monitoring
     * @return this Settings for chaining
     */
    public Settings fingerprintMonitoringEnabled(boolean enabled) {
        this.fingerprintMonitoringEnabled = enabled;
        return this;
    }

    // ==================== Warming ====================

    /**
     * Gets the default warm profile setting.
     *
     * @return true if profile warming enabled by default
     */
    public boolean defaultWarmProfile() {
        return defaultWarmProfile;
    }

    /**
     * Sets the default warm profile setting.
     *
     * @param enabled true to enable warming by default
     * @return this Settings for chaining
     */
    public Settings defaultWarmProfile(boolean enabled) {
        this.defaultWarmProfile = enabled;
        return this;
    }

    // ==================== Captcha API Keys ====================

    /**
     * Gets the AutoSolve AI API key.
     *
     * @return the API key, or empty string if not set
     */
    public String autoSolveApiKey() {
        return autoSolveApiKey;
    }

    /**
     * Sets the AutoSolve AI API key.
     *
     * @param apiKey the API key
     * @return this Settings for chaining
     */
    public Settings autoSolveApiKey(String apiKey) {
        this.autoSolveApiKey = apiKey != null ? apiKey : "";
        return this;
    }

    /**
     * Checks if AutoSolve API key is configured.
     *
     * @return true if a non-empty key is set
     */
    public boolean hasAutoSolveApiKey() {
        return autoSolveApiKey != null && !autoSolveApiKey.isBlank();
    }

    /**
     * Gets the CapSolver API key.
     *
     * @return the API key, or empty string if not set
     */
    public String capsolverApiKey() {
        return capsolverApiKey;
    }

    /**
     * Sets the CapSolver API key.
     *
     * @param apiKey the API key
     * @return this Settings for chaining
     */
    public Settings capsolverApiKey(String apiKey) {
        this.capsolverApiKey = apiKey != null ? apiKey : "";
        return this;
    }

    /**
     * Checks if CapSolver API key is configured.
     *
     * @return true if a non-empty key is set
     */
    public boolean hasCapsolverApiKey() {
        return capsolverApiKey != null && !capsolverApiKey.isBlank();
    }

    /**
     * Gets the 2Captcha API key.
     *
     * @return the API key, or empty string if not set
     */
    public String twoCaptchaApiKey() {
        return twoCaptchaApiKey;
    }

    /**
     * Sets the 2Captcha API key.
     *
     * @param apiKey the API key
     * @return this Settings for chaining
     */
    public Settings twoCaptchaApiKey(String apiKey) {
        this.twoCaptchaApiKey = apiKey != null ? apiKey : "";
        return this;
    }

    /**
     * Checks if 2Captcha API key is configured.
     *
     * @return true if a non-empty key is set
     */
    public boolean hasTwoCaptchaApiKey() {
        return twoCaptchaApiKey != null && !twoCaptchaApiKey.isBlank();
    }

    // ==================== SMS Provider API Keys ====================

    /**
     * Gets the TextVerified API key.
     *
     * @return the API key, or empty string if not set
     */
    public String textVerifiedApiKey() {
        return textVerifiedApiKey;
    }

    /**
     * Sets the TextVerified API key.
     *
     * @param apiKey the API key
     * @return this Settings for chaining
     */
    public Settings textVerifiedApiKey(String apiKey) {
        this.textVerifiedApiKey = apiKey != null ? apiKey : "";
        return this;
    }

    /**
     * Checks if TextVerified API key is configured.
     *
     * @return true if a non-empty key is set
     */
    public boolean hasTextVerifiedApiKey() {
        return textVerifiedApiKey != null && !textVerifiedApiKey.isBlank();
    }

    /**
     * Gets the TextVerified account email (username).
     *
     * <p>TextVerified requires both an API key and an email for
     * bearer token authentication.</p>
     *
     * @return the email, or empty string if not set
     */
    public String textVerifiedEmail() {
        return textVerifiedEmail;
    }

    /**
     * Sets the TextVerified account email (username).
     *
     * @param email the account email
     * @return this Settings for chaining
     */
    public Settings textVerifiedEmail(String email) {
        this.textVerifiedEmail = email != null ? email : "";
        return this;
    }

    /**
     * Checks if TextVerified email is configured.
     *
     * @return true if a non-empty email is set
     */
    public boolean hasTextVerifiedEmail() {
        return textVerifiedEmail != null && !textVerifiedEmail.isBlank();
    }

    /**
     * Gets the SMS-Man API token.
     *
     * @return the API token, or empty string if not set
     */
    public String smsManApiKey() {
        return smsManApiKey;
    }

    /**
     * Sets the SMS-Man API token.
     *
     * @param apiKey the API token
     * @return this Settings for chaining
     */
    public Settings smsManApiKey(String apiKey) {
        this.smsManApiKey = apiKey != null ? apiKey : "";
        return this;
    }

    /**
     * Checks if SMS-Man API token is configured.
     *
     * @return true if a non-empty token is set
     */
    public boolean hasSmsManApiKey() {
        return smsManApiKey != null && !smsManApiKey.isBlank();
    }

    /**
     * Gets the DaisySMS API key.
     *
     * @return the API key, or empty string if not set
     * @deprecated DaisySMS is shutting down on March 26, 2026.
     */
    @Deprecated(since = "2026-03-26", forRemoval = true)
    public String daisySmsApiKey() {
        return daisySmsApiKey;
    }

    /**
     * Sets the DaisySMS API key.
     *
     * @param apiKey the API key
     * @return this Settings for chaining
     * @deprecated DaisySMS is shutting down on March 26, 2026.
     */
    @Deprecated(since = "2026-03-26", forRemoval = true)
    public Settings daisySmsApiKey(String apiKey) {
        this.daisySmsApiKey = apiKey != null ? apiKey : "";
        return this;
    }

    /**
     * Checks if DaisySMS API key is configured.
     *
     * @return true if a non-empty key is set
     * @deprecated DaisySMS is shutting down on March 26, 2026.
     */
    @Deprecated(since = "2026-03-26", forRemoval = true)
    public boolean hasDaisySmsApiKey() {
        return daisySmsApiKey != null && !daisySmsApiKey.isBlank();
    }

    // ==================== Userdata ====================

    /**
     * Gets the base path for browser userdata directories.
     *
     * <p>Individual task userdata folders are created under this path.</p>
     *
     * @return the userdata base path
     */
    public String userdataBasePath() {
        return userdataBasePath;
    }

    /**
     * Sets the base path for browser userdata directories.
     *
     * @param path the base path
     * @return this Settings for chaining
     */
    public Settings userdataBasePath(String path) {
        this.userdataBasePath = path != null ? path : Path.of(DATA_DIRECTORY, "userdata").toString();
        return this;
    }

    /**
     * Gets the userdata path for a specific task.
     *
     * @param taskId the task ID
     * @return the full userdata path for the task
     */
    public Path userdataPathForTask(long taskId) {
        return Path.of(userdataBasePath, "task-" + taskId);
    }

    // ==================== Fingerprints ====================

    /**
     * Gets the path to the fingerprint profiles JSONL file.
     *
     * @return the fingerprints file path
     */
    public String fingerprintsPath() {
        return fingerprintsPath;
    }

    /**
     * Sets the path to the fingerprint profiles JSONL file.
     *
     * @param path the path to the JSONL file
     * @return this Settings for chaining
     */
    public Settings fingerprintsPath(String path) {
        this.fingerprintsPath = path != null ? path : Path.of(DATA_DIRECTORY, "fingerprints.jsonl").toString();
        return this;
    }

    /**
     * Checks if fingerprints path is configured.
     *
     * @return true if a non-empty path is set
     */
    public boolean hasFingerprintsPath() {
        return fingerprintsPath != null && !fingerprintsPath.isBlank();
    }

    // ==================== Exception ====================

    /**
     * Exception thrown for settings-related errors.
     */
    public static class SettingsException extends RuntimeException {

        public SettingsException(String message) {
            super(message);
        }

        public SettingsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}