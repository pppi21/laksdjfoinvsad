package org.nodriver4j.core.monitoring;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Aggregated report of fingerprint API accesses captured during a monitoring session.
 *
 * <p>Groups raw {@link FingerprintAccessEvent}s by API and category,
 * computes access counts and timing, and exports the full report
 * to a JSON file for offline analysis.</p>
 *
 * <h2>Categories</h2>
 * <p>APIs are classified into categories based on their name prefix:</p>
 * <ul>
 *   <li><b>Navigator</b> — navigator.userAgent, navigator.platform, etc.</li>
 *   <li><b>Screen</b> — screen.width, window.devicePixelRatio, etc.</li>
 *   <li><b>Canvas</b> — HTMLCanvasElement.toDataURL, CanvasRenderingContext2D.*, etc.</li>
 *   <li><b>WebGL</b> — WebGLRenderingContext.getParameter, etc.</li>
 *   <li><b>Audio</b> — AudioContext, BaseAudioContext.*, etc.</li>
 *   <li><b>Fonts</b> — document.fonts</li>
 *   <li><b>Timezone</b> — Intl.DateTimeFormat, Date.getTimezoneOffset</li>
 *   <li><b>Storage</b> — window.localStorage, window.sessionStorage, window.indexedDB</li>
 *   <li><b>WebRTC</b> — RTCPeerConnection</li>
 *   <li><b>Performance</b> — Performance.now</li>
 *   <li><b>CSS/Media</b> — window.matchMedia</li>
 * </ul>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Aggregate events by API name and category</li>
 *   <li>Compute access counts and first/last access times</li>
 *   <li>Export full report as pretty-printed JSON</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Event collection (handled by {@link FingerprintMonitor})</li>
 *   <li>Deciding when or where to export (caller controls this)</li>
 * </ul>
 *
 * @see FingerprintMonitor
 * @see FingerprintAccessEvent
 */
public class FingerprintReport {

    // ==================== Category Classification ====================

    /**
     * Ordered list of prefix-to-category mappings. Checked in order,
     * so more specific prefixes must come before general ones.
     */
    private static final List<Map.Entry<String, String>> CATEGORY_RULES = List.of(
            // Navigator
            Map.entry("navigator.", "Navigator"),

            // Screen
            Map.entry("screen.", "Screen"),
            Map.entry("window.devicePixelRatio", "Screen"),

            // Canvas
            Map.entry("HTMLCanvasElement.", "Canvas"),
            Map.entry("CanvasRenderingContext2D.", "Canvas"),

            // WebGL
            Map.entry("WebGLRenderingContext.", "WebGL"),
            Map.entry("WebGL2RenderingContext.", "WebGL"),

            // Audio
            Map.entry("AudioContext", "Audio"),
            Map.entry("OfflineAudioContext", "Audio"),
            Map.entry("webkitAudioContext", "Audio"),
            Map.entry("BaseAudioContext.", "Audio"),
            Map.entry("AudioDestinationNode.", "Audio"),

            // Fonts
            Map.entry("document.fonts", "Fonts"),

            // Timezone
            Map.entry("DateTimeFormat", "Timezone"),
            Map.entry("Date.", "Timezone"),

            // Storage
            Map.entry("window.localStorage", "Storage"),
            Map.entry("window.sessionStorage", "Storage"),
            Map.entry("window.indexedDB", "Storage"),

            // WebRTC
            Map.entry("RTCPeerConnection", "WebRTC"),
            Map.entry("webkitRTCPeerConnection", "WebRTC"),

            // Performance
            Map.entry("Performance.", "Performance"),

            // CSS/Media
            Map.entry("window.matchMedia", "CSS/Media")
    );

    // ==================== Fields ====================

    private final List<FingerprintAccessEvent> events;
    private final String url;
    private final long monitoringStartMs;
    private final long monitoringEndMs;
    private final int droppedEvents;

    // Lazily computed
    private Map<String, Integer> accessCounts;
    private Map<String, Map<String, ApiSummary>> byCategory;

    // ==================== Constructor ====================

    /**
     * Creates a report from raw events.
     *
     * @param events            all captured events
     * @param url               the page URL at report time, or null
     * @param monitoringStartMs when monitoring started (epoch ms)
     * @param monitoringEndMs   when monitoring ended (epoch ms)
     * @param droppedEvents     number of events dropped due to buffer cap
     */
    FingerprintReport(List<FingerprintAccessEvent> events, String url,
                      long monitoringStartMs, long monitoringEndMs, int droppedEvents) {
        this.events = List.copyOf(events);
        this.url = url;
        this.monitoringStartMs = monitoringStartMs;
        this.monitoringEndMs = monitoringEndMs;
        this.droppedEvents = droppedEvents;
    }

    // ==================== Public API ====================

    /**
     * Returns the number of accesses per API name.
     *
     * @return unmodifiable map of api name to access count
     */
    public Map<String, Integer> accessCounts() {
        computeIfNeeded();
        return Collections.unmodifiableMap(accessCounts);
    }

    /**
     * Returns APIs grouped by fingerprinting category with per-API summaries.
     *
     * @return unmodifiable map of category to (api name -> summary)
     */
    public Map<String, Map<String, ApiSummary>> byCategory() {
        computeIfNeeded();
        return Collections.unmodifiableMap(byCategory);
    }

    /**
     * Returns the list of categories that were probed.
     *
     * @return sorted list of category names
     */
    public List<String> probedCategories() {
        computeIfNeeded();
        return byCategory.keySet().stream().sorted().toList();
    }

    /**
     * Returns the total number of accesses recorded.
     *
     * @return total access count
     */
    public int totalAccesses() {
        return events.size();
    }

    /**
     * Returns the number of unique APIs accessed.
     *
     * @return unique API count
     */
    public int uniqueApis() {
        computeIfNeeded();
        return accessCounts.size();
    }

    /**
     * Exports the full report as pretty-printed JSON.
     *
     * @param outputPath the file path to write to
     * @throws IOException if writing fails
     */
    public void exportToFile(Path outputPath) throws IOException {
        computeIfNeeded();

        JsonObject root = new JsonObject();
        root.addProperty("url", url);
        root.addProperty("monitoringStartMs", monitoringStartMs);
        root.addProperty("monitoringEndMs", monitoringEndMs);
        root.addProperty("durationMs", monitoringEndMs - monitoringStartMs);
        root.addProperty("totalAccesses", events.size());
        root.addProperty("uniqueApis", accessCounts.size());
        root.addProperty("droppedEvents", droppedEvents);

        // byCategory
        JsonObject categoriesJson = new JsonObject();
        for (var catEntry : byCategory.entrySet()) {
            JsonObject catObj = new JsonObject();
            for (var apiEntry : catEntry.getValue().entrySet()) {
                ApiSummary summary = apiEntry.getValue();
                JsonObject apiObj = new JsonObject();
                apiObj.addProperty("count", summary.count);
                apiObj.addProperty("firstAccessMs", summary.firstAccessMs);
                apiObj.addProperty("lastAccessMs", summary.lastAccessMs);
                catObj.add(apiEntry.getKey(), apiObj);
            }
            categoriesJson.add(catEntry.getKey(), catObj);
        }
        root.add("byCategory", categoriesJson);

        // Raw events
        JsonArray eventsJson = new JsonArray();
        for (FingerprintAccessEvent event : events) {
            JsonObject eventObj = new JsonObject();
            eventObj.addProperty("api", event.api());
            eventObj.addProperty("timestamp", event.timestamp());
            if (event.stackTrace() != null) {
                eventObj.addProperty("stackTrace", event.stackTrace());
            }
            eventsJson.add(eventObj);
        }
        root.add("events", eventsJson);

        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        Files.writeString(outputPath, json);
    }

    // ==================== Internal ====================

    /**
     * Lazily computes the aggregated data structures.
     */
    private void computeIfNeeded() {
        if (accessCounts != null) {
            return;
        }

        accessCounts = new LinkedHashMap<>();
        byCategory = new TreeMap<>();

        for (FingerprintAccessEvent event : events) {
            String api = event.api();
            accessCounts.merge(api, 1, Integer::sum);

            String category = categorize(api);
            byCategory.computeIfAbsent(category, k -> new LinkedHashMap<>())
                    .merge(api, new ApiSummary(1, event.timestamp(), event.timestamp()),
                            ApiSummary::merge);
        }
    }

    /**
     * Classifies an API name into a fingerprinting category.
     *
     * @param api the API name (e.g. "navigator.userAgent")
     * @return the category (e.g. "Navigator") or "Other"
     */
    private static String categorize(String api) {
        for (var rule : CATEGORY_RULES) {
            if (api.startsWith(rule.getKey())) {
                return rule.getValue();
            }
        }
        return "Other";
    }

    // ==================== Inner Types ====================

    /**
     * Summary statistics for a single API.
     */
    public static class ApiSummary {

        final int count;
        final long firstAccessMs;
        final long lastAccessMs;

        ApiSummary(int count, long firstAccessMs, long lastAccessMs) {
            this.count = count;
            this.firstAccessMs = firstAccessMs;
            this.lastAccessMs = lastAccessMs;
        }

        static ApiSummary merge(ApiSummary a, ApiSummary b) {
            return new ApiSummary(
                    a.count + b.count,
                    Math.min(a.firstAccessMs, b.firstAccessMs),
                    Math.max(a.lastAccessMs, b.lastAccessMs)
            );
        }

        public int count() {
            return count;
        }

        public long firstAccessMs() {
            return firstAccessMs;
        }

        public long lastAccessMs() {
            return lastAccessMs;
        }
    }
}