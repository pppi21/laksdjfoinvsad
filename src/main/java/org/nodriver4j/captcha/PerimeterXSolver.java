package org.nodriver4j.captcha;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.nodriver4j.cdp.CDPSession;
import org.nodriver4j.core.InteractionOptions;
import org.nodriver4j.core.Page;
import org.nodriver4j.core.exceptions.AutomationException;
import org.nodriver4j.core.exceptions.FrameException;
import org.nodriver4j.core.exceptions.ScriptExecutionException;
import org.nodriver4j.math.BoundingBox;
import org.nodriver4j.math.HumanBehavior;
import org.nodriver4j.math.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static utility class for solving PerimeterX press-and-hold captchas.
 *
 * <p>This solver handles the PerimeterX "Human Challenge" which requires users to
 * press and hold a button for a specific duration determined by a CSS animation.</p>
 *
 * <h2>How PerimeterX Captcha Works</h2>
 * <ol>
 *   <li>A {@code #px-captcha} element with a closed Shadow DOM appears</li>
 *   <li>Inside the shadow root are multiple iframes (only one is visible)</li>
 *   <li>The visible iframe contains a button that must be pressed and held</li>
 *   <li>The button has a CSS animation whose duration indicates how long to hold</li>
 *   <li>Successfully holding for the full duration solves the captcha</li>
 * </ol>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Basic usage
 * PerimeterXSolver.SolveResult result = PerimeterXSolver.solve(page);
 *
 * if (result.result() == PerimeterXSolver.AttemptResult.ATTEMPTED) {
 *     System.out.println("Held for " + result.detectedDurationMs() + "ms");
 * }
 *
 * // With custom timeout
 * PerimeterXSolver.SolveResult result = PerimeterXSolver.solve(page,
 *     PerimeterXSolver.SolveOptions.builder()
 *         .waitTimeoutMs(5000)
 *         .build());
 * }</pre>
 *
 * <h2>Implementation Notes</h2>
 * <p>This solver uses CDP's DOM domain with shadow piercing to access the closed
 * shadow root, which is normally inaccessible via standard JavaScript APIs.</p>
 *
 * @see Page
 */
public final class PerimeterXSolver {

    // ==================== Selectors ====================

    /** Selector for PerimeterX captcha shadow host */
    private static final String PX_CAPTCHA_SELECTOR = "#px-captcha";

    /** XPath to button element inside captcha iframe (constant structure) */
    private static final String CAPTCHA_BUTTON_XPATH = "/html/body/div/div/div[2]/div[2]/p";

    // ==================== Timing Constants ====================

    /** Time to wait for animation style to apply after mousedown */
    private static final int INITIAL_WAIT_MS = 150;

    /** Buffer time after animation ends before releasing */
    private static final int BUFFER_MS = 600;

    /** Default hold duration if animation parsing fails */
    private static final int DEFAULT_DURATION_MS = 10000;

    /** Default timeout waiting for captcha to appear */
    private static final int DEFAULT_WAIT_TIMEOUT_MS = 7000;

    // ==================== Private Constructor ====================

    private PerimeterXSolver() {
        // Static utility class - prevent instantiation
    }

    // ==================== Public API ====================

    /**
     * Attempts to solve a PerimeterX press-and-hold captcha.
     *
     * <p>Uses the default timeout of 7 seconds waiting for captcha to appear.</p>
     *
     * @param page the Page containing the captcha
     * @return the result of the solve attempt
     * @throws IllegalArgumentException if page is null
     */
    public static SolveResult solve(Page page) {
        return solve(page, SolveOptions.defaults());
    }

    /**
     * Attempts to solve a PerimeterX press-and-hold captcha with custom options.
     *
     * <p>This method uses CDP Input events for realistic mouse simulation:</p>
     * <ul>
     *   <li>Bezier curve mouse movement path</li>
     *   <li>Overshoot and correction for distant targets</li>
     *   <li>Micro-jitter to simulate hand tremor</li>
     *   <li>Realistic timing patterns</li>
     * </ul>
     *
     * <p><strong>Note:</strong> Success/failure verification is the caller's responsibility.
     * Check for page navigation, element disappearance, or other indicators after calling.</p>
     *
     * @param page    the Page containing the captcha
     * @param options custom solve options
     * @return the result of the solve attempt
     * @throws IllegalArgumentException if any parameter is null
     */
    public static SolveResult solve(Page page, SolveOptions options) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("SolveOptions cannot be null");
        }

        System.out.println("[PerimeterXSolver] Checking for press-and-hold captcha...");

        try {
            // Step 1: Wait for captcha shadow host to appear
            long deadline = System.currentTimeMillis() + options.waitTimeoutMs();
            boolean captchaFound = false;

            while (System.currentTimeMillis() < deadline) {
                if (page.exists(PX_CAPTCHA_SELECTOR)) {
                    captchaFound = true;
                    break;
                }
                page.sleep(page.options().getRetryInterval());
            }

            if (!captchaFound) {
                System.out.println("[PerimeterXSolver] No captcha detected within timeout");
                return SolveResult.notFound();
            }

            System.out.println("[PerimeterXSolver] Captcha detected, locating visible iframe...");

            // Step 2: Find the visible iframe (frameId + nodeId)
            IframeInfo iframeInfo = findCaptchaIframeInfo(page);
            if (iframeInfo == null) {
                return SolveResult.error("Could not find visible captcha iframe");
            }

            System.out.println("[PerimeterXSolver] Found iframe: frameId=" + iframeInfo.frameId() +
                    ", backendNodeId=" + iframeInfo.backendNodeId());

            // Step 3: Get iframe's bounding box and scroll into view if needed
            BoundingBox iframeBox = page.getNodeBoundingBox(iframeInfo.backendNodeId());
            if (iframeBox == null) {
                return SolveResult.error("Could not get iframe bounding box");
            }

            System.out.println("[PerimeterXSolver] Iframe position: " + iframeBox);

            // Scroll into view if needed
            page.scrollIntoViewIfNeeded(iframeBox);

            // Re-get position after potential scroll
            iframeBox = page.getNodeBoundingBox(iframeInfo.backendNodeId());
            if (iframeBox == null) {
                return SolveResult.error("Could not get iframe bounding box after scroll");
            }

            // Step 4: Create execution context in iframe
            int executionContextId = page.createIframeContext(iframeInfo.frameId());
            System.out.println("[PerimeterXSolver] Created execution context: " + executionContextId);

            // Step 5: Get button position within iframe
            BoundingBox buttonBoxInIframe = getButtonPositionInIframe(page, executionContextId);
            if (buttonBoxInIframe == null) {
                return SolveResult.error("Could not find button in iframe");
            }

            // Step 6: Calculate absolute button position
            BoundingBox absoluteButtonBox = new BoundingBox(
                    iframeBox.getX() + buttonBoxInIframe.getX(),
                    iframeBox.getY() + buttonBoxInIframe.getY(),
                    buttonBoxInIframe.getWidth(),
                    buttonBoxInIframe.getHeight()
            );

            System.out.println("[PerimeterXSolver] Button absolute position: " + absoluteButtonBox);

            // Step 7: Move mouse to button with human-like movement
            InteractionOptions interactionOptions = page.options();
            Vector targetPoint = absoluteButtonBox.getRandomPoint(interactionOptions.getPaddingPercentage());

            System.out.println("[PerimeterXSolver] Moving mouse to button at: " + targetPoint);
            page.moveMouseTo(targetPoint);

            // Step 8: Pre-click hesitation
            int hesitation = HumanBehavior.hesitationDelay(
                    interactionOptions.getPreClickDelayMin(),
                    interactionOptions.getPreClickDelayMax());
            page.sleep(hesitation);

            // Step 9: Mouse down
            System.out.println("[PerimeterXSolver] Pressing button...");
            page.mouseDown(targetPoint);

            // Step 10: Wait for animation style to apply
            page.sleep(INITIAL_WAIT_MS);

            // Step 11: Read animation duration from button
            long animationDuration = getAnimationDurationFromButton(page, executionContextId);
            System.out.println("[PerimeterXSolver] Detected animation duration: " + animationDuration + "ms");

            // Step 12: Hold for remaining time + buffer
            long remainingHold = Math.max(0, animationDuration - INITIAL_WAIT_MS + BUFFER_MS);
            System.out.println("[PerimeterXSolver] Holding for " + remainingHold + "ms more...");
            page.sleep(remainingHold);

            // Step 13: Mouse up
            System.out.println("[PerimeterXSolver] Releasing button...");
            page.mouseUp(targetPoint);

            System.out.println("[PerimeterXSolver] Press-and-hold completed (held for " + animationDuration + "ms)");
            return SolveResult.attempted(animationDuration);

        } catch (AutomationException e) {
            System.err.println("[PerimeterXSolver] Timeout: " + e.getMessage());
            return SolveResult.error("Timeout: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[PerimeterXSolver] Exception: " + e.getMessage());
            e.printStackTrace();
            return SolveResult.error("Exception: " + e.getMessage());
        }
    }

    /**
     * Checks if a PerimeterX captcha is present on the page.
     *
     * @param page the Page to check
     * @return true if the {@code #px-captcha} element is found
     */
    public static boolean isPresent(Page page) {
        if (page == null) {
            return false;
        }
        return page.exists(PX_CAPTCHA_SELECTOR);
    }

    // ==================== Internal Methods ====================

    /**
     * Finds the visible captcha iframe's frameId and backendNodeId using CDP DOM inspection.
     *
     * <p>PerimeterX uses a closed shadow root containing multiple iframes, only one of which
     * is visible (has {@code display: block} in its style). This method uses the custom
     * {@code DOM.getShadowRoot} CDP command to directly access the closed shadow root,
     * then inspects its children to find the visible iframe.</p>
     *
     * @param page the Page containing the captcha
     * @return IframeInfo with frameId and backendNodeId, or null if not found
     * @throws TimeoutException if CDP operations timeout
     */
    private static IframeInfo findCaptchaIframeInfo(Page page) throws TimeoutException {
        CDPSession cdp = page.cdpSession();

        // Step 1: Get document root
        JsonObject docParams = new JsonObject();
        docParams.addProperty("depth", 0);

        JsonObject docResult = cdp.send("DOM.getDocument", docParams);
        int rootNodeId = docResult.getAsJsonObject("root").get("nodeId").getAsInt();

        // Step 2: Query for #px-captcha
        JsonObject queryParams = new JsonObject();
        queryParams.addProperty("nodeId", rootNodeId);
        queryParams.addProperty("selector", PX_CAPTCHA_SELECTOR);

        JsonObject queryResult = cdp.send("DOM.querySelector", queryParams);

        if (!queryResult.has("nodeId") || queryResult.get("nodeId").getAsInt() == 0) {
            System.err.println("[PerimeterXSolver] #px-captcha element not found via DOM.querySelector");
            return null;
        }

        int captchaNodeId = queryResult.get("nodeId").getAsInt();

        // Step 3: Get the closed shadow root directly via custom CDP command
        JsonObject shadowParams = new JsonObject();
        shadowParams.addProperty("nodeId", captchaNodeId);

        JsonObject shadowResult = cdp.send("DOM.getShadowRoot", shadowParams);

        if (!shadowResult.has("shadowRoot") || shadowResult.get("shadowRoot").isJsonNull()) {
            System.err.println("[PerimeterXSolver] #px-captcha has no shadow root");
            return null;
        }

        int shadowRootNodeId = shadowResult.getAsJsonObject("shadowRoot").get("nodeId").getAsInt();

        // Step 4: Describe the shadow root to get its children (the iframes)
        JsonObject describeParams = new JsonObject();
        describeParams.addProperty("nodeId", shadowRootNodeId);
        describeParams.addProperty("depth", 1);
        describeParams.addProperty("pierce", true);

        JsonObject describeResult = cdp.send("DOM.describeNode", describeParams);

        if (!describeResult.has("node")) {
            System.err.println("[PerimeterXSolver] DOM.describeNode returned no node for shadow root");
            return null;
        }

        JsonObject shadowNode = describeResult.getAsJsonObject("node");

        if (!shadowNode.has("children")) {
            System.err.println("[PerimeterXSolver] Shadow root has no children");
            return null;
        }

        JsonArray children = shadowNode.getAsJsonArray("children");

        // Step 5: Find the iframe with display: block
        for (JsonElement child : children) {
            JsonObject childNode = child.getAsJsonObject();

            if (!"IFRAME".equals(childNode.get("nodeName").getAsString())) {
                continue;
            }

            if (!childNode.has("frameId")) {
                continue;
            }

            if (!childNode.has("attributes")) {
                continue;
            }

            if (!childNode.has("backendNodeId")) {
                System.err.println("[PerimeterXSolver] Iframe missing backendNodeId");
                continue;
            }

            JsonArray attributes = childNode.getAsJsonArray("attributes");
            String styleValue = getCdpAttributeValue(attributes, "style");

            if (styleValue != null && styleValue.contains("display: block")) {
                String frameId = childNode.get("frameId").getAsString();
                int backendNodeId = childNode.get("backendNodeId").getAsInt();

                System.out.println("[PerimeterXSolver] Found visible iframe with style: " +
                        styleValue.substring(0, Math.min(50, styleValue.length())) + "...");

                return new IframeInfo(frameId, backendNodeId);
            }
        }

        System.err.println("[PerimeterXSolver] No iframe with 'display: block' found in shadow root");
        return null;
    }

    /**
     * Gets the button's bounding box within the captcha iframe.
     *
     * @param page               the Page
     * @param executionContextId the execution context ID for the iframe
     * @return the button's bounding box (iframe-relative), or null if not found
     * @throws TimeoutException if CDP operation times out
     */
    private static BoundingBox getButtonPositionInIframe(Page page, int executionContextId)
            throws TimeoutException {
        String script = String.format("""
            (function() {
                const button = document.evaluate(
                    '%s', document, null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE, null
                ).singleNodeValue;
                if (!button) return null;
                const rect = button.getBoundingClientRect();
                return JSON.stringify({x: rect.x, y: rect.y, width: rect.width, height: rect.height});
            })();
            """, CAPTCHA_BUTTON_XPATH);

        JsonObject evalParams = new JsonObject();
        evalParams.addProperty("contextId", executionContextId);
        evalParams.addProperty("expression", script);
        evalParams.addProperty("returnByValue", true);

        JsonObject evalResult = page.cdpSession().send("Runtime.evaluate", evalParams);

        if (!evalResult.has("result")) {
            return null;
        }

        JsonObject resultObj = evalResult.getAsJsonObject("result");
        if (!resultObj.has("value") || resultObj.get("value").isJsonNull()) {
            return null;
        }

        String json = resultObj.get("value").getAsString();
        JsonObject rect = com.google.gson.JsonParser.parseString(json).getAsJsonObject();

        return new BoundingBox(
                rect.get("x").getAsDouble(),
                rect.get("y").getAsDouble(),
                rect.get("width").getAsDouble(),
                rect.get("height").getAsDouble()
        );
    }

    /**
     * Reads the animation duration from the captcha button's style attribute.
     *
     * <p>Parses patterns like:</p>
     * <ul>
     *   <li>"animation: 1027ms ease 0s 1 normal none running textColorInvert"</li>
     *   <li>"animation: 4s linear"</li>
     * </ul>
     *
     * @param page               the Page
     * @param executionContextId the execution context ID for the iframe
     * @return the animation duration in milliseconds, or default if not found
     * @throws TimeoutException if CDP operation times out
     */
    private static long getAnimationDurationFromButton(Page page, int executionContextId)
            throws TimeoutException {
        String script = String.format("""
            (function() {
                const button = document.evaluate(
                    '%s', document, null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE, null
                ).singleNodeValue;
                if (!button) return null;
                return button.getAttribute('style') || '';
            })();
            """, CAPTCHA_BUTTON_XPATH);

        JsonObject evalParams = new JsonObject();
        evalParams.addProperty("contextId", executionContextId);
        evalParams.addProperty("expression", script);
        evalParams.addProperty("returnByValue", true);

        JsonObject evalResult = page.cdpSession().send("Runtime.evaluate", evalParams);

        if (!evalResult.has("result")) {
            return DEFAULT_DURATION_MS;
        }

        JsonObject resultObj = evalResult.getAsJsonObject("result");
        if (!resultObj.has("value") || resultObj.get("value").isJsonNull()) {
            return DEFAULT_DURATION_MS;
        }

        String style = resultObj.get("value").getAsString();

        // Parse animation duration: "animation: 1027ms ease 0s 1 normal none running textColorInvert"
        Pattern pattern = Pattern.compile(
                "animation:\\s*([\\d.]+)(ms|s)",
                Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(style);

        if (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2).toLowerCase();
            return (long) (unit.equals("s") ? value * 1000 : value);
        }

        return DEFAULT_DURATION_MS;
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
    private static String getCdpAttributeValue(JsonArray attributes, String name) {
        for (int i = 0; i < attributes.size() - 1; i += 2) {
            String attrName = attributes.get(i).getAsString();
            if (name.equals(attrName)) {
                return attributes.get(i + 1).getAsString();
            }
        }
        return null;
    }

    // ==================== Result Types ====================

    /**
     * Result of a press-and-hold captcha solve attempt.
     */
    public enum AttemptResult {
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
     * @param result             the outcome of the attempt
     * @param detectedDurationMs the animation duration detected (0 if not found/error)
     * @param errorMessage       error description if result is ERROR, null otherwise
     */
    public record SolveResult(
            AttemptResult result,
            long detectedDurationMs,
            String errorMessage
    ) {
        /** Creates a NOT_FOUND result. */
        public static SolveResult notFound() {
            return new SolveResult(AttemptResult.NOT_FOUND, 0, null);
        }

        /** Creates an ATTEMPTED result with the detected duration. */
        public static SolveResult attempted(long durationMs) {
            return new SolveResult(AttemptResult.ATTEMPTED, durationMs, null);
        }

        /** Creates an ERROR result with the error message. */
        public static SolveResult error(String message) {
            return new SolveResult(AttemptResult.ERROR, 0, message);
        }

        /** Checks if the attempt was successful (captcha found and held). */
        public boolean wasAttempted() {
            return result == AttemptResult.ATTEMPTED;
        }

        /** Checks if no captcha was found. */
        public boolean wasNotFound() {
            return result == AttemptResult.NOT_FOUND;
        }

        /** Checks if an error occurred. */
        public boolean hadError() {
            return result == AttemptResult.ERROR;
        }
    }

    /**
     * Options for customizing solve behavior.
     */
    public record SolveOptions(
            int waitTimeoutMs
    ) {
        /** Creates options with default values. */
        public static SolveOptions defaults() {
            return new SolveOptions(DEFAULT_WAIT_TIMEOUT_MS);
        }

        /** Creates a new builder. */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Builder for SolveOptions.
         */
        public static class Builder {
            private int waitTimeoutMs = DEFAULT_WAIT_TIMEOUT_MS;

            private Builder() {}

            /**
             * Sets the maximum time to wait for captcha to appear.
             *
             * @param waitTimeoutMs timeout in milliseconds
             * @return this builder
             */
            public Builder waitTimeoutMs(int waitTimeoutMs) {
                this.waitTimeoutMs = waitTimeoutMs;
                return this;
            }

            /**
             * Builds the SolveOptions.
             *
             * @return the configured SolveOptions
             */
            public SolveOptions build() {
                return new SolveOptions(waitTimeoutMs);
            }
        }
    }

    // ==================== Internal Types ====================

    /**
     * Holds information about the captcha iframe needed for solving.
     */
    private record IframeInfo(String frameId, int backendNodeId) {}
}
