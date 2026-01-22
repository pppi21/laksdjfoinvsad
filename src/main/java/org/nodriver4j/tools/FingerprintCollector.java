package org.nodriver4j.tools;

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

/**
 * Utility tool to collect browser fingerprints from Bablosoft's FingerprintSwitcher API.
 *
 * This is a standalone data collection script, NOT part of the main library.
 * Run it to build up a local database of real-world fingerprints for later use.
 *
 * Output format: JSONL (JSON Lines) - one fingerprint JSON object per line.
 * This format allows safe append operations and crash recovery.
 */
public class FingerprintCollector {

    private static final String API_URL = "https://fingerprints.bablosoft.com/preview";
    private static final String OUTPUT_FILE = "data/fingerprints.jsonl";
    private static final int TARGET_COUNT = 1000;
    private static final int DELAY_MS = 350;

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
        System.out.println("==============================================\n");

        FingerprintCollector collector = new FingerprintCollector();
        collector.run();
    }

    public void run() {
        // Load existing fingerprints to support resume after crash
        loadExistingFingerprints();

        int collected = existingFingerprints.size();
        int attempts = 0;
        int duplicates = 0;
        int errors = 0;

        System.out.println("[Collector] Starting collection. Already have: " + collected + " fingerprints\n");

        while (collected < TARGET_COUNT) {
            attempts++;

            try {
                String fingerprint = fetchFingerprint();

                if (fingerprint == null) {
                    errors++;
                    System.err.println("[Collector] Empty/invalid response, skipping...");
                    Thread.sleep(DELAY_MS);
                    continue;
                }

                // Check for exact duplicate
                if (existingFingerprints.contains(fingerprint)) {
                    duplicates++;
                    System.out.println("[Collector] Duplicate #" + duplicates + " (attempt " + attempts + "), skipping...");
                } else {
                    // Save immediately to file
                    appendFingerprint(fingerprint);
                    existingFingerprints.add(fingerprint);
                    collected++;

                    // Extract UA for logging (simple substring extraction)
                    String uaPreview = extractUaPreview(fingerprint);
                    System.out.println("[Collector] " + collected + "/" + TARGET_COUNT + " - " + uaPreview);
                }

                // Rate limit
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

        // Final summary
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

    /**
     * Loads existing fingerprints from the output file.
     * This allows resuming collection after a crash or rate limit.
     */
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

    /**
     * Fetches a single fingerprint from the Bablosoft API.
     *
     * @return the raw JSON response, or null if invalid
     */
    private String fetchFingerprint() throws IOException, InterruptedException {
        // Generate random number for the rand parameter (mimics browser behavior)
        double rand = random.nextDouble();

        String url = API_URL + "?rand=" + rand + "&tags=Chrome,Microsoft%20Windows";

        // Note: "Connection" header is restricted by Java HttpClient (managed internally)
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

        // Basic validation - ensure we got a valid fingerprint response
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

    /**
     * Appends a fingerprint to the output file immediately.
     * Uses JSONL format (one JSON object per line) for safe append operations.
     */
    private void appendFingerprint(String fingerprint) throws IOException {
        Path path = Path.of(OUTPUT_FILE);

        // Ensure parent directory exists
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        // Append to file with newline
        try (BufferedWriter writer = Files.newBufferedWriter(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            writer.write(fingerprint);
            writer.newLine();
        }
    }

    /**
     * Extracts a preview of the UA string for logging purposes.
     */
    private String extractUaPreview(String json) {
        try {
            int uaStart = json.indexOf("\"ua\":\"") + 6;
            int uaEnd = json.indexOf("\"", uaStart);
            if (uaStart > 5 && uaEnd > uaStart) {
                String ua = json.substring(uaStart, uaEnd);
                // Extract Chrome version for concise logging
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
}