package org.nodriver4j.persistence.importer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.nodriver4j.persistence.entity.FingerprintEntity;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms a raw JSONL fingerprint profile line into a fully populated
 * {@link FingerprintEntity} with all browser identity values.
 *
 * <p>This is the core extraction engine that runs once per fingerprint import.
 * It parses the UA string, classifies the device type, derives screen dimensions,
 * generates version strings, and populates all fields that Chrome needs for a
 * consistent browser identity.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * FingerprintExtractor extractor = new FingerprintExtractor();
 *
 * // From a JSONL line
 * String jsonLine = readLineFromFile(index);
 * FingerprintEntity entity = extractor.extract(jsonLine, index);
 *
 * // Save to database
 * repository.save(entity);
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Parse UA string → brand, major version, platform</li>
 *   <li>Classify device type (laptop/desktop) from GPU renderer + screen</li>
 *   <li>Generate Chrome version string from build number lookup table</li>
 *   <li>Derive platform version with OS-appropriate distributions</li>
 *   <li>Derive screen dimensions by snapping to known resolutions</li>
 *   <li>Generate device memory, media device counts, color depth</li>
 *   <li>Reject mobile fingerprints (Mali, Adreno, PowerVR)</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Reading the JSONL file (caller provides the line)</li>
 *   <li>Persisting the entity (use FingerprintRepository)</li>
 *   <li>Building Chrome CLI arguments (use Browser / BrowserConfig)</li>
 *   <li>Per-session values: battery, screen position, history length, timezone</li>
 * </ul>
 *
 * @see FingerprintEntity
 */
public class FingerprintExtractor {

    private static final Gson GSON = new Gson();

    // ==================== UA Parsing ====================

    private static final Pattern CHROME_VERSION_PATTERN =
            Pattern.compile("Chrome/(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");

    // ==================== Hardware Concurrency (matches Fingerprint.java) ====================

    private static final int[] HARDWARE_CONCURRENCY_VALUES = {4, 6, 8, 12, 16, 20, 24, 32};

    // ==================== Mobile GPU Detection ====================

    private static final String[] MOBILE_GPU_MARKERS = {"mali", "adreno", "powervr", "imagination technologies"};

    // ==================== NVIDIA Laptop Detection ====================

    /** Matches older NVIDIA mobile GPUs with M suffix (e.g., "GT 740M", "GTX 970M"). */
    private static final Pattern NVIDIA_MOBILE_SUFFIX = Pattern.compile("\\d{3,4}M[\\s),]");

    // ==================== AMD Laptop Detection ====================

    /** Matches AMD discrete mobile GPUs with M or S suffix (e.g., "RX 6600M", "RX 7600S"). */
    private static final Pattern AMD_MOBILE_SUFFIX = Pattern.compile("RX\\s+\\d{3,4}[MS]\\b", Pattern.CASE_INSENSITIVE);

    // ==================== Apple Laptop Screen Widths (CSS pixels) ====================

    /** MacBook Air 13", MacBook Air 13" (older), MacBook Air 15", MacBook Pro 14", MacBook Pro 16". */
    private static final int[] APPLE_LAPTOP_WIDTHS = {1280, 1440, 1470, 1512, 1728};

    // ==================== Apple Notch Model Widths ====================

    /** MacBook Pro 14" and 16" — models with notch requiring taller menu bar. */
    private static final int[] APPLE_NOTCH_WIDTHS = {1512, 1728};

    // ==================== Chrome Build Number Table ====================

    /**
     * Maps Chrome major version to the stable build base number.
     *
     * <p>Format: {@code major.0.buildBase.patch} (e.g., 145.0.7632.117).
     * Versions 115–119 are estimated by extrapolation. Version 124 is
     * interpolated between 123 and 125.</p>
     */
    private static final Map<Integer, Integer> BUILD_NUMBERS = Map.ofEntries(
            // Estimated (extrapolated backwards from 120)
            Map.entry(115, 5774),
            Map.entry(116, 5839),
            Map.entry(117, 5904),
            Map.entry(118, 5969),
            Map.entry(119, 6034),
            // Verified from stable releases
            Map.entry(120, 6099),
            Map.entry(121, 6167),
            Map.entry(122, 6261),
            Map.entry(123, 6312),
            Map.entry(124, 6367), // Interpolated
            Map.entry(125, 6422),
            Map.entry(126, 6478),
            Map.entry(127, 6533),
            Map.entry(128, 6613),
            Map.entry(129, 6668),
            Map.entry(130, 6723),
            Map.entry(131, 6778),
            Map.entry(132, 6834),
            Map.entry(133, 6943),
            Map.entry(134, 6998),
            Map.entry(135, 7049),
            Map.entry(136, 7103),
            Map.entry(137, 7151),
            Map.entry(138, 7204),
            Map.entry(139, 7258),
            Map.entry(140, 7339),
            Map.entry(141, 7390),
            Map.entry(142, 7444),
            Map.entry(143, 7499),
            Map.entry(144, 7559),
            Map.entry(145, 7632)
    );

    /** Average build number increment per major version, used for extrapolation. */
    private static final int BUILD_INCREMENT_ESTIMATE = 55;

    // ==================== Public API ====================

    /**
     * Extracts a fully populated {@link FingerprintEntity} from a JSONL profile line.
     *
     * <p>Parses the JSON, extracts raw values, derives all browser identity fields,
     * and returns an unpersisted entity ready for saving via repository.</p>
     *
     * @param jsonLine  a single line from the fingerprints JSONL file
     * @param lineIndex the zero-based line index (for traceability)
     * @return a fully populated, unpersisted FingerprintEntity
     * @throws IllegalArgumentException if the line contains a mobile GPU or
     *                                  the UA string cannot be parsed
     */
    public FingerprintEntity extract(String jsonLine, int lineIndex) {
        JsonObject profile = GSON.fromJson(jsonLine, JsonObject.class);

        // --- Raw extraction from JSONL ---
        String ua = getStringOrNull(profile, "ua");
        if (ua == null || ua.isBlank()) {
            throw new IllegalArgumentException("Fingerprint at line " + lineIndex + " has no UA string");
        }

        String gpuVendor = getStringOrDefault(profile, "vendor", "Google Inc. (NVIDIA)");
        String gpuRenderer = getStringOrDefault(profile, "renderer",
                "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 (0x00002504) Direct3D11 vs_5_0 ps_5_0, D3D11)");
        int availWidth = getIntOrDefault(profile, "availWidth", 1920);
        int availHeight = getIntOrDefault(profile, "availHeight", 1080);

        // --- Reject mobile fingerprints ---
        rejectIfMobile(gpuRenderer, lineIndex);

        // --- Seed (matches Fingerprint.java logic) ---
        String canvasData = getStringOrDefault(profile, "canvas", String.valueOf(lineIndex));
        int seed = generateSeedFromString(canvasData);
        int hardwareConcurrency = HARDWARE_CONCURRENCY_VALUES[seed % HARDWARE_CONCURRENCY_VALUES.length];

        // --- UA parsing ---
        int majorVersion = parseMajorVersion(ua, lineIndex);
        String platform = parsePlatform(ua);

        // --- Device classification ---
        String deviceType = classifyDeviceType(gpuRenderer, platform, availWidth);

        // --- Version generation ---
        String brandVersionLong = generateBrandVersionLong(majorVersion, seed);
        String platformVersion = derivePlatformVersion(platform, seed);

        // --- Screen derivation ---
        int screenWidth = availWidth;
        int screenHeight = resolveScreenHeight(screenWidth, platform);
        int availTop = deriveAvailTop(platform, availWidth);

        // --- Value generation ---
        int colorDepth = pickColorDepth(seed);
        int deviceMemory = generateDeviceMemory(deviceType, seed);
        int[] media = generateMediaDevices(deviceType, seed);

        // --- Audio context ---
        JsonObject audioProps = profile.has("audio_properties") && !profile.get("audio_properties").isJsonNull()
                ? profile.getAsJsonObject("audio_properties") : null;

        int defaultSampleRate = FingerprintEntity.PLATFORM_MACOS.equals(platform) ? 44100 : 48000;
        int audioSampleRate = audioProps != null
                ? getIntOrDefault(audioProps, "BaseAudioContextSampleRate", defaultSampleRate) : defaultSampleRate;
        double audioBaseLatency = audioProps != null
                ? getDoubleOrDefault(audioProps, "AudioContextBaseLatency", 0.01) : 0.01;
        double audioOutputLatency = audioProps != null
                ? getDoubleOrDefault(audioProps, "AudioContextOutputLatency", 0.0) : 0.0;
        int audioMaxChannelCount = audioProps != null
                ? getIntOrDefault(audioProps, "AudioDestinationNodeMaxChannelCount", 2) : 2;

        // --- Build entity ---
        return FingerprintEntity.builder()
                .jsonlLineIndex(lineIndex)
                .browserBrand("Chrome")
                .browserMajorVersion(majorVersion)
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
                .devicePixelRatio(null) // Placeholder until DPR switch is implemented
                .audioSampleRate(audioSampleRate)
                .audioBaseLatency(audioBaseLatency)
                .audioOutputLatency(audioOutputLatency)
                .audioMaxChannelCount(audioMaxChannelCount)
                .mediaMics(media[0])
                .mediaWebcams(media[1])
                .mediaSpeakers(media[2])
                .build();
    }

    // ==================== UA Parsing ====================

    /**
     * Extracts the Chrome major version from the UA string.
     *
     * @throws IllegalArgumentException if Chrome version cannot be found
     */
    private static int parseMajorVersion(String ua, int lineIndex) {
        Matcher matcher = CHROME_VERSION_PATTERN.matcher(ua);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Cannot parse Chrome version from UA at line " + lineIndex + ": " + ua);
        }
        return Integer.parseInt(matcher.group(1));
    }

    /** Determines the platform from the UA string. Defaults to Windows if unrecognizable. */
    private static String parsePlatform(String ua) {
        if (ua.contains("Macintosh") || ua.contains("Mac OS X")) {
            return FingerprintEntity.PLATFORM_MACOS;
        }
        if (ua.contains("Linux") && !ua.contains("Android")) {
            return FingerprintEntity.PLATFORM_LINUX;
        }
        // Windows NT or fallback — Windows is the most common desktop platform
        return FingerprintEntity.PLATFORM_WINDOWS;
    }

    // ==================== Version Generation ====================

    /**
     * Generates a full Chrome version string (e.g., "145.0.7632.117").
     *
     * <p>Looks up the build base from the table and generates a deterministic
     * patch number from the seed. For versions outside the table, extrapolates
     * from the nearest known version.</p>
     */
    private static String generateBrandVersionLong(int majorVersion, int seed) {
        int buildBase = resolveBuildBase(majorVersion);
        int patch = deterministic(seed, 7, 200) + 26; // Range [26, 225]
        return majorVersion + ".0." + buildBase + "." + patch;
    }

    /** Resolves the build base number for a Chrome major version, extrapolating if needed. */
    private static int resolveBuildBase(int majorVersion) {
        Integer buildBase = BUILD_NUMBERS.get(majorVersion);
        if (buildBase != null) {
            return buildBase;
        }

        // Extrapolate from the closest known version
        int maxKnown = 145;
        int minKnown = 115;

        if (majorVersion > maxKnown) {
            return BUILD_NUMBERS.get(maxKnown) + (majorVersion - maxKnown) * BUILD_INCREMENT_ESTIMATE;
        }
        if (majorVersion < minKnown) {
            return BUILD_NUMBERS.get(minKnown) - (minKnown - majorVersion) * BUILD_INCREMENT_ESTIMATE;
        }

        // Shouldn't happen if table is complete, but fallback
        return BUILD_NUMBERS.get(maxKnown);
    }

    /**
     * Generates an OS-appropriate platform version string for Client Hints.
     *
     * <p>Distributions:</p>
     * <ul>
     *   <li><b>Windows:</b> 80% "15.0.0" (Win11), 20% "10.0.0" (Win10)</li>
     *   <li><b>macOS:</b> 40% v14, 25% v15, 25% v13, 10% v12 — with minor 0–6</li>
     *   <li><b>Linux:</b> 35% "6.8.0", 25% "6.5.0", 25% "6.1.0", 15% "5.15.0"</li>
     * </ul>
     */
    private static String derivePlatformVersion(String platform, int seed) {
        return switch (platform) {
            case FingerprintEntity.PLATFORM_WINDOWS -> deriveWindowsVersion(seed);
            case FingerprintEntity.PLATFORM_MACOS -> deriveMacOSVersion(seed);
            case FingerprintEntity.PLATFORM_LINUX -> deriveLinuxVersion(seed);
            default -> "10.0.0";
        };
    }

    private static String deriveWindowsVersion(int seed) {
        return deterministic(seed, 67, 100) < 80 ? "15.0.0" : "10.0.0";
    }

    private static String deriveMacOSVersion(int seed) {
        int pick = deterministic(seed, 53, 100);
        int major;
        if (pick < 40) major = 14;       // 40% Sonoma
        else if (pick < 65) major = 15;   // 25% Sequoia
        else if (pick < 90) major = 13;   // 25% Ventura
        else major = 12;                  // 10% Monterey

        int minor = deterministic(seed, 59, 7); // 0–6
        return major + "." + minor + ".0";
    }

    private static String deriveLinuxVersion(int seed) {
        int pick = deterministic(seed, 61, 100);
        if (pick < 35) return "6.8.0";
        if (pick < 60) return "6.5.0";
        if (pick < 85) return "6.1.0";
        return "5.15.0";
    }

    // ==================== Device Classification ====================

    /**
     * Classifies the fingerprint as laptop or desktop based on GPU renderer,
     * platform, and screen width.
     *
     * <p>Classification rules by GPU vendor:</p>
     * <ul>
     *   <li><b>NVIDIA:</b> "Laptop GPU", "Max-Q", or M-suffix → laptop</li>
     *   <li><b>AMD:</b> Integrated APU or M/S-suffix → laptop</li>
     *   <li><b>Intel:</b> Iris or generic UHD/HD → laptop; UHD 630/770 → desktop</li>
     *   <li><b>Apple:</b> Known MacBook screen widths → laptop; other widths → desktop</li>
     * </ul>
     */
    private static String classifyDeviceType(String gpuRenderer, String platform, int availWidth) {
        String lower = gpuRenderer.toLowerCase();

        if (lower.contains("nvidia")) {
            return isNvidiaLaptop(gpuRenderer) ? FingerprintEntity.DEVICE_LAPTOP : FingerprintEntity.DEVICE_DESKTOP;
        }

        if (lower.contains("amd") || lower.contains("radeon")) {
            return isAmdLaptop(lower) ? FingerprintEntity.DEVICE_LAPTOP : FingerprintEntity.DEVICE_DESKTOP;
        }

        if (lower.contains("intel")) {
            return isIntelLaptop(lower) ? FingerprintEntity.DEVICE_LAPTOP : FingerprintEntity.DEVICE_DESKTOP;
        }

        if (lower.contains("apple") || FingerprintEntity.PLATFORM_MACOS.equals(platform)) {
            return isAppleLaptop(availWidth) ? FingerprintEntity.DEVICE_LAPTOP : FingerprintEntity.DEVICE_DESKTOP;
        }

        // Unknown GPU vendor — default to desktop
        return FingerprintEntity.DEVICE_DESKTOP;
    }

    /** Rejects mobile fingerprints (phones/tablets) by checking for mobile GPU markers. */
    private static void rejectIfMobile(String gpuRenderer, int lineIndex) {
        String lower = gpuRenderer.toLowerCase();
        for (String marker : MOBILE_GPU_MARKERS) {
            if (lower.contains(marker)) {
                throw new IllegalArgumentException(
                        "Mobile GPU detected at line " + lineIndex + " (marker: " + marker + "): " + gpuRenderer);
            }
        }
    }

    private static boolean isNvidiaLaptop(String renderer) {
        if (renderer.contains("Laptop GPU") || renderer.contains("Max-Q")) {
            return true;
        }
        return NVIDIA_MOBILE_SUFFIX.matcher(renderer).find();
    }

    private static boolean isAmdLaptop(String rendererLower) {
        // Integrated APU: "Radeon(TM) Graphics" or "Radeon Graphics" without RX model
        if ((rendererLower.contains("radeon(tm) graphics") || rendererLower.contains("radeon graphics"))
                && !rendererLower.contains("rx ")) {
            return true;
        }

        // Integrated Vega APU
        if (rendererLower.contains("radeon vega")) {
            return true;
        }

        // Discrete mobile: M or S suffix on RX model
        return AMD_MOBILE_SUFFIX.matcher(rendererLower).find();
    }

    private static boolean isIntelLaptop(String rendererLower) {
        // Iris (Plus, Xe, Pro) — almost exclusively laptop
        if (rendererLower.contains("iris")) {
            return true;
        }

        // Known desktop Intel iGPUs
        if (rendererLower.contains("uhd graphics 630") || rendererLower.contains("uhd graphics 770")) {
            return false;
        }

        // Generic UHD/HD Graphics — lean laptop (vast majority are laptops)
        if (rendererLower.contains("uhd graphics") || rendererLower.contains("hd graphics")) {
            return true;
        }

        // Fallback: Intel GPU without clear indicator → laptop
        return true;
    }

    private static boolean isAppleLaptop(int availWidth) {
        for (int laptopWidth : APPLE_LAPTOP_WIDTHS) {
            if (availWidth == laptopWidth) {
                return true;
            }
        }
        return false;
    }

    // ==================== Screen Derivation ====================

    /**
     * Resolves the full screen height from a known resolution lookup table.
     *
     * <p>Since {@code screen.availWidth} almost always equals {@code screen.width}
     * (OS chrome is at top/bottom, not on sides), the screen width is taken directly
     * from {@code availWidth}. The height is looked up from known resolutions for
     * that width, with platform-specific disambiguation where needed.</p>
     *
     * @param screenWidth the screen width (equal to availWidth)
     * @param platform    the OS platform for disambiguation
     * @return the resolved screen height
     */
    private static int resolveScreenHeight(int screenWidth, String platform) {
        return switch (screenWidth) {
            // macOS-specific resolutions
            case 1440 -> 900;   // MacBook Air 13"
            case 1470 -> 956;   // MacBook Air 15"
            case 1512 -> 982;   // MacBook Pro 14"
            case 1728 -> 1117;  // MacBook Pro 16"
            case 2240 -> 1260;  // iMac 24"
            case 2048 -> 1152;  // iMac (scaled)

            // Disambiguate by platform
            case 1280 -> FingerprintEntity.PLATFORM_MACOS.equals(platform) ? 800 : 720;

            // Universal resolutions
            case 1366 -> 768;
            case 1536 -> 864;   // Windows 1920×1080 at 125% scale
            case 1600 -> 900;
            case 1920 -> 1080;
            case 2560 -> 1440;
            case 3440 -> 1440;  // Ultrawide
            case 3840 -> 2160;  // 4K

            // Unknown width: assume 16:9 aspect ratio
            default -> (int) Math.round(screenWidth * 9.0 / 16.0);
        };
    }

    /**
     * Derives the {@code availTop} offset based on platform and screen width.
     *
     * <p>On macOS, this accounts for the menu bar. Notch-era MacBook Pros
     * (14" and 16", identified by screen widths 1512 and 1728) have a taller
     * menu bar of 37px. All other Macs use the standard 25px. Windows and
     * Linux have no top offset (taskbar/panel at bottom).</p>
     */
    private static int deriveAvailTop(String platform, int availWidth) {
        if (!FingerprintEntity.PLATFORM_MACOS.equals(platform)) {
            return 0;
        }

        for (int notchWidth : APPLE_NOTCH_WIDTHS) {
            if (availWidth == notchWidth) {
                return 37; // Notch models: MacBook Pro 14"/16"
            }
        }
        return 25; // Standard macOS menu bar
    }

    // ==================== Value Generation ====================

    /** Picks color depth: 50/50 between 24 and 30. */
    private static int pickColorDepth(int seed) {
        return deterministic(seed, 19, 2) == 0 ? 24 : 30;
    }

    /**
     * Generates device memory based on device type.
     *
     * <p>Desktop: random from {8, 16}. Laptop: random from {4, 8, 16}
     * weighted toward 8.</p>
     */
    private static int generateDeviceMemory(String deviceType, int seed) {
        if (FingerprintEntity.DEVICE_LAPTOP.equals(deviceType)) {
            int pick = deterministic(seed, 31, 100);
            if (pick < 20) return 4;   // 20%
            if (pick < 70) return 8;   // 50%
            return 16;                  // 30%
        }
        // Desktop
        return deterministic(seed, 31, 2) == 0 ? 8 : 16;
    }

    /**
     * Generates media device counts based on device type.
     *
     * <p>Laptop: 1 mic, 1 webcam, 1 speaker (built-in hardware).</p>
     * <p>Desktop: 1 mic always, 90% no webcam / 10% webcam,
     * 45% 1 speaker / 45% 2 speakers / 10% 3 speakers.</p>
     *
     * @return array of [mics, webcams, speakers]
     */
    private static int[] generateMediaDevices(String deviceType, int seed) {
        if (FingerprintEntity.DEVICE_LAPTOP.equals(deviceType)) {
            return new int[]{1, 1, 1};
        }

        // Desktop
        int mics = 1;

        int webcamPick = deterministic(seed, 43, 100);
        int webcams = webcamPick < 90 ? 0 : 1;

        int speakerPick = deterministic(seed, 37, 100);
        int speakers;
        if (speakerPick < 45) speakers = 1;
        else if (speakerPick < 90) speakers = 2;
        else speakers = 3;

        return new int[]{mics, webcams, speakers};
    }

    // ==================== Seed & Utility ====================

    /**
     * Generates a deterministic positive seed from a string.
     * Matches the algorithm in {@code Fingerprint.java} for consistency.
     */
    private static int generateSeedFromString(String input) {
        int hash = input.hashCode();
        return hash == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(hash);
    }

    /**
     * Deterministic value selection using a mixing function.
     *
     * <p>Different salt values produce decorrelated results from the same seed,
     * so multiple derivations (version, color depth, device memory, etc.) don't
     * produce correlated outputs.</p>
     *
     * @param seed  the base seed
     * @param salt  a unique salt per use case (use different primes)
     * @param range the output range [0, range)
     * @return a deterministic value in [0, range)
     */
    private static int deterministic(int seed, int salt, int range) {
        long mixed = ((long) seed * 2654435761L + salt) & 0x7FFFFFFFL;
        return (int) (mixed % range);
    }

    // ==================== JSON Helpers ====================

    private static String getStringOrNull(JsonObject obj, String key) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

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

    private static double getDoubleOrDefault(JsonObject obj, String key, double defaultValue) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsDouble();
        }
        return defaultValue;
    }
}