package org.nodriver4j.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.nodriver4j.cdp.CDPSession;
import org.nodriver4j.math.BoundingBox;
import org.nodriver4j.math.HumanBehavior;
import org.nodriver4j.math.Vector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * High-level automation API for a browser page/tab.
 *
 * <p>Page provides human-like interactions with web pages including:</p>
 * <ul>
 *   <li>Navigation: {@link #navigate}, {@link #reload}, {@link #goBack}, {@link #goForward}</li>
 *   <li>Clicking: {@link #click} with realistic mouse movement</li>
 *   <li>Typing: {@link #type} with human-like keystroke timing</li>
 *   <li>Scrolling: {@link #scrollBy}, {@link #scrollTo}, {@link #scrollIntoView}</li>
 *   <li>Waiting: {@link #waitForSelector}, {@link #waitForNavigation}</li>
 *   <li>Queries: {@link #querySelector}, {@link #getText}, {@link #getAttribute}</li>
 * </ul>
 *
 * <p>All query methods support both XPath and CSS selectors. The selector type is
 * auto-detected based on the string pattern:</p>
 * <ul>
 *   <li><strong>XPath</strong>: Starts with "/" or "(" (e.g., "//div[@id='test']")</li>
 *   <li><strong>CSS</strong>: Everything else (e.g., "div#test", "button[aria-label='Close']")</li>
 * </ul>
 *
 * <p>All interactions use realistic timing and movement patterns based on
 * human behavior research to avoid bot detection.</p>
 *
 * <p>Page instances are created and managed by {@link Browser}.</p>
 */
public class Page {

    private static final int DEFAULT_NAVIGATION_TIMEOUT = 30000;

    private CDPSession cdp;
    private final String targetId;
    private final InteractionOptions options;
    private final Browser browser;
    private final ConcurrentHashMap<String, String> oopifSessions = new ConcurrentHashMap<>();

    // Current mouse position (tracked for realistic movement)
    private Vector mousePosition;

    // Cursor overlay tracking
    private boolean cursorOverlayInjected = false;

    // Enabled CDP domains tracking
    private boolean pageEnabled = false;
    private boolean runtimeEnabled = false;
    private boolean networkEnabled = false;

    /**
     * JavaScript for cursor overlay injection.
     * Creates a visual indicator that follows the emulated mouse.
     */
    private static final String CURSOR_OVERLAY_SCRIPT = """
        (function() {
            function initCursor() {
                if (document.getElementById('__nodriver4j_cursor')) return;
                if (!document.body) {
                    if (document.readyState === 'loading') {
                        document.addEventListener('DOMContentLoaded', initCursor);
                    } else {
                        setTimeout(initCursor, 50);
                    }
                    return;
                }

                var cursor = document.createElement('div');
                cursor.id = '__nodriver4j_cursor';
                cursor.style.cssText = 'position:fixed;width:12px;height:12px;background:#ff0000;border-radius:50%;z-index:2147483647;pointer-events:none;top:0;left:0;display:none;box-shadow:0 0 4px rgba(0,0,0,0.5);transform:translate(-50%,-50%);';
                document.body.appendChild(cursor);
            }

            window.__nodriver4j_moveCursor = function(x, y) {
                var c = document.getElementById('__nodriver4j_cursor');
                if (!c) {
                    initCursor();
                    c = document.getElementById('__nodriver4j_cursor');
                }
                if (c) {
                    c.style.left = x + 'px';
                    c.style.top = y + 'px';
                    c.style.display = 'block';
                }
            };

            window.__nodriver4j_clickCursor = function() {
                var c = document.getElementById('__nodriver4j_cursor');
                if (c) {
                    c.style.background = '#ff8c00';
                    setTimeout(function() {
                        if (c) c.style.background = '#ff0000';
                    }, 500);
                }
            };

            initCursor();
        })();
        """;

    /**
     * Creates a new Page with default interaction options.
     *
     * @param cdp      the CDP client connected to this page's target
     * @param targetId the target ID for this page
     */
    public Page(CDPSession cdp, String targetId, Browser browser) {
        this(cdp, targetId, browser, InteractionOptions.defaults());
    }

    /**
     * Creates a new Page with custom interaction options.
     *
     * @param cdp      the CDP client connected to this page's target
     * @param targetId the target ID for this page
     * @param options  the interaction options
     */
    public Page(CDPSession cdp, String targetId, Browser browser, InteractionOptions options) {
        this.cdp = cdp;
        this.targetId = targetId;
        this.browser = browser;
        this.options = options;
        this.mousePosition = Vector.ORIGIN;
    }

    /**
     * Gets the target ID for this page.
     *
     * @return the target ID
     */
    public String targetId() {
        return targetId;
    }

    /**
     * Gets the target ID for this page.
     *
     * @return the target ID
     * @deprecated Use {@link #targetId()} instead
     */
    @Deprecated
    public String getTargetId() {
        return targetId;
    }

    /**
     * Gets the CDP session for this page.
     *
     * @return the CDP session
     */
    public CDPSession cdpSession() {
        return cdp;
    }

    /**
     * Gets the interaction options for this page.
     *
     * @return the interaction options
     */
    public InteractionOptions options() {
        return options;
    }

    /**
     * Gets the interaction options for this page.
     *
     * @return the interaction options
     * @deprecated Use {@link #options()} instead
     */
    @Deprecated
    public InteractionOptions getOptions() {
        return options;
    }

    // ==================== CDP Domain Management ====================

    private void ensurePageEnabled() throws TimeoutException {
        if (!pageEnabled) {
            cdp.send("Page.enable", null);
            pageEnabled = true;
        }
    }

    public void ensureRuntimeEnabled() throws TimeoutException {
        if (!runtimeEnabled) {
            cdp.send("Runtime.enable", null);
            runtimeEnabled = true;
        }
    }

    public void ensureRuntimeDisabled() throws TimeoutException {
        if (runtimeEnabled) {
            cdp.send("Runtime.disable", null);
            runtimeEnabled = false;
        }
    }

    private void ensureNetworkEnabled() throws TimeoutException {
        if (!networkEnabled) {
            cdp.send("Network.enable", null);
            networkEnabled = true;
        }
    }

    /**
     * Wires a CDP session into this page, making it functional for automation.
     *
     * <p>Package-private — called by {@link Browser#attachToPage(String)} after
     * a successful {@code Target.attachToTarget} call.</p>
     *
     * @param session the CDP session for this page's target
     * @throws IllegalArgumentException if session is null
     */
    void attachSession(CDPSession session) {
        if (session == null) {
            throw new IllegalArgumentException("CDPSession cannot be null");
        }
        this.cdp = session;
    }

    /**
     * Checks whether this page has an attached CDP session.
     *
     * <p>An unattached page is tracked by the browser but cannot execute
     * automation commands. Call {@link Browser#attachToPage(String)} to
     * attach it.</p>
     *
     * @return true if a CDP session is wired and commands can be sent
     */
    public boolean isAttached() {
        return cdp != null;
    }

    // ==================== Selector Type Detection ====================

    /**
     * Determines if a selector is XPath (vs CSS).
     *
     * <p>XPath selectors start with "/" (absolute or descendant) or "(" (for grouped expressions).
     * Everything else is treated as a CSS selector.</p>
     *
     * @param selector the selector string
     * @return true if the selector is XPath, false if CSS
     */
    private boolean isXPath(String selector) {
        if (selector == null || selector.isEmpty()) {
            return false;
        }
        String trimmed = selector.trim();
        return trimmed.startsWith("/") || trimmed.startsWith("(");
    }

    // ==================== Cursor Overlay ====================

    /**
     * Injects the cursor overlay script into the page.
     * This is called automatically on first mouse movement if enabled.
     */
    private void ensureCursorOverlayInjected() {
        if (!options.isShowCursorOverlay() || cursorOverlayInjected) {
            return;
        }

        try {
            ensurePageEnabled();

            // Inject script for future navigations
            JsonObject params = new JsonObject();
            params.addProperty("source", CURSOR_OVERLAY_SCRIPT);
            cdp.send("Page.addScriptToEvaluateOnNewDocument", params);

            // Also run immediately on current page
            ensureRuntimeEnabled();
            evaluate(CURSOR_OVERLAY_SCRIPT);

            cursorOverlayInjected = true;
        } catch (TimeoutException e) {
            System.err.println("[Page] Warning: Failed to inject cursor overlay: " + e.getMessage());
        }
    }

    /**
     * Updates the cursor overlay position.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     */
    private void updateCursorOverlay(double x, double y) {
        if (!options.isShowCursorOverlay()) {
            return;
        }

        try {
            evaluate(String.format("window.__nodriver4j_moveCursor(%f, %f)", x, y));
        } catch (TimeoutException e) {
            // Silently ignore - cursor overlay is non-critical
        }
    }

    /**
     * Triggers the click animation on the cursor overlay.
     */
    public void triggerCursorClickAnimation() {
        if (!options.isShowCursorOverlay()) {
            return;
        }

        try {
            evaluate("window.__nodriver4j_clickCursor()");
        } catch (TimeoutException e) {
            // Silently ignore - cursor overlay is non-critical
        }
    }

    // ==================== Cross-Origin Iframe Helpers ====================

    /**
     * Holds information about an iframe needed for interaction.
     */
    public record IframeInfo(String frameId, int backendNodeId, BoundingBox boundingBox, String url) {}

    /**
     * Finds an iframe by CSS selector and returns its CDP frame information.
     *
     * <p>This method is designed for cross-origin iframes (like reCAPTCHA) where
     * direct JavaScript access is blocked. It uses CDP's DOM domain to retrieve
     * the iframe's frameId directly from the DOM node, bypassing frame tree
     * limitations with cross-origin frames.</p>
     *
     * @param iframeSelector CSS selector for the iframe element
     * @return IframeInfo containing frameId, backendNodeId, and bounding box
     * @throws TimeoutException if iframe not found or CDP operations fail
     */
    public IframeInfo getIframeInfo(String iframeSelector) throws TimeoutException {
        return getIframeInfo(iframeSelector, 0);
    }

    /**
     * Finds an iframe by CSS selector and index (for when multiple iframes match).
     *
     * <p>Uses CDP DOM.describeNode to get frameId directly from the iframe element,
     * which works reliably for cross-origin iframes that may appear as about:blank
     * in the frame tree.</p>
     *
     * @param iframeSelector CSS selector for the iframe element
     * @param index          which matching iframe to select (0-based)
     * @return IframeInfo containing frameId, backendNodeId, and bounding box
     * @throws TimeoutException if iframe not found or CDP operations fail
     */
    public IframeInfo getIframeInfo(String iframeSelector, int index) throws TimeoutException {
        // Step 1: Get document root
        JsonObject docParams = new JsonObject();
        docParams.addProperty("depth", 0);
        JsonObject docResult = cdp.send("DOM.getDocument", docParams);
        int rootNodeId = docResult.getAsJsonObject("root").get("nodeId").getAsInt();

        // Step 2: Query for all matching iframes
        JsonObject queryParams = new JsonObject();
        queryParams.addProperty("nodeId", rootNodeId);
        queryParams.addProperty("selector", iframeSelector);

        JsonObject queryResult = cdp.send("DOM.querySelectorAll", queryParams);

        if (!queryResult.has("nodeIds")) {
            throw new TimeoutException("Iframe not found: " + iframeSelector);
        }

        JsonArray nodeIds = queryResult.getAsJsonArray("nodeIds");

        if (nodeIds.isEmpty() || nodeIds.size() <= index) {
            throw new TimeoutException("Iframe not found: " + iframeSelector + " at index " + index +
                    " (found " + nodeIds.size() + " matches)");
        }

        int iframeNodeId = nodeIds.get(index).getAsInt();

        // Step 3: Describe the iframe node to get frameId
        JsonObject describeParams = new JsonObject();
        describeParams.addProperty("nodeId", iframeNodeId);
        describeParams.addProperty("depth", 0);

        JsonObject describeResult = cdp.send("DOM.describeNode", describeParams);

        if (!describeResult.has("node")) {
            throw new TimeoutException("Could not describe iframe node: " + iframeSelector);
        }

        JsonObject node = describeResult.getAsJsonObject("node");

        // Verify it's an iframe
        String nodeName = node.has("nodeName") ? node.get("nodeName").getAsString() : "";
        if (!"IFRAME".equalsIgnoreCase(nodeName)) {
            throw new TimeoutException("Selector matched non-iframe element: " + nodeName);
        }

        // Get frameId from the node
        if (!node.has("frameId")) {
            throw new TimeoutException("Iframe node has no frameId - iframe may not be loaded yet");
        }

        String frameId = node.get("frameId").getAsString();
        int backendNodeId = node.has("backendNodeId") ? node.get("backendNodeId").getAsInt() : -1;

        // Step 4: Get bounding box via DOM.getBoxModel
        BoundingBox boundingBox = getNodeBoundingBox(backendNodeId);

        if (boundingBox == null) {
            // Fallback: try getting bounding box via JavaScript
            boundingBox = getIframeBoundingBoxViaJs(iframeSelector, index);
        }

        if (boundingBox == null) {
            throw new TimeoutException("Could not get bounding box for iframe: " + iframeSelector);
        }

        String iframeUrl = getIframeUrlFromNode(iframeNodeId);

        System.out.println("[Page] Found iframe: frameId=" + frameId +
                ", backendNodeId=" + backendNodeId + ", url=" + iframeUrl + ", bounds=" + boundingBox);

        return new IframeInfo(frameId, backendNodeId, boundingBox, iframeUrl);
    }

    /**
     * Gets the src URL from an iframe node.
     */
    private String getIframeUrlFromNode(int nodeId) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("nodeId", nodeId);
            JsonObject result = cdp.send("DOM.getAttributes", params);

            if (result.has("attributes")) {
                JsonArray attrs = result.getAsJsonArray("attributes");
                for (int i = 0; i < attrs.size() - 1; i += 2) {
                    if ("src".equals(attrs.get(i).getAsString())) {
                        return attrs.get(i + 1).getAsString();
                    }
                }
            }
        } catch (TimeoutException e) {
            // Ignore, URL is optional
        }
        return null;
    }

    /**
     * Gets an iframe's bounding box using JavaScript as a fallback.
     */
    private BoundingBox getIframeBoundingBoxViaJs(String iframeSelector, int index) {
        try {
            ensureRuntimeEnabled();

            String script = String.format(
                    "(function() {" +
                            "  var iframes = document.querySelectorAll(\"%s\");" +
                            "  if (!iframes || iframes.length <= %d) return null;" +
                            "  var iframe = iframes[%d];" +
                            "  var rect = iframe.getBoundingClientRect();" +
                            "  return JSON.stringify({x: rect.x, y: rect.y, width: rect.width, height: rect.height});" +
                            "})()",
                    escapeCss(iframeSelector), index, index
            );

            String result = evaluate(script);
            if (result == null || result.equals("null")) {
                return null;
            }

            JsonObject obj = JsonParser.parseString(result).getAsJsonObject();
            return new BoundingBox(
                    obj.get("x").getAsDouble(),
                    obj.get("y").getAsDouble(),
                    obj.get("width").getAsDouble(),
                    obj.get("height").getAsDouble()
            );
        } catch (TimeoutException e) {
            return null;
        }
    }

    /**
     * Creates an isolated JavaScript execution context within an iframe.
     *
     * @param frameId the CDP frameId of the iframe
     * @return the execution context ID for use with evaluateInFrame
     * @throws TimeoutException if context creation fails
     */
    public int createIframeContext(String frameId) throws TimeoutException {
        ensurePageEnabled();

        JsonObject params = new JsonObject();
        params.addProperty("frameId", frameId);
        params.addProperty("worldName", "nodriver4j_iframe_" + System.currentTimeMillis());

        JsonObject result = cdp.send("Page.createIsolatedWorld", params);
        return result.get("executionContextId").getAsInt();
    }


    /**
     * Evaluates JavaScript within an iframe's context.
     * Handles both same-origin (via createIsolatedWorld) and cross-origin OOPIFs (via session).
     *
     * @param iframeInfo the iframe info (must include URL for OOPIF support)
     * @param script     the JavaScript to execute
     * @return the result as a string, or null
     * @throws TimeoutException if evaluation fails
     */
    public String evaluateInFrame(IframeInfo iframeInfo, String script) throws TimeoutException {
        String frameId = iframeInfo.frameId();

        // Check if this is an OOPIF (not in frame tree)
        if (!isFrameInTree(frameId)) {
            // OOPIF - need to use session-based approach
            if (iframeInfo.url() == null) {
                throw new TimeoutException("Cannot evaluate in OOPIF without URL");
            }

            String sessionId = attachToOOPIF(iframeInfo.url());
            return evaluateInSession(sessionId, script);
        }

        // Same-origin iframe - use createIsolatedWorld
        int contextId = createIframeContext(frameId);
        return evaluateWithContext(contextId, script);
    }

    /**
     * Evaluates JavaScript within an iframe by frameId only (legacy method).
     * Note: For OOPIF support, prefer evaluateInFrame(IframeInfo, script).
     */
    public String evaluateInFrame(String frameId, String script) throws TimeoutException {
        // This method doesn't have URL info, so can only work for same-origin iframes
        int contextId = createIframeContext(frameId);
        return evaluateWithContext(contextId, script);
    }

    /**
     * Evaluates script in an OOPIF session.
     */
    private String evaluateInSession(String sessionId, String script) throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("expression", script);
        params.addProperty("returnByValue", true);
        params.addProperty("awaitPromise", true);

        JsonObject result = cdp.sendWithSession("Runtime.evaluate", params, sessionId);

        if (result.has("exceptionDetails")) {
            System.err.println("[Page] Script exception in OOPIF: " + result.getAsJsonObject("exceptionDetails"));
            return null;
        }

        if (result.has("result")) {
            JsonObject resultObj = result.getAsJsonObject("result");
            if (resultObj.has("value")) {
                JsonElement value = resultObj.get("value");
                if (value.isJsonNull()) return null;
                if (value.isJsonPrimitive()) return value.getAsString();
                return value.toString();
            }
        }
        return null;
    }

    /**
     * Evaluates with a specific execution context.
     */
    private String evaluateWithContext(int contextId, String script) throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("contextId", contextId);
        params.addProperty("expression", script);
        params.addProperty("returnByValue", true);
        params.addProperty("awaitPromise", true);

        JsonObject result = cdp.send("Runtime.evaluate", params);

        if (result.has("exceptionDetails")) {
            System.err.println("[Page] Script exception: " + result.getAsJsonObject("exceptionDetails"));
            return null;
        }

        if (result.has("result")) {
            JsonObject resultObj = result.getAsJsonObject("result");
            if (resultObj.has("value")) {
                JsonElement value = resultObj.get("value");
                if (value.isJsonNull()) return null;
                if (value.isJsonPrimitive()) return value.getAsString();
                return value.toString();
            }
        }
        return null;
    }

    /**
     * Gets an element's bounding box within an iframe.
     *
     * <p>The returned bounding box is relative to the iframe's origin, not the page.</p>
     *
     * @param iframeInfo the iframe info
     * @param selector   CSS selector for the element within the iframe
     * @return the element's bounding box (iframe-relative), or null if not found
     * @throws TimeoutException if the operation fails
     */
    public BoundingBox querySelectorInFrame(IframeInfo iframeInfo, String selector) throws TimeoutException {
        String script = String.format(
                "(function() {" +
                        "  var el = document.querySelector(\"%s\");" +
                        "  if (!el) return null;" +
                        "  var rect = el.getBoundingClientRect();" +
                        "  return JSON.stringify({x: rect.x, y: rect.y, width: rect.width, height: rect.height});" +
                        "})()",
                escapeCss(selector)
        );

        String result = evaluateInFrame(iframeInfo, script);
        if (result == null || result.equals("null")) {
            return null;
        }

        JsonObject obj = JsonParser.parseString(result).getAsJsonObject();
        return new BoundingBox(
                obj.get("x").getAsDouble(),
                obj.get("y").getAsDouble(),
                obj.get("width").getAsDouble(),
                obj.get("height").getAsDouble()
        );
    }

    /**
     * Gets the text content of an element within an iframe.
     *
     * @param iframeInfo  Holds information about an iframe needed for interaction
     * @param selector CSS selector for the element within the iframe
     * @return the element's text content, or null if not found
     * @throws TimeoutException if the operation fails
     */
    public String getTextInFrame(IframeInfo iframeInfo, String selector) throws TimeoutException {
        String script = String.format(
                "(function() {" +
                        "  var el = document.querySelector(\"%s\");" +
                        "  return el ? el.innerText || el.textContent : null;" +
                        "})()",
                escapeCss(selector)
        );

        return evaluateInFrame(iframeInfo, script);
    }

    /**
     * Checks if an element exists within an iframe.
     *
     * @param iframeInfo  Holds information about an iframe needed for interaction
     * @param selector CSS selector for the element within the iframe
     * @return true if the element exists
     * @throws TimeoutException if the operation fails
     */
    public boolean existsInFrame(IframeInfo iframeInfo, String selector) throws TimeoutException {
        String script = String.format(
                "document.querySelector(\"%s\") !== null",
                escapeCss(selector)
        );

        String result = evaluateInFrame(iframeInfo, script);
        return "true".equals(result);
    }

    /**
     * Checks if an element has a specific CSS class within an iframe.
     *
     * @param iframeInfo Holds information about an iframe needed for interaction
     * @param selector  CSS selector for the element within the iframe
     * @param className the class name to check for
     * @return true if the element has the class
     * @throws TimeoutException if the operation fails
     */
    public boolean hasClassInFrame(IframeInfo iframeInfo, String selector, String className) throws TimeoutException {
        String script = String.format(
                "(function() {" +
                        "  var el = document.querySelector(\"%s\");" +
                        "  return el ? el.classList.contains('%s') : false;" +
                        "})()",
                escapeCss(selector), escapeJs(className)
        );

        String result = evaluateInFrame(iframeInfo, script);
        return "true".equals(result);
    }

    /**
     * Clicks an element inside a cross-origin iframe using CDP Input events.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Gets the iframe's position on the page</li>
     *   <li>Gets the element's position within the iframe</li>
     *   <li>Calculates absolute coordinates</li>
     *   <li>Performs a human-like click at those coordinates</li>
     * </ol>
     *
     * @param iframeInfo the iframe information (from {@link #getIframeInfo})
     * @param selector   CSS selector for the element within the iframe
     * @throws TimeoutException if the element cannot be found or clicked
     */
    public void clickInFrame(IframeInfo iframeInfo, String selector) throws TimeoutException {
        // Get element position within iframe - use IframeInfo version
        BoundingBox elementBox = querySelectorInFrame(iframeInfo, selector);
        if (elementBox == null) {
            throw new TimeoutException("Element not found in iframe: " + selector);
        }

        // Calculate absolute position
        BoundingBox iframeBox = iframeInfo.boundingBox();
        BoundingBox absoluteBox = new BoundingBox(
                iframeBox.getX() + elementBox.getX(),
                iframeBox.getY() + elementBox.getY(),
                elementBox.getWidth(),
                elementBox.getHeight()
        );

        System.out.println("[Page] Clicking in iframe at absolute position: " + absoluteBox);

        // Use existing click behavior
        clickAtBox(absoluteBox);
    }

    /**
     * Clicks an element inside a cross-origin iframe (convenience method).
     *
     * @param iframeSelector CSS selector for the iframe
     * @param elementSelector CSS selector for the element within the iframe
     * @throws TimeoutException if the element cannot be found or clicked
     */
    public void clickInFrame(String iframeSelector, String elementSelector) throws TimeoutException {
        clickInFrame(iframeSelector, 0, elementSelector);
    }

    /**
     * Clicks an element inside a cross-origin iframe at a specific index.
     *
     * @param iframeSelector  CSS selector for the iframe
     * @param iframeIndex     which matching iframe to use (0-based)
     * @param elementSelector CSS selector for the element within the iframe
     * @throws TimeoutException if the element cannot be found or clicked
     */
    public void clickInFrame(String iframeSelector, int iframeIndex, String elementSelector) throws TimeoutException {
        IframeInfo iframeInfo = getIframeInfo(iframeSelector, iframeIndex);
        clickInFrame(iframeInfo, elementSelector);
    }

    /**
     * Dispatches a mouse down event at the specified position.
     *
     * <p>This sends a low-level CDP Input.dispatchMouseEvent with type "mousePressed".
     * Use this for press-and-hold interactions where you need separate control over
     * mouse down and mouse up events.</p>
     *
     * @param position the position to press at
     * @throws TimeoutException if the operation times out
     */
    public void mouseDown(Vector position) throws TimeoutException {
        dispatchMouseButton(position, "mousePressed", "left", 1);
    }

    /**
     * Dispatches a mouse up event at the specified position.
     *
     * <p>This sends a low-level CDP Input.dispatchMouseEvent with type "mouseReleased".
     * Use this for press-and-hold interactions where you need separate control over
     * mouse down and mouse up events.</p>
     *
     * @param position the position to release at
     * @throws TimeoutException if the operation times out
     */
    public void mouseUp(Vector position) throws TimeoutException {
        dispatchMouseButton(position, "mouseReleased", "left", 1);
    }

    /**
     * Takes a screenshot of a region within an iframe.
     *
     * @param iframeInfo the iframe information
     * @param selector   CSS selector for the element within the iframe
     * @return the screenshot as PNG bytes
     * @throws TimeoutException if the operation fails
     */
    public byte[] screenshotElementInFrame(IframeInfo iframeInfo, String selector) throws TimeoutException {
        // Get element position within iframe
        BoundingBox elementBox = querySelectorInFrame(iframeInfo, selector);
        if (elementBox == null) {
            throw new TimeoutException("Element not found in iframe: " + selector);
        }

        // Calculate absolute position
        BoundingBox iframeBox = iframeInfo.boundingBox();
        BoundingBox absoluteBox = new BoundingBox(
                iframeBox.getX() + elementBox.getX(),
                iframeBox.getY() + elementBox.getY(),
                elementBox.getWidth(),
                elementBox.getHeight()
        );

        return screenshotRegionBytes(absoluteBox);
    }

    /**
     * Attaches to an OOPIF target by URL and returns the session ID.
     *
     * @param targetUrl the URL of the iframe to attach to
     * @return the session ID for the attached target
     * @throws TimeoutException if attachment fails
     */
    private String attachToOOPIF(String targetUrl) throws TimeoutException {
        // Check cache first
        String cached = oopifSessions.get(targetUrl);
        if (cached != null) {
            return cached;
        }

        // Get all targets
        JsonObject targetsResult = cdp.send("Target.getTargets", null);
        JsonArray targetInfos = targetsResult.getAsJsonArray("targetInfos");

        String targetId = null;
        for (JsonElement elem : targetInfos) {
            JsonObject target = elem.getAsJsonObject();
            String type = target.get("type").getAsString();
            String url = target.has("url") ? target.get("url").getAsString() : "";

            // Match iframe targets by URL
            if ("iframe".equals(type) && urlMatchesTarget(targetUrl, url)) {
                targetId = target.get("targetId").getAsString();
                System.out.println("[Page] Found OOPIF target: " + targetId + " for URL: " + url);
                break;
            }
        }

        if (targetId == null) {
            throw new TimeoutException("No OOPIF target found for URL: " + targetUrl);
        }

        // Attach to the target
        JsonObject attachParams = new JsonObject();
        attachParams.addProperty("targetId", targetId);
        attachParams.addProperty("flatten", true);

        JsonObject attachResult = cdp.send("Target.attachToTarget", attachParams);
        String sessionId = attachResult.get("sessionId").getAsString();

        System.out.println("[Page] Attached to OOPIF, sessionId: " + sessionId);

        // Cache it
        oopifSessions.put(targetUrl, sessionId);

        return sessionId;
    }

    /**
     * Checks if a target URL matches an iframe src URL.
     * Compares base path and key query parameters to distinguish between
     * multiple similar iframes (e.g., multiple reCAPTCHA instances).
     */
    private boolean urlMatchesTarget(String iframeSrc, String targetUrl) {
        if (iframeSrc == null || targetUrl == null) return false;

        try {
            // Parse both URLs
            java.net.URI srcUri = java.net.URI.create(iframeSrc);
            java.net.URI targetUri = java.net.URI.create(targetUrl);

            // Compare scheme, host, and path (must match exactly)
            if (!java.util.Objects.equals(srcUri.getScheme(), targetUri.getScheme()) ||
                    !java.util.Objects.equals(srcUri.getHost(), targetUri.getHost()) ||
                    !java.util.Objects.equals(srcUri.getPath(), targetUri.getPath())) {
                return false;
            }

            // Parse query parameters
            java.util.Map<String, String> srcParams = parseQueryParams(srcUri.getQuery());
            java.util.Map<String, String> targetParams = parseQueryParams(targetUri.getQuery());

            // For reCAPTCHA and similar services, match on key identifying parameters
            // 'k' = site key (unique per reCAPTCHA instance)
            // 'size' = normal vs invisible
            String[] keyParams = {"k", "size"};

            for (String param : keyParams) {
                String srcValue = srcParams.get(param);
                String targetValue = targetParams.get(param);

                // If the source URL has this param, target must match
                if (srcValue != null && !srcValue.equals(targetValue)) {
                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            // Fallback to simple comparison if URL parsing fails
            return iframeSrc.equals(targetUrl);
        }
    }

    /**
     * Parses query string into a map of key-value pairs.
     */
    private java.util.Map<String, String> parseQueryParams(String query) {
        java.util.Map<String, String> params = new java.util.HashMap<>();

        if (query == null || query.isBlank()) {
            return params;
        }

        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = pair.substring(0, idx);
                String value = idx < pair.length() - 1 ? pair.substring(idx + 1) : "";
                params.put(key, value);
            }
        }

        return params;
    }

    /**
     * Checks if a frameId exists in the current frame tree.
     */
    private boolean isFrameInTree(String frameId) throws TimeoutException {
        ensurePageEnabled();
        JsonObject frameTreeResult = cdp.send("Page.getFrameTree", null);
        return findFrameInTree(frameTreeResult.getAsJsonObject("frameTree"), frameId);
    }

    private boolean findFrameInTree(JsonObject frameTree, String targetFrameId) {
        JsonObject frame = frameTree.getAsJsonObject("frame");
        if (targetFrameId.equals(frame.get("id").getAsString())) {
            return true;
        }
        if (frameTree.has("childFrames")) {
            for (JsonElement child : frameTree.getAsJsonArray("childFrames")) {
                if (findFrameInTree(child.getAsJsonObject(), targetFrameId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Gets the bounding box of a DOM node via CDP using its backend node ID.
     *
     * @param backendNodeId the backend node ID from DOM domain
     * @return the bounding box, or null if unable to get
     * @throws TimeoutException if CDP operation times out
     */
    public BoundingBox getNodeBoundingBox(int backendNodeId) throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("backendNodeId", backendNodeId);

        JsonObject result = cdp.send("DOM.getBoxModel", params);

        if (!result.has("model")) {
            System.err.println("[Page] DOM.getBoxModel returned no model");
            return null;
        }

        JsonObject model = result.getAsJsonObject("model");

        // The "content" quad is an array of 8 numbers: [x1,y1, x2,y2, x3,y3, x4,y4]
        // representing the 4 corners. For a non-rotated rectangle, we can use x1,y1 as top-left
        if (!model.has("content")) {
            System.err.println("[Page] Box model has no content quad");
            return null;
        }

        JsonArray contentQuad = model.getAsJsonArray("content");

        // Extract coordinates from quad [x1,y1, x2,y2, x3,y3, x4,y4]
        double x1 = contentQuad.get(0).getAsDouble();
        double y1 = contentQuad.get(1).getAsDouble();
        double x2 = contentQuad.get(2).getAsDouble();
        double y3 = contentQuad.get(5).getAsDouble();

        double width = x2 - x1;
        double height = y3 - y1;

        return new BoundingBox(x1, y1, width, height);
    }

    /**
     * Scrolls the page to bring a bounding box into view if it's outside the viewport.
     *
     * <p>This method calculates whether the element is visible within the current
     * viewport (with a margin) and scrolls if necessary to center it.</p>
     *
     * @param box the bounding box of the element to scroll into view
     * @throws TimeoutException if operations time out
     */
    public void scrollIntoViewIfNeeded(BoundingBox box) throws TimeoutException {
        ensureRuntimeEnabled();

        String viewportHeightStr = evaluate("window.innerHeight");
        String viewportWidthStr = evaluate("window.innerWidth");

        int vpHeight = Integer.parseInt(viewportHeightStr);
        int vpWidth = Integer.parseInt(viewportWidthStr);

        // Check if element is in viewport (with some margin)
        int margin = 50;
        boolean inViewport = box.getTop() >= margin &&
                box.getLeft() >= margin &&
                box.getBottom() <= vpHeight - margin &&
                box.getRight() <= vpWidth - margin;

        if (!inViewport) {
            System.out.println("[Page] Element not in viewport, scrolling...");

            // Calculate scroll needed to center the element
            int deltaY = 0;
            int deltaX = 0;

            if (box.getTop() < margin) {
                deltaY = (int) box.getTop() - vpHeight / 2;
            } else if (box.getBottom() > vpHeight - margin) {
                deltaY = (int) (box.getBottom() - (double) vpHeight / 2);
            }

            if (box.getLeft() < margin) {
                deltaX = (int) box.getLeft() - vpWidth / 2;
            } else if (box.getRight() > vpWidth - margin) {
                deltaX = (int) (box.getRight() - (double) vpWidth / 2);
            }

            if (deltaX != 0 || deltaY != 0) {
                scrollBy(deltaX, deltaY);
                sleep(500); // Allow scroll to settle
            }
        }
    }

    // ==================== Navigation ====================

    /**
     * Navigates to a URL and waits for the page to load.
     *
     * @param url the URL to navigate to
     * @throws TimeoutException if navigation times out
     */
    public void navigate(String url) throws TimeoutException {
        navigate(url, DEFAULT_NAVIGATION_TIMEOUT);
    }

    /**
     * Navigates to a URL and waits for the page to load.
     *
     * @param url       the URL to navigate to
     * @param timeoutMs timeout in milliseconds
     * @throws TimeoutException if navigation times out
     */
    public void navigate(String url, int timeoutMs) throws TimeoutException {
        ensurePageEnabled();

        JsonObject params = new JsonObject();
        params.addProperty("url", url);
        cdp.send("Page.navigate", params);

        waitForLoadEvent(timeoutMs);
    }

    /**
     * Reloads the current page.
     *
     * @throws TimeoutException if reload times out
     */
    public void reload() throws TimeoutException {
        reload(false, DEFAULT_NAVIGATION_TIMEOUT);
    }

    /**
     * Reloads the current page.
     *
     * @param ignoreCache if true, bypasses cache
     * @param timeoutMs   timeout in milliseconds
     * @throws TimeoutException if reload times out
     */
    public void reload(boolean ignoreCache, int timeoutMs) throws TimeoutException {
        ensurePageEnabled();

        JsonObject params = new JsonObject();
        params.addProperty("ignoreCache", ignoreCache);
        cdp.send("Page.reload", params);

        waitForLoadEvent(timeoutMs);
    }

    /**
     * Navigates back in history.
     *
     * @throws TimeoutException if navigation times out
     */
    public void goBack() throws TimeoutException {
        ensurePageEnabled();

        JsonObject history = cdp.send("Page.getNavigationHistory", null);
        int currentIndex = history.get("currentIndex").getAsInt();

        if (currentIndex > 0) {
            JsonArray entries = history.getAsJsonArray("entries");
            JsonObject previousEntry = entries.get(currentIndex - 1).getAsJsonObject();
            int entryId = previousEntry.get("id").getAsInt();

            JsonObject params = new JsonObject();
            params.addProperty("entryId", entryId);
            cdp.send("Page.navigateToHistoryEntry", params);

            waitForLoadEvent(DEFAULT_NAVIGATION_TIMEOUT);
        }
    }

    /**
     * Navigates forward in history.
     *
     * @throws TimeoutException if navigation times out
     */
    public void goForward() throws TimeoutException {
        ensurePageEnabled();

        JsonObject history = cdp.send("Page.getNavigationHistory", null);
        int currentIndex = history.get("currentIndex").getAsInt();
        JsonArray entries = history.getAsJsonArray("entries");

        if (currentIndex < entries.size() - 1) {
            JsonObject nextEntry = entries.get(currentIndex + 1).getAsJsonObject();
            int entryId = nextEntry.get("id").getAsInt();

            JsonObject params = new JsonObject();
            params.addProperty("entryId", entryId);
            cdp.send("Page.navigateToHistoryEntry", params);

            waitForLoadEvent(DEFAULT_NAVIGATION_TIMEOUT);
        }
    }

    /**
     * Gets the current page URL.
     *
     * @return the current URL
     * @throws TimeoutException if the operation times out
     */
    public String currentUrl() throws TimeoutException {
        ensureRuntimeEnabled();
        return evaluate("window.location.href");
    }

    /**
     * Gets the current page URL.
     *
     * @return the current URL
     * @throws TimeoutException if the operation times out
     * @deprecated Use {@link #currentUrl()} instead
     */
    @Deprecated
    public String getCurrentUrl() throws TimeoutException {
        return currentUrl();
    }

    /**
     * Gets the current page title.
     *
     * @return the page title
     * @throws TimeoutException if the operation times out
     */
    public String title() throws TimeoutException {
        ensureRuntimeEnabled();
        return evaluate("document.title");
    }

    /**
     * Gets the current page title.
     *
     * @return the page title
     * @throws TimeoutException if the operation times out
     * @deprecated Use {@link #title()} instead
     */
    @Deprecated
    public String getTitle() throws TimeoutException {
        return title();
    }

    public void waitForLoadEvent(int timeoutMs) throws TimeoutException {
        try {
            cdp.waitForEvent("Page.loadEventFired", timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Some pages may not fire loadEventFired reliably
            System.err.println("[Page] Warning: Page load timeout, continuing...");
        }
    }

    // ==================== Element Queries (XPath + CSS) ====================

    /**
     * Finds the first element matching the selector.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @return the element's bounding box, or null if not found
     * @throws TimeoutException if the operation times out
     */
    public BoundingBox querySelector(String selector) throws TimeoutException {
        return querySelector(selector, options.getDefaultTimeout());
    }

    /**
     * Finds the first element matching the selector.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector  the XPath or CSS selector
     * @param timeoutMs timeout to wait for element
     * @return the element's bounding box, or null if not found
     * @throws TimeoutException if the operation times out
     */
    public BoundingBox querySelector(String selector, int timeoutMs) throws TimeoutException {
        ensureRuntimeEnabled();

        String script = isXPath(selector)
                ? buildXPathScript(selector, false)
                : buildCssScript(selector, false);

        long deadline = System.currentTimeMillis() + timeoutMs;
        int retryCount = 0;

        do {
            try {
                String result = evaluate(script);

                if (result != null && !result.equals("null") && !result.isEmpty()) {
                    return parseBoundingBox(result);
                }
            } catch (Exception e) {
                // Element not found yet, retry
            }

            if (timeoutMs == 0 || retryCount >= options.getMaxRetries()) {
                break;
            }

            retryCount++;
            sleep(options.getRetryInterval());
        } while (System.currentTimeMillis() < deadline);

        return null;
    }

    /**
     * Finds all elements matching the selector.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @return list of element bounding boxes
     * @throws TimeoutException if the operation times out
     */
    public List<BoundingBox> querySelectorAll(String selector) throws TimeoutException {
        ensureRuntimeEnabled();

        String script = isXPath(selector)
                ? buildXPathScript(selector, true)
                : buildCssScript(selector, true);

        String result = evaluate(script);

        List<BoundingBox> boxes = new ArrayList<>();
        if (result != null && !result.equals("null") && !result.equals("[]")) {
            // Parse JSON array of bounding boxes
            JsonArray array = com.google.gson.JsonParser.parseString(result).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                boxes.add(new BoundingBox(
                        obj.get("x").getAsDouble(),
                        obj.get("y").getAsDouble(),
                        obj.get("width").getAsDouble(),
                        obj.get("height").getAsDouble()
                ));
            }
        }

        return boxes;
    }

    /**
     * Checks if an element exists.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @return true if element exists
     * @throws TimeoutException if the operation times out
     */
    public boolean exists(String selector) throws TimeoutException {
        ensureRuntimeEnabled();

        String script;
        if (isXPath(selector)) {
            script = String.format(
                    "document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue !== null",
                    escapeXPath(selector)
            );
        } else {
            script = String.format(
                    "document.querySelector(\"%s\") !== null",
                    escapeCss(selector)
            );
        }

        String result = evaluate(script);
        return "true".equals(result);
    }

    /**
     * Checks if an element exists by CSS selector.
     *
     * @param selector the CSS selector
     * @return true if element exists
     * @throws TimeoutException if the operation times out
     * @deprecated Use {@link #exists(String)} instead - it auto-detects selector type
     */
    @Deprecated
    public boolean existsCss(String selector) throws TimeoutException {
        return exists(selector);
    }

    /**
     * Checks if an element is visible.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @return true if element is visible
     * @throws TimeoutException if the operation times out
     */
    public boolean isVisible(String selector) throws TimeoutException {
        ensureRuntimeEnabled();

        String script;
        if (isXPath(selector)) {
            script = String.format(
                    "(function() {" +
                            "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                            "  if (!el) return false;" +
                            "  var style = window.getComputedStyle(el);" +
                            "  var rect = el.getBoundingClientRect();" +
                            "  return style.display !== 'none' && " +
                            "         style.visibility !== 'hidden' && " +
                            "         style.opacity !== '0' && " +
                            "         rect.width > 0 && rect.height > 0;" +
                            "})()",
                    escapeXPath(selector)
            );
        } else {
            script = String.format(
                    "(function() {" +
                            "  var el = document.querySelector(\"%s\");" +
                            "  if (!el) return false;" +
                            "  var style = window.getComputedStyle(el);" +
                            "  var rect = el.getBoundingClientRect();" +
                            "  return style.display !== 'none' && " +
                            "         style.visibility !== 'hidden' && " +
                            "         style.opacity !== '0' && " +
                            "         rect.width > 0 && rect.height > 0;" +
                            "})()",
                    escapeCss(selector)
            );
        }

        String result = evaluate(script);
        return "true".equals(result);
    }

    /**
     * Gets the inner text of an element.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @return the inner text, or null if not found
     * @throws TimeoutException if the operation times out
     */
    public String getText(String selector) throws TimeoutException {
        ensureRuntimeEnabled();

        String script;
        if (isXPath(selector)) {
            script = String.format(
                    "(function() {" +
                            "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                            "  return el ? el.innerText : null;" +
                            "})()",
                    escapeXPath(selector)
            );
        } else {
            script = String.format(
                    "(function() {" +
                            "  var el = document.querySelector(\"%s\");" +
                            "  return el ? el.innerText : null;" +
                            "})()",
                    escapeCss(selector)
            );
        }

        return evaluate(script);
    }

    /**
     * Gets an attribute value of an element.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector  the XPath or CSS selector
     * @param attribute the attribute name
     * @return the attribute value, or null if not found
     * @throws TimeoutException if the operation times out
     */
    public String getAttribute(String selector, String attribute) throws TimeoutException {
        ensureRuntimeEnabled();

        String script;
        if (isXPath(selector)) {
            script = String.format(
                    "(function() {" +
                            "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                            "  return el ? el.getAttribute('%s') : null;" +
                            "})()",
                    escapeXPath(selector), escapeJs(attribute)
            );
        } else {
            script = String.format(
                    "(function() {" +
                            "  var el = document.querySelector(\"%s\");" +
                            "  return el ? el.getAttribute('%s') : null;" +
                            "})()",
                    escapeCss(selector), escapeJs(attribute)
            );
        }

        return evaluate(script);
    }

    /**
     * Gets the value of an input element.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @return the input value, or null if not found
     * @throws TimeoutException if the operation times out
     */
    public String getValue(String selector) throws TimeoutException {
        ensureRuntimeEnabled();

        String script;
        if (isXPath(selector)) {
            script = String.format(
                    "(function() {" +
                            "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                            "  return el ? el.value : null;" +
                            "})()",
                    escapeXPath(selector)
            );
        } else {
            script = String.format(
                    "(function() {" +
                            "  var el = document.querySelector(\"%s\");" +
                            "  return el ? el.value : null;" +
                            "})()",
                    escapeCss(selector)
            );
        }

        return evaluate(script);
    }

    /**
     * Validates that an input field contains the expected value.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector      the XPath or CSS selector
     * @param expectedValue the expected value
     * @return true if the actual value matches the expected value
     * @throws TimeoutException if the operation times out
     */
    public boolean validateValue(String selector, String expectedValue) throws TimeoutException {
        String actualValue = getValue(selector);
        if (actualValue == null && expectedValue == null) {
            return true;
        }
        if (actualValue == null || expectedValue == null) {
            return false;
        }
        return actualValue.equals(expectedValue);
    }

    /**
     * Validates that an element's text content exactly matches the expected text.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * <p>This performs an exact match comparison. For substring matching, use
     * {@link #getText(String)} and perform your own comparison.</p>
     *
     * @param selector     the XPath or CSS selector
     * @param expectedText the exact text expected
     * @return true if the element's innerText exactly matches expectedText
     * @throws TimeoutException if the operation times out
     */
    public boolean containsText(String selector, String expectedText) throws TimeoutException {
        String actualText = getText(selector);

        if (actualText == null && expectedText == null) {
            return true;
        }
        if (actualText == null || expectedText == null) {
            return false;
        }

        return actualText.equals(expectedText);
    }

    /**
     * Validates that an element's text content exactly matches the expected text,
     * ignoring leading and trailing whitespace.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector     the XPath or CSS selector
     * @param expectedText the exact text expected (will be trimmed for comparison)
     * @return true if the element's trimmed innerText matches trimmed expectedText
     * @throws TimeoutException if the operation times out
     */
    public boolean containsTextTrimmed(String selector, String expectedText) throws TimeoutException {
        String actualText = getText(selector);

        if (actualText == null && expectedText == null) {
            return true;
        }
        if (actualText == null || expectedText == null) {
            return false;
        }

        return actualText.trim().equals(expectedText.trim());
    }

    // ==================== Script Builders ====================

    private String buildXPathScript(String xpath, boolean multiple) {
        if (multiple) {
            return String.format(
                    "(function() {" +
                            "  var result = [];" +
                            "  var nodes = document.evaluate(\"%s\", document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);" +
                            "  for (var i = 0; i < nodes.snapshotLength; i++) {" +
                            "    var rect = nodes.snapshotItem(i).getBoundingClientRect();" +
                            "    result.push({x: rect.x, y: rect.y, width: rect.width, height: rect.height});" +
                            "  }" +
                            "  return JSON.stringify(result);" +
                            "})()",
                    escapeXPath(xpath)
            );
        } else {
            return String.format(
                    "(function() {" +
                            "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                            "  if (!el) return null;" +
                            "  var rect = el.getBoundingClientRect();" +
                            "  return JSON.stringify({x: rect.x, y: rect.y, width: rect.width, height: rect.height});" +
                            "})()",
                    escapeXPath(xpath)
            );
        }
    }

    private String buildCssScript(String cssSelector, boolean multiple) {
        if (multiple) {
            return String.format(
                    "(function() {" +
                            "  var result = [];" +
                            "  var nodes = document.querySelectorAll(\"%s\");" +
                            "  for (var i = 0; i < nodes.length; i++) {" +
                            "    var rect = nodes[i].getBoundingClientRect();" +
                            "    result.push({x: rect.x, y: rect.y, width: rect.width, height: rect.height});" +
                            "  }" +
                            "  return JSON.stringify(result);" +
                            "})()",
                    escapeCss(cssSelector)
            );
        } else {
            return String.format(
                    "(function() {" +
                            "  var el = document.querySelector(\"%s\");" +
                            "  if (!el) return null;" +
                            "  var rect = el.getBoundingClientRect();" +
                            "  return JSON.stringify({x: rect.x, y: rect.y, width: rect.width, height: rect.height});" +
                            "})()",
                    escapeCss(cssSelector)
            );
        }
    }

    private BoundingBox parseBoundingBox(String json) {
        JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        return new BoundingBox(
                obj.get("x").getAsDouble(),
                obj.get("y").getAsDouble(),
                obj.get("width").getAsDouble(),
                obj.get("height").getAsDouble()
        );
    }

    private String escapeXPath(String xpath) {
        return xpath.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escapeCss(String selector) {
        return selector.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escapeJs(String str) {
        return str.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
    }

    // ==================== Waiting ====================

    /**
     * Waits for an amount of time with a 10% margin of error.
     *
     * @param ms the amount of time in Milliseconds
     */
    public void sleep(long ms) {
        try {
            Thread.sleep((long) ((ms - (ms * 0.1)) + (Math.random() * (ms * 0.2))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits for an element to appear.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @return the element's bounding box
     * @throws TimeoutException if element doesn't appear within timeout
     */
    public BoundingBox waitForSelector(String selector) throws TimeoutException {
        return waitForSelector(selector, options.getDefaultTimeout());
    }

    /**
     * Waits for an element to appear.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector  the XPath or CSS selector
     * @param timeoutMs timeout in milliseconds
     * @return the element's bounding box
     * @throws TimeoutException if element doesn't appear within timeout
     */
    public BoundingBox waitForSelector(String selector, int timeoutMs) throws TimeoutException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            BoundingBox box = querySelector(selector, 0);
            if (box != null && box.isValid()) {
                return box;
            }
            sleep(options.getRetryInterval());
        }

        throw new TimeoutException("Element not found: " + selector);
    }

    /**
     * Waits for an element to disappear.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @throws TimeoutException if element doesn't disappear within timeout
     */
    public void waitForSelectorHidden(String selector) throws TimeoutException {
        waitForSelectorHidden(selector, options.getDefaultTimeout());
    }

    /**
     * Waits for an element to disappear.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector  the XPath or CSS selector
     * @param timeoutMs timeout in milliseconds
     * @throws TimeoutException if element doesn't disappear within timeout
     */
    public void waitForSelectorHidden(String selector, int timeoutMs) throws TimeoutException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (!exists(selector) || !isVisible(selector)) {
                return;
            }
            sleep(options.getRetryInterval());
        }

        throw new TimeoutException("Element still visible: " + selector);
    }

    public void waitForVisible(String selector) throws TimeoutException {
        waitForVisible(selector, options.getDefaultTimeout());
    }

    public void waitForVisible(String selector, int timeoutMs) throws TimeoutException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if(exists(selector) && isVisible(selector)) {
                return;
            }
            sleep(options.getRetryInterval());
        }
    }

    // ==================== Clickability Checks ====================

    /**
     * Checks if an element is ready to be clicked by performing the same
     * validation steps as {@link #click(String)}.
     *
     * <p>This method mirrors the click process exactly:</p>
     * <ol>
     *   <li>Scrolls the element into view (same as click)</li>
     *   <li>Waits for a valid bounding box (same as click)</li>
     * </ol>
     *
     * <p><strong>Note:</strong> This method has a side effect - it scrolls the page
     * if the element is not in the viewport. This is necessary to accurately predict
     * whether {@link #click(String)} will succeed, as scrolling can change element state
     * (lazy loading, viewport-dependent rendering, etc.).</p>
     *
     * <p>Supports both XPath and CSS selectors.</p>
     *
     * @param selector the XPath or CSS selector
     * @return true if click() would succeed on this element
     */
    public boolean isClickable(String selector) {
        return isClickable(selector, options.getDefaultTimeout());
    }

    /**
     * Checks if an element is ready to be clicked within a specified timeout.
     *
     * <p>Mirrors {@link #click(String)} behavior:</p>
     * <ol>
     *   <li>Scrolls element into view via {@link #scrollIntoView(String)}</li>
     *   <li>Checks for valid bounding box via {@link #querySelector(String, int)} or
     *       {@link #waitForSelector(String, int)}</li>
     * </ol>
     *
     * @param selector  the XPath or CSS selector
     * @param timeoutMs maximum time to wait for element to become clickable (0 for immediate check)
     * @return true if click() would succeed on this element
     */
    public boolean isClickable(String selector, int timeoutMs) {
        try {
            // Step 1: Same as click() - scroll element into view
            // This may trigger lazy loading, change element state, etc.
            scrollIntoView(selector);

            // Step 2: Same as click() - get bounding box
            BoundingBox box;
            if (timeoutMs <= 0) {
                // Immediate check - querySelector directly
                // (waitForSelector with 0 timeout would throw immediately)
                box = querySelector(selector, 0);
            } else {
                // Wait for element - same as click() uses waitForSelector
                box = waitForSelector(selector, timeoutMs);
            }

            // Step 3: Same validation as waitForSelector
            return box != null && box.isValid();

        } catch (TimeoutException e) {
            // scrollIntoView or waitForSelector failed
            return false;
        }
    }

    /**
     * Waits for an element to become clickable.
     *
     * <p>This method performs the same steps as {@link #click(String)} but throws
     * on failure instead of returning false. Use this in automation flows where
     * failure should halt execution.</p>
     *
     * @param selector the XPath or CSS selector
     * @return the element's bounding box (ready for clicking)
     * @throws TimeoutException if element doesn't become clickable within timeout
     */
    public BoundingBox waitForClickable(String selector) throws TimeoutException {
        return waitForClickable(selector, options.getDefaultTimeout());
    }

    /**
     * Waits for an element to become clickable.
     *
     * @param selector  the XPath or CSS selector
     * @param timeoutMs timeout in milliseconds
     * @return the element's bounding box (ready for clicking)
     * @throws TimeoutException if element doesn't become clickable within timeout
     */
    public BoundingBox waitForClickable(String selector, int timeoutMs) throws TimeoutException {
        // Step 1: Same as click() - scroll into view
        scrollIntoView(selector);

        // Step 2: Same as click() - wait for valid box
        return waitForSelector(selector, timeoutMs);
    }

    /**
     * Waits for a navigation event.
     *
     * @throws TimeoutException if navigation doesn't occur within timeout
     */
    public void waitForNavigation() throws TimeoutException {
        waitForNavigation(DEFAULT_NAVIGATION_TIMEOUT);
    }

    /**
     * Waits for a navigation event.
     *
     * @param timeoutMs timeout in milliseconds
     * @throws TimeoutException if navigation doesn't occur within timeout
     */
    public void waitForNavigation(int timeoutMs) throws TimeoutException {
        ensurePageEnabled();
        cdp.waitForEvent("Page.frameNavigated", timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Waits for network to become idle.
     *
     * @param idleTimeMs time with no network activity to consider idle
     * @throws TimeoutException if network doesn't become idle within timeout
     */
    public void waitForNetworkIdle(int idleTimeMs) throws TimeoutException {
        waitForNetworkIdle(idleTimeMs, options.getDefaultTimeout());
    }

    /**
     * Waits for network to become idle.
     *
     * @param idleTimeMs time with no network activity to consider idle
     * @param timeoutMs  total timeout
     * @throws TimeoutException if network doesn't become idle within timeout
     */
    public void waitForNetworkIdle(int idleTimeMs, int timeoutMs) throws TimeoutException {
        ensureNetworkEnabled();

        // Simple implementation: just wait for the idle time
        // A more sophisticated implementation would track active requests
        sleep(idleTimeMs);
    }

    // ==================== Mouse Interaction ====================

    /**
     * Clicks on an element with human-like mouse movement.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @throws TimeoutException if the operation times out
     */
    public void click(String selector) throws TimeoutException {
        scrollIntoView(selector);
        BoundingBox box = waitForSelector(selector);
        clickAtBox(box);
    }

    /**
     * Clicks at a specific position.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @throws TimeoutException if the operation times out
     */
    public void clickAt(double x, double y) throws TimeoutException {
        Vector target = new Vector(x, y);
        moveMouseTo(target);
        performClick(target);
    }

    /**
     * Clicks within a bounding box with human-like behavior.
     *
     * @param box the target bounding box
     * @throws TimeoutException if the operation times out
     */
    public void clickAtBox(BoundingBox box) throws TimeoutException {
        // Get random point within the box (biased toward center)
        Vector target = box.getRandomPoint(options.getPaddingPercentage());

        // Move mouse to target
        moveMouseTo(target);

        // Perform click
        performClick(target);
    }

    /**
     * Hovers over an element with human-like mouse movement.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @throws TimeoutException if the operation times out
     */
    public void hover(String selector) throws TimeoutException {
        BoundingBox box = waitForSelector(selector);
        Vector target = box.getRandomPoint(options.getPaddingPercentage());
        moveMouseTo(target);
    }

    /**
     * Moves the mouse to a position with human-like movement.
     *
     * @param target the target position
     * @throws TimeoutException if the operation times out
     */
    public void moveMouseTo(Vector target) throws TimeoutException {
        // Ensure cursor overlay is injected on first mouse movement
        ensureCursorOverlayInjected();

        if (!options.isSimulateMousePath()) {
            // Direct move
            dispatchMouseMove(target);
            mousePosition = target;
            return;
        }

        // Check for overshoot
        if (options.isOvershootEnabled() &&
                HumanBehavior.shouldOvershoot(mousePosition, target, options.getOvershootThreshold())) {

            // Move to overshoot point first
            Vector overshootPoint = HumanBehavior.calculateOvershoot(target, options.getOvershootRadius());
            moveAlongPath(mousePosition, overshootPoint, null);

            // Then move to actual target with tighter spread
            moveAlongPath(overshootPoint, target, HumanBehavior.OVERSHOOT_SPREAD);
        } else {
            // Direct path to target
            moveAlongPath(mousePosition, target, null);
        }

        // Apply move delay
        if (options.getMoveDelayMax() > 0) {
            int delay = options.isRandomizeMoveDelay()
                    ? HumanBehavior.randomDelay(options.getMoveDelayMin(), options.getMoveDelayMax())
                    : options.getMoveDelayMax();
            sleep(delay);
        }
    }

    private void moveAlongPath(Vector from, Vector to, Double spreadOverride) throws TimeoutException {
        Integer moveSpeed = options.getMoveSpeed() > 0 ? options.getMoveSpeed() : null;
        List<Vector> path = HumanBehavior.generatePath(from, to, moveSpeed, null, spreadOverride);

        for (Vector point : path) {
            Vector finalPoint = point;

            // Add jitter if enabled
            if (options.isJitterEnabled()) {
                finalPoint = point.addJitter(options.getJitterAmount());
            }

            dispatchMouseMove(finalPoint);
            mousePosition = finalPoint;
        }
    }

    private void performClick(Vector position) throws TimeoutException {
        // Pre-click hesitation
        int hesitation = HumanBehavior.hesitationDelay(
                options.getPreClickDelayMin(), options.getPreClickDelayMax());
        sleep(hesitation);

        // Trigger click animation on cursor overlay
        triggerCursorClickAnimation();

        ensureRuntimeDisabled();

        // Mouse down
        dispatchMouseButton(position, "mousePressed", "left", 1);

        // Hold duration
        int holdDuration = HumanBehavior.clickHoldDuration(
                options.getClickHoldDurationMin(), options.getClickHoldDurationMax());
        sleep(holdDuration);

        // Mouse up
        dispatchMouseButton(position, "mouseReleased", "left", 1);
    }

    private void dispatchMouseMove(Vector position) throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("type", "mouseMoved");
        params.addProperty("x", position.getX());
        params.addProperty("y", position.getY());

        cdp.send("Input.dispatchMouseEvent", params);

        // Update cursor overlay position
        updateCursorOverlay(position.getX(), position.getY());
    }

    private void dispatchMouseButton(Vector position, String type, String button, int clickCount)
            throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("type", type);
        params.addProperty("x", position.getX());
        params.addProperty("y", position.getY());
        params.addProperty("button", button);
        params.addProperty("clickCount", clickCount);

        cdp.send("Input.dispatchMouseEvent", params);
    }

    /**
     * Clicks an element by directly invoking its {@code .click()} method via JavaScript.
     *
     * <p>Unlike {@link #click(String)}, which scrolls the element into view and dispatches
     * synthetic mouse events at the element's coordinates, this method executes
     * {@code element.click()} directly through CDP's {@code Runtime.evaluate}. This makes
     * it immune to overlays, popups, scroll position issues, or other elements intercepting
     * the click.</p>
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @throws TimeoutException if the element is not found or the operation times out
     */
    public void jsClick(String selector) throws TimeoutException {
        ensureRuntimeEnabled();


        String script;
        if (isXPath(selector)) {
            script = String.format(
                    "(function() {" +
                            "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                            "  if (!el) return 'NOT_FOUND';" +
                            "  el.click();" +
                            "  return 'OK';" +
                            "})()",
                    escapeXPath(selector)
            );
        } else {
            script = String.format(
                    "(function() {" +
                            "  var el = document.querySelector(\"%s\");" +
                            "  if (!el) return 'NOT_FOUND';" +
                            "  el.click();" +
                            "  return 'OK';" +
                            "})()",
                    escapeCss(selector)
            );
        }

        String result = evaluate(script);

        if (!"OK".equals(result)) {
            throw new TimeoutException("Element not found for jsClick: " + selector);
        }
    }

    // ==================== Keyboard Interaction ====================

    /**
     * Types text into the focused element with human-like timing.
     *
     * @param text the text to type
     * @throws TimeoutException if the operation times out
     */
    public void type(String text) throws TimeoutException {
        type(text, 1.0);
    }

    /**
     * Types text into the focused element with human-like timing, scaled by a speed multiplier.
     *
     * <p>For multipliers below 2.0, each character is typed individually via keyDown/keyUp
     * events with scaled delays. For multipliers of 2.0 and above, characters are inserted
     * in bursts via {@code Input.insertText} to reduce CDP call frequency while maintaining
     * the correct overall typing rate.</p>
     *
     * <p>Burst size is determined by {@code floor(multiplier)}, and any fractional remainder
     * is applied as additional delay scaling. For example, a multiplier of 2.4 sends 2-character
     * bursts at 20% faster delays than a 2.0 multiplier, achieving the correct 2.4x speed.</p>
     *
     * <p>Special characters like newlines ({@code \n}) are never included in bursts — they
     * flush any pending burst and are dispatched individually as Enter key presses.</p>
     *
     * @param text            the text to type
     * @param speedMultiplier typing speed multiplier (e.g., 2.0 = twice as fast, 0.5 = half speed)
     * @throws TimeoutException         if the operation times out
     * @throws IllegalArgumentException if speedMultiplier is not positive
     */
    public void type(String text, double speedMultiplier) throws TimeoutException {
        if (speedMultiplier <= 0) {
            throw new IllegalArgumentException("speedMultiplier must be positive, got: " + speedMultiplier);
        }

        int burstSize = Math.max(1, (int) Math.floor(speedMultiplier));
        double delayMultiplier = speedMultiplier / burstSize;
        boolean useBurstMode = burstSize >= 2;

        int scaledKeystrokeMin = Math.max(1, (int) (options.getKeystrokeDelayMin() / delayMultiplier));
        int scaledKeystrokeMax = Math.max(scaledKeystrokeMin, (int) (options.getKeystrokeDelayMax() / delayMultiplier));

        Character previousChar = null;
        int i = 0;

        while (i < text.length()) {
            char c = text.charAt(i);

            // Calculate delay based on current character context
            int delay;
            if (options.isContextAwareTyping()) {
                delay = HumanBehavior.keystrokeDelay(c, previousChar, scaledKeystrokeMin, scaledKeystrokeMax);
            } else {
                delay = HumanBehavior.keystrokeDelay(scaledKeystrokeMin, scaledKeystrokeMax);
            }

            // Occasional thinking pause (scaled by delay multiplier)
            int thinkingPause = HumanBehavior.thinkingPause(
                    options.getThinkingPauseProbability(),
                    options.getThinkingPauseMin(),
                    options.getThinkingPauseMax());
            if (thinkingPause > 0) {
                sleep((int) Math.max(1, thinkingPause / delayMultiplier));
            }

            // Dispatch character(s)
            if (c == '\n') {
                pressKey("Enter", false, false, false);
                previousChar = c;
                i++;
            } else if (useBurstMode) {
                // Collect a burst of printable characters
                StringBuilder burst = new StringBuilder();
                burst.append(c);
                int j = i + 1;
                while (j < text.length() && burst.length() < burstSize) {
                    char next = text.charAt(j);
                    if (next == '\n') {
                        break;
                    }
                    burst.append(next);
                    j++;
                }

                insertText(burst.toString());
                previousChar = burst.charAt(burst.length() - 1);
                i = j;
            } else {
                // Single character mode (multiplier < 2.0)
                boolean needsShift = isShiftRequired(c);
                String key = String.valueOf(c);
                pressKey(key, false, false, needsShift);
                previousChar = c;
                i++;
            }

            sleep(delay);
        }
    }

    /**
     * Inserts text directly into the focused element via CDP's Input.insertText.
     *
     * <p>Unlike {@link #pressKey}, this does not dispatch individual keyDown/keyUp events.
     * It inserts the text atomically as a single CDP call, making it suitable for
     * burst typing where per-character key events would overwhelm the CDP connection.</p>
     *
     * @param text the text to insert
     * @throws TimeoutException if the CDP call times out
     */
    private void insertText(String text) throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("text", text);
        cdp.send("Input.insertText", params);
    }

    /**
     * Clears the content of an input element.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @throws TimeoutException if the operation times out
     */
    public void clear(String selector) throws TimeoutException {
        click(selector);
        sleep(50);

        // Select all and delete
        pressKey("a", true, false, false); // Ctrl+A
        pressKey("Backspace", false, false, false);
    }

    /**
     * Focuses an element.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @throws TimeoutException if the operation times out
     */
    public void focus(String selector) throws TimeoutException {
        ensureRuntimeEnabled();

        String script;
        if (isXPath(selector)) {
            script = String.format(
                    "(function() {" +
                            "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                            "  if (el) el.focus();" +
                            "})()",
                    escapeXPath(selector)
            );
        } else {
            script = String.format(
                    "(function() {" +
                            "  var el = document.querySelector(\"%s\");" +
                            "  if (el) el.focus();" +
                            "})()",
                    escapeCss(selector)
            );
        }

        evaluate(script);
    }

    /**
     * Selects an option from a dropdown.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector for the select element
     * @param value    the value to select
     * @throws TimeoutException if the operation times out
     */
    public void select(String selector, String value) throws TimeoutException {
        ensureRuntimeEnabled();

        String script;
        if (isXPath(selector)) {
            script = String.format(
                    "(function() {" +
                            "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                            "  if (el) {" +
                            "    el.value = '%s';" +
                            "    el.dispatchEvent(new Event('change', { bubbles: true }));" +
                            "  }" +
                            "})()",
                    escapeXPath(selector), escapeJs(value)
            );
        } else {
            script = String.format(
                    "(function() {" +
                            "  var el = document.querySelector(\"%s\");" +
                            "  if (el) {" +
                            "    el.value = '%s';" +
                            "    el.dispatchEvent(new Event('change', { bubbles: true }));" +
                            "  }" +
                            "})()",
                    escapeCss(selector), escapeJs(value)
            );
        }

        evaluate(script);
    }

    /**
     * Presses a key with optional modifiers using full CDP parameters for realistic simulation.
     *
     * @param key   the key to press (character like "a" or special key like "Backspace", "Enter")
     * @param ctrl  if true, hold Ctrl
     * @param alt   if true, hold Alt
     * @param shift if true, hold Shift
     * @throws TimeoutException if the operation times out
     */
    public void pressKey(String key, boolean ctrl, boolean alt, boolean shift) throws TimeoutException {
        int modifiers = 0;
        if (alt) modifiers |= 1;
        if (ctrl) modifiers |= 2;
        if (shift) modifiers |= 8;

        String code = getKeyCode(key);
        int windowsVirtualKeyCode = getWindowsVirtualKeyCode(key);

        // Determine if this is a printable character that should insert text
        // Text should only be inserted for printable characters without Ctrl/Alt modifiers
        String textToInsert = null;
        if (!ctrl && !alt && isPrintableCharacter(key)) {
            textToInsert = key;
        } else if ("Enter".equals(key)) {
            textToInsert = "\r";
        }

        JsonObject keyDown = new JsonObject();
        keyDown.addProperty("type", "keyDown");
        keyDown.addProperty("key", key);
        keyDown.addProperty("code", code);
        keyDown.addProperty("windowsVirtualKeyCode", windowsVirtualKeyCode);
        keyDown.addProperty("nativeVirtualKeyCode", windowsVirtualKeyCode);
        keyDown.addProperty("modifiers", modifiers);

        // Add text property for printable characters (required for actual text input)
        if (textToInsert != null) {
            keyDown.addProperty("text", textToInsert);
        }

        cdp.send("Input.dispatchKeyEvent", keyDown);

        JsonObject keyUp = new JsonObject();
        keyUp.addProperty("type", "keyUp");
        keyUp.addProperty("key", key);
        keyUp.addProperty("code", code);
        keyUp.addProperty("windowsVirtualKeyCode", windowsVirtualKeyCode);
        keyUp.addProperty("nativeVirtualKeyCode", windowsVirtualKeyCode);
        keyUp.addProperty("modifiers", modifiers);
        cdp.send("Input.dispatchKeyEvent", keyUp);
    }

    /**
     * Determines if a key represents a printable character that should insert text.
     *
     * @param key the key string
     * @return true if this key should insert text into an input field
     */
    private boolean isPrintableCharacter(String key) {
        if (key == null || key.length() != 1) {
            return false;
        }

        char c = key.charAt(0);

        // Printable ASCII characters (space through tilde)
        // Space (32) through ~ (126)
        return c >= 32 && c <= 126;
    }

    /**
     * Gets the physical key code (e.g., "KeyA", "Digit1", "Backspace") for CDP.
     *
     * @param key the key character or name
     * @return the physical key code
     */
    private String getKeyCode(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }

        // Special keys
        return switch (key) {
            case "Backspace" -> "Backspace";
            case "Tab" -> "Tab";
            case "Enter" -> "Enter";
            case "Shift" -> "ShiftLeft";
            case "Control" -> "ControlLeft";
            case "Alt" -> "AltLeft";
            case "Escape" -> "Escape";
            case " " -> "Space";
            case "ArrowLeft" -> "ArrowLeft";
            case "ArrowUp" -> "ArrowUp";
            case "ArrowRight" -> "ArrowRight";
            case "ArrowDown" -> "ArrowDown";
            case "Delete" -> "Delete";
            case "Home" -> "Home";
            case "End" -> "End";
            case "PageUp" -> "PageUp";
            case "PageDown" -> "PageDown";
            default -> {
                if (key.length() == 1) {
                    char c = key.charAt(0);
                    // Letters
                    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                        yield "Key" + Character.toUpperCase(c);
                    }
                    // Digits
                    if (c >= '0' && c <= '9') {
                        yield "Digit" + c;
                    }
                    // Common symbols (US keyboard layout)
                    yield switch (c) {
                        case '-', '_' -> "Minus";
                        case '=', '+' -> "Equal";
                        case '[', '{' -> "BracketLeft";
                        case ']', '}' -> "BracketRight";
                        case '\\', '|' -> "Backslash";
                        case ';', ':' -> "Semicolon";
                        case '\'', '"' -> "Quote";
                        case ',', '<' -> "Comma";
                        case '.', '>' -> "Period";
                        case '/', '?' -> "Slash";
                        case '`', '~' -> "Backquote";
                        case '!', '@', '#', '$', '%', '^', '&', '*', '(', ')' -> "Digit" + getShiftedDigit(c);
                        default -> "Unidentified";
                    };
                }
                yield key; // Return as-is for unknown keys
            }
        };
    }

    /**
     * Gets the digit that produces a shifted symbol.
     */
    private char getShiftedDigit(char symbol) {
        return switch (symbol) {
            case '!' -> '1';
            case '@' -> '2';
            case '#' -> '3';
            case '$' -> '4';
            case '%' -> '5';
            case '^' -> '6';
            case '&' -> '7';
            case '*' -> '8';
            case '(' -> '9';
            case ')' -> '0';
            default -> '0';
        };
    }

    /**
     * Gets the Windows virtual key code for a key.
     *
     * @param key the key character or name
     * @return the virtual key code
     */
    private int getWindowsVirtualKeyCode(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }

        // Special keys
        return switch (key) {
            case "Backspace" -> 8;
            case "Tab" -> 9;
            case "Enter" -> 13;
            case "Shift" -> 16;
            case "Control" -> 17;
            case "Alt" -> 18;
            case "Escape" -> 27;
            case " " -> 32;
            case "PageUp" -> 33;
            case "PageDown" -> 34;
            case "End" -> 35;
            case "Home" -> 36;
            case "ArrowLeft" -> 37;
            case "ArrowUp" -> 38;
            case "ArrowRight" -> 39;
            case "ArrowDown" -> 40;
            case "Delete" -> 46;
            default -> {
                if (key.length() == 1) {
                    char c = key.charAt(0);
                    // Letters (A-Z = 65-90, both upper and lower use uppercase code)
                    if (c >= 'a' && c <= 'z') {
                        yield c - 'a' + 65;
                    }
                    if (c >= 'A' && c <= 'Z') {
                        yield c - 'A' + 65;
                    }
                    // Digits (0-9 = 48-57)
                    if (c >= '0' && c <= '9') {
                        yield c;
                    }
                    // Shifted digit symbols use the digit's key code
                    yield switch (c) {
                        case '!' -> 49;  // 1
                        case '@' -> 50;  // 2
                        case '#' -> 51;  // 3
                        case '$' -> 52;  // 4
                        case '%' -> 53;  // 5
                        case '^' -> 54;  // 6
                        case '&' -> 55;  // 7
                        case '*' -> 56;  // 8
                        case '(' -> 57;  // 9
                        case ')' -> 48;  // 0
                        case '-', '_' -> 189;
                        case '=', '+' -> 187;
                        case '[', '{' -> 219;
                        case ']', '}' -> 221;
                        case '\\', '|' -> 220;
                        case ';', ':' -> 186;
                        case '\'', '"' -> 222;
                        case ',', '<' -> 188;
                        case '.', '>' -> 190;
                        case '/', '?' -> 191;
                        case '`', '~' -> 192;
                        default -> 0;
                    };
                }
                yield 0;
            }
        };
    }

    /**
     * Determines if Shift is required to type a character.
     */
    private boolean isShiftRequired(char c) {
        // Uppercase letters
        if (c >= 'A' && c <= 'Z') {
            return true;
        }
        // Shifted symbols
        return "~!@#$%^&*()_+{}|:\"<>?".indexOf(c) >= 0;
    }

    // ==================== Scrolling ====================

    /**
     * Scrolls by a relative amount.
     *
     * @param deltaX horizontal scroll amount (positive = right)
     * @param deltaY vertical scroll amount (positive = down)
     * @throws TimeoutException if the operation times out
     */
    public void scrollBy(int deltaX, int deltaY) throws TimeoutException {
        int tickPixels = options.getScrollTickPixels();
        int tickCount = HumanBehavior.scrollTickCount(
                Math.max(Math.abs(deltaX), Math.abs(deltaY)), tickPixels);

        int xDirection = deltaX >= 0 ? 1 : -1;
        int yDirection = deltaY >= 0 ? 1 : -1;

        int remainingX = Math.abs(deltaX);
        int remainingY = Math.abs(deltaY);

        for (int i = 0; i < tickCount && (remainingX > 0 || remainingY > 0); i++) {
            int tickX = Math.min(remainingX,
                    HumanBehavior.scrollTickAmount(tickPixels, options.getScrollTickVariance()));
            int tickY = Math.min(remainingY,
                    HumanBehavior.scrollTickAmount(tickPixels, options.getScrollTickVariance()));

            dispatchScroll(tickX * xDirection, tickY * yDirection);

            remainingX -= tickX;
            remainingY -= tickY;

            // Delay between ticks
            int delay = HumanBehavior.randomDelay(
                    options.getScrollDelayMin(), options.getScrollDelayMax());
            sleep(delay);
        }
    }

    public void scrollTo(int x, int y) throws TimeoutException {
        ensureRuntimeEnabled();

        // Get current scroll position
        String currentX = evaluate("window.scrollX");
        String currentY = evaluate("window.scrollY");

        int deltaX = x - (int) Double.parseDouble(currentX);
        int deltaY = y - (int) Double.parseDouble(currentY);

        scrollBy(deltaX, deltaY);
    }

    /**
     * Scrolls an element into view.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @throws TimeoutException if the operation times out
     */
    public void scrollIntoView(String selector) throws TimeoutException {
        BoundingBox box = querySelector(selector);
        if (box == null) {
            throw new TimeoutException("Element not found: " + selector);
        }

        // Check if already in viewport
        ensureRuntimeEnabled();
        String viewportHeight = evaluate("window.innerHeight");
        String viewportWidth = evaluate("window.innerWidth");

        int vpHeight = Integer.parseInt(viewportHeight);
        int vpWidth = Integer.parseInt(viewportWidth);

        boolean inViewport = box.getTop() >= 0 && box.getLeft() >= 0 &&
                box.getBottom() <= vpHeight && box.getRight() <= vpWidth;

        if (!inViewport) {
            // Calculate scroll needed
            int deltaY = 0;
            int deltaX = 0;

            if (box.getTop() < 0) {
                deltaY = (int) box.getTop() - 50; // Scroll up with margin
            } else if (box.getBottom() > vpHeight) {
                deltaY = (int) (box.getBottom() - vpHeight) + 50; // Scroll down with margin
            }

            if (box.getLeft() < 0) {
                deltaX = (int) box.getLeft() - 50;
            } else if (box.getRight() > vpWidth) {
                deltaX = (int) (box.getRight() - vpWidth) + 50;
            }

            scrollBy(deltaX, deltaY);
        }
    }

    /**
     * Scrolls to the top of the page.
     *
     * @throws TimeoutException if the operation times out
     */
    public void scrollToTop() throws TimeoutException {
        scrollTo(0, 0);
    }

    /**
     * Scrolls to the bottom of the page.
     *
     * @throws TimeoutException if the operation times out
     */
    public void scrollToBottom() throws TimeoutException {
        ensureRuntimeEnabled();
        String height = evaluate("document.body.scrollHeight");
        scrollTo(0, Integer.parseInt(height));
    }

    private void dispatchScroll(int deltaX, int deltaY) throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("type", "mouseWheel");
        params.addProperty("x", mousePosition.getX());
        params.addProperty("y", mousePosition.getY());
        params.addProperty("deltaX", deltaX);
        params.addProperty("deltaY", deltaY);

        cdp.send("Input.dispatchMouseEvent", params);
    }

    /**
     * Fills a form field with click, delay, type, delay pattern.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector      the XPath or CSS selector
     * @param value         the value to type
     * @param preTypeDelay  delay before typing (ms)
     * @param postTypeDelay delay after typing (ms)
     * @throws TimeoutException     if the operation times out
     * @throws InterruptedException if interrupted during sleep
     */
    public void fillFormField(String selector, String value, long preTypeDelay, long postTypeDelay)
            throws TimeoutException, InterruptedException {
        fillFormField(selector, value, preTypeDelay, postTypeDelay, 1.0);
    }

    /**
     * Fills a form field with click, delay, type, delay pattern at a scaled typing speed.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector        the XPath or CSS selector
     * @param value           the value to type
     * @param preTypeDelay    delay before typing (ms)
     * @param postTypeDelay   delay after typing (ms)
     * @param speedMultiplier typing speed multiplier (e.g., 2.0 = twice as fast)
     * @throws TimeoutException     if the operation times out
     * @throws InterruptedException if interrupted during sleep
     */
    public void fillFormField(String selector, String value, long preTypeDelay, long postTypeDelay,
                              double speedMultiplier) throws TimeoutException, InterruptedException {
        click(selector);
        sleep(preTypeDelay);
        type(value, speedMultiplier);
        sleep(postTypeDelay);
    }

    // ==================== JavaScript Execution ====================

    /**
     * Evaluates JavaScript in the page context.
     *
     * @param script the JavaScript to execute
     * @return the result as a string, or null
     * @throws TimeoutException if the operation times out
     */
    public String evaluate(String script) throws TimeoutException {
        ensureRuntimeEnabled();

        JsonObject params = new JsonObject();
        params.addProperty("expression", script);
        params.addProperty("returnByValue", true);
        params.addProperty("awaitPromise", true);

        JsonObject result = cdp.send("Runtime.evaluate", params);

        if (result.has("result")) {
            JsonObject resultObj = result.getAsJsonObject("result");
            if (resultObj.has("value")) {
                JsonElement value = resultObj.get("value");
                if (value.isJsonNull()) {
                    return null;
                }
                if (value.isJsonPrimitive()) {
                    return value.getAsString();
                }
                return value.toString();
            }
        }

        return null;
    }

    /**
     * Evaluates JavaScript and returns a boolean result.
     *
     * @param script the JavaScript to execute
     * @return the boolean result
     * @throws TimeoutException if the operation times out
     */
    public boolean evaluateBoolean(String script) throws TimeoutException {
        String result = evaluate(script);
        return "true".equals(result);
    }

    /**
     * Evaluates JavaScript and returns an integer result.
     *
     * @param script the JavaScript to execute
     * @return the integer result
     * @throws TimeoutException if the operation times out
     */
    public int evaluateInt(String script) throws TimeoutException {
        String result = evaluate(script);
        return result != null ? Integer.parseInt(result) : 0;
    }

    public String frameTree() throws TimeoutException {
        JsonObject result = cdp.send("Page.getFrameTree", null);
        return result.toString();
    }

    /**
     * @deprecated Use {@link #frameTree()} instead
     */
    @Deprecated
    public String getFrameTree() throws TimeoutException {
        return frameTree();
    }

    // ==================== Image Extraction ====================

    /**
     * Data container for extracted image information.
     *
     * @param base64   the image data as base64 string (no data URL prefix)
     * @param width    the natural/intrinsic width of the image in pixels
     * @param height   the natural/intrinsic height of the image in pixels
     * @param mimeType the MIME type of the image (e.g., "image/jpeg", "image/png")
     */
    public record ImageData(String base64, int width, int height, String mimeType) {

        /**
         * Checks if the image has the expected square dimensions.
         *
         * @param expectedSize the expected width and height in pixels
         * @return true if both width and height match the expected size
         */
        public boolean hasExpectedSize(int expectedSize) {
            return width == expectedSize && height == expectedSize;
        }

        /**
         * Returns the image size as a formatted string (e.g., "300x300").
         *
         * @return the dimensions string
         */
        public String dimensionsString() {
            return width + "x" + height;
        }
    }

    /**
     * Fetches an image from within an iframe and returns it as base64 data.
     *
     * <p>This method uses the browser's fetch API to retrieve the image at its
     * native/intrinsic resolution, bypassing any CSS scaling applied in the DOM.</p>
     *
     * <p>The image's natural dimensions are read from the img element's
     * {@code naturalWidth} and {@code naturalHeight} properties.</p>
     *
     * @param iframeInfo  the iframe containing the image
     * @param imgSelector CSS selector for the img element within the iframe
     * @return ImageData containing the base64 data and dimensions
     * @throws TimeoutException if the image cannot be found or fetched
     */
    public ImageData fetchImageInFrame(IframeInfo iframeInfo, String imgSelector) throws TimeoutException {
        String script = buildFetchImageScript(imgSelector);
        String result = evaluateInFrame(iframeInfo, script);

        if (result == null || result.isBlank()) {
            throw new TimeoutException("Failed to fetch image: no result from script");
        }

        return parseImageDataResult(result);
    }

    /**
     * Fetches an image from the main page and returns it as base64 data.
     *
     * @param imgSelector CSS selector for the img element
     * @return ImageData containing the base64 data and dimensions
     * @throws TimeoutException if the image cannot be found or fetched
     */
    public ImageData fetchImage(String imgSelector) throws TimeoutException {
        ensureRuntimeEnabled();

        String script = buildFetchImageScript(imgSelector);
        String result = evaluate(script);

        if (result == null || result.isBlank()) {
            throw new TimeoutException("Failed to fetch image: no result from script");
        }

        return parseImageDataResult(result);
    }

    /**
     * Builds the JavaScript to fetch an image and return its data.
     */
    private String buildFetchImageScript(String imgSelector) {
        return String.format("""
        (async function() {
            var img = document.querySelector("%s");
            if (!img) {
                return JSON.stringify({error: "Image element not found"});
            }
            
            // Wait for image to load if not complete
            if (!img.complete) {
                await new Promise((resolve, reject) => {
                    img.onload = resolve;
                    img.onerror = () => reject(new Error("Image failed to load"));
                    // Timeout after 10 seconds
                    setTimeout(() => reject(new Error("Image load timeout")), 10000);
                });
            }
            
            var naturalWidth = img.naturalWidth;
            var naturalHeight = img.naturalHeight;
            
            if (naturalWidth === 0 || naturalHeight === 0) {
                return JSON.stringify({error: "Image has zero dimensions"});
            }
            
            try {
                var response = await fetch(img.src, {credentials: 'include'});
                if (!response.ok) {
                    return JSON.stringify({error: "Fetch failed: " + response.status});
                }
                
                var blob = await response.blob();
                var mimeType = blob.type || "image/unknown";
                
                return new Promise((resolve) => {
                    var reader = new FileReader();
                    reader.onloadend = function() {
                        var base64 = reader.result.split(',')[1];
                        resolve(JSON.stringify({
                            base64: base64,
                            width: naturalWidth,
                            height: naturalHeight,
                            mimeType: mimeType
                        }));
                    };
                    reader.onerror = function() {
                        resolve(JSON.stringify({error: "FileReader error"}));
                    };
                    reader.readAsDataURL(blob);
                });
            } catch (e) {
                return JSON.stringify({error: "Fetch error: " + e.message});
            }
        })();
        """, escapeCss(imgSelector));
    }

    /**
     * Parses the JSON result from the fetch image script.
     */
    private ImageData parseImageDataResult(String jsonResult) throws TimeoutException {
        try {
            JsonObject json = JsonParser.parseString(jsonResult).getAsJsonObject();

            // Check for error
            if (json.has("error")) {
                throw new TimeoutException("Failed to fetch image: " + json.get("error").getAsString());
            }

            // Extract fields
            String base64 = json.get("base64").getAsString();
            int width = json.get("width").getAsInt();
            int height = json.get("height").getAsInt();
            String mimeType = json.has("mimeType") ? json.get("mimeType").getAsString() : "image/unknown";

            return new ImageData(base64, width, height, mimeType);

        } catch (Exception e) {
            if (e instanceof TimeoutException) {
                throw (TimeoutException) e;
            }
            throw new TimeoutException("Failed to parse image data: " + e.getMessage());
        }
    }

    // ==================== Screenshots ====================

    /**
     * Takes a screenshot and saves it to the screenshots directory.
     *
     * @throws TimeoutException if screenshot capture times out
     * @throws IOException      if file cannot be written
     */
    public void screenshot() throws TimeoutException, IOException {
        byte[] pngBytes = screenshotBytes();

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String filename = "screenshot_" + timestamp + ".png";

        Path outputPath = Path.of("screenshots", filename);
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, pngBytes);

        System.out.println("[Page] Screenshot saved to: " + outputPath);
    }



    /**
     * Takes a screenshot of a specific region.
     *
     * @param box the bounding box defining the region to capture
     * @return the screenshot as PNG bytes
     * @throws TimeoutException if the operation times out
     * @throws IllegalArgumentException if box is null or invalid
     */
    public byte[] screenshotRegionBytes(BoundingBox box) throws TimeoutException {
        if (box == null) {
            throw new IllegalArgumentException("BoundingBox cannot be null");
        }
        if (!box.isValid()) {
            throw new IllegalArgumentException("BoundingBox is invalid: " + box);
        }

        ensurePageEnabled();

        JsonObject clip = new JsonObject();
        clip.addProperty("x", box.getX());
        clip.addProperty("y", box.getY());
        clip.addProperty("width", box.getWidth());
        clip.addProperty("height", box.getHeight());
        clip.addProperty("scale", 1);

        JsonObject params = new JsonObject();
        params.addProperty("format", "png");
        params.add("clip", clip);

        JsonObject result = cdp.send("Page.captureScreenshot", params);
        String data = result.get("data").getAsString();

        return Base64.getDecoder().decode(data);
    }

    /**
     * Takes a screenshot of the page.
     *
     * @return the screenshot as PNG bytes
     * @throws TimeoutException if the operation times out
     */
    public byte[] screenshotBytes() throws TimeoutException {
        ensurePageEnabled();

        JsonObject params = new JsonObject();
        params.addProperty("format", "png");

        JsonObject result = cdp.send("Page.captureScreenshot", params);
        String data = result.get("data").getAsString();

        return Base64.getDecoder().decode(data);
    }

    /**
     * Takes a screenshot of a specific element.
     *
     * <p>Supports both XPath and CSS selectors. XPath selectors start with "/" or "(".</p>
     *
     * @param selector the XPath or CSS selector
     * @return the screenshot as PNG bytes
     * @throws TimeoutException if the operation times out or element not found
     */
    public byte[] screenshotElementBytes(String selector) throws TimeoutException {
        BoundingBox box = querySelector(selector);
        if (box == null) {
            throw new TimeoutException("Element not found: " + selector);
        }
        return screenshotRegionBytes(box);
    }

    // ==================== Cookies ====================

    /**
     * Gets all cookies for the current page.
     *
     * @return list of cookies as JSON objects
     * @throws TimeoutException if the operation times out
     */
    public List<JsonObject> getCookies() throws TimeoutException {
        ensureNetworkEnabled();

        JsonObject result = cdp.send("Network.getAllCookies", null);
        JsonArray cookiesArray = result.getAsJsonArray("cookies");

        List<JsonObject> cookies = new ArrayList<>();
        for (JsonElement element : cookiesArray) {
            cookies.add(element.getAsJsonObject());
        }

        return cookies;
    }

    /**
     * Sets a cookie.
     *
     * @param name   cookie name
     * @param value  cookie value
     * @param domain cookie domain
     * @throws TimeoutException if the operation times out
     */
    public void setCookie(String name, String value, String domain) throws TimeoutException {
        setCookie(name, value, domain, "/", false, false);
    }

    /**
     * Sets a cookie with full options.
     *
     * @param name     cookie name
     * @param value    cookie value
     * @param domain   cookie domain
     * @param path     cookie path
     * @param secure   if true, cookie is secure
     * @param httpOnly if true, cookie is httpOnly
     * @throws TimeoutException if the operation times out
     */
    public void setCookie(String name, String value, String domain, String path,
                          boolean secure, boolean httpOnly) throws TimeoutException {
        ensureNetworkEnabled();

        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.addProperty("value", value);
        params.addProperty("domain", domain);
        params.addProperty("path", path);
        params.addProperty("secure", secure);
        params.addProperty("httpOnly", httpOnly);

        cdp.send("Network.setCookie", params);
    }

    /**
     * Deletes all cookies.
     *
     * @throws TimeoutException if the operation times out
     */
    public void deleteCookies() throws TimeoutException {
        ensureNetworkEnabled();
        cdp.send("Network.clearBrowserCookies", null);
    }

    /**
     * Deletes a specific cookie.
     *
     * @param name   cookie name
     * @param domain cookie domain
     * @throws TimeoutException if the operation times out
     */
    public void deleteCookie(String name, String domain) throws TimeoutException {
        ensureNetworkEnabled();

        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.addProperty("domain", domain);

        cdp.send("Network.deleteCookies", params);
    }

    public Browser browser(){
        return browser;
    }

    // ==================== Utility Methods ====================

    private void sleep(int ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return String.format("Page{targetId=%s}", targetId);
    }
}