package org.nodriver4j.core.monitoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.nodriver4j.core.Page;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors fingerprint-related API accesses on a browser page.
 *
 * <p>Injects a stealthy JavaScript monitoring script via
 * {@code Page.addScriptToEvaluateOnNewDocument} that wraps known
 * fingerprint-sensitive APIs (navigator, canvas, WebGL, audio, etc.)
 * and records accesses into an in-memory buffer. The Java side polls
 * this buffer periodically via {@code Runtime.evaluate} to drain
 * events.</p>
 *
 * <h2>Why Buffer-and-Poll</h2>
 * <p>The custom Chromium build disables {@code Runtime.addBinding}
 * (the V8 patch guts {@code addBindings()} and hardcodes
 * {@code enabled()} to {@code false}). This prevents event-push
 * from JS to Java, forcing the poll architecture.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * FingerprintMonitor monitor = new FingerprintMonitor(page, false);
 * monitor.start();
 *
 * page.navigate("https://example.com");
 * Thread.sleep(5000);
 *
 * FingerprintReport report = monitor.report();
 * report.exportToFile(Path.of("fp-report.json"));
 *
 * monitor.close();
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Load and inject the JavaScript monitoring script</li>
 *   <li>Poll the JS buffer at regular intervals</li>
 *   <li>Parse drain results into {@link FingerprintAccessEvent}s</li>
 *   <li>Build a {@link FingerprintReport} on demand</li>
 *   <li>Clean shutdown via {@link AutoCloseable}</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Browser lifecycle (managed by caller / TaskExecutionService)</li>
 *   <li>File export decisions (caller decides path and whether to export)</li>
 *   <li>UI display of results</li>
 * </ul>
 *
 * @see FingerprintReport
 * @see FingerprintAccessEvent
 */
public class FingerprintMonitor implements AutoCloseable {

    private static final long DEFAULT_POLL_INTERVAL_MS = 2000;

    /**
     * JavaScript to drain the buffer. Returns JSON: {@code {"e":[...], "d":0}}.
     * Buffer is cleared atomically by the drain function defined in the IIFE.
     */
    private static final String DRAIN_SCRIPT = "window.__nd4j_fp_drain()";

    /** The monitoring script, loaded once from classpath. */
    private static final String MONITOR_SCRIPT;

    static {
        try (InputStream is = FingerprintMonitor.class.getResourceAsStream("fingerprint-monitor.js")) {
            if (is == null) {
                throw new IllegalStateException("fingerprint-monitor.js not found on classpath");
            }
            MONITOR_SCRIPT = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ==================== Instance Fields ====================

    private final Page page;
    private final boolean captureStackTraces;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final List<FingerprintAccessEvent> events = Collections.synchronizedList(new ArrayList<>());

    private volatile ScheduledExecutorService scheduler;
    private volatile long monitoringStartMs;
    private volatile int totalDropped;

    // ==================== Constructor ====================

    /**
     * Creates a new fingerprint monitor for the given page.
     *
     * @param page               the page to monitor
     * @param captureStackTraces true to capture JS stack traces on each access
     *                           (expensive — ~10x overhead, use for deep analysis)
     */
    public FingerprintMonitor(Page page, boolean captureStackTraces) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        this.page = page;
        this.captureStackTraces = captureStackTraces;
    }

    // ==================== Lifecycle ====================

    /**
     * Injects the monitoring script and starts polling.
     *
     * <p>The script is injected in two ways:</p>
     * <ol>
     *   <li>{@code Page.addScriptToEvaluateOnNewDocument} — runs on all
     *       future navigations in this page</li>
     *   <li>{@code Runtime.evaluate} — runs immediately on the current
     *       document (in case a page is already loaded)</li>
     * </ol>
     *
     * @throws IllegalStateException if the monitor is already active
     */
    public void start() {
        if (!active.compareAndSet(false, true)) {
            throw new IllegalStateException("FingerprintMonitor is already active");
        }

        monitoringStartMs = System.currentTimeMillis();

        try {
            // Build the script to inject (optionally prepend stack trace flag)
            String script = captureStackTraces
                    ? "window.__nd4j_fp_stacks = true;\n" + MONITOR_SCRIPT
                    : MONITOR_SCRIPT;

            // Inject for all future navigations
            JsonObject params = new JsonObject();
            params.addProperty("source", script);
            page.cdpSession().send("Page.addScriptToEvaluateOnNewDocument", params);

            // Also run immediately on the current page
            page.evaluate(script);

        } catch (Exception e) {
            active.set(false);
            throw new RuntimeException("Failed to inject fingerprint monitoring script: " + e.getMessage(), e);
        }

        // Start polling on a daemon thread
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FingerprintMonitor-Poll");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::poll,
                DEFAULT_POLL_INTERVAL_MS, DEFAULT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        System.out.println("[FingerprintMonitor] Started (stackTraces=" + captureStackTraces + ")");
    }

    /**
     * Stops polling and performs a final drain of the buffer.
     *
     * <p>Idempotent — calling when already stopped is a no-op.</p>
     */
    public void stop() {
        if (!active.compareAndSet(true, false)) {
            return;
        }

        // Shut down the scheduler
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }

        // Final drain — capture any remaining events
        poll();

        System.out.println("[FingerprintMonitor] Stopped (" + events.size() +
                " events captured, " + totalDropped + " dropped)");
    }

    /**
     * Checks if the monitor is currently active.
     *
     * @return true if monitoring is in progress
     */
    public boolean isActive() {
        return active.get();
    }

    /**
     * Generates a report from all events captured so far.
     *
     * <p>Can be called while monitoring is active (snapshot) or after
     * {@link #stop()} for the final report.</p>
     *
     * @return the fingerprint probing report
     */
    public FingerprintReport report() {
        String url = null;
        try {
            url = page.evaluate("window.location.href");
        } catch (Exception e) {
            // Browser may be closed — use null
        }

        List<FingerprintAccessEvent> snapshot;
        synchronized (events) {
            snapshot = new ArrayList<>(events);
        }

        long endMs = monitoringStartMs;
        if (!snapshot.isEmpty()) {
            endMs = snapshot.getLast().timestamp();
        }

        return new FingerprintReport(snapshot, url, monitoringStartMs, endMs, totalDropped);
    }

    /**
     * Stops the monitor and releases resources. Called automatically
     * by {@link org.nodriver4j.services.TaskContext} on cancellation.
     */
    @Override
    public void close() {
        stop();
    }

    // ==================== Internal ====================

    /**
     * Drains the JavaScript buffer and parses the results.
     *
     * <p>Called by the scheduler every {@value #DEFAULT_POLL_INTERVAL_MS}ms
     * and once more during {@link #stop()} for the final sweep.</p>
     */
    private void poll() {
        try {
            String json = page.evaluate(DRAIN_SCRIPT);
            if (json == null || json.isEmpty()) {
                return;
            }

            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // Track dropped events
            if (root.has("d")) {
                totalDropped += root.get("d").getAsInt();
            }

            // Parse events
            JsonArray eventArray = root.getAsJsonArray("e");
            if (eventArray == null || eventArray.isEmpty()) {
                return;
            }

            for (JsonElement elem : eventArray) {
                JsonObject obj = elem.getAsJsonObject();
                String api = obj.get("a").getAsString();
                long timestamp = obj.get("t").getAsLong();
                String stack = obj.has("s") ? obj.get("s").getAsString() : null;
                events.add(new FingerprintAccessEvent(api, timestamp, stack));
            }

        } catch (Exception e) {
            // Expected when the browser is closing or navigating.
            // Silently skip — the next poll (or final drain) will try again.
        }
    }
}
