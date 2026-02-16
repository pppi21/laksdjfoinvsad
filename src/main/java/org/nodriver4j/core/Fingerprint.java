package org.nodriver4j.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Container for browser fingerprint data extracted from real browser profiles.
 *
 * <p>Parses a single line from a JSONL profile file and provides structured
 * access to the fingerprint properties that are passed to Chrome via
 * command-line arguments in {@link Browser#launch}.</p>
 *
 * <p>Profile lines are cached in memory on first access to avoid repeated
 * file reads. Use {@link #totalCount()} to query the number of available
 * profiles and {@link #Fingerprint(int)} to load a specific one by index.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Random fingerprint (for first-time task creation)
 * Fingerprint fp = new Fingerprint();
 * int index = fp.lineIndex(); // persist this on the task
 *
 * // Deterministic reload (for subsequent runs)
 * Fingerprint fp = new Fingerprint(savedIndex);
 *
 * // Check how many profiles are available
 * int count = Fingerprint.totalCount();
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Parse JSONL profile lines into Chrome fingerprint fields</li>
 *   <li>Provide deterministic index-based loading for persistence</li>
 *   <li>Cache profile data in memory for fast access</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Deciding which index to assign to a task (service layer)</li>
 *   <li>Persisting the index (TaskEntity / TaskRepository)</li>
 *   <li>Building Chrome command-line arguments (Browser)</li>
 * </ul>
 *
 * @see Browser
 * @see BrowserConfig
 */
public class Fingerprint {

    private static final String PROFILES_RESOURCE_PATH = System.getenv("fingerprint_profiles");
    private static final Gson GSON = new Gson();
    private static final Random RANDOM = new Random();

    // Valid hardware concurrency values (typical for modern systems)
    private static final int[] HARDWARE_CONCURRENCY_VALUES = {4, 6, 8, 12, 16, 20, 24, 32};

    // ==================== Profile Cache ====================

    /**
     * Lazily-initialized, immutable cache of all profile lines from the JSONL file.
     * Loaded once on first access and reused for all subsequent Fingerprint creations.
     */
    private static volatile List<String> cachedProfiles;

    // ==================== Instance Fields ====================

    /** The line index in the JSONL file that produced this fingerprint. */
    private final int lineIndex;

    /** Deterministic seed derived from the profile's canvas data. */
    private final int seed;

    /** CPU core count passed to Chrome's fingerprint flags. */
    private final int hardwareConcurrency;

    /** WebGL vendor string (e.g., "Google Inc. (NVIDIA)"). */
    private final String gpuVendor;

    /** WebGL renderer string (e.g., "ANGLE (NVIDIA, ... Direct3D11 ...)"). */
    private final String gpuRenderer;

    // ==================== Constructors ====================

    /**
     * Creates a Fingerprint by loading a random profile from the JSONL file.
     *
     * <p>The selected line index is available via {@link #lineIndex()} so it
     * can be persisted for deterministic reloading on future runs.</p>
     *
     * @throws IOException if the profile file cannot be read
     */
    public Fingerprint() throws IOException {
        this(RANDOM.nextInt(loadProfiles().size()));
    }

    /**
     * Creates a Fingerprint by loading a specific profile line from the JSONL file.
     *
     * <p>Use this constructor to reload the same fingerprint across browser
     * sessions by persisting the line index on the task entity.</p>
     *
     * @param lineIndex the zero-based index into the JSONL file
     * @throws IOException              if the profile file cannot be read
     * @throws IllegalArgumentException if lineIndex is out of range
     */
    public Fingerprint(int lineIndex) throws IOException {
        List<String> profiles = loadProfiles();

        if (lineIndex < 0 || lineIndex >= profiles.size()) {
            throw new IllegalArgumentException(
                    "Fingerprint line index out of range: " + lineIndex +
                            " (available: 0-" + (profiles.size() - 1) + ")");
        }

        this.lineIndex = lineIndex;

        JsonObject profile = GSON.fromJson(profiles.get(lineIndex), JsonObject.class);

        // Seed — derived deterministically from canvas data
        String canvasData = getStringOrDefault(profile, "canvas", String.valueOf(lineIndex));
        this.seed = generateSeedFromString(canvasData);

        // Hardware concurrency — deterministic from seed
        this.hardwareConcurrency = HARDWARE_CONCURRENCY_VALUES[seed % HARDWARE_CONCURRENCY_VALUES.length];

        // WebGL
        this.gpuVendor = getStringOrDefault(profile, "vendor", "Google Inc. (NVIDIA)");
        this.gpuRenderer = buildRendererString(profile);
    }

    // ==================== Static Methods ====================

    /**
     * Returns the total number of available fingerprint profiles.
     *
     * <p>Use this to validate indices or generate a random one externally:</p>
     * <pre>{@code
     * int index = new Random().nextInt(Fingerprint.totalCount());
     * }</pre>
     *
     * @return the number of profile lines in the JSONL file
     * @throws IOException if the profile file cannot be read
     */
    public static int totalCount() throws IOException {
        return loadProfiles().size();
    }

    // ==================== Getters ====================

    /**
     * Gets the line index in the JSONL file that produced this fingerprint.
     *
     * <p>Persist this value on the task entity to reload the same fingerprint
     * on future browser sessions.</p>
     *
     * @return the zero-based line index
     */
    public int lineIndex() {
        return lineIndex;
    }

    /**
     * Gets the deterministic seed derived from the profile's canvas data.
     *
     * @return the fingerprint seed (always positive)
     */
    public int seed() {
        return seed;
    }

    /**
     * Gets the CPU hardware concurrency value.
     *
     * @return the number of logical processors
     */
    public int hardwareConcurrency() {
        return hardwareConcurrency;
    }

    /**
     * Gets the WebGL vendor string.
     *
     * @return the GPU vendor (e.g., "Google Inc. (NVIDIA)")
     */
    public String gpuVendor() {
        return gpuVendor;
    }

    /**
     * Gets the WebGL renderer string.
     *
     * @return the GPU renderer (e.g., "ANGLE (NVIDIA, ... Direct3D11 ...)")
     */
    public String gpuRenderer() {
        return gpuRenderer;
    }

    // ==================== Deprecated Getters (backward compatibility) ====================

    /** @deprecated Use {@link #seed()} instead. */
    @Deprecated
    public int getSeed() {
        return seed;
    }

    /** @deprecated Use {@link #hardwareConcurrency()} instead. */
    @Deprecated
    public int getHardwareConcurrency() {
        return hardwareConcurrency;
    }

    /** @deprecated Use {@link #gpuVendor()} instead. */
    @Deprecated
    public String getGpuVendor() {
        return gpuVendor;
    }

    /** @deprecated Use {@link #gpuRenderer()} instead. */
    @Deprecated
    public String getGpuRenderer() {
        return gpuRenderer;
    }

    // ==================== Profile Loading ====================

    /**
     * Loads and caches all profile lines from the JSONL file.
     *
     * <p>Uses double-checked locking to ensure the file is read at most once,
     * even under concurrent access from multiple browser launches.</p>
     *
     * @return unmodifiable list of JSON profile strings
     * @throws IOException if the file cannot be read or contains no profiles
     */
    private static List<String> loadProfiles() throws IOException {
        List<String> profiles = cachedProfiles;
        if (profiles != null) {
            return profiles;
        }

        synchronized (Fingerprint.class) {
            // Re-check after acquiring lock
            if (cachedProfiles != null) {
                return cachedProfiles;
            }

            Path profilesPath = Path.of(PROFILES_RESOURCE_PATH);

            if (!Files.exists(profilesPath)) {
                throw new IOException("Fingerprint profiles file not found: " + PROFILES_RESOURCE_PATH);
            }

            List<String> loaded = new ArrayList<>();

            try (BufferedReader reader = Files.newBufferedReader(profilesPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        loaded.add(line);
                    }
                }
            }

            if (loaded.isEmpty()) {
                throw new IOException("No profiles found in file: " + PROFILES_RESOURCE_PATH);
            }

            cachedProfiles = Collections.unmodifiableList(loaded);
            System.out.println("[Fingerprint] Cached " + cachedProfiles.size() + " profiles from " + profilesPath);
            return cachedProfiles;
        }
    }

    // ==================== Parsing Helpers ====================

    /**
     * Builds the WebGL renderer string from the profile data.
     */
    private static String buildRendererString(JsonObject profile) {
        String renderer = getStringOrDefault(profile, "renderer", null);

        if (renderer != null && !renderer.isEmpty()) {
            return renderer;
        }

        return "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 (0x00002504) Direct3D11 vs_5_0 ps_5_0, D3D11)";
    }

    /**
     * Generates a deterministic positive seed from a string.
     */
    private static int generateSeedFromString(String input) {
        int hash = input.hashCode();
        return hash == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(hash);
    }

    private static String getStringOrDefault(JsonObject obj, String key, String defaultValue) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format(
                "Fingerprint{index=%d, seed=%d, gpu=%s, cores=%d}",
                lineIndex, seed,
                gpuRenderer.substring(0, Math.min(50, gpuRenderer.length())) + "...",
                hardwareConcurrency
        );
    }
}