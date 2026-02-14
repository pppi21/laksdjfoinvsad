package org.nodriver4j.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Container for browser fingerprint data extracted from real browser profiles.
 * Parses JSONL profile data and provides structured access to fingerprint properties.
 */
public class Fingerprint {

    private static final String PROFILES_RESOURCE_PATH = System.getenv("fingerprint_profiles");
    private static final Gson GSON = new Gson();
    private static final Random RANDOM = new Random();

    // US Timezones (IANA format as used by Chrome)
    private static final String[] US_TIMEZONES = {
            "America/New_York",
            "America/Chicago",
            "America/Los_Angeles"
    };

    // Valid hardware concurrency values (typical for modern systems)
    private static final int[] HARDWARE_CONCURRENCY_VALUES = {4, 6, 8, 12, 16, 20, 24, 32};

    // Core fingerprint seed
    private final int seed;

    // Platform information
    private final String platform;
    private final String platformVersion;

    // Browser brand information
    private final String brand;
    private final String brandVersion;

    // Screen dimensions
    private final int screenWidth;
    private final int screenHeight;
    private final int availWidth;
    private final int availHeight;

    // WebGL information
    private final String gpuVendor;
    private final String gpuRenderer;

    // Hardware
    private final int hardwareConcurrency;

    // Locale settings
    private final String timezone;
    private final String language;
    private final String acceptLanguage;

    // Raw profile data for reference
    private final String userAgent;
    private final int pluginsCount;
    private final String pluginsFirstName;
    private final int mimeTypesCount;
    private final int fontsCount;

    // Storage capabilities
    private final boolean hasSessionStorage;
    private final boolean hasLocalStorage;
    private final boolean hasIndexedDB;

    /**
     * Creates a Fingerprint by loading a random profile from the bundled JSONL resource.
     *
     * @throws IOException if the profile resource cannot be read
     */
    public Fingerprint() throws IOException {
        this(loadRandomProfile());
    }

    /**
     * Creates a Fingerprint from a specific JSON profile string.
     *
     * @param profileJson the JSON string representing a browser profile
     */
    public Fingerprint(String profileJson) {
        JsonObject profile = GSON.fromJson(profileJson, JsonObject.class);

        // Extract User-Agent and parse it
        this.userAgent = getStringOrDefault(profile, "ua", "");
        UserAgentInfo uaInfo = parseUserAgent(this.userAgent);

        // Platform from UA parsing
        this.platform = uaInfo.platform;
        this.platformVersion = uaInfo.platformVersion;

        // Brand from UA parsing
        this.brand = uaInfo.brand;
        this.brandVersion = uaInfo.brandVersion;

        // Screen dimensions
        this.screenWidth = getIntOrDefault(profile, "width", 1920);
        this.screenHeight = getIntOrDefault(profile, "height", 1080);
        this.availWidth = getIntOrDefault(profile, "availWidth", this.screenWidth);
        this.availHeight = getIntOrDefault(profile, "availHeight", this.screenHeight - 40);

        // WebGL - extract from profile
        this.gpuVendor = getStringOrDefault(profile, "vendor", "Google Inc. (NVIDIA)");
        this.gpuRenderer = buildRendererString(profile);

        // Generate seed from canvas data for deterministic behavior
        String canvasData = getStringOrDefault(profile, "canvas", String.valueOf(System.currentTimeMillis()));
        this.seed = generateSeedFromString(canvasData);

        // Hardware concurrency - random from typical values
        this.hardwareConcurrency = HARDWARE_CONCURRENCY_VALUES[RANDOM.nextInt(HARDWARE_CONCURRENCY_VALUES.length)];

        // Locale - random US timezone
        this.timezone = US_TIMEZONES[RANDOM.nextInt(US_TIMEZONES.length)];
        this.language = "en-US";
        this.acceptLanguage = "en-US,en;q=0.9";

        // Plugins info
        JsonObject plugins = profile.getAsJsonObject("plugins");
        this.pluginsCount = plugins != null ? getIntOrDefault(plugins, "count", 5) : 5;
        this.pluginsFirstName = plugins != null ? getStringOrDefault(plugins, "first", "PDF Viewer") : "PDF Viewer";

        // Mime types
        JsonObject mimeTypes = profile.getAsJsonObject("mimeTypes");
        this.mimeTypesCount = mimeTypes != null ? getIntOrDefault(mimeTypes, "count", 2) : 2;

        // Fonts
        JsonObject fonts = profile.getAsJsonObject("fonts");
        this.fontsCount = fonts != null ? getIntOrDefault(fonts, "count", 300) : 300;

        // Storage capabilities
        this.hasSessionStorage = getBooleanOrDefault(profile, "hasSessionStorage", true);
        this.hasLocalStorage = getBooleanOrDefault(profile, "hasLocalStorage", true);
        this.hasIndexedDB = getBooleanOrDefault(profile, "hasIndexedDB", true);
    }

    /**
     * Loads a random profile from the bundled JSONL resource file.
     */
    private static String loadRandomProfile() throws IOException {
        Path profilesPath = Path.of(PROFILES_RESOURCE_PATH);

        if (!Files.exists(profilesPath)) {
            throw new IOException("Fingerprint profiles file not found: " + PROFILES_RESOURCE_PATH);
        }

        List<String> profiles = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(profilesPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    profiles.add(line);
                }
            }
        }

        if (profiles.isEmpty()) {
            throw new IOException("No profiles found in file: " + PROFILES_RESOURCE_PATH);
        }

        return profiles.get(RANDOM.nextInt(profiles.size()));
    }

    /**
     * Parses a User-Agent string to extract platform and browser information.
     */
    private UserAgentInfo parseUserAgent(String ua) {
        UserAgentInfo info = new UserAgentInfo();

        // Detect platform
        if (ua.contains("Windows NT")) {
            info.platform = "windows";
            Matcher m = Pattern.compile("Windows NT ([\\d.]+)").matcher(ua);
            info.platformVersion = m.find() ? m.group(1) : "10.0";
        } else if (ua.contains("Mac OS X")) {
            info.platform = "macos";
            Matcher m = Pattern.compile("Mac OS X ([\\d_]+)").matcher(ua);
            if (m.find()) {
                info.platformVersion = m.group(1).replace("_", ".");
            } else {
                info.platformVersion = "10.15.7";
            }
        } else if (ua.contains("Linux")) {
            info.platform = "linux";
            info.platformVersion = "6.5.0";
        } else {
            info.platform = "windows";
            info.platformVersion = "10.0";
        }

        // Detect browser brand - check for specific browsers first
        if (ua.contains("Edg/")) {
            info.brand = "Edge";
            Matcher m = Pattern.compile("Edg/([\\d.]+)").matcher(ua);
            info.brandVersion = m.find() ? m.group(1) : "120.0.0.0";
        } else if (ua.contains("OPR/")) {
            info.brand = "Opera";
            Matcher m = Pattern.compile("OPR/([\\d.]+)").matcher(ua);
            info.brandVersion = m.find() ? m.group(1) : "100.0.0.0";
        } else if (ua.contains("Vivaldi/")) {
            info.brand = "Vivaldi";
            Matcher m = Pattern.compile("Vivaldi/([\\d.]+)").matcher(ua);
            info.brandVersion = m.find() ? m.group(1) : "6.0.0.0";
        } else {
            // Default to Chrome
            info.brand = "Chrome";
            Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(ua);
            info.brandVersion = m.find() ? m.group(1) : "120.0.0.0";
        }

        return info;
    }

    /**
     * Builds the WebGL renderer string in the format expected by fingerprint-chromium.
     */
    private String buildRendererString(JsonObject profile) {
        String renderer = getStringOrDefault(profile, "renderer", null);

        if (renderer != null && !renderer.isEmpty()) {
            // The profile renderer is in format like:
            // "ANGLE (AMD, AMD Radeon(TM) Graphics (0x00001636) Direct3D11 vs_5_0 ps_5_0, D3D11)"
            // We need to convert vendor format for fingerprint-chromium
            return renderer;
        }

        // Default renderer
        return "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 (0x00002504) Direct3D11 vs_5_0 ps_5_0, D3D11)";
    }

    /**
     * Generates a deterministic seed from a string using hash.
     */
    private int generateSeedFromString(String input) {
        int hash = input.hashCode();
        // Ensure positive value
        return hash == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(hash);
    }

    // JSON helper methods

    private static String getStringOrDefault(JsonObject obj, String key, String defaultValue) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }

    private static int getIntOrDefault(JsonObject obj, String key, int defaultValue) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsInt();
        }
        return defaultValue;
    }

    private static boolean getBooleanOrDefault(JsonObject obj, String key, boolean defaultValue) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsBoolean();
        }
        return defaultValue;
    }

    // Getters

    public int getSeed() {
        return seed;
    }

    public String getPlatform() {
        return platform;
    }

    public String getPlatformVersion() {
        return platformVersion;
    }

    public String getBrand() {
        return brand;
    }

    public String getBrandVersion() {
        return brandVersion;
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    public int getAvailWidth() {
        return availWidth;
    }

    public int getAvailHeight() {
        return availHeight;
    }

    public String getGpuVendor() {
        return gpuVendor;
    }

    public String getGpuRenderer() {
        return gpuRenderer;
    }

    public int getHardwareConcurrency() {
        return hardwareConcurrency;
    }

    public String getTimezone() {
        return timezone;
    }

    public String getLanguage() {
        return language;
    }

    public String getAcceptLanguage() {
        return acceptLanguage;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public int getPluginsCount() {
        return pluginsCount;
    }

    public String getPluginsFirstName() {
        return pluginsFirstName;
    }

    public int getMimeTypesCount() {
        return mimeTypesCount;
    }

    public int getFontsCount() {
        return fontsCount;
    }

    public boolean hasSessionStorage() {
        return hasSessionStorage;
    }

    public boolean hasLocalStorage() {
        return hasLocalStorage;
    }

    public boolean hasIndexedDB() {
        return hasIndexedDB;
    }

    /**
     * Returns a summary of the fingerprint for logging purposes.
     */
    @Override
    public String toString() {
        return String.format(
                "Fingerprint{seed=%d, platform=%s, brand=%s/%s, screen=%dx%d, gpu=%s, cores=%d, tz=%s}",
                seed, platform, brand, brandVersion, screenWidth, screenHeight,
                gpuRenderer.substring(0, Math.min(50, gpuRenderer.length())) + "...",
                hardwareConcurrency, timezone
        );
    }

    /**
     * Internal class for User-Agent parsing results.
     */
    private static class UserAgentInfo {
        String platform = "windows";
        String platformVersion = "10.0";
        String brand = "Chrome";
        String brandVersion = "120.0.0.0";
    }
}