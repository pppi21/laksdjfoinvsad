package org.nodriver4j.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.nodriver4j.cdp.CDPSession;
import org.nodriver4j.math.BoundingBox;
import org.nodriver4j.math.Vector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    private CDPSession cdp;
    private final String targetId;
    private final InteractionOptions options;
    private final Browser browser;
    private final ElementQuery elementQuery;
    private final InputController input;
    private final FrameManager frames;
    private final NavigationController navigation;

    // Enabled CDP domains tracking
    private boolean pageEnabled = false;
    private boolean runtimeEnabled = false;
    private boolean networkEnabled = false;


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
        this.elementQuery = new ElementQuery(this);
        this.input = new InputController(this);
        this.frames = new FrameManager(this);
        this.navigation = new NavigationController(this);
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

    // ==================== CDP Domain Management ====================

    void ensurePageEnabled() throws TimeoutException {
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

    void ensureNetworkEnabled() throws TimeoutException {
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

    // ==================== Iframe (delegated to FrameManager) ====================

    /**
     * Holds information about an iframe needed for interaction.
     */
    public record IframeInfo(String frameId, int backendNodeId, BoundingBox boundingBox, String url) {}

    public IframeInfo getIframeInfo(String iframeSelector) {
        var info = frames.getIframeInfo(iframeSelector);
        return new IframeInfo(info.frameId(), info.backendNodeId(), info.boundingBox(), info.url());
    }

    public IframeInfo getIframeInfo(String iframeSelector, int index) {
        var info = frames.getIframeInfo(iframeSelector, index);
        return new IframeInfo(info.frameId(), info.backendNodeId(), info.boundingBox(), info.url());
    }

    public int createIframeContext(String frameId) {
        return frames.createIframeContext(frameId);
    }

    public String evaluateInFrame(IframeInfo iframeInfo, String script) {
        return frames.evaluateInFrame(toFrameManagerInfo(iframeInfo), script);
    }

    public String evaluateInFrame(String frameId, String script) {
        return frames.evaluateInFrame(frameId, script);
    }

    public BoundingBox querySelectorInFrame(IframeInfo iframeInfo, String selector) {
        return frames.querySelectorInFrame(toFrameManagerInfo(iframeInfo), selector);
    }

    public String getTextInFrame(IframeInfo iframeInfo, String selector) {
        return frames.getTextInFrame(toFrameManagerInfo(iframeInfo), selector);
    }

    public boolean existsInFrame(IframeInfo iframeInfo, String selector) {
        return frames.existsInFrame(toFrameManagerInfo(iframeInfo), selector);
    }

    public boolean hasClassInFrame(IframeInfo iframeInfo, String selector, String className) {
        return frames.hasClassInFrame(toFrameManagerInfo(iframeInfo), selector, className);
    }

    public void clickInFrame(IframeInfo iframeInfo, String selector) {
        frames.clickInFrame(toFrameManagerInfo(iframeInfo), selector);
    }

    public void clickInFrame(String iframeSelector, String elementSelector) {
        frames.clickInFrame(iframeSelector, elementSelector);
    }

    public void clickInFrame(String iframeSelector, int iframeIndex, String elementSelector) {
        frames.clickInFrame(iframeSelector, iframeIndex, elementSelector);
    }

    public byte[] screenshotElementInFrame(IframeInfo iframeInfo, String selector) {
        return frames.screenshotElementInFrame(toFrameManagerInfo(iframeInfo), selector);
    }

    public BoundingBox getNodeBoundingBox(int backendNodeId) {
        return frames.getNodeBoundingBox(backendNodeId);
    }

    private FrameManager.IframeInfo toFrameManagerInfo(IframeInfo info) {
        return new FrameManager.IframeInfo(info.frameId(), info.backendNodeId(), info.boundingBox(), info.url());
    }

    public void scrollIntoViewIfNeeded(BoundingBox box) {
        input.scrollIntoViewIfNeeded(box);
    }

    // ==================== Navigation (delegated to NavigationController) ====================

    public void navigate(String url) {
        navigation.navigate(url);
    }

    public void navigate(String url, int timeoutMs) {
        navigation.navigate(url, timeoutMs);
    }

    public void reload() {
        navigation.reload();
    }

    public void reload(boolean ignoreCache, int timeoutMs) {
        navigation.reload(ignoreCache, timeoutMs);
    }

    public void goBack() {
        navigation.goBack();
    }

    public void goForward() {
        navigation.goForward();
    }

    public String currentUrl() {
        return navigation.currentUrl();
    }

    public String title() {
        return navigation.title();
    }

    public void waitForLoadEvent(int timeoutMs) {
        navigation.waitForLoadEvent(timeoutMs);
    }

    // ==================== Element Queries (delegated to ElementQuery) ====================

    public BoundingBox querySelector(String selector) {
        return elementQuery.querySelector(selector);
    }

    public BoundingBox querySelector(String selector, int timeoutMs) {
        return elementQuery.querySelector(selector, timeoutMs);
    }

    public List<BoundingBox> querySelectorAll(String selector) {
        return elementQuery.querySelectorAll(selector);
    }

    public boolean exists(String selector) {
        return elementQuery.exists(selector);
    }

    public boolean isVisible(String selector) {
        return elementQuery.isVisible(selector);
    }

    public String getText(String selector) {
        return elementQuery.getText(selector);
    }

    public String getAttribute(String selector, String attribute) {
        return elementQuery.getAttribute(selector, attribute);
    }

    public String getValue(String selector) {
        return elementQuery.getValue(selector);
    }

    public boolean validateValue(String selector, String expectedValue) {
        return elementQuery.validateValue(selector, expectedValue);
    }

    public boolean containsText(String selector, String expectedText) {
        return elementQuery.containsText(selector, expectedText);
    }

    public boolean containsTextTrimmed(String selector, String expectedText) {
        return elementQuery.containsTextTrimmed(selector, expectedText);
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

    public BoundingBox waitForSelector(String selector) {
        return elementQuery.waitForSelector(selector);
    }

    public BoundingBox waitForSelector(String selector, int timeoutMs) {
        return elementQuery.waitForSelector(selector, timeoutMs);
    }

    public void waitForSelectorHidden(String selector) {
        elementQuery.waitForSelectorHidden(selector);
    }

    public void waitForSelectorHidden(String selector, int timeoutMs) {
        elementQuery.waitForSelectorHidden(selector, timeoutMs);
    }

    public void waitForVisible(String selector) {
        elementQuery.waitForVisible(selector);
    }

    public void waitForVisible(String selector, int timeoutMs) {
        elementQuery.waitForVisible(selector, timeoutMs);
    }

    // ==================== Clickability Checks (delegated to InputController) ====================

    public boolean isClickable(String selector) {
        return input.isClickable(selector);
    }

    public boolean isClickable(String selector, int timeoutMs) {
        return input.isClickable(selector, timeoutMs);
    }

    public BoundingBox waitForClickable(String selector) {
        return input.waitForClickable(selector);
    }

    public BoundingBox waitForClickable(String selector, int timeoutMs) {
        return input.waitForClickable(selector, timeoutMs);
    }

    public void waitForNavigation() {
        navigation.waitForNavigation();
    }

    public void waitForNavigation(int timeoutMs) {
        navigation.waitForNavigation(timeoutMs);
    }

    public void waitForNetworkIdle(int idleTimeMs) {
        navigation.waitForNetworkIdle(idleTimeMs);
    }

    public void waitForNetworkIdle(int idleTimeMs, int timeoutMs) {
        navigation.waitForNetworkIdle(idleTimeMs, timeoutMs);
    }

    // ==================== Input (delegated to InputController) ====================

    public void click(String selector) {
        input.click(selector);
    }

    public void clickAt(double x, double y) {
        input.clickAt(x, y);
    }

    public void clickAtBox(BoundingBox box) {
        input.clickAtBox(box);
    }

    public void hover(String selector) {
        input.hover(selector);
    }

    public void moveMouseTo(Vector target) {
        input.moveMouseTo(target);
    }

    public void mouseDown(Vector position) {
        input.mouseDown(position);
    }

    public void mouseUp(Vector position) {
        input.mouseUp(position);
    }

    public void jsClick(String selector) {
        input.jsClick(selector);
    }

    public void type(String text) {
        input.type(text);
    }

    public void type(String text, double speedMultiplier) {
        input.type(text, speedMultiplier);
    }

    public void clear(String selector) {
        input.clear(selector);
    }

    public void focus(String selector) {
        input.focus(selector);
    }

    public void select(String selector, String value) {
        input.select(selector, value);
    }

    public void pressKey(String key, boolean ctrl, boolean alt, boolean shift) {
        input.pressKey(key, ctrl, alt, shift);
    }

    public void scrollBy(int deltaX, int deltaY) {
        input.scrollBy(deltaX, deltaY);
    }

    public void scrollTo(int x, int y) {
        input.scrollTo(x, y);
    }

    public void scrollIntoView(String selector) {
        input.scrollIntoView(selector);
    }

    public void scrollToTop() {
        input.scrollToTop();
    }

    public void scrollToBottom() {
        input.scrollToBottom();
    }

    public void fillFormField(String selector, String value, long preTypeDelay, long postTypeDelay) {
        input.fillFormField(selector, value, preTypeDelay, postTypeDelay);
    }

    public void fillFormField(String selector, String value, long preTypeDelay, long postTypeDelay,
                              double speedMultiplier) {
        input.fillFormField(selector, value, preTypeDelay, postTypeDelay, speedMultiplier);
    }

    // ==================== JavaScript Execution ====================

    /**
     * Evaluates JavaScript in the page context.
     *
     * @param script the JavaScript to execute
     * @return the result as a string, or null
     * @throws TimeoutException if the operation times out
     */
    public String evaluate(String script) {
        try {
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
        } catch (TimeoutException e) {
            throw new org.nodriver4j.core.exceptions.ScriptExecutionException("Script evaluation failed", e);
        }
    }

    public boolean evaluateBoolean(String script) {
        String result = evaluate(script);
        return "true".equals(result);
    }

    public int evaluateInt(String script) {
        String result = evaluate(script);
        return result != null ? Integer.parseInt(result) : 0;
    }

    public String frameTree() {
        try {
            JsonObject result = cdp.send("Page.getFrameTree", null);
            return result.toString();
        } catch (TimeoutException e) {
            throw new org.nodriver4j.core.exceptions.ScriptExecutionException("Failed to get frame tree", e);
        }
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
    public ImageData fetchImageInFrame(IframeInfo iframeInfo, String imgSelector) {
        String script = buildFetchImageScript(imgSelector);
        String result = evaluateInFrame(iframeInfo, script);

        if (result == null || result.isBlank()) {
            throw new org.nodriver4j.core.exceptions.ScriptExecutionException("Failed to fetch image: no result from script");
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
    public ImageData fetchImage(String imgSelector) {
        String script = buildFetchImageScript(imgSelector);
        String result = evaluate(script);

        if (result == null || result.isBlank()) {
            throw new org.nodriver4j.core.exceptions.ScriptExecutionException("Failed to fetch image: no result from script");
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
        """, ElementQuery.escapeCss(imgSelector));
    }

    /**
     * Parses the JSON result from the fetch image script.
     */
    private ImageData parseImageDataResult(String jsonResult) {
        try {
            JsonObject json = JsonParser.parseString(jsonResult).getAsJsonObject();

            if (json.has("error")) {
                throw new org.nodriver4j.core.exceptions.ScriptExecutionException(
                        "Failed to fetch image: " + json.get("error").getAsString());
            }

            String base64 = json.get("base64").getAsString();
            int width = json.get("width").getAsInt();
            int height = json.get("height").getAsInt();
            String mimeType = json.has("mimeType") ? json.get("mimeType").getAsString() : "image/unknown";

            return new ImageData(base64, width, height, mimeType);

        } catch (org.nodriver4j.core.exceptions.ScriptExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new org.nodriver4j.core.exceptions.ScriptExecutionException(
                    "Failed to parse image data: " + e.getMessage(), e);
        }
    }

    // ==================== Screenshots ====================

    /**
     * Takes a screenshot and saves it to the screenshots directory.
     *
     * @throws TimeoutException if screenshot capture times out
     * @throws IOException      if file cannot be written
     */
    public void screenshot() throws IOException {
        byte[] pngBytes = screenshotBytes();

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String filename = "screenshot_" + timestamp + ".png";

        Path outputPath = Path.of("screenshots", filename);
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, pngBytes);

        System.out.println("[Page] Screenshot saved to: " + outputPath);
    }

    /**
     * Scrolls an element into view, then takes a screenshot and saves
     * it to the screenshots directory.
     *
     * @throws TimeoutException if screenshot capture times out or selector isn't found
     * @throws IOException      if file cannot be written
     */
    public void screenshot(String selector) throws IOException {
        scrollIntoView(selector);
        screenshot();
    }



    /**
     * Takes a screenshot of a specific region.
     *
     * @param box the bounding box defining the region to capture
     * @return the screenshot as PNG bytes
     * @throws TimeoutException if the operation times out
     * @throws IllegalArgumentException if box is null or invalid
     */
    public byte[] screenshotRegionBytes(BoundingBox box) {
        if (box == null) {
            throw new IllegalArgumentException("BoundingBox cannot be null");
        }
        if (!box.isValid()) {
            throw new IllegalArgumentException("BoundingBox is invalid: " + box);
        }

        try {
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
        } catch (TimeoutException e) {
            throw new org.nodriver4j.core.exceptions.ScriptExecutionException("Screenshot capture failed", e);
        }
    }

    /**
     * Takes a screenshot of the page.
     *
     * @return the screenshot as PNG bytes
     * @throws TimeoutException if the operation times out
     */
    public byte[] screenshotBytes() {
        try {
            ensurePageEnabled();

            JsonObject params = new JsonObject();
            params.addProperty("format", "png");

            JsonObject result = cdp.send("Page.captureScreenshot", params);
            String data = result.get("data").getAsString();

            return Base64.getDecoder().decode(data);
        } catch (TimeoutException e) {
            throw new org.nodriver4j.core.exceptions.ScriptExecutionException("Screenshot capture failed", e);
        }
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
    public byte[] screenshotElementBytes(String selector) {
        BoundingBox box = querySelector(selector);
        if (box == null) {
            throw new org.nodriver4j.core.exceptions.ElementNotFoundException(selector);
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
    public List<JsonObject> getCookies() {
        try {
            ensureNetworkEnabled();
            JsonObject result = cdp.send("Network.getAllCookies", null);
            JsonArray cookiesArray = result.getAsJsonArray("cookies");

            List<JsonObject> cookies = new ArrayList<>();
            for (JsonElement element : cookiesArray) {
                cookies.add(element.getAsJsonObject());
            }
            return cookies;
        } catch (TimeoutException e) {
            throw new org.nodriver4j.core.exceptions.ScriptExecutionException("Failed to get cookies", e);
        }
    }

    public void setCookie(String name, String value, String domain) {
        setCookie(name, value, domain, "/", false, false);
    }

    public void setCookie(String name, String value, String domain, String path,
                          boolean secure, boolean httpOnly) {
        try {
            ensureNetworkEnabled();
            JsonObject params = new JsonObject();
            params.addProperty("name", name);
            params.addProperty("value", value);
            params.addProperty("domain", domain);
            params.addProperty("path", path);
            params.addProperty("secure", secure);
            params.addProperty("httpOnly", httpOnly);
            cdp.send("Network.setCookie", params);
        } catch (TimeoutException e) {
            throw new org.nodriver4j.core.exceptions.ScriptExecutionException("Failed to set cookie", e);
        }
    }

    public void deleteCookies() {
        try {
            ensureNetworkEnabled();
            cdp.send("Network.clearBrowserCookies", null);
        } catch (TimeoutException e) {
            throw new org.nodriver4j.core.exceptions.ScriptExecutionException("Failed to delete cookies", e);
        }
    }

    public void deleteCookie(String name, String domain) {
        try {
            ensureNetworkEnabled();
            JsonObject params = new JsonObject();
            params.addProperty("name", name);
            params.addProperty("domain", domain);
            cdp.send("Network.deleteCookies", params);
        } catch (TimeoutException e) {
            throw new org.nodriver4j.core.exceptions.ScriptExecutionException("Failed to delete cookie", e);
        }
    }

    public Browser browser(){
        return browser;
    }

    ElementQuery elementQuery() {
        return elementQuery;
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