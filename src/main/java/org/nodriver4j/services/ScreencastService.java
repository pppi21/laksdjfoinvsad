package org.nodriver4j.services;

import com.google.gson.JsonObject;
import org.nodriver4j.cdp.CDPSession;

import java.util.Base64;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Manages a CDP screencast session for a single browser.
 *
 * <p>Streams live visual output from a headless Chrome browser by using the
 * CDP {@code Page.startScreencast} / {@code Page.stopScreencast} commands.
 * Each frame is received as a base64-encoded JPEG, decoded to raw bytes,
 * and pushed to a callback. The caller is responsible for displaying the
 * frames (e.g., converting to a JavaFX {@code Image} on the FX thread).</p>
 *
 * <h2>CDP Protocol Flow</h2>
 * <ol>
 *   <li>{@code Page.startScreencast} — tells Chrome to begin sending frames</li>
 *   <li>{@code Page.screencastFrame} event — fired for each frame; contains
 *       base64-encoded image data, metadata (offset, timestamp, device dimensions),
 *       and a {@code sessionId} used for acknowledgment</li>
 *   <li>{@code Page.screencastFrameAck} — must be sent for each received frame;
 *       Chrome will not send the next frame until the previous one is acknowledged</li>
 *   <li>{@code Page.stopScreencast} — stops the stream</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * <p>The frame callback is invoked on the WebSocket thread. Callers that need
 * to update UI must marshal to the appropriate thread (e.g., {@code Platform.runLater}
 * for JavaFX). This class has no JavaFX dependency.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * CDPClient cdpClient = browser.cdpClient();
 * ScreencastService screencast = new ScreencastService(cdpClient);
 *
 * // Start streaming — callback fires on WebSocket thread
 * screencast.start(frameBytes -> {
 *     Platform.runLater(() -> viewWindow.updateFrame(frameBytes));
 * });
 *
 * // Later, stop streaming
 * screencast.stop();
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Start and stop CDP screencast sessions</li>
 *   <li>Listen for {@code Page.screencastFrame} events</li>
 *   <li>Decode base64 image data to raw {@code byte[]}</li>
 *   <li>Acknowledge each frame to receive the next one</li>
 *   <li>Invoke a frame callback with decoded bytes</li>
 *   <li>Track active/inactive state</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>UI display or JavaFX concerns (delegated to the caller / ViewBrowserWindow)</li>
 *   <li>Task tracking or browser lifecycle (delegated to TaskExecutionService)</li>
 *   <li>CDP connection management (receives a CDPSession from the caller)</li>
 *   <li>Threading to the JavaFX thread (caller wraps callback accordingly)</li>
 * </ul>
 *
 * @see CDPSession
 */
public class ScreencastService {

    // ==================== Screencast Defaults ====================

    /** Image format for screencast frames. JPEG is more efficient than PNG for streaming. */
    private static final String DEFAULT_FORMAT = "jpeg";

    /** JPEG quality (0–100). 60 balances visual clarity with bandwidth. */
    private static final int DEFAULT_QUALITY = 60;

    /** Maximum frame width in pixels. Frames are scaled down if the viewport is larger. */
    private static final int DEFAULT_MAX_WIDTH = 1280;

    /** Maximum frame height in pixels. Frames are scaled down if the viewport is larger. */
    private static final int DEFAULT_MAX_HEIGHT = 720;

    private static final String EVENT_SCREENCAST_FRAME = "Page.screencastFrame";

    // ==================== Instance Fields ====================

    private final CDPSession cdpSession;
    private final AtomicBoolean active = new AtomicBoolean(false);

    /**
     * The frame listener reference, stored for removal on stop.
     * Volatile because it is written during start() and read during stop(),
     * potentially from different threads.
     */
    private volatile Consumer<JsonObject> frameListener;

    // ==================== Constructor ====================

    /**
     * Creates a new ScreencastService for the given CDP session.
     *
     * <p>The CDP session should target the page whose screencast
     * is desired, since {@code Page.startScreencast} is a page
     * domain command.</p>
     *
     * @param cdpSession the CDP session for the target page
     * @throws IllegalArgumentException if cdpSession is null
     */
    public ScreencastService(CDPSession cdpSession) {
        if (cdpSession == null) {
            throw new IllegalArgumentException("CDPSession cannot be null");
        }
        this.cdpSession = cdpSession;
    }

    // ==================== Start / Stop ====================

    /**
     * Starts the screencast with default settings.
     *
     * <p>Uses JPEG format at quality 60, capped at 1280×720. Frames are
     * delivered as raw {@code byte[]} (decoded JPEG data) to the callback.</p>
     *
     * @param onFrame callback invoked for each frame; receives decoded JPEG bytes.
     *                Called on the WebSocket thread — callers must marshal to the
     *                UI thread if needed.
     * @throws IllegalStateException if the screencast is already active
     * @throws IllegalArgumentException if onFrame is null
     */
    public void start(Consumer<byte[]> onFrame) {
        start(DEFAULT_FORMAT, DEFAULT_QUALITY, DEFAULT_MAX_WIDTH, DEFAULT_MAX_HEIGHT, onFrame);
    }

    /**
     * Starts the screencast with custom settings.
     *
     * @param format    image format: "jpeg" or "png"
     * @param quality   JPEG quality (0–100); ignored for PNG
     * @param maxWidth  maximum frame width in pixels
     * @param maxHeight maximum frame height in pixels
     * @param onFrame   callback invoked for each frame; receives decoded image bytes.
     *                  Called on the WebSocket thread.
     * @throws IllegalStateException if the screencast is already active
     * @throws IllegalArgumentException if onFrame is null or parameters are invalid
     */
    public void start(String format, int quality, int maxWidth, int maxHeight,
                      Consumer<byte[]> onFrame) {
        if (onFrame == null) {
            throw new IllegalArgumentException("Frame callback cannot be null");
        }
        if (!active.compareAndSet(false, true)) {
            throw new IllegalStateException("Screencast is already active");
        }

        // Register the frame event listener before sending the start command
        // to avoid missing the first frame
        frameListener = params -> handleFrame(params, onFrame);
        cdpSession.addEventListener(EVENT_SCREENCAST_FRAME, frameListener);

        // Send Page.startScreencast
        JsonObject params = new JsonObject();
        params.addProperty("format", format);
        params.addProperty("quality", quality);
        params.addProperty("maxWidth", maxWidth);
        params.addProperty("maxHeight", maxHeight);

        try {
            cdpSession.send("Page.startScreencast", params);
            System.out.println("[ScreencastService] Started (format=" + format +
                    ", quality=" + quality + ", maxSize=" + maxWidth + "×" + maxHeight + ")");
        } catch (TimeoutException e) {
            // Roll back — remove listener and reset state
            cdpSession.removeEventListener(EVENT_SCREENCAST_FRAME, frameListener);
            frameListener = null;
            active.set(false);
            throw new RuntimeException("Failed to start screencast: " + e.getMessage(), e);
        }
    }

    /**
     * Stops the screencast and removes the frame listener.
     *
     * <p>This method is idempotent — calling it when the screencast is
     * already stopped is a no-op.</p>
     */
    public void stop() {
        if (!active.compareAndSet(true, false)) {
            return; // Already stopped
        }

        // Remove listener first to stop processing frames immediately
        if (frameListener != null) {
            cdpSession.removeEventListener(EVENT_SCREENCAST_FRAME, frameListener);
            frameListener = null;
        }

        // Send Page.stopScreencast — fire and forget since we've already
        // removed the listener. Use sendAsync to avoid blocking if the
        // browser is closing or unresponsive.
        try {
            cdpSession.sendAsync("Page.stopScreencast", null);
            System.out.println("[ScreencastService] Stopped");
        } catch (Exception e) {
            // Non-fatal — the browser may already be closing
            System.err.println("[ScreencastService] Error sending stopScreencast: " + e.getMessage());
        }
    }

    // ==================== Frame Handling ====================

    /**
     * Processes a single screencast frame event.
     *
     * <p>Decodes the base64 image data, sends an acknowledgment to Chrome
     * (so it will send the next frame), and invokes the frame callback.
     * The ack is sent before the callback to minimize latency between frames.</p>
     *
     * @param params  the event parameters from {@code Page.screencastFrame}
     * @param onFrame the frame callback to invoke
     */
    private void handleFrame(JsonObject params, Consumer<byte[]> onFrame) {
        // Guard against frames arriving after stop() but before the listener
        // is fully removed (race condition window)
        if (!active.get()) {
            return;
        }

        try {
            // Extract session ID for acknowledgment (required to receive next frame)
            int sessionId = params.get("sessionId").getAsInt();

            // Acknowledge the frame immediately to keep the pipeline flowing.
            // This is fire-and-forget — we don't need to wait for a response.
            acknowledgeFrame(sessionId);

            // Extract and decode the base64 image data
            String base64Data = params.get("data").getAsString();
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);

            // Deliver the frame to the callback
            onFrame.accept(imageBytes);

        } catch (Exception e) {
            System.err.println("[ScreencastService] Error processing frame: " + e.getMessage());
        }
    }

    /**
     * Sends a frame acknowledgment to Chrome.
     *
     * <p>Chrome uses a flow-control mechanism where it will not send the
     * next frame until the previous one is acknowledged. This prevents
     * frame buildup when the consumer is slower than the producer.</p>
     *
     * @param sessionId the session ID from the received frame event
     */
    private void acknowledgeFrame(int sessionId) {
        JsonObject ackParams = new JsonObject();
        ackParams.addProperty("sessionId", sessionId);
        cdpSession.sendAsync("Page.screencastFrameAck", ackParams);
    }

    // ==================== State Queries ====================

    /**
     * Checks if the screencast is currently active.
     *
     * @return true if the screencast is streaming frames
     */
    public boolean isActive() {
        return active.get();
    }
}