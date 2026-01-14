package org.nodriver4j.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility tool to collect browser fingerprints from Bablosoft's FingerprintSwitcher API.
 *
 * This is a standalone data collection script, NOT part of the main library.
 * Run it to build up a local database of real-world fingerprints for later use.
 *
 * Output format: JSONL (JSON Lines) - one fingerprint JSON object per line.
 * This format allows safe append operations and crash recovery.
 *
 * Each profile is enriched with generated Client Hints metadata derived from the UA string,
 * ensuring consistency between the UA and Sec-CH-UA-* headers for realistic spoofing.
 */
public class FingerprintCollector {

    private static final String API_URL = "https://fingerprints.bablosoft.com/preview";
    private static final String OUTPUT_FILE = "data/fingerprints.jsonl";
    private static final int TARGET_COUNT = 1000;
    private static final int DELAY_MS = 1000;

    private static final Gson GSON = new GsonBuilder().create();

    // ========== Chrome Version Parsing ==========

    // Pattern to extract Chrome version: Chrome/138.0.0.0 or Chrome/138.0.6801.57
    private static final Pattern CHROME_VERSION_PATTERN =
            Pattern.compile("Chrome/(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");

    // Pattern to extract Windows NT version
    private static final Pattern WINDOWS_NT_PATTERN =
            Pattern.compile("Windows NT (\\d+\\.\\d+)");

    // Pattern to extract macOS version: Mac OS X 10_15_7 or Mac OS X 10.15.7
    private static final Pattern MACOS_VERSION_PATTERN =
            Pattern.compile("Mac OS X (\\d+)[_\\.](\\d+)(?:[_\\.]?(\\d+))?");

    // ========== GREASE Configuration ==========
    // GREASE (Generate Random Extensions And Sustain Extensibility) brands
    // Chrome rotates through these to prevent server-side ossification on specific patterns
    // Source: Observed from real Chrome browsers across versions

    private static final String[][] GREASE_BRANDS_BY_VERSION_MOD = {
            // Index 0: Chrome versions where major % 8 == 0
            {"Not/A)Brand", "8"},
            // Index 1: Chrome versions where major % 8 == 1
            {"Not A(Brand", "8"},
            // Index 2: Chrome versions where major % 8 == 2
            {"Not)A;Brand", "8"},
            // Index 3: Chrome versions where major % 8 == 3
            {" Not A;Brand", "99"},
            // Index 4: Chrome versions where major % 8 == 4
            {"Not_A Brand", "24"},
            // Index 5: Chrome versions where major % 8 == 5
            {"Not;A Brand", "8"},
            // Index 6: Chrome versions where major % 8 == 6
            {"Not.A/Brand", "24"},
            // Index 7: Chrome versions where major % 8 == 7
            {"Not A Brand;", "99"},
    };

    // Brand order also varies by Chrome version
    // Some versions put GREASE first, others put it last
    private static final int[][] BRAND_ORDER_BY_VERSION_MOD = {
            // {greasePosition, chromiumPosition, chromePosition}
            {0, 1, 2}, // GREASE, Chromium, Google Chrome
            {2, 1, 0}, // Google Chrome, Chromium, GREASE
            {1, 0, 2}, // Chromium, GREASE, Google Chrome
            {0, 2, 1}, // GREASE, Google Chrome, Chromium
    };

    private final HttpClient httpClient;
    private final Set<String> existingFingerprints;
    private final Random random;

    public FingerprintCollector() {
        this.httpClient = HttpClient.newHttpClient();
        this.existingFingerprints = new HashSet<>();
        this.random = new Random();
    }

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("  Bablosoft Fingerprint Collector");
        System.out.println("  Target: " + TARGET_COUNT + " fingerprints");
        System.out.println("  Output: " + OUTPUT_FILE);
        System.out.println("  Delay: " + DELAY_MS + "ms between requests");
        System.out.println("  Client Hints: Auto-generated from UA");
        System.out.println("==============================================\n");

        FingerprintCollector collector = new FingerprintCollector();
        collector.run();
    }

    public void run() {
        loadExistingFingerprints();

        int collected = existingFingerprints.size();
        int attempts = 0;
        int duplicates = 0;
        int errors = 0;

        System.out.println("[Collector] Starting collection. Already have: " + collected + " fingerprints\n");

        while (collected < TARGET_COUNT) {
            attempts++;

            try {
                String rawFingerprint = fetchFingerprint();

                if (rawFingerprint == null) {
                    errors++;
                    System.err.println("[Collector] Empty/invalid response, skipping...");
                    Thread.sleep(DELAY_MS);
                    continue;
                }

                // Enrich with Client Hints before deduplication check
                String enrichedFingerprint = enrichWithClientHints(rawFingerprint);

                if (enrichedFingerprint == null) {
                    errors++;
                    System.err.println("[Collector] Failed to enrich fingerprint, skipping...");
                    Thread.sleep(DELAY_MS);
                    continue;
                }

                // Check for exact duplicate (on enriched version)
                if (existingFingerprints.contains(enrichedFingerprint)) {
                    duplicates++;
                    System.out.println("[Collector] Duplicate #" + duplicates + " (attempt " + attempts + "), skipping...");
                } else {
                    appendFingerprint(enrichedFingerprint);
                    existingFingerprints.add(enrichedFingerprint);
                    collected++;

                    String uaPreview = extractUaPreview(enrichedFingerprint);
                    System.out.println("[Collector] " + collected + "/" + TARGET_COUNT + " - " + uaPreview);
                }

                Thread.sleep(DELAY_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("\n[Collector] Interrupted by user, stopping...");
                break;
            } catch (IOException e) {
                errors++;
                System.err.println("\n[Collector] HTTP Error: " + e.getMessage());
                System.err.println("[Collector] This may indicate rate limiting. Wait 1-2 minutes and restart.");
                System.err.println("[Collector] Progress saved. Collected so far: " + collected);
                break;
            } catch (Exception e) {
                errors++;
                System.err.println("\n[Collector] Unexpected error: " + e.getMessage());
                e.printStackTrace();
                break;
            }
        }

        System.out.println("\n==============================================");
        System.out.println("  Collection Complete");
        System.out.println("==============================================");
        System.out.println("  Total collected:    " + collected);
        System.out.println("  Duplicates skipped: " + duplicates);
        System.out.println("  Errors:             " + errors);
        System.out.println("  Total attempts:     " + attempts);
        System.out.println("  Output file:        " + OUTPUT_FILE);
        System.out.println("==============================================");
    }

    // ==================== HTTP / File Operations ====================

    private void loadExistingFingerprints() {
        Path path = Path.of(OUTPUT_FILE);

        if (!Files.exists(path)) {
            System.out.println("[Collector] No existing file found, starting fresh.");
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    existingFingerprints.add(line);
                }
            }
            System.out.println("[Collector] Loaded " + existingFingerprints.size() + " existing fingerprints from file.");
        } catch (IOException e) {
            System.err.println("[Collector] Warning: Error loading existing file: " + e.getMessage());
            System.err.println("[Collector] Starting with empty set to avoid data loss.");
        }
    }

    private String fetchFingerprint() throws IOException, InterruptedException {
        double rand = random.nextDouble();
        String url = API_URL + "?rand=" + rand + "&tags=Chrome,Microsoft%20Windows";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "*/*")
                .header("Accept-Language", "en")
                .header("Origin", "https://fp.bablosoft.com")
                .header("Referer", "https://fp.bablosoft.com/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-site")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36")
                .header("sec-ch-ua", "\"Google Chrome\";v=\"143\", \"Chromium\";v=\"143\", \"Not A(Brand\";v=\"24\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Windows\"")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }

        String body = response.body();

        if (body == null || body.isBlank()) {
            return null;
        }

        if (!body.contains("\"found\":true")) {
            System.err.println("[Collector] API returned found:false - may need to adjust tags");
            return null;
        }

        if (!body.contains("\"ua\":")) {
            System.err.println("[Collector] Response missing 'ua' field");
            return null;
        }

        return body;
    }

    private void appendFingerprint(String fingerprint) throws IOException {
        Path path = Path.of(OUTPUT_FILE);

        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        try (BufferedWriter writer = Files.newBufferedWriter(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            writer.write(fingerprint);
            writer.newLine();
        }
    }

    private String extractUaPreview(String json) {
        try {
            int uaStart = json.indexOf("\"ua\":\"") + 6;
            int uaEnd = json.indexOf("\"", uaStart);
            if (uaStart > 5 && uaEnd > uaStart) {
                String ua = json.substring(uaStart, uaEnd);
                int chromeIdx = ua.indexOf("Chrome/");
                if (chromeIdx >= 0) {
                    int versionEnd = ua.indexOf(" ", chromeIdx);
                    if (versionEnd < 0) versionEnd = ua.length();
                    return ua.substring(chromeIdx, Math.min(versionEnd, chromeIdx + 20));
                }
                return ua.substring(0, Math.min(50, ua.length())) + "...";
            }
        } catch (Exception ignored) {
        }
        return "(unknown UA)";
    }

    // ==================== Client Hints Generation ====================

    /**
     * Enriches a raw Bablosoft fingerprint with generated Client Hints metadata.
     *
     * @param rawJson the raw JSON response from Bablosoft API
     * @return enriched JSON string with clientHints field added, or null on error
     */
    private String enrichWithClientHints(String rawJson) {
        try {
            JsonObject fingerprint = GSON.fromJson(rawJson, JsonObject.class);
            String userAgent = fingerprint.get("ua").getAsString();

            JsonObject clientHints = generateClientHints(userAgent);
            fingerprint.add("clientHints", clientHints);

            return GSON.toJson(fingerprint);
        } catch (Exception e) {
            System.err.println("[Collector] Error generating Client Hints: " + e.getMessage());
            return null;
        }
    }

    /**
     * Generates complete Client Hints metadata from a User-Agent string.
     *
     * This handles:
     * - GREASE brand generation (deterministic based on Chrome version)
     * - Brand ordering (varies by Chrome version)
     * - Platform detection and version mapping
     * - Architecture and bitness detection
     * - Windows 10 vs 11 handling
     *
     * @param userAgent the full User-Agent string
     * @return JsonObject containing all Client Hints fields
     */
    private JsonObject generateClientHints(String userAgent) {
        JsonObject hints = new JsonObject();

        // Parse Chrome version from UA
        ChromeVersion chromeVersion = parseChromeVersion(userAgent);
        if (chromeVersion == null) {
            throw new IllegalArgumentException("Could not parse Chrome version from UA: " + userAgent);
        }

        // Detect platform info
        PlatformInfo platform = detectPlatform(userAgent);

        // Generate GREASE brand (deterministic based on Chrome major version)
        String[] grease = getGreaseBrand(chromeVersion.major);
        String greaseBrand = grease[0];
        String greaseVersion = grease[1];

        // Build brands array (low-entropy - major versions only)
        JsonArray brands = buildBrandsArray(
                chromeVersion.major,
                greaseBrand,
                greaseVersion,
                false
        );
        hints.add("brands", brands);

        // Build fullVersionList array (high-entropy - full versions)
        JsonArray fullVersionList = buildBrandsArray(
                chromeVersion.major,
                greaseBrand,
                greaseVersion + ".0.0.0",
                true
        );
        // Replace Chrome/Chromium versions with full versions
        for (int i = 0; i < fullVersionList.size(); i++) {
            JsonObject brand = fullVersionList.get(i).getAsJsonObject();
            String brandName = brand.get("brand").getAsString();
            if (brandName.equals("Google Chrome") || brandName.equals("Chromium")) {
                brand.addProperty("version", chromeVersion.full);
            }
        }
        hints.add("fullVersionList", fullVersionList);

        // Platform info
        hints.addProperty("platform", platform.platform);
        hints.addProperty("platformVersion", platform.platformVersion);
        hints.addProperty("architecture", platform.architecture);
        hints.addProperty("bitness", platform.bitness);
        hints.addProperty("mobile", false);
        hints.addProperty("model", "");
        hints.addProperty("wow64", platform.wow64);

        // Add navigator.platform value (different from Client Hints platform!)
        hints.addProperty("navigatorPlatform", platform.navigatorPlatform);

        return hints;
    }

    /**
     * Parses Chrome version components from User-Agent string.
     */
    private ChromeVersion parseChromeVersion(String userAgent) {
        Matcher matcher = CHROME_VERSION_PATTERN.matcher(userAgent);
        if (!matcher.find()) {
            return null;
        }

        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int build = Integer.parseInt(matcher.group(3));
        int patch = Integer.parseInt(matcher.group(4));
        String full = major + "." + minor + "." + build + "." + patch;

        return new ChromeVersion(major, minor, build, patch, full);
    }

    /**
     * Gets the GREASE brand and version for a given Chrome major version.
     * This is deterministic - same Chrome version always produces same GREASE.
     *
     * @return String array: [brand, version]
     */
    private String[] getGreaseBrand(int chromeMajor) {
        int index = chromeMajor % GREASE_BRANDS_BY_VERSION_MOD.length;
        return GREASE_BRANDS_BY_VERSION_MOD[index];
    }

    /**
     * Gets the brand ordering for a given Chrome major version.
     * Chrome varies the order of brands in the Sec-CH-UA header.
     *
     * @return int array indicating positions: [greasePos, chromiumPos, chromePos]
     */
    private int[] getBrandOrder(int chromeMajor) {
        int index = chromeMajor % BRAND_ORDER_BY_VERSION_MOD.length;
        return BRAND_ORDER_BY_VERSION_MOD[index];
    }

    /**
     * Builds the brands or fullVersionList array with proper ordering.
     *
     * @param chromeMajor Chrome major version number
     * @param greaseBrand GREASE brand string
     * @param greaseVersion GREASE version string
     * @param useFullVersion if true, use full version format (x.0.0.0)
     */
    private JsonArray buildBrandsArray(int chromeMajor, String greaseBrand, String greaseVersion, boolean useFullVersion) {
        String chromeVersion = useFullVersion ? chromeMajor + ".0.0.0" : String.valueOf(chromeMajor);
        String chromiumVersion = chromeVersion; // Chromium version matches Chrome

        // Create brand objects
        JsonObject greaseBrandObj = new JsonObject();
        greaseBrandObj.addProperty("brand", greaseBrand);
        greaseBrandObj.addProperty("version", greaseVersion);

        JsonObject chromiumBrandObj = new JsonObject();
        chromiumBrandObj.addProperty("brand", "Chromium");
        chromiumBrandObj.addProperty("version", chromiumVersion);

        JsonObject chromeBrandObj = new JsonObject();
        chromeBrandObj.addProperty("brand", "Google Chrome");
        chromeBrandObj.addProperty("version", chromeVersion);

        // Get ordering for this Chrome version
        int[] order = getBrandOrder(chromeMajor);

        // Build array with correct ordering
        JsonObject[] orderedBrands = new JsonObject[3];
        orderedBrands[order[0]] = greaseBrandObj;
        orderedBrands[order[1]] = chromiumBrandObj;
        orderedBrands[order[2]] = chromeBrandObj;

        JsonArray brands = new JsonArray();
        for (JsonObject brand : orderedBrands) {
            brands.add(brand);
        }

        return brands;
    }

    /**
     * Detects platform information from User-Agent string.
     * Handles Windows, macOS, and Linux with proper version mapping.
     */
    private PlatformInfo detectPlatform(String userAgent) {
        String platform;
        String platformVersion;
        String navigatorPlatform;
        String architecture;
        String bitness;
        boolean wow64 = false;

        if (userAgent.contains("Windows")) {
            platform = "Windows";
            navigatorPlatform = detectNavigatorPlatformWindows(userAgent);
            platformVersion = detectWindowsPlatformVersion(userAgent);

            // Architecture and bitness detection
            if (userAgent.contains("Win64") || userAgent.contains("x64")) {
                architecture = "x86";
                bitness = "64";
            } else if (userAgent.contains("WOW64")) {
                // 32-bit process on 64-bit Windows
                architecture = "x86";
                bitness = "64";
                wow64 = true;
            } else if (userAgent.contains("ARM64") || userAgent.contains("ARM")) {
                architecture = "arm";
                bitness = "64";
            } else {
                // Assume 64-bit for modern Windows (32-bit is rare)
                architecture = "x86";
                bitness = "64";
            }
        } else if (userAgent.contains("Macintosh") || userAgent.contains("Mac OS X")) {
            platform = "macOS";
            navigatorPlatform = "MacIntel"; // Even ARM Macs report MacIntel for compatibility
            platformVersion = detectMacOSVersion(userAgent);
            architecture = userAgent.contains("ARM64") ? "arm" : "x86";
            bitness = "64";
        } else if (userAgent.contains("Linux")) {
            platform = "Linux";
            navigatorPlatform = "Linux x86_64";
            platformVersion = ""; // Linux typically doesn't expose version in Client Hints
            architecture = userAgent.contains("aarch64") ? "arm" : "x86";
            bitness = userAgent.contains("x86_64") || userAgent.contains("aarch64") ? "64" : "32";
        } else {
            // Fallback to Windows defaults
            platform = "Windows";
            navigatorPlatform = "Win32";
            platformVersion = "10.0.0";
            architecture = "x86";
            bitness = "64";
        }

        return new PlatformInfo(platform, platformVersion, navigatorPlatform, architecture, bitness, wow64);
    }

    /**
     * Detects navigator.platform value for Windows.
     *
     * Quirk: Even on 64-bit Windows, navigator.platform often returns "Win32"
     * due to browser compatibility concerns. Only some configurations return "Win64".
     */
    private String detectNavigatorPlatformWindows(String userAgent) {
        if (userAgent.contains("Win64") || userAgent.contains("x64")) {
            // Modern 64-bit Chrome on 64-bit Windows
            // Note: Some old Chrome versions returned "Win32" even on 64-bit
            // Chrome 64+ typically returns "Win32" for compatibility
            // We'll return "Win32" to match most real browsers
            return "Win32";
        } else if (userAgent.contains("WOW64")) {
            // 32-bit Chrome on 64-bit Windows
            return "Win32";
        } else {
            // 32-bit Windows or unknown
            return "Win32";
        }
    }

    /**
     * Detects Windows platform version for Client Hints.
     *
     * IMPORTANT: Windows 10 and 11 BOTH report "Windows NT 10.0" in the UA string.
     * The platformVersion in Client Hints CAN distinguish them:
     *
     * Windows Version | NT Version | platformVersion range
     * ----------------|------------|----------------------
     * Windows 7       | NT 6.1     | 6.1.x
     * Windows 8       | NT 6.2     | 6.2.x
     * Windows 8.1     | NT 6.3     | 6.3.x
     * Windows 10      | NT 10.0    | 1.0.0 - 10.0.x (build dependent)
     * Windows 11      | NT 10.0    | 13.0.0+ (21H2 = 13.0.0, 22H2 = 14.0.0, etc.)
     *
     * Since we cannot reliably detect Win10 vs Win11 from UA alone,
     * we default to Windows 10 (10.0.0) which is safer and more common.
     */
    private String detectWindowsPlatformVersion(String userAgent) {
        Matcher matcher = WINDOWS_NT_PATTERN.matcher(userAgent);
        if (matcher.find()) {
            String ntVersion = matcher.group(1);

            switch (ntVersion) {
                case "10.0":
                    // Could be Windows 10 or Windows 11
                    // Default to Windows 10 for broader compatibility
                    return "10.0.0";
                case "6.3":
                    return "6.3.0"; // Windows 8.1
                case "6.2":
                    return "6.2.0"; // Windows 8
                case "6.1":
                    return "6.1.0"; // Windows 7
                case "6.0":
                    return "6.0.0"; // Windows Vista
                case "5.1":
                case "5.2":
                    return "5.1.0"; // Windows XP
                default:
                    return "10.0.0"; // Fallback to Win10
            }
        }
        return "10.0.0";
    }

    /**
     * Detects macOS version from User-Agent string.
     * UA format: "Mac OS X 10_15_7" or "Mac OS X 10.15.7"
     */
    private String detectMacOSVersion(String userAgent) {
        Matcher matcher = MACOS_VERSION_PATTERN.matcher(userAgent);
        if (matcher.find()) {
            String major = matcher.group(1);
            String minor = matcher.group(2);
            String patch = matcher.group(3) != null ? matcher.group(3) : "0";
            return major + "." + minor + "." + patch;
        }
        return "10.15.0"; // Default to Catalina
    }

    // ==================== Inner Classes ====================

    /**
     * Holds parsed Chrome version components.
     */
    private static class ChromeVersion {
        final int major;
        final int minor;
        final int build;
        final int patch;
        final String full;

        ChromeVersion(int major, int minor, int build, int patch, String full) {
            this.major = major;
            this.minor = minor;
            this.build = build;
            this.patch = patch;
            this.full = full;
        }
    }

    /**
     * Holds detected platform information.
     */
    private static class PlatformInfo {
        final String platform;           // Client Hints platform (e.g., "Windows")
        final String platformVersion;    // Client Hints version (e.g., "10.0.0")
        final String navigatorPlatform;  // navigator.platform value (e.g., "Win32")
        final String architecture;       // CPU architecture (e.g., "x86", "arm")
        final String bitness;            // Bitness (e.g., "64", "32")
        final boolean wow64;             // Windows-on-Windows 64-bit flag

        PlatformInfo(String platform, String platformVersion, String navigatorPlatform,
                     String architecture, String bitness, boolean wow64) {
            this.platform = platform;
            this.platformVersion = platformVersion;
            this.navigatorPlatform = navigatorPlatform;
            this.architecture = architecture;
            this.bitness = bitness;
            this.wow64 = wow64;
        }
    }
}