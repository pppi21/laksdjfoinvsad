package org.nodriver4j.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.nodriver4j.cdp.CDPClient;
import org.nodriver4j.math.BoundingBox;
import org.nodriver4j.math.HumanBehavior;
import org.nodriver4j.math.Vector;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
 *   <li>Captcha: {@link #solvePressHoldCaptcha} for PerimeterX-style challenges</li>
 * </ul>
 *
 * <p>All interactions use realistic timing and movement patterns based on
 * human behavior research to avoid bot detection.</p>
 *
 * <p>Page instances are created and managed by {@link Browser}.</p>
 */
public class Page {

    private static final int DEFAULT_NAVIGATION_TIMEOUT = 30000;

    // ==================== Captcha Constants ====================

    /** Selector for PerimeterX captcha shadow host */
    private static final String PX_CAPTCHA_SELECTOR = "#px-captcha";

    /** Time to wait for animation style to apply after mousedown */
    private static final int CAPTCHA_INITIAL_WAIT_MS = 150;

    /** Buffer time after animation ends before releasing */
    private static final int CAPTCHA_BUFFER_MS = 600;

    /** Default hold duration if animation parsing fails */
    private static final int CAPTCHA_DEFAULT_DURATION_MS = 10000;

    /** Default timeout waiting for captcha to appear */
    private static final int CAPTCHA_DEFAULT_WAIT_TIMEOUT_MS = 7000;

    private final CDPClient cdp;
    private final String targetId;
    private final InteractionOptions options;

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
     * JavaScript for solving press-and-hold captcha.
     * Executed inside the captcha iframe's context via CDP.
     * Uses constant XPath since we're already in the correct iframe.
     */
    private static final String PRESS_HOLD_CAPTCHA_SCRIPT = """
        (async () => {
            const INITIAL_WAIT_MS = %d;
            const BUFFER_MS = %d;
            const DEFAULT_DURATION_MS = %d;
            const BUTTON_XPATH = '/html/body/div/div/div[2]/div[2]/p';
            
            // Find button by constant XPath (we're inside the iframe)
            const button = document.evaluate(
                BUTTON_XPATH, document, null,
                XPathResult.FIRST_ORDERED_NODE_TYPE, null
            ).singleNodeValue;
            
            if (!button) {
                return JSON.stringify({ success: false, error: 'BUTTON_NOT_FOUND', duration: 0 });
            }
            
            // Press down
            button.dispatchEvent(new MouseEvent('mousedown', {
                bubbles: true,
                cancelable: true,
                view: window,
                button: 0
            }));
            button.dispatchEvent(new PointerEvent('pointerdown', {
                bubbles: true,
                cancelable: true,
                view: window,
                pointerType: 'mouse'
            }));
            
            // Wait for animation style to apply
            await new Promise(r => setTimeout(r, INITIAL_WAIT_MS));
            
            // Parse animation duration from style
            let animationDuration = DEFAULT_DURATION_MS;
            const style = button.getAttribute('style') || '';
            const match = style.match(/animation:\\s*([\\d.]+)(ms|s)/i);
            if (match) {
                const value = parseFloat(match[1]);
                const unit = match[2].toLowerCase();
                animationDuration = unit === 's' ? value * 1000 : value;
            }
            
            // Hold for remaining time + buffer
            const remainingHold = Math.max(0, animationDuration - INITIAL_WAIT_MS + BUFFER_MS);
            await new Promise(r => setTimeout(r, remainingHold));
            
            // Release
            button.dispatchEvent(new MouseEvent('mouseup', {
                bubbles: true,
                cancelable: true,
                view: window,
                button: 0
            }));
            button.dispatchEvent(new PointerEvent('pointerup', {
                bubbles: true,
                cancelable: true,
                view: window,
                pointerType: 'mouse'
            }));
            
            return JSON.stringify({ success: true, error: null, duration: animationDuration });
        })();
        """;

    // ==================== Captcha Result Types ====================

    /**
     * Result of a press-and-hold captcha solve attempt.
     */
    public enum CaptchaAttemptResult {
        /** No captcha was detected within the timeout period */
        NOT_FOUND,

        /** Captcha was found and press-and-hold was completed */
        ATTEMPTED,

        /** An error occurred during the solve attempt */
        ERROR
    }

    /**
     * Detailed result of a captcha solve attempt.
     *
     * @param result           the outcome of the attempt
     * @param detectedDurationMs the animation duration detected (0 if not found/error)
     * @param errorMessage     error description if result is ERROR, null otherwise
     */
    public record CaptchaSolveResult(
            CaptchaAttemptResult result,
            long detectedDurationMs,
            String errorMessage
    ) {
        /**
         * Creates a NOT_FOUND result.
         */
        public static CaptchaSolveResult notFound() {
            return new CaptchaSolveResult(CaptchaAttemptResult.NOT_FOUND, 0, null);
        }

        /**
         * Creates an ATTEMPTED result with the detected duration.
         */
        public static CaptchaSolveResult attempted(long durationMs) {
            return new CaptchaSolveResult(CaptchaAttemptResult.ATTEMPTED, durationMs, null);
        }

        /**
         * Creates an ERROR result with the error message.
         */
        public static CaptchaSolveResult error(String message) {
            return new CaptchaSolveResult(CaptchaAttemptResult.ERROR, 0, message);
        }
    }

    /**
     * Creates a new Page with default interaction options.
     *
     * @param cdp      the CDP client connected to this page's target
     * @param targetId the target ID for this page
     */
    public Page(CDPClient cdp, String targetId) {
        this(cdp, targetId, InteractionOptions.defaults());
    }

    /**
     * Creates a new Page with custom interaction options.
     *
     * @param cdp      the CDP client connected to this page's target
     * @param targetId the target ID for this page
     * @param options  the interaction options
     */
    public Page(CDPClient cdp, String targetId, InteractionOptions options) {
        this.cdp = cdp;
        this.targetId = targetId;
        this.options = options;
        this.mousePosition = Vector.ORIGIN;
    }

    /**
     * Gets the target ID for this page.
     *
     * @return the target ID
     */
    public String getTargetId() {
        return targetId;
    }

    /**
     * Gets the CDP client for this page.
     *
     * @return the CDP client
     */
    public CDPClient getCdpClient() {
        return cdp;
    }

    /**
     * Gets the interaction options for this page.
     *
     * @return the interaction options
     */
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

    private void ensureRuntimeEnabled() throws TimeoutException {
        if (!runtimeEnabled) {
            cdp.send("Runtime.enable", null);
            runtimeEnabled = true;
        }
    }

    private void ensureNetworkEnabled() throws TimeoutException {
        if (!networkEnabled) {
            cdp.send("Network.enable", null);
            networkEnabled = true;
        }
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
    private void triggerCursorClickAnimation() {
        if (!options.isShowCursorOverlay()) {
            return;
        }

        try {
            evaluate("window.__nodriver4j_clickCursor()");
        } catch (TimeoutException e) {
            // Silently ignore - cursor overlay is non-critical
        }
    }

    // ==================== Press-and-Hold Captcha ====================

    /**
     * Attempts to solve a press-and-hold captcha (e.g., PerimeterX) if present.
     *
     * <p>Uses the default timeout of 7 seconds waiting for captcha to appear.</p>
     *
     * @return the result of the captcha solve attempt
     * @see #solvePressHoldCaptcha(int)
     */
    public CaptchaSolveResult solvePressHoldCaptcha() {
        return solvePressHoldCaptcha(CAPTCHA_DEFAULT_WAIT_TIMEOUT_MS);
    }

    /**
     * Attempts to solve a press-and-hold captcha (e.g., PerimeterX) if present.
     *
     * <p>This method uses CDP's DOM domain to:</p>
     * <ul>
     *   <li>Pierce the closed shadow DOM of #px-captcha</li>
     *   <li>Find the visible iframe (display: block) among decoys</li>
     *   <li>Execute the press-and-hold script directly in that iframe's context</li>
     * </ul>
     *
     * <p><strong>Note:</strong> Success/failure verification is the caller's responsibility.
     * Check for page navigation, element disappearance, or other indicators after calling.</p>
     *
     * @param waitTimeoutMs maximum time to wait for captcha to appear (recommended: 7000)
     * @return the result of the captcha solve attempt
     */
    public CaptchaSolveResult solvePressHoldCaptcha(int waitTimeoutMs) {
        System.out.println("[Page] Checking for press-and-hold captcha...");

        try {
            // Step 1: Wait for captcha shadow host to appear
            long deadline = System.currentTimeMillis() + waitTimeoutMs;
            boolean captchaFound = false;

            while (System.currentTimeMillis() < deadline) {
                if (existsCss(PX_CAPTCHA_SELECTOR)) {
                    captchaFound = true;
                    break;
                }
                sleep(options.getRetryInterval());
            }

            if (!captchaFound) {
                System.out.println("[Page] No captcha detected within timeout");
                return CaptchaSolveResult.notFound();
            }

            System.out.println("[Page] Captcha detected, finding visible iframe via CDP DOM...");

            // Step 2: Find the visible iframe's frameId using CDP DOM
            String frameId = findCaptchaFrameId();
            if (frameId == null) {
                return CaptchaSolveResult.error("Could not find visible captcha iframe");
            }

            System.out.println("[Page] Found captcha iframe frameId: " + frameId);

            // Step 3: Create isolated world in the iframe
            JsonObject createWorldParams = new JsonObject();
            createWorldParams.addProperty("frameId", frameId);
            createWorldParams.addProperty("worldName", "nodriver4j_captcha");

            JsonObject worldResult = cdp.send("Page.createIsolatedWorld", createWorldParams);
            int executionContextId = worldResult.get("executionContextId").getAsInt();

            System.out.println("[Page] Created execution context: " + executionContextId);

            // Step 4: Execute press-hold script in iframe context
            String script = String.format(
                    PRESS_HOLD_CAPTCHA_SCRIPT,
                    CAPTCHA_INITIAL_WAIT_MS,
                    CAPTCHA_BUFFER_MS,
                    CAPTCHA_DEFAULT_DURATION_MS
            );

            JsonObject evalParams = new JsonObject();
            evalParams.addProperty("contextId", executionContextId);
            evalParams.addProperty("expression", script);
            evalParams.addProperty("returnByValue", true);
            evalParams.addProperty("awaitPromise", true);

            JsonObject evalResult = cdp.send("Runtime.evaluate", evalParams);

            // Step 5: Parse result
            if (!evalResult.has("result")) {
                return CaptchaSolveResult.error("No result from script execution");
            }

            JsonObject resultObj = evalResult.getAsJsonObject("result");
            if (!resultObj.has("value")) {
                // Check for exception
                if (evalResult.has("exceptionDetails")) {
                    String exceptionText = evalResult.getAsJsonObject("exceptionDetails").toString();
                    return CaptchaSolveResult.error("Script exception: " + exceptionText);
                }
                return CaptchaSolveResult.error("Script returned no value");
            }

            String resultJson = resultObj.get("value").getAsString();
            JsonObject result = com.google.gson.JsonParser.parseString(resultJson).getAsJsonObject();

            boolean success = result.get("success").getAsBoolean();
            long duration = result.get("duration").getAsLong();

            if (success) {
                System.out.println("[Page] Captcha press-and-hold completed (held for " + duration + "ms)");
                return CaptchaSolveResult.attempted(duration);
            } else {
                String error = result.get("error").getAsString();
                System.err.println("[Page] Captcha solve failed: " + error);
                return CaptchaSolveResult.error(error);
            }

        } catch (TimeoutException e) {
            System.err.println("[Page] Captcha solve timeout: " + e.getMessage());
            return CaptchaSolveResult.error("Timeout: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[Page] Captcha solve exception: " + e.getMessage());
            e.printStackTrace();
            return CaptchaSolveResult.error("Exception: " + e.getMessage());
        }
    }

    /**
     * Finds the frameId of the visible captcha iframe using CDP DOM inspection.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Gets the document root with shadow DOM piercing</li>
     *   <li>Queries for #px-captcha element</li>
     *   <li>Describes the node to access its shadow root children</li>
     *   <li>Finds the iframe with "display: block" in its style attribute</li>
     *   <li>Returns that iframe's frameId</li>
     * </ol>
     *
     * @return the frameId of the visible captcha iframe, or null if not found
     * @throws TimeoutException if CDP operations timeout
     */
    private String findCaptchaFrameId() throws TimeoutException {
        // Step 1: Get document root
        JsonObject docParams = new JsonObject();
        docParams.addProperty("pierce", true);
        docParams.addProperty("depth", 0);

        JsonObject docResult = cdp.send("DOM.getDocument", docParams);
        int rootNodeId = docResult.getAsJsonObject("root").get("nodeId").getAsInt();

        // Step 2: Query for #px-captcha
        JsonObject queryParams = new JsonObject();
        queryParams.addProperty("nodeId", rootNodeId);
        queryParams.addProperty("selector", PX_CAPTCHA_SELECTOR);

        JsonObject queryResult = cdp.send("DOM.querySelector", queryParams);

        if (!queryResult.has("nodeId") || queryResult.get("nodeId").getAsInt() == 0) {
            System.err.println("[Page] #px-captcha element not found via DOM.querySelector");
            return null;
        }

        int captchaNodeId = queryResult.get("nodeId").getAsInt();

        // Step 3: Describe the node with shadow piercing to get iframe children
        JsonObject describeParams = new JsonObject();
        describeParams.addProperty("nodeId", captchaNodeId);
        describeParams.addProperty("pierce", true);
        describeParams.addProperty("depth", 2); // Enough to see shadow root and its children

        JsonObject describeResult = cdp.send("DOM.describeNode", describeParams);

        if (!describeResult.has("node")) {
            System.err.println("[Page] DOM.describeNode returned no node");
            return null;
        }

        JsonObject node = describeResult.getAsJsonObject("node");

        // Step 4: Navigate to shadow root and find iframes
        if (!node.has("shadowRoots")) {
            System.err.println("[Page] #px-captcha has no shadowRoots");
            return null;
        }

        JsonArray shadowRoots = node.getAsJsonArray("shadowRoots");
        if (shadowRoots.isEmpty()) {
            System.err.println("[Page] shadowRoots array is empty");
            return null;
        }

        JsonObject shadowRoot = shadowRoots.get(0).getAsJsonObject();

        if (!shadowRoot.has("children")) {
            System.err.println("[Page] Shadow root has no children");
            return null;
        }

        JsonArray children = shadowRoot.getAsJsonArray("children");

        // Step 5: Find the iframe with display: block
        for (JsonElement child : children) {
            JsonObject childNode = child.getAsJsonObject();

            // Only look at IFRAME elements
            if (!"IFRAME".equals(childNode.get("nodeName").getAsString())) {
                continue;
            }

            // Check if this iframe has frameId
            if (!childNode.has("frameId")) {
                continue;
            }

            // Parse attributes array to find style
            if (!childNode.has("attributes")) {
                continue;
            }

            JsonArray attributes = childNode.getAsJsonArray("attributes");
            String styleValue = getAttributeValue(attributes, "style");

            if (styleValue != null && styleValue.contains("display: block")) {
                String frameId = childNode.get("frameId").getAsString();
                System.out.println("[Page] Found visible iframe with style: " +
                        styleValue.substring(0, Math.min(50, styleValue.length())) + "...");
                return frameId;
            }
        }

        System.err.println("[Page] No iframe with 'display: block' found in shadow root");
        return null;
    }

    /**
     * Extracts an attribute value from a CDP attributes array.
     *
     * <p>CDP returns attributes as a flat array: ["name1", "value1", "name2", "value2", ...]</p>
     *
     * @param attributes the attributes JsonArray from CDP
     * @param name       the attribute name to find
     * @return the attribute value, or null if not found
     */
    private String getAttributeValue(JsonArray attributes, String name) {
        for (int i = 0; i < attributes.size() - 1; i += 2) {
            String attrName = attributes.get(i).getAsString();
            if (name.equals(attrName)) {
                return attributes.get(i + 1).getAsString();
            }
        }
        return null;
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
    public String getCurrentUrl() throws TimeoutException {
        ensureRuntimeEnabled();
        return evaluate("window.location.href");
    }

    /**
     * Gets the current page title.
     *
     * @return the page title
     * @throws TimeoutException if the operation times out
     */
    public String getTitle() throws TimeoutException {
        ensureRuntimeEnabled();
        return evaluate("document.title");
    }

    private void waitForLoadEvent(int timeoutMs) throws TimeoutException {
        try {
            cdp.waitForEvent("Page.loadEventFired", timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Some pages may not fire loadEventFired reliably
            System.err.println("[Page] Warning: Page load timeout, continuing...");
        }
    }

    // ==================== Element Queries (XPath) ====================

    /**
     * Finds the first element matching the XPath selector.
     *
     * @param xpath the XPath expression
     * @return the element's bounding box, or null if not found
     * @throws TimeoutException if the operation times out
     */
    public BoundingBox querySelector(String xpath) throws TimeoutException {
        return querySelector(xpath, options.getDefaultTimeout());
    }

    /**
     * Finds the first element matching the XPath selector.
     *
     * @param xpath     the XPath expression
     * @param timeoutMs timeout to wait for element
     * @return the element's bounding box, or null if not found
     * @throws TimeoutException if the operation times out
     */
    public BoundingBox querySelector(String xpath, int timeoutMs) throws TimeoutException {
        ensureRuntimeEnabled();

        String script = buildXPathScript(xpath, false);
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
     * Finds all elements matching the XPath selector.
     *
     * @param xpath the XPath expression
     * @return list of element bounding boxes
     * @throws TimeoutException if the operation times out
     */
    public List<BoundingBox> querySelectorAll(String xpath) throws TimeoutException {
        ensureRuntimeEnabled();

        String script = buildXPathScript(xpath, true);
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
     * @param xpath the XPath expression
     * @return true if element exists
     * @throws TimeoutException if the operation times out
     */
    public boolean exists(String xpath) throws TimeoutException {
        ensureRuntimeEnabled();

        String script = String.format(
                "document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue !== null",
                escapeXPath(xpath)
        );

        String result = evaluate(script);
        return "true".equals(result);
    }

    /**
     * Checks if an element exists by CSS selector.
     *
     * @param selector the CSS selector
     * @return true if element exists
     * @throws TimeoutException if the operation times out
     */
    public boolean existsCss(String selector) throws TimeoutException {
        ensureRuntimeEnabled();

        String script = String.format(
                "document.querySelector(\"%s\") !== null",
                escapeJs(selector)
        );

        String result = evaluate(script);
        return "true".equals(result);
    }

    /**
     * Checks if an element is visible.
     *
     * @param xpath the XPath expression
     * @return true if element is visible
     * @throws TimeoutException if the operation times out
     */
    public boolean isVisible(String xpath) throws TimeoutException {
        ensureRuntimeEnabled();

        String script = String.format(
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
                escapeXPath(xpath)
        );

        String result = evaluate(script);
        return "true".equals(result);
    }

    /**
     * Gets the inner text of an element.
     *
     * @param xpath the XPath expression
     * @return the inner text, or null if not found
     * @throws TimeoutException if the operation times out
     */
    public String getText(String xpath) throws TimeoutException {
        ensureRuntimeEnabled();

        String script = String.format(
                "(function() {" +
                        "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                        "  return el ? el.innerText : null;" +
                        "})()",
                escapeXPath(xpath)
        );

        return evaluate(script);
    }

    /**
     * Gets an attribute value of an element.
     *
     * @param xpath     the XPath expression
     * @param attribute the attribute name
     * @return the attribute value, or null if not found
     * @throws TimeoutException if the operation times out
     */
    public String getAttribute(String xpath, String attribute) throws TimeoutException {
        ensureRuntimeEnabled();

        String script = String.format(
                "(function() {" +
                        "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                        "  return el ? el.getAttribute('%s') : null;" +
                        "})()",
                escapeXPath(xpath), escapeJs(attribute)
        );

        return evaluate(script);
    }

    /**
     * Gets the value of an input element.
     *
     * @param xpath the XPath expression
     * @return the input value, or null if not found
     * @throws TimeoutException if the operation times out
     */
    public String getValue(String xpath) throws TimeoutException {
        ensureRuntimeEnabled();

        String script = String.format(
                "(function() {" +
                        "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                        "  return el ? el.value : null;" +
                        "})()",
                escapeXPath(xpath)
        );

        return evaluate(script);
    }

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
     * @param xpath the XPath expression
     * @return the element's bounding box
     * @throws TimeoutException if element doesn't appear within timeout
     */
    public BoundingBox waitForSelector(String xpath) throws TimeoutException {
        return waitForSelector(xpath, options.getDefaultTimeout());
    }

    /**
     * Waits for an element to appear.
     *
     * @param xpath     the XPath expression
     * @param timeoutMs timeout in milliseconds
     * @return the element's bounding box
     * @throws TimeoutException if element doesn't appear within timeout
     */
    public BoundingBox waitForSelector(String xpath, int timeoutMs) throws TimeoutException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            BoundingBox box = querySelector(xpath, 0);
            if (box != null && box.isValid()) {
                return box;
            }
            sleep(options.getRetryInterval());
        }

        throw new TimeoutException("Element not found: " + xpath);
    }

    /**
     * Waits for an element to disappear.
     *
     * @param xpath the XPath expression
     * @throws TimeoutException if element doesn't disappear within timeout
     */
    public void waitForSelectorHidden(String xpath) throws TimeoutException {
        waitForSelectorHidden(xpath, options.getDefaultTimeout());
    }

    /**
     * Waits for an element to disappear.
     *
     * @param xpath     the XPath expression
     * @param timeoutMs timeout in milliseconds
     * @throws TimeoutException if element doesn't disappear within timeout
     */
    public void waitForSelectorHidden(String xpath, int timeoutMs) throws TimeoutException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (!exists(xpath) || !isVisible(xpath)) {
                return;
            }
            sleep(options.getRetryInterval());
        }

        throw new TimeoutException("Element still visible: " + xpath);
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
     * @param xpath the XPath expression
     * @throws TimeoutException if the operation times out
     */
    public void click(String xpath) throws TimeoutException {
        scrollIntoView(xpath);
        BoundingBox box = waitForSelector(xpath);
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
     * @param xpath the XPath expression
     * @throws TimeoutException if the operation times out
     */
    public void hover(String xpath) throws TimeoutException {
        BoundingBox box = waitForSelector(xpath);
        Vector target = box.getRandomPoint(options.getPaddingPercentage());
        moveMouseTo(target);
    }

    /**
     * Moves the mouse to a position with human-like movement.
     *
     * @param target the target position
     * @throws TimeoutException if the operation times out
     */
    private void moveMouseTo(Vector target) throws TimeoutException {
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

    // ==================== Keyboard Interaction ====================

    /**
     * Types text into the focused element with human-like timing.
     *
     * @param text the text to type
     * @throws TimeoutException if the operation times out
     */
    public void type(String text) throws TimeoutException {
        Character previousChar = null;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // Calculate delay
            int delay;
            if (options.isContextAwareTyping()) {
                delay = HumanBehavior.keystrokeDelay(c, previousChar,
                        options.getKeystrokeDelayMin(), options.getKeystrokeDelayMax());
            } else {
                delay = HumanBehavior.keystrokeDelay(
                        options.getKeystrokeDelayMin(), options.getKeystrokeDelayMax());
            }

            // Occasional thinking pause
            int thinkingPause = HumanBehavior.thinkingPause(
                    options.getThinkingPauseProbability(),
                    options.getThinkingPauseMin(),
                    options.getThinkingPauseMax());
            if (thinkingPause > 0) {
                sleep(thinkingPause);
            }

            // Type the character
            typeCharacter(c);

            // Inter-key delay
            sleep(delay);

            previousChar = c;
        }
    }

    /**
     * Types text into an element after clicking it.
     *
     * @param xpath the XPath expression
     * @param text  the text to type
     * @throws TimeoutException if the operation times out
     */
    public void type(String xpath, String text) throws TimeoutException {
        click(xpath);
        sleep(100); // Small delay after click before typing
        type(text);
    }

    /**
     * Clears the content of an input element.
     *
     * @param xpath the XPath expression
     * @throws TimeoutException if the operation times out
     */
    public void clear(String xpath) throws TimeoutException {
        click(xpath);
        sleep(50);

        // Select all and delete
        pressKey("a", true, false, false); // Ctrl+A
        pressKey("Backspace", false, false, false);
    }

    /**
     * Focuses an element.
     *
     * @param xpath the XPath expression
     * @throws TimeoutException if the operation times out
     */
    public void focus(String xpath) throws TimeoutException {
        ensureRuntimeEnabled();

        String script = String.format(
                "(function() {" +
                        "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                        "  if (el) el.focus();" +
                        "})()",
                escapeXPath(xpath)
        );

        evaluate(script);
    }

    /**
     * Selects an option from a dropdown.
     *
     * @param xpath the XPath expression for the select element
     * @param value the value to select
     * @throws TimeoutException if the operation times out
     */
    public void select(String xpath, String value) throws TimeoutException {
        ensureRuntimeEnabled();

        String script = String.format(
                "(function() {" +
                        "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                        "  if (el) {" +
                        "    el.value = '%s';" +
                        "    el.dispatchEvent(new Event('change', { bubbles: true }));" +
                        "  }" +
                        "})()",
                escapeXPath(xpath), escapeJs(value)
        );

        evaluate(script);
    }

    private void typeCharacter(char c) throws TimeoutException {
        String text = String.valueOf(c);

        // Key down - inserts the character
        JsonObject keyDown = new JsonObject();
        keyDown.addProperty("type", "keyDown");
        keyDown.addProperty("text", text);
        keyDown.addProperty("key", text);
        cdp.send("Input.dispatchKeyEvent", keyDown);

        // Key up
        JsonObject keyUp = new JsonObject();
        keyUp.addProperty("type", "keyUp");
        keyUp.addProperty("key", text);
        cdp.send("Input.dispatchKeyEvent", keyUp);
    }

    /**
     * Presses a key with optional modifiers.
     *
     * @param key   the key to press
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

        JsonObject keyDown = new JsonObject();
        keyDown.addProperty("type", "keyDown");
        keyDown.addProperty("key", key);
        keyDown.addProperty("modifiers", modifiers);
        cdp.send("Input.dispatchKeyEvent", keyDown);

        JsonObject keyUp = new JsonObject();
        keyUp.addProperty("type", "keyUp");
        keyUp.addProperty("key", key);
        keyUp.addProperty("modifiers", modifiers);
        cdp.send("Input.dispatchKeyEvent", keyUp);
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

    /**
     * Scrolls to an absolute position.
     *
     * @param x target x scroll position
     * @param y target y scroll position
     * @throws TimeoutException if the operation times out
     */
    public void scrollTo(int x, int y) throws TimeoutException {
        ensureRuntimeEnabled();

        // Get current scroll position
        String currentX = evaluate("window.scrollX");
        String currentY = evaluate("window.scrollY");

        int deltaX = x - Integer.parseInt(currentX);
        int deltaY = y - Integer.parseInt(currentY);

        scrollBy(deltaX, deltaY);
    }

    /**
     * Scrolls an element into view.
     *
     * @param xpath the XPath expression
     * @throws TimeoutException if the operation times out
     */
    public void scrollIntoView(String xpath) throws TimeoutException {
        BoundingBox box = querySelector(xpath);
        if (box == null) {
            throw new TimeoutException("Element not found: " + xpath);
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

    public String getFrameTree() throws TimeoutException {
        JsonObject result = cdp.send("Page.getFrameTree", null);
        return result.getAsString();
    }

    // ==================== Screenshots ====================

    /**
     * Takes a screenshot of the page.
     *
     * @return the screenshot as PNG bytes
     * @throws TimeoutException if the operation times out
     */
    public byte[] screenshot() throws TimeoutException {
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
     * @param xpath the XPath expression
     * @return the screenshot as PNG bytes
     * @throws TimeoutException if the operation times out
     */
    public byte[] screenshotElement(String xpath) throws TimeoutException {
        BoundingBox box = querySelector(xpath);
        if (box == null) {
            throw new TimeoutException("Element not found: " + xpath);
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