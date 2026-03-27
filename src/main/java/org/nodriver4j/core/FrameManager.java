package org.nodriver4j.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.nodriver4j.core.exceptions.ElementNotFoundException;
import org.nodriver4j.core.exceptions.FrameException;
import org.nodriver4j.core.exceptions.ScriptExecutionException;
import org.nodriver4j.math.BoundingBox;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Manages iframe attachment, context creation, and cross-frame operations.
 *
 * <p>Handles both same-origin iframes (via isolated worlds) and
 * out-of-process iframes / OOPIFs (via session-based attachment).</p>
 *
 * <p>This is an internal implementation class — scripts interact with
 * these operations through {@link Page}'s public API.</p>
 */
class FrameManager {

    /**
     * Holds information about an iframe needed for interaction.
     */
    public record IframeInfo(String frameId, int backendNodeId, BoundingBox boundingBox, String url) {}

    private final Page page;
    private final ConcurrentHashMap<String, String> oopifSessions = new ConcurrentHashMap<>();

    FrameManager(Page page) {
        this.page = page;
    }

    // ==================== Iframe Discovery ====================

    IframeInfo getIframeInfo(String iframeSelector) {
        return getIframeInfo(iframeSelector, 0);
    }

    IframeInfo getIframeInfo(String iframeSelector, int index) {
        try {
            // Step 1: Get document root
            JsonObject docParams = new JsonObject();
            docParams.addProperty("depth", 0);
            JsonObject docResult = page.cdpSession().send("DOM.getDocument", docParams);
            int rootNodeId = docResult.getAsJsonObject("root").get("nodeId").getAsInt();

            // Step 2: Query for all matching iframes
            JsonObject queryParams = new JsonObject();
            queryParams.addProperty("nodeId", rootNodeId);
            queryParams.addProperty("selector", iframeSelector);

            JsonObject queryResult = page.cdpSession().send("DOM.querySelectorAll", queryParams);

            if (!queryResult.has("nodeIds")) {
                throw new FrameException("Iframe not found: " + iframeSelector);
            }

            JsonArray nodeIds = queryResult.getAsJsonArray("nodeIds");

            if (nodeIds.isEmpty() || nodeIds.size() <= index) {
                throw new FrameException("Iframe not found: " + iframeSelector + " at index " + index +
                        " (found " + nodeIds.size() + " matches)");
            }

            int iframeNodeId = nodeIds.get(index).getAsInt();

            // Step 3: Describe the iframe node to get frameId
            JsonObject describeParams = new JsonObject();
            describeParams.addProperty("nodeId", iframeNodeId);
            describeParams.addProperty("depth", 0);

            JsonObject describeResult = page.cdpSession().send("DOM.describeNode", describeParams);

            if (!describeResult.has("node")) {
                throw new FrameException("Could not describe iframe node: " + iframeSelector);
            }

            JsonObject node = describeResult.getAsJsonObject("node");

            String nodeName = node.has("nodeName") ? node.get("nodeName").getAsString() : "";
            if (!"IFRAME".equalsIgnoreCase(nodeName)) {
                throw new FrameException("Selector matched non-iframe element: " + nodeName);
            }

            if (!node.has("frameId")) {
                throw new FrameException("Iframe node has no frameId - iframe may not be loaded yet");
            }

            String frameId = node.get("frameId").getAsString();
            int backendNodeId = node.has("backendNodeId") ? node.get("backendNodeId").getAsInt() : -1;

            // Step 4: Get bounding box
            BoundingBox boundingBox = getNodeBoundingBox(backendNodeId);

            if (boundingBox == null) {
                boundingBox = getIframeBoundingBoxViaJs(iframeSelector, index);
            }

            if (boundingBox == null) {
                throw new FrameException("Could not get bounding box for iframe: " + iframeSelector);
            }

            String iframeUrl = getIframeUrlFromNode(iframeNodeId);

            System.out.println("[Page] Found iframe: frameId=" + frameId +
                    ", backendNodeId=" + backendNodeId + ", url=" + iframeUrl + ", bounds=" + boundingBox);

            return new IframeInfo(frameId, backendNodeId, boundingBox, iframeUrl);

        } catch (FrameException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new FrameException("Failed to get iframe info: " + iframeSelector, e);
        }
    }

    // ==================== Iframe Context & Evaluation ====================

    int createIframeContext(String frameId) {
        try {
            page.ensurePageEnabled();

            JsonObject params = new JsonObject();
            params.addProperty("frameId", frameId);
            params.addProperty("worldName", "nodriver4j_iframe_" + System.currentTimeMillis());

            JsonObject result = page.cdpSession().send("Page.createIsolatedWorld", params);
            return result.get("executionContextId").getAsInt();
        } catch (TimeoutException e) {
            throw new FrameException("Failed to create iframe context for frame: " + frameId, e);
        }
    }

    String evaluateInFrame(IframeInfo iframeInfo, String script) {
        try {
            String frameId = iframeInfo.frameId();

            if (!isFrameInTree(frameId)) {
                if (iframeInfo.url() == null) {
                    throw new FrameException("Cannot evaluate in OOPIF without URL");
                }

                String sessionId = attachToOOPIF(iframeInfo.url());
                return evaluateInSession(sessionId, script);
            }

            int contextId = createIframeContext(frameId);
            return evaluateWithContext(contextId, script);
        } catch (FrameException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new FrameException("Failed to evaluate in frame: " + iframeInfo.frameId(), e);
        }
    }

    String evaluateInFrame(String frameId, String script) {
        try {
            int contextId = createIframeContext(frameId);
            return evaluateWithContext(contextId, script);
        } catch (FrameException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new FrameException("Failed to evaluate in frame: " + frameId, e);
        }
    }

    private String evaluateInSession(String sessionId, String script) throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("expression", script);
        params.addProperty("returnByValue", true);
        params.addProperty("awaitPromise", true);

        JsonObject result = page.cdpSession().sendWithSession("Runtime.evaluate", params, sessionId);

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

    private String evaluateWithContext(int contextId, String script) throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("contextId", contextId);
        params.addProperty("expression", script);
        params.addProperty("returnByValue", true);
        params.addProperty("awaitPromise", true);

        JsonObject result = page.cdpSession().send("Runtime.evaluate", params);

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

    // ==================== In-Frame Queries ====================

    BoundingBox querySelectorInFrame(IframeInfo iframeInfo, String selector) {
        String script = String.format(
                "(function() {" +
                        "  var el = document.querySelector(\"%s\");" +
                        "  if (!el) return null;" +
                        "  var rect = el.getBoundingClientRect();" +
                        "  return JSON.stringify({x: rect.x, y: rect.y, width: rect.width, height: rect.height});" +
                        "})()",
                ElementQuery.escapeCss(selector)
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

    String getTextInFrame(IframeInfo iframeInfo, String selector) {
        String script = String.format(
                "(function() {" +
                        "  var el = document.querySelector(\"%s\");" +
                        "  return el ? el.innerText || el.textContent : null;" +
                        "})()",
                ElementQuery.escapeCss(selector)
        );

        return evaluateInFrame(iframeInfo, script);
    }

    boolean existsInFrame(IframeInfo iframeInfo, String selector) {
        String script = String.format(
                "document.querySelector(\"%s\") !== null",
                ElementQuery.escapeCss(selector)
        );

        String result = evaluateInFrame(iframeInfo, script);
        return "true".equals(result);
    }

    boolean hasClassInFrame(IframeInfo iframeInfo, String selector, String className) {
        String script = String.format(
                "(function() {" +
                        "  var el = document.querySelector(\"%s\");" +
                        "  return el ? el.classList.contains('%s') : false;" +
                        "})()",
                ElementQuery.escapeCss(selector), ElementQuery.escapeJs(className)
        );

        String result = evaluateInFrame(iframeInfo, script);
        return "true".equals(result);
    }

    // ==================== In-Frame Click ====================

    void clickInFrame(IframeInfo iframeInfo, String selector) {
        BoundingBox elementBox = querySelectorInFrame(iframeInfo, selector);
        if (elementBox == null) {
            throw new ElementNotFoundException("Element not found in iframe: " + selector, selector);
        }

        BoundingBox iframeBox = iframeInfo.boundingBox();
        BoundingBox absoluteBox = new BoundingBox(
                iframeBox.getX() + elementBox.getX(),
                iframeBox.getY() + elementBox.getY(),
                elementBox.getWidth(),
                elementBox.getHeight()
        );

        System.out.println("[Page] Clicking in iframe at absolute position: " + absoluteBox);
        page.clickAtBox(absoluteBox);
    }

    void clickInFrame(String iframeSelector, String elementSelector) {
        clickInFrame(iframeSelector, 0, elementSelector);
    }

    void clickInFrame(String iframeSelector, int iframeIndex, String elementSelector) {
        IframeInfo iframeInfo = getIframeInfo(iframeSelector, iframeIndex);
        clickInFrame(iframeInfo, elementSelector);
    }

    // ==================== In-Frame Screenshot ====================

    byte[] screenshotElementInFrame(IframeInfo iframeInfo, String selector) {
        BoundingBox elementBox = querySelectorInFrame(iframeInfo, selector);
        if (elementBox == null) {
            throw new ElementNotFoundException("Element not found in iframe: " + selector, selector);
        }

        BoundingBox iframeBox = iframeInfo.boundingBox();
        BoundingBox absoluteBox = new BoundingBox(
                iframeBox.getX() + elementBox.getX(),
                iframeBox.getY() + elementBox.getY(),
                elementBox.getWidth(),
                elementBox.getHeight()
        );

        return page.screenshotRegionBytes(absoluteBox);
    }

    // ==================== Node Bounding Box ====================

    BoundingBox getNodeBoundingBox(int backendNodeId) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("backendNodeId", backendNodeId);

            JsonObject result = page.cdpSession().send("DOM.getBoxModel", params);

            if (!result.has("model")) {
                System.err.println("[Page] DOM.getBoxModel returned no model");
                return null;
            }

            JsonObject model = result.getAsJsonObject("model");

            if (!model.has("content")) {
                System.err.println("[Page] Box model has no content quad");
                return null;
            }

            JsonArray contentQuad = model.getAsJsonArray("content");

            double x1 = contentQuad.get(0).getAsDouble();
            double y1 = contentQuad.get(1).getAsDouble();
            double x2 = contentQuad.get(2).getAsDouble();
            double y3 = contentQuad.get(5).getAsDouble();

            double width = x2 - x1;
            double height = y3 - y1;

            return new BoundingBox(x1, y1, width, height);
        } catch (TimeoutException e) {
            return null;
        }
    }

    // ==================== OOPIF Attachment ====================

    private String attachToOOPIF(String targetUrl) throws TimeoutException {
        String cached = oopifSessions.get(targetUrl);
        if (cached != null) {
            return cached;
        }

        JsonObject targetsResult = page.cdpSession().send("Target.getTargets", null);
        JsonArray targetInfos = targetsResult.getAsJsonArray("targetInfos");

        String targetId = null;
        for (JsonElement elem : targetInfos) {
            JsonObject target = elem.getAsJsonObject();
            String type = target.get("type").getAsString();
            String url = target.has("url") ? target.get("url").getAsString() : "";

            if ("iframe".equals(type) && urlMatchesTarget(targetUrl, url)) {
                targetId = target.get("targetId").getAsString();
                System.out.println("[Page] Found OOPIF target: " + targetId + " for URL: " + url);
                break;
            }
        }

        if (targetId == null) {
            throw new FrameException("No OOPIF target found for URL: " + targetUrl);
        }

        JsonObject attachParams = new JsonObject();
        attachParams.addProperty("targetId", targetId);
        attachParams.addProperty("flatten", true);

        JsonObject attachResult = page.cdpSession().send("Target.attachToTarget", attachParams);
        String sessionId = attachResult.get("sessionId").getAsString();

        System.out.println("[Page] Attached to OOPIF, sessionId: " + sessionId);

        oopifSessions.put(targetUrl, sessionId);

        return sessionId;
    }

    // ==================== Frame Tree ====================

    private boolean isFrameInTree(String frameId) throws TimeoutException {
        page.ensurePageEnabled();
        JsonObject frameTreeResult = page.cdpSession().send("Page.getFrameTree", null);
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

    // ==================== URL Matching ====================

    private boolean urlMatchesTarget(String iframeSrc, String targetUrl) {
        if (iframeSrc == null || targetUrl == null) return false;

        try {
            java.net.URI srcUri = java.net.URI.create(iframeSrc);
            java.net.URI targetUri = java.net.URI.create(targetUrl);

            if (!java.util.Objects.equals(srcUri.getScheme(), targetUri.getScheme()) ||
                    !java.util.Objects.equals(srcUri.getHost(), targetUri.getHost()) ||
                    !java.util.Objects.equals(srcUri.getPath(), targetUri.getPath())) {
                return false;
            }

            java.util.Map<String, String> srcParams = parseQueryParams(srcUri.getQuery());
            java.util.Map<String, String> targetParams = parseQueryParams(targetUri.getQuery());

            String[] keyParams = {"k", "size"};

            for (String param : keyParams) {
                String srcValue = srcParams.get(param);
                String targetValue = targetParams.get(param);

                if (srcValue != null && !srcValue.equals(targetValue)) {
                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            return iframeSrc.equals(targetUrl);
        }
    }

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

    private String getIframeUrlFromNode(int nodeId) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("nodeId", nodeId);
            JsonObject result = page.cdpSession().send("DOM.getAttributes", params);

            if (result.has("attributes")) {
                JsonArray attrs = result.getAsJsonArray("attributes");
                for (int i = 0; i < attrs.size() - 1; i += 2) {
                    if ("src".equals(attrs.get(i).getAsString())) {
                        return attrs.get(i + 1).getAsString();
                    }
                }
            }
        } catch (TimeoutException e) {
            // URL is optional
        }
        return null;
    }

    private BoundingBox getIframeBoundingBoxViaJs(String iframeSelector, int index) {
        try {
            page.ensureRuntimeEnabled();

            String script = String.format(
                    "(function() {" +
                            "  var iframes = document.querySelectorAll(\"%s\");" +
                            "  if (!iframes || iframes.length <= %d) return null;" +
                            "  var iframe = iframes[%d];" +
                            "  var rect = iframe.getBoundingClientRect();" +
                            "  return JSON.stringify({x: rect.x, y: rect.y, width: rect.width, height: rect.height});" +
                            "})()",
                    ElementQuery.escapeCss(iframeSelector), index, index
            );

            String result = page.evaluate(script);
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
}
