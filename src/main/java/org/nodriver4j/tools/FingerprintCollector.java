package org.nodriver4j.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
 * IMPORTANT: This collector strips version-dependent fields from profiles.
 * The following fields are EXCLUDED because they must match the actual browser:
 * - ua (User-Agent string)
 * - clientHints.brands (low-entropy brand list)
 * - clientHints.fullVersionList (high-entropy brand list)
 * - headers (HTTP header order is version-dependent)
 * - plugins (standardized in modern Chrome, use actual browser's plugins)
 *
 * The production library will use actual browser values for these fields,
 * combined with the hardware/OS-dependent fields stored in these profiles.
 */
public class FingerprintCollector {

    private static final String API_URL = "https://fingerprints.bablosoft.com/preview";
    private static final String OUTPUT_FILE = "data/fingerprints.jsonl";
    private static final int TARGET_COUNT = 1000;
    private static final int DELAY_MS = 1000;

    private static final Gson GSON = new GsonBuilder().create();

    // ========== Platform Detection Patterns ==========

    // Pattern to extract Windows NT version
    private static final Pattern WINDOWS_NT_PATTERN =
            Pattern.compile("Windows NT (\\d+\\.\\d+)");

    // Pattern to extract macOS version: Mac OS X 10_15_7 or Mac OS X 10.15.7
    private static final Pattern MACOS_VERSION_PATTERN =
            Pattern.compile("Mac OS X (\\d+)[_\\.](\\d+)(?:[_\\.]?(\\d+))?");

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
        System.out.println("==============================================");
        System.out.println("  EXCLUDED (version-dependent):");
        System.out.println("    - ua, brands, fullVersionList");
        System.out.println("    - headers, plugins");
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

                // Process and strip version-dependent fields
                String processedFingerprint = processFingerprint(rawFingerprint);

                if (processedFingerprint == null) {
                    errors++;
                    System.err.println("[Collector] Failed to process fingerprint, skipping...");
                    Thread.sleep(DELAY_MS);
                    continue;
                }

                // Check for exact duplicate (on processed version)
                if (existingFingerprints.contains(processedFingerprint)) {
                    duplicates++;
                    System.out.println("[Collector] Duplicate #" + duplicates + " (attempt " + attempts + "), skipping...");
                } else {
                    appendFingerprint(processedFingerprint);
                    existingFingerprints.add(processedFingerprint);
                    collected++;

                    String preview = extractPreview(processedFingerprint);
                    System.out.println("[Collector] " + collected + "/" + TARGET_COUNT + " - " + preview);
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

    // ==================== Fingerprint Processing ====================

    /**
     * Processes a raw Bablosoft fingerprint:
     * 1. Parses platform info from the UA string
     * 2. Removes version-dependent fields
     * 3. Returns the cleaned profile
     *
     * @param rawJson the raw JSON response from Bablosoft API
     * @return processed JSON string with version-dependent fields removed, or null on error
     */
    private String processFingerprint(String rawJson) {
        try {
            JsonObject fingerprint = GSON.fromJson(rawJson, JsonObject.class);
            String userAgent = fingerprint.get("ua").getAsString();

            // Extract platform info from UA before we discard it
            JsonObject platformInfo = extractPlatformInfo(userAgent);

            // Remove version-dependent fields from root
            fingerprint.remove("ua");
            fingerprint.remove("headers");
            fingerprint.remove("plugins");
            fingerprint.remove("found");

            // Remove version-dependent fields from clientHints if it exists,
            // otherwise create clientHints from extracted platform info
            if (fingerprint.has("clientHints")) {
                JsonObject clientHints = fingerprint.getAsJsonObject("clientHints");
                clientHints.remove("brands");
                clientHints.remove("fullVersionList");
            } else {
                // No clientHints from API, create from extracted platform info
                fingerprint.add("clientHints", platformInfo);
            }

            return GSON.toJson(fingerprint);
        } catch (Exception e) {
            System.err.println("[Collector] Error processing fingerprint: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extracts platform information from a User-Agent string.
     * This data is OS/hardware-dependent, not Chrome-version-dependent.
     *
     * @param userAgent the full User-Agent string
     * @return JsonObject containing platform fields for clientHints
     */
    private JsonObject extractPlatformInfo(String userAgent) {
        JsonObject info = new JsonObject();

        if (userAgent.contains("Windows")) {
            info.addProperty("platform", "Windows");
            info.addProperty("navigatorPlatform", detectNavigatorPlatformWindows(userAgent));
            info.addProperty("platformVersion", detectWindowsPlatformVersion(userAgent));

            if (userAgent.contains("Win64") || userAgent.contains("x64")) {
                info.addProperty("architecture", "x86");
                info.addProperty("bitness", "64");
                info.addProperty("wow64", false);
            } else if (userAgent.contains("WOW64")) {
                info.addProperty("architecture", "x86");
                info.addProperty("bitness", "64");
                info.addProperty("wow64", true);
            } else if (userAgent.contains("ARM64") || userAgent.contains("ARM")) {
                info.addProperty("architecture", "arm");
                info.addProperty("bitness", "64");
                info.addProperty("wow64", false);
            } else {
                info.addProperty("architecture", "x86");
                info.addProperty("bitness", "64");
                info.addProperty("wow64", false);
            }
        } else if (userAgent.contains("Macintosh") || userAgent.contains("Mac OS X")) {
            info.addProperty("platform", "macOS");
            info.addProperty("navigatorPlatform", "MacIntel");
            info.addProperty("platformVersion", detectMacOSVersion(userAgent));
            info.addProperty("architecture", userAgent.contains("ARM64") ? "arm" : "x86");
            info.addProperty("bitness", "64");
            info.addProperty("wow64", false);
        } else if (userAgent.contains("Linux")) {
            info.addProperty("platform", "Linux");
            info.addProperty("navigatorPlatform", "Linux x86_64");
            info.addProperty("platformVersion", "");
            info.addProperty("architecture", userAgent.contains("aarch64") ? "arm" : "x86");
            info.addProperty("bitness", userAgent.contains("x86_64") || userAgent.contains("aarch64") ? "64" : "32");
            info.addProperty("wow64", false);
        } else {
            // Fallback to Windows defaults
            info.addProperty("platform", "Windows");
            info.addProperty("navigatorPlatform", "Win32");
            info.addProperty("platformVersion", "10.0.0");
            info.addProperty("architecture", "x86");
            info.addProperty("bitness", "64");
            info.addProperty("wow64", false);
        }

        // These are always the same for desktop
        info.addProperty("mobile", false);
        info.addProperty("model", "");

        return info;
    }

    /**
     * Detects navigator.platform value for Windows.
     * Even on 64-bit Windows, navigator.platform typically returns "Win32" for compatibility.
     */
    private String detectNavigatorPlatformWindows(String userAgent) {
        // Chrome consistently returns "Win32" for compatibility, even on 64-bit
        return "Win32";
    }

    /**
     * Detects Windows platform version for Client Hints.
     */
    private String detectWindowsPlatformVersion(String userAgent) {
        Matcher matcher = WINDOWS_NT_PATTERN.matcher(userAgent);
        if (matcher.find()) {
            String ntVersion = matcher.group(1);

            return switch (ntVersion) {
                case "10.0" -> "10.0.0"; // Windows 10 or 11
                case "6.3" -> "6.3.0";   // Windows 8.1
                case "6.2" -> "6.2.0";   // Windows 8
                case "6.1" -> "6.1.0";   // Windows 7
                case "6.0" -> "6.0.0";   // Windows Vista
                case "5.1", "5.2" -> "5.1.0"; // Windows XP
                default -> "10.0.0";
            };
        }
        return "10.0.0";
    }

    /**
     * Detects macOS version from User-Agent string.
     */
    private String detectMacOSVersion(String userAgent) {
        Matcher matcher = MACOS_VERSION_PATTERN.matcher(userAgent);
        if (matcher.find()) {
            String major = matcher.group(1);
            String minor = matcher.group(2);
            String patch = matcher.group(3) != null ? matcher.group(3) : "0";
            return major + "." + minor + "." + patch;
        }
        return "10.15.0";
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

    /**
     * Extracts a preview string for logging (shows WebGL renderer since UA is removed).
     */
    private String extractPreview(String json) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj.has("renderer")) {
                String renderer = obj.get("renderer").getAsString();
                // Extract GPU name from ANGLE string
                int start = renderer.indexOf("(");
                int end = renderer.indexOf(",", start);
                if (start >= 0 && end > start) {
                    return renderer.substring(start + 1, end).trim();
                }
                return renderer.substring(0, Math.min(40, renderer.length()));
            }
            if (obj.has("vendor")) {
                return obj.get("vendor").getAsString();
            }
        } catch (Exception ignored) {
        }
        return "(hardware fingerprint)";
    }
}