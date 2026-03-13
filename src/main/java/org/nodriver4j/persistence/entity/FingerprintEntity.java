package org.nodriver4j.persistence.entity;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entity representing a browser fingerprint identity in the database.
 *
 * <p>A fingerprint captures all the browser identity values needed to launch
 * a Chrome session that appears as a consistent, real device. Values are
 * extracted once from a JSONL profile line and persisted so the identity
 * remains stable across browser restarts without re-reading the source file.</p>
 *
 * <p>Each fingerprint belongs to exactly one task (1:1 relationship).
 * Tasks reference their fingerprint via {@code fingerprint_id} foreign key.</p>
 *
 * <h2>Database Table</h2>
 * <pre>
 * fingerprints (
 *     id INTEGER PRIMARY KEY,
 *     jsonl_line_index INTEGER,
 *     browser_brand TEXT NOT NULL,
 *     browser_major_version INTEGER NOT NULL,
 *     brand_version_long TEXT NOT NULL,
 *     platform TEXT NOT NULL,
 *     platform_version TEXT NOT NULL,
 *     gpu_vendor TEXT NOT NULL,
 *     gpu_renderer TEXT NOT NULL,
 *     device_type TEXT NOT NULL DEFAULT 'desktop',
 *     seed INTEGER NOT NULL,
 *     hardware_concurrency INTEGER NOT NULL,
 *     device_memory INTEGER NOT NULL,
 *     screen_width INTEGER NOT NULL,
 *     screen_height INTEGER NOT NULL,
 *     avail_width INTEGER NOT NULL,
 *     avail_height INTEGER NOT NULL,
 *     avail_top INTEGER NOT NULL DEFAULT 0,
 *     color_depth INTEGER NOT NULL DEFAULT 24,
 *     device_pixel_ratio REAL,
 *     audio_sample_rate INTEGER NOT NULL,
 *     audio_base_latency REAL NOT NULL,
 *     audio_output_latency REAL NOT NULL,
 *     audio_max_channel_count INTEGER NOT NULL,
 *     media_mics INTEGER NOT NULL DEFAULT 1,
 *     media_webcams INTEGER NOT NULL DEFAULT 1,
 *     media_speakers INTEGER NOT NULL DEFAULT 1,
 *     extra_switches TEXT,
 *     created_at TEXT NOT NULL,
 *     updated_at TEXT NOT NULL
 * )
 * </pre>
 *
 * <h2>Value Sources</h2>
 * <ul>
 *   <li><b>Extracted from JSONL:</b> gpuVendor, gpuRenderer, availWidth, availHeight,
 *       audioSampleRate, audioBaseLatency, audioOutputLatency, audioMaxChannelCount</li>
 *   <li><b>Parsed from UA string:</b> browserBrand, browserMajorVersion, platform,
 *       brandVersionLong, platformVersion</li>
 *   <li><b>Derived/generated on first extraction:</b> seed, hardwareConcurrency,
 *       deviceType, deviceMemory, screenWidth, screenHeight, availTop, colorDepth,
 *       mediaMics, mediaWebcams, mediaSpeakers</li>
 * </ul>
 *
 * <h2>NOT Stored Here (per-session or external)</h2>
 * <ul>
 *   <li>Battery values — randomized per launch</li>
 *   <li>Screen position (screenX/Y) — randomized per launch</li>
 *   <li>History length — randomized per launch</li>
 *   <li>Timezone — derived from proxy/task context, stored on BrowserConfig</li>
 * </ul>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Hold all persistent browser identity fields</li>
 *   <li>Represent a row in the fingerprints table</li>
 *   <li>Define device type constants</li>
 *   <li>Manage extra switches as a JSON key-value map</li>
 *   <li>Provide convenience methods for device classification</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Extracting values from JSONL (use Fingerprint / extraction service)</li>
 *   <li>Database operations (use FingerprintRepository)</li>
 *   <li>Building Chrome command-line arguments (use Browser / BrowserConfig)</li>
 *   <li>Per-session value generation (battery, screen position, history length)</li>
 * </ul>
 *
 * @see TaskEntity
 */
public class FingerprintEntity {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Gson GSON = new Gson();
    private static final Type EXTRA_SWITCHES_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    // ==================== Device Type Constants ====================

    /** Standard desktop computer or workstation. */
    public static final String DEVICE_DESKTOP = "desktop";

    /** Laptop or notebook computer. */
    public static final String DEVICE_LAPTOP = "laptop";

    // ==================== Platform Constants ====================

    /** Windows operating system. */
    public static final String PLATFORM_WINDOWS = "Windows";

    /** macOS operating system. */
    public static final String PLATFORM_MACOS = "macOS";

    /** Linux operating system. */
    public static final String PLATFORM_LINUX = "Linux";

    // ==================== Identity ====================

    private long id;

    // ==================== Source Traceability ====================

    /** The JSONL line index this fingerprint was extracted from. Null if not from JSONL. */
    private Integer jsonlLineIndex;

    // ==================== UA-Derived ====================

    private String browserBrand;
    private int browserMajorVersion;
    private String brandVersionLong;
    private String platform;
    private String platformVersion;

    // ==================== GPU ====================

    private String gpuVendor;
    private String gpuRenderer;

    // ==================== Classification ====================

    private String deviceType;

    // ==================== Seeds ====================

    private int seed;
    private int hardwareConcurrency;

    // ==================== Device Hardware ====================

    private int deviceMemory;

    // ==================== Screen ====================

    private int screenWidth;
    private int screenHeight;
    private int availWidth;
    private int availHeight;
    private int availTop;
    private int colorDepth;
    private Double devicePixelRatio;

    // ==================== Audio Context ====================

    private int audioSampleRate;
    private double audioBaseLatency;
    private double audioOutputLatency;
    private int audioMaxChannelCount;

    // ==================== Media Features (CSS) ====================

    private String prefersColorScheme;
    private String colorGamut;

    // ==================== Media Devices ====================

    private int mediaMics;
    private int mediaWebcams;
    private int mediaSpeakers;

    // ==================== Extra Switches ====================

    /**
     * JSON-serialized map of additional CLI switches not covered by dedicated columns.
     * Format: {"--switch-name": "value", ...}. Null when empty.
     */
    private String extraSwitches;

    // ==================== Metadata ====================

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ==================== Constructors ====================

    /**
     * Default constructor for repository mapping.
     */
    public FingerprintEntity() {
        this.deviceType = DEVICE_DESKTOP;
        this.colorDepth = 24;
        this.mediaMics = 1;
        this.mediaWebcams = 1;
        this.mediaSpeakers = 1;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Private constructor for builder.
     */
    private FingerprintEntity(Builder builder) {
        this.id = builder.id;
        this.jsonlLineIndex = builder.jsonlLineIndex;
        this.browserBrand = builder.browserBrand;
        this.browserMajorVersion = builder.browserMajorVersion;
        this.brandVersionLong = builder.brandVersionLong;
        this.platform = builder.platform;
        this.platformVersion = builder.platformVersion;
        this.gpuVendor = builder.gpuVendor;
        this.gpuRenderer = builder.gpuRenderer;
        this.deviceType = builder.deviceType;
        this.seed = builder.seed;
        this.hardwareConcurrency = builder.hardwareConcurrency;
        this.deviceMemory = builder.deviceMemory;
        this.screenWidth = builder.screenWidth;
        this.screenHeight = builder.screenHeight;
        this.availWidth = builder.availWidth;
        this.availHeight = builder.availHeight;
        this.availTop = builder.availTop;
        this.colorDepth = builder.colorDepth;
        this.devicePixelRatio = builder.devicePixelRatio;
        this.audioSampleRate = builder.audioSampleRate;
        this.audioBaseLatency = builder.audioBaseLatency;
        this.audioOutputLatency = builder.audioOutputLatency;
        this.audioMaxChannelCount = builder.audioMaxChannelCount;
        this.prefersColorScheme = builder.prefersColorScheme;
        this.colorGamut = builder.colorGamut;
        this.mediaMics = builder.mediaMics;
        this.mediaWebcams = builder.mediaWebcams;
        this.mediaSpeakers = builder.mediaSpeakers;
        this.extraSwitches = builder.extraSwitches;
        this.createdAt = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
        this.updatedAt = builder.updatedAt != null ? builder.updatedAt : LocalDateTime.now();
    }

    // ==================== Builder Factory ====================

    /**
     * Creates a new builder for FingerprintEntity.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder initialized with this entity's values.
     *
     * @return a Builder with current values
     */
    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .jsonlLineIndex(jsonlLineIndex)
                .browserBrand(browserBrand)
                .browserMajorVersion(browserMajorVersion)
                .brandVersionLong(brandVersionLong)
                .platform(platform)
                .platformVersion(platformVersion)
                .gpuVendor(gpuVendor)
                .gpuRenderer(gpuRenderer)
                .deviceType(deviceType)
                .seed(seed)
                .hardwareConcurrency(hardwareConcurrency)
                .deviceMemory(deviceMemory)
                .screenWidth(screenWidth)
                .screenHeight(screenHeight)
                .availWidth(availWidth)
                .availHeight(availHeight)
                .availTop(availTop)
                .colorDepth(colorDepth)
                .devicePixelRatio(devicePixelRatio)
                .audioSampleRate(audioSampleRate)
                .audioBaseLatency(audioBaseLatency)
                .audioOutputLatency(audioOutputLatency)
                .audioMaxChannelCount(audioMaxChannelCount)
                .prefersColorScheme(prefersColorScheme)
                .colorGamut(colorGamut)
                .mediaMics(mediaMics)
                .mediaWebcams(mediaWebcams)
                .mediaSpeakers(mediaSpeakers)
                .extraSwitches(extraSwitches)
                .createdAt(createdAt)
                .updatedAt(updatedAt);
    }

    // ==================== Getters ====================

    /**
     * Gets the fingerprint ID.
     *
     * @return the ID, or 0 if not yet persisted
     */
    public long id() {
        return id;
    }

    /**
     * Gets the JSONL line index this fingerprint was extracted from.
     *
     * @return the zero-based line index, or null if not sourced from JSONL
     */
    public Integer jsonlLineIndex() {
        return jsonlLineIndex;
    }

    /**
     * Gets the browser brand name.
     *
     * @return the brand (e.g., "Chrome", "Chromium")
     */
    public String browserBrand() {
        return browserBrand;
    }

    /**
     * Gets the browser major version number.
     *
     * @return the major version (e.g., 120, 145)
     */
    public int browserMajorVersion() {
        return browserMajorVersion;
    }

    /**
     * Gets the full browser version string for Client Hints.
     *
     * @return the full version (e.g., "145.0.7632.117")
     */
    public String brandVersionLong() {
        return brandVersionLong;
    }

    /**
     * Gets the operating system platform name.
     *
     * @return the platform (one of {@link #PLATFORM_WINDOWS}, {@link #PLATFORM_MACOS},
     *         {@link #PLATFORM_LINUX})
     */
    public String platform() {
        return platform;
    }

    /**
     * Gets the operating system version string for Client Hints.
     *
     * @return the version (e.g., "10.0.0" for Win10, "15.0.0" for Win11, "10.15.7" for macOS)
     */
    public String platformVersion() {
        return platformVersion;
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
     * @return the GPU renderer (e.g., "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 ...)")
     */
    public String gpuRenderer() {
        return gpuRenderer;
    }

    /**
     * Gets the device classification.
     *
     * @return the device type ({@link #DEVICE_DESKTOP} or {@link #DEVICE_LAPTOP})
     */
    public String deviceType() {
        return deviceType;
    }

    /**
     * Gets the deterministic seed derived from the profile's canvas data.
     *
     * <p>Used for {@code --canvas-fingerprint} and {@code --audio-fingerprint}.</p>
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
     * Gets the device memory in GB.
     *
     * <p>The Chromium patch auto-clamps this to a plausible value based on
     * the spoofed browser version.</p>
     *
     * @return the device memory (e.g., 4, 8, 16)
     */
    public int deviceMemory() {
        return deviceMemory;
    }

    /**
     * Gets the screen width in CSS pixels.
     *
     * @return the screen width
     */
    public int screenWidth() {
        return screenWidth;
    }

    /**
     * Gets the screen height in CSS pixels.
     *
     * @return the screen height
     */
    public int screenHeight() {
        return screenHeight;
    }

    /**
     * Gets the available screen width (excluding OS chrome like taskbar on sides).
     *
     * @return the available width
     */
    public int availWidth() {
        return availWidth;
    }

    /**
     * Gets the available screen height (excluding OS chrome like taskbar/dock).
     *
     * @return the available height
     */
    public int availHeight() {
        return availHeight;
    }

    /**
     * Gets the top offset of the available screen area.
     *
     * <p>On macOS this accounts for the menu bar (25px) or notch (37-38px).
     * On Windows this is typically 0 (taskbar at bottom).</p>
     *
     * @return the available top offset
     */
    public int availTop() {
        return availTop;
    }

    /**
     * Gets the screen color depth in bits.
     *
     * @return the color depth (typically 24 or 32)
     */
    public int colorDepth() {
        return colorDepth;
    }

    /**
     * Gets the device pixel ratio.
     *
     * <p>Null until the devicePixelRatio switch is implemented.</p>
     *
     * @return the DPR (e.g., 1.0, 1.25, 1.5, 2.0), or null if not set
     */
    public Double devicePixelRatio() {
        return devicePixelRatio;
    }

    /**
     * Gets the AudioContext sample rate.
     *
     * @return the sample rate in Hz (e.g., 44100, 48000)
     */
    public int audioSampleRate() {
        return audioSampleRate;
    }

    /**
     * Gets the AudioContext base latency.
     *
     * @return the base latency in seconds
     */
    public double audioBaseLatency() {
        return audioBaseLatency;
    }

    /**
     * Gets the AudioContext output latency.
     *
     * @return the output latency in seconds
     */
    public double audioOutputLatency() {
        return audioOutputLatency;
    }

    /**
     * Gets the AudioDestinationNode max channel count.
     *
     * @return the max channel count (typically 2)
     */
    public int audioMaxChannelCount() {
        return audioMaxChannelCount;
    }

    /**
     * Gets the preferred color scheme for CSS media features.
     *
     * @return "light" or "dark", or null if not set
     */
    public String prefersColorScheme() {
        return prefersColorScheme;
    }

    /**
     * Gets the color gamut for CSS media features.
     *
     * @return "srgb" or "p3", or null if not set
     */
    public String colorGamut() {
        return colorGamut;
    }

    /**
     * Gets the number of spoofed microphone devices.
     *
     * @return the mic count
     */
    public int mediaMics() {
        return mediaMics;
    }

    /**
     * Gets the number of spoofed webcam devices.
     *
     * @return the webcam count
     */
    public int mediaWebcams() {
        return mediaWebcams;
    }

    /**
     * Gets the number of spoofed speaker devices.
     *
     * @return the speaker count
     */
    public int mediaSpeakers() {
        return mediaSpeakers;
    }

    /**
     * Gets the raw JSON string of extra CLI switches.
     *
     * @return the JSON map string, or null if no extras
     */
    public String extraSwitches() {
        return extraSwitches;
    }

    /**
     * Gets extra CLI switches as a parsed map.
     *
     * @return unmodifiable map of switch name to value, empty if none
     */
    public Map<String, String> extraSwitchesMap() {
        if (extraSwitches == null || extraSwitches.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = GSON.fromJson(extraSwitches, EXTRA_SWITCHES_TYPE);
        return map != null ? Collections.unmodifiableMap(map) : Collections.emptyMap();
    }

    /**
     * Gets the creation timestamp.
     *
     * @return the creation time
     */
    public LocalDateTime createdAt() {
        return createdAt;
    }

    /**
     * Gets the creation timestamp as a string for database storage.
     *
     * @return ISO-formatted datetime string
     */
    public String createdAtString() {
        return createdAt != null ? createdAt.format(FORMATTER) : null;
    }

    /**
     * Gets the last update timestamp.
     *
     * @return the last update time
     */
    public LocalDateTime updatedAt() {
        return updatedAt;
    }

    /**
     * Gets the last update timestamp as a string for database storage.
     *
     * @return ISO-formatted datetime string
     */
    public String updatedAtString() {
        return updatedAt != null ? updatedAt.format(FORMATTER) : null;
    }

    // ==================== Setters ====================

    /**
     * Sets the fingerprint ID.
     *
     * @param id the ID
     * @return this entity for chaining
     */
    public FingerprintEntity id(long id) {
        this.id = id;
        return this;
    }

    /**
     * Sets the JSONL line index.
     *
     * @param jsonlLineIndex the index, or null to clear
     * @return this entity for chaining
     */
    public FingerprintEntity jsonlLineIndex(Integer jsonlLineIndex) {
        this.jsonlLineIndex = jsonlLineIndex;
        return this;
    }

    /**
     * Sets the browser brand name.
     *
     * @param browserBrand the brand
     * @return this entity for chaining
     */
    public FingerprintEntity browserBrand(String browserBrand) {
        this.browserBrand = browserBrand;
        return this;
    }

    /**
     * Sets the browser major version.
     *
     * @param browserMajorVersion the major version
     * @return this entity for chaining
     */
    public FingerprintEntity browserMajorVersion(int browserMajorVersion) {
        this.browserMajorVersion = browserMajorVersion;
        return this;
    }

    /**
     * Sets the full browser version string.
     *
     * @param brandVersionLong the full version
     * @return this entity for chaining
     */
    public FingerprintEntity brandVersionLong(String brandVersionLong) {
        this.brandVersionLong = brandVersionLong;
        return this;
    }

    /**
     * Sets the operating system platform.
     *
     * @param platform the platform name
     * @return this entity for chaining
     */
    public FingerprintEntity platform(String platform) {
        this.platform = platform;
        return this;
    }

    /**
     * Sets the operating system version.
     *
     * @param platformVersion the version string
     * @return this entity for chaining
     */
    public FingerprintEntity platformVersion(String platformVersion) {
        this.platformVersion = platformVersion;
        return this;
    }

    /**
     * Sets the WebGL vendor string.
     *
     * @param gpuVendor the vendor
     * @return this entity for chaining
     */
    public FingerprintEntity gpuVendor(String gpuVendor) {
        this.gpuVendor = gpuVendor;
        return this;
    }

    /**
     * Sets the WebGL renderer string.
     *
     * @param gpuRenderer the renderer
     * @return this entity for chaining
     */
    public FingerprintEntity gpuRenderer(String gpuRenderer) {
        this.gpuRenderer = gpuRenderer;
        return this;
    }

    /**
     * Sets the device type classification.
     *
     * @param deviceType the type ({@link #DEVICE_DESKTOP} or {@link #DEVICE_LAPTOP})
     * @return this entity for chaining
     */
    public FingerprintEntity deviceType(String deviceType) {
        this.deviceType = deviceType != null ? deviceType : DEVICE_DESKTOP;
        return this;
    }

    /**
     * Sets the fingerprint seed.
     *
     * @param seed the seed value
     * @return this entity for chaining
     */
    public FingerprintEntity seed(int seed) {
        this.seed = seed;
        return this;
    }

    /**
     * Sets the hardware concurrency.
     *
     * @param hardwareConcurrency the core count
     * @return this entity for chaining
     */
    public FingerprintEntity hardwareConcurrency(int hardwareConcurrency) {
        this.hardwareConcurrency = hardwareConcurrency;
        return this;
    }

    /**
     * Sets the device memory in GB.
     *
     * @param deviceMemory the memory value
     * @return this entity for chaining
     */
    public FingerprintEntity deviceMemory(int deviceMemory) {
        this.deviceMemory = deviceMemory;
        return this;
    }

    /**
     * Sets the screen width.
     *
     * @param screenWidth the width in CSS pixels
     * @return this entity for chaining
     */
    public FingerprintEntity screenWidth(int screenWidth) {
        this.screenWidth = screenWidth;
        return this;
    }

    /**
     * Sets the screen height.
     *
     * @param screenHeight the height in CSS pixels
     * @return this entity for chaining
     */
    public FingerprintEntity screenHeight(int screenHeight) {
        this.screenHeight = screenHeight;
        return this;
    }

    /**
     * Sets the available screen width.
     *
     * @param availWidth the available width
     * @return this entity for chaining
     */
    public FingerprintEntity availWidth(int availWidth) {
        this.availWidth = availWidth;
        return this;
    }

    /**
     * Sets the available screen height.
     *
     * @param availHeight the available height
     * @return this entity for chaining
     */
    public FingerprintEntity availHeight(int availHeight) {
        this.availHeight = availHeight;
        return this;
    }

    /**
     * Sets the available top offset.
     *
     * @param availTop the top offset
     * @return this entity for chaining
     */
    public FingerprintEntity availTop(int availTop) {
        this.availTop = availTop;
        return this;
    }

    /**
     * Sets the color depth.
     *
     * @param colorDepth the depth in bits
     * @return this entity for chaining
     */
    public FingerprintEntity colorDepth(int colorDepth) {
        this.colorDepth = colorDepth;
        return this;
    }

    /**
     * Sets the device pixel ratio.
     *
     * @param devicePixelRatio the DPR, or null to clear
     * @return this entity for chaining
     */
    public FingerprintEntity devicePixelRatio(Double devicePixelRatio) {
        this.devicePixelRatio = devicePixelRatio;
        return this;
    }

    /**
     * Sets the AudioContext sample rate.
     *
     * @param audioSampleRate the sample rate in Hz
     * @return this entity for chaining
     */
    public FingerprintEntity audioSampleRate(int audioSampleRate) {
        this.audioSampleRate = audioSampleRate;
        return this;
    }

    /**
     * Sets the AudioContext base latency.
     *
     * @param audioBaseLatency the latency in seconds
     * @return this entity for chaining
     */
    public FingerprintEntity audioBaseLatency(double audioBaseLatency) {
        this.audioBaseLatency = audioBaseLatency;
        return this;
    }

    /**
     * Sets the AudioContext output latency.
     *
     * @param audioOutputLatency the latency in seconds
     * @return this entity for chaining
     */
    public FingerprintEntity audioOutputLatency(double audioOutputLatency) {
        this.audioOutputLatency = audioOutputLatency;
        return this;
    }

    /**
     * Sets the AudioDestinationNode max channel count.
     *
     * @param audioMaxChannelCount the channel count
     * @return this entity for chaining
     */
    public FingerprintEntity audioMaxChannelCount(int audioMaxChannelCount) {
        this.audioMaxChannelCount = audioMaxChannelCount;
        return this;
    }

    /**
     * Sets the preferred color scheme.
     *
     * @param prefersColorScheme "light" or "dark"
     * @return this entity for chaining
     */
    public FingerprintEntity prefersColorScheme(String prefersColorScheme) {
        this.prefersColorScheme = prefersColorScheme;
        return this;
    }

    /**
     * Sets the color gamut.
     *
     * @param colorGamut "srgb" or "p3"
     * @return this entity for chaining
     */
    public FingerprintEntity colorGamut(String colorGamut) {
        this.colorGamut = colorGamut;
        return this;
    }

    /**
     * Sets the spoofed microphone count.
     *
     * @param mediaMics the mic count
     * @return this entity for chaining
     */
    public FingerprintEntity mediaMics(int mediaMics) {
        this.mediaMics = mediaMics;
        return this;
    }

    /**
     * Sets the spoofed webcam count.
     *
     * @param mediaWebcams the webcam count
     * @return this entity for chaining
     */
    public FingerprintEntity mediaWebcams(int mediaWebcams) {
        this.mediaWebcams = mediaWebcams;
        return this;
    }

    /**
     * Sets the spoofed speaker count.
     *
     * @param mediaSpeakers the speaker count
     * @return this entity for chaining
     */
    public FingerprintEntity mediaSpeakers(int mediaSpeakers) {
        this.mediaSpeakers = mediaSpeakers;
        return this;
    }

    /**
     * Sets the extra switches JSON string directly.
     *
     * @param extraSwitches the JSON string, or null to clear
     * @return this entity for chaining
     */
    public FingerprintEntity extraSwitches(String extraSwitches) {
        this.extraSwitches = extraSwitches;
        return this;
    }

    /**
     * Sets extra switches from a map, serializing to JSON.
     *
     * @param switchMap the switch map, or null to clear
     * @return this entity for chaining
     */
    public FingerprintEntity extraSwitchesMap(Map<String, String> switchMap) {
        if (switchMap == null || switchMap.isEmpty()) {
            this.extraSwitches = null;
        } else {
            this.extraSwitches = GSON.toJson(switchMap);
        }
        return this;
    }

    /**
     * Adds a single extra switch. Creates the map if it doesn't exist.
     *
     * @param switchName  the CLI switch name (e.g., "--some-switch")
     * @param switchValue the switch value
     * @return this entity for chaining
     */
    public FingerprintEntity putExtraSwitch(String switchName, String switchValue) {
        Map<String, String> map = new LinkedHashMap<>(extraSwitchesMap());
        map.put(switchName, switchValue);
        this.extraSwitches = GSON.toJson(map);
        return this;
    }

    /**
     * Removes a single extra switch.
     *
     * @param switchName the CLI switch name to remove
     * @return this entity for chaining
     */
    public FingerprintEntity removeExtraSwitch(String switchName) {
        Map<String, String> map = new LinkedHashMap<>(extraSwitchesMap());
        map.remove(switchName);
        this.extraSwitches = map.isEmpty() ? null : GSON.toJson(map);
        return this;
    }

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt the creation time
     * @return this entity for chaining
     */
    public FingerprintEntity createdAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    /**
     * Sets the creation timestamp from a string.
     *
     * @param createdAt ISO-formatted datetime string
     * @return this entity for chaining
     */
    public FingerprintEntity createdAtString(String createdAt) {
        this.createdAt = createdAt != null ? LocalDateTime.parse(createdAt, FORMATTER) : null;
        return this;
    }

    /**
     * Sets the last update timestamp.
     *
     * @param updatedAt the update time
     * @return this entity for chaining
     */
    public FingerprintEntity updatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
        return this;
    }

    /**
     * Sets the last update timestamp from a string.
     *
     * @param updatedAt ISO-formatted datetime string
     * @return this entity for chaining
     */
    public FingerprintEntity updatedAtString(String updatedAt) {
        this.updatedAt = updatedAt != null ? LocalDateTime.parse(updatedAt, FORMATTER) : null;
        return this;
    }

    /**
     * Updates the updatedAt timestamp to the current time.
     *
     * @return this entity for chaining
     */
    public FingerprintEntity touchUpdatedAt() {
        this.updatedAt = LocalDateTime.now();
        return this;
    }

    // ==================== Convenience Methods ====================

    /**
     * Checks if this is a laptop fingerprint.
     *
     * @return true if device type is laptop
     */
    public boolean isLaptop() {
        return DEVICE_LAPTOP.equals(deviceType);
    }

    /**
     * Checks if this is a desktop fingerprint.
     *
     * @return true if device type is desktop
     */
    public boolean isDesktop() {
        return DEVICE_DESKTOP.equals(deviceType);
    }

    /**
     * Checks if the platform is Windows.
     *
     * @return true if platform is Windows
     */
    public boolean isWindows() {
        return PLATFORM_WINDOWS.equals(platform);
    }

    /**
     * Checks if the platform is macOS.
     *
     * @return true if platform is macOS
     */
    public boolean isMacOS() {
        return PLATFORM_MACOS.equals(platform);
    }

    /**
     * Checks if the platform is Linux.
     *
     * @return true if platform is Linux
     */
    public boolean isLinux() {
        return PLATFORM_LINUX.equals(platform);
    }

    /**
     * Checks if any extra switches are configured.
     *
     * @return true if extra switches are present
     */
    public boolean hasExtraSwitches() {
        return extraSwitches != null && !extraSwitches.isBlank();
    }

    /**
     * Checks if a device pixel ratio is set.
     *
     * @return true if DPR is configured
     */
    public boolean hasDevicePixelRatio() {
        return devicePixelRatio != null;
    }

    /**
     * Checks if this entity has been persisted.
     *
     * @return true if ID is set (greater than 0)
     */
    public boolean isPersisted() {
        return id > 0;
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format(
                "FingerprintEntity{id=%d, line=%s, brand=%s/%d, platform=%s, device=%s, gpu=%s, seed=%d, screen=%dx%d}",
                id,
                jsonlLineIndex != null ? jsonlLineIndex : "n/a",
                browserBrand, browserMajorVersion,
                platform,
                deviceType,
                gpuRenderer != null ? gpuRenderer.substring(0, Math.min(40, gpuRenderer.length())) + "..." : "null",
                seed,
                screenWidth, screenHeight
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FingerprintEntity that = (FingerprintEntity) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    // ==================== Builder ====================

    /**
     * Builder for creating FingerprintEntity instances.
     */
    public static class Builder {

        private long id;
        private Integer jsonlLineIndex;
        private String browserBrand;
        private int browserMajorVersion;
        private String brandVersionLong;
        private String platform;
        private String platformVersion;
        private String gpuVendor;
        private String gpuRenderer;
        private String deviceType = DEVICE_DESKTOP;
        private int seed;
        private int hardwareConcurrency;
        private int deviceMemory;
        private int screenWidth;
        private int screenHeight;
        private int availWidth;
        private int availHeight;
        private int availTop;
        private int colorDepth = 24;
        private Double devicePixelRatio;
        private int audioSampleRate;
        private double audioBaseLatency;
        private double audioOutputLatency;
        private int audioMaxChannelCount;
        private String prefersColorScheme;
        private String colorGamut;
        private int mediaMics = 1;
        private int mediaWebcams = 1;
        private int mediaSpeakers = 1;
        private String extraSwitches;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        private Builder() {}

        public Builder id(long id) {
            this.id = id;
            return this;
        }

        public Builder jsonlLineIndex(Integer jsonlLineIndex) {
            this.jsonlLineIndex = jsonlLineIndex;
            return this;
        }

        public Builder browserBrand(String browserBrand) {
            this.browserBrand = browserBrand;
            return this;
        }

        public Builder browserMajorVersion(int browserMajorVersion) {
            this.browserMajorVersion = browserMajorVersion;
            return this;
        }

        public Builder brandVersionLong(String brandVersionLong) {
            this.brandVersionLong = brandVersionLong;
            return this;
        }

        public Builder platform(String platform) {
            this.platform = platform;
            return this;
        }

        public Builder platformVersion(String platformVersion) {
            this.platformVersion = platformVersion;
            return this;
        }

        public Builder gpuVendor(String gpuVendor) {
            this.gpuVendor = gpuVendor;
            return this;
        }

        public Builder gpuRenderer(String gpuRenderer) {
            this.gpuRenderer = gpuRenderer;
            return this;
        }

        public Builder deviceType(String deviceType) {
            this.deviceType = deviceType != null ? deviceType : DEVICE_DESKTOP;
            return this;
        }

        public Builder seed(int seed) {
            this.seed = seed;
            return this;
        }

        public Builder hardwareConcurrency(int hardwareConcurrency) {
            this.hardwareConcurrency = hardwareConcurrency;
            return this;
        }

        public Builder deviceMemory(int deviceMemory) {
            this.deviceMemory = deviceMemory;
            return this;
        }

        public Builder screenWidth(int screenWidth) {
            this.screenWidth = screenWidth;
            return this;
        }

        public Builder screenHeight(int screenHeight) {
            this.screenHeight = screenHeight;
            return this;
        }

        public Builder availWidth(int availWidth) {
            this.availWidth = availWidth;
            return this;
        }

        public Builder availHeight(int availHeight) {
            this.availHeight = availHeight;
            return this;
        }

        public Builder availTop(int availTop) {
            this.availTop = availTop;
            return this;
        }

        public Builder colorDepth(int colorDepth) {
            this.colorDepth = colorDepth;
            return this;
        }

        public Builder devicePixelRatio(Double devicePixelRatio) {
            this.devicePixelRatio = devicePixelRatio;
            return this;
        }

        public Builder audioSampleRate(int audioSampleRate) {
            this.audioSampleRate = audioSampleRate;
            return this;
        }

        public Builder audioBaseLatency(double audioBaseLatency) {
            this.audioBaseLatency = audioBaseLatency;
            return this;
        }

        public Builder audioOutputLatency(double audioOutputLatency) {
            this.audioOutputLatency = audioOutputLatency;
            return this;
        }

        public Builder audioMaxChannelCount(int audioMaxChannelCount) {
            this.audioMaxChannelCount = audioMaxChannelCount;
            return this;
        }

        public Builder prefersColorScheme(String prefersColorScheme) {
            this.prefersColorScheme = prefersColorScheme;
            return this;
        }

        public Builder colorGamut(String colorGamut) {
            this.colorGamut = colorGamut;
            return this;
        }

        public Builder mediaMics(int mediaMics) {
            this.mediaMics = mediaMics;
            return this;
        }

        public Builder mediaWebcams(int mediaWebcams) {
            this.mediaWebcams = mediaWebcams;
            return this;
        }

        public Builder mediaSpeakers(int mediaSpeakers) {
            this.mediaSpeakers = mediaSpeakers;
            return this;
        }

        public Builder extraSwitches(String extraSwitches) {
            this.extraSwitches = extraSwitches;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        /**
         * Builds the FingerprintEntity instance.
         *
         * @return a new FingerprintEntity
         */
        public FingerprintEntity build() {
            return new FingerprintEntity(this);
        }
    }
}