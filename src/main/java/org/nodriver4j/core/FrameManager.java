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

            return evaluateViaContentWindow(iframeInfo.backendNodeId(), script);
        } catch (FrameException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new FrameException("Failed to evaluate in frame: " + iframeInfo.frameId(), e);
        }
    }

    String evaluateInFrame(String frameId, String script) {
        try {
            // Get the iframe's owner node from its frameId
            JsonObject ownerParams = new JsonObject();
            ownerParams.addProperty("frameId", frameId);
            JsonObject ownerResult = page.cdpSession().send("DOM.getFrameOwner", ownerParams);
            int backendNodeId = ownerResult.get("backendNodeId").getAsInt();

            return evaluateViaContentWindow(backendNodeId, script);
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

    /**
     * Evaluates a script in the iframe's main-world execution context.
     *
     * <p>Unlike {@link #createIframeContext} which creates an isolated world (no access to
     * page-defined JS globals), this method resolves the iframe's contentDocument via
     * {@code DOM.describeNode} / {@code DOM.resolveNode}, obtaining a RemoteObject reference
     * that lives in the iframe's own execution context. {@code Runtime.callFunctionOn} then
     * runs the script in that context with full access to the iframe's globals.</p>
     */
    private String evaluateViaContentWindow(int backendNodeId, String script) throws TimeoutException {
        // Step 1: Describe the iframe node to obtain its contentDocument
        JsonObject describeParams = new JsonObject();
        describeParams.addProperty("backendNodeId", backendNodeId);
        describeParams.addProperty("depth", 0);
        JsonObject describeResult = page.cdpSession().send("DOM.describeNode", describeParams);
        JsonObject node = describeResult.getAsJsonObject("node");

        if (!node.has("contentDocument")) {
            throw new FrameException("Iframe has no contentDocument (backendNodeId=" + backendNodeId
                    + "). The iframe may not have loaded yet.");
        }

        JsonObject contentDoc = node.getAsJsonObject("contentDocument");
        int docBackendNodeId = contentDoc.get("backendNodeId").getAsInt();

        // Step 2: Resolve the contentDocument to a RemoteObject.
        // DOM.resolveNode resolves a node in its OWNING execution context — for a document
        // that belongs to the iframe, this is the iframe's main-world context.
        JsonObject resolveParams = new JsonObject();
        resolveParams.addProperty("backendNodeId", docBackendNodeId);
        JsonObject resolveResult = page.cdpSession().send("DOM.resolveNode", resolveParams);

        if (!resolveResult.has("object") || !resolveResult.getAsJsonObject("object").has("objectId")) {
            throw new FrameException("Could not resolve iframe contentDocument (backendNodeId=" + backendNodeId + ")");
        }
        String docObjectId = resolveResult.getAsJsonObject("object").get("objectId").getAsString();

        // Step 3: Evaluate the script via callFunctionOn the document object.
        // Because docObjectId belongs to the iframe's context, the function executes there —
        // `document`, `window`, and all page-defined globals refer to the iframe's scope.
        JsonObject evalParams = new JsonObject();
        evalParams.addProperty("objectId", docObjectId);
        evalParams.addProperty("functionDeclaration", "function() {\n  return (" + script + ");\n}");
        evalParams.addProperty("returnByValue", true);
        evalParams.addProperty("awaitPromise", true);
        JsonObject result = page.cdpSession().send("Runtime.callFunctionOn", evalParams);

        if (result.has("exceptionDetails")) {
            System.err.println("[Page] Script exception in frame: " + result.getAsJsonObject("exceptionDetails"));
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
        // Compute the absolute position of the target element
        BoundingBox iframeBox = iframeInfo.boundingBox();
        BoundingBox elementBox = querySelectorInFrame(iframeInfo, selector);
        if (elementBox == null) {
            throw new ElementNotFoundException("Element not found in iframe: " + selector, selector);
        }

        BoundingBox absoluteBox = new BoundingBox(
                iframeBox.getX() + elementBox.getX(),
                iframeBox.getY() + elementBox.getY(),
                elementBox.getWidth(),
                elementBox.getHeight()
        );

        // Scroll the target element (not just the iframe) into view
        page.scrollIntoViewIfNeeded(absoluteBox);

        // Re-query after scroll — both iframe and element positions may have changed
        iframeBox = refreshIframeBox(iframeInfo);
        elementBox = querySelectorInFrame(iframeInfo, selector);
        if (elementBox == null) {
            throw new ElementNotFoundException("Element not found in iframe: " + selector, selector);
        }

        absoluteBox = new BoundingBox(
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

    // ==================== Nested Iframe Click ====================

    /**
     * Clicks an element inside a nested iframe (iframe within an iframe).
     *
     * <p>Accumulates viewport offsets through the frame chain. The outer frame's
     * bounding box is in page-viewport coordinates; the nested iframe's bounding
     * box is relative to the outer frame's content area.</p>
     *
     * @param outerFrame            the parent iframe (resolved from the main page)
     * @param nestedIframeSelector  CSS selector for the iframe element <em>inside</em> the outer frame
     * @param elementSelector       CSS selector for the target element inside the nested frame
     */
    void clickInNestedFrames(IframeInfo outerFrame, String nestedIframeSelector, String elementSelector) {
        // Get the nested iframe's content area relative to the outer frame
        BoundingBox nestedContentArea = getIframeContentAreaInFrame(outerFrame, nestedIframeSelector);
        if (nestedContentArea == null) {
            throw new FrameException("Nested iframe not found in parent frame: " + nestedIframeSelector);
        }

        // Build an IframeInfo for the nested frame so we can evaluate inside it
        IframeInfo nestedFrame = getNestedIframeInfo(outerFrame, nestedIframeSelector);

        BoundingBox elementBox = querySelectorInFrame(nestedFrame, elementSelector);
        if (elementBox == null) {
            throw new ElementNotFoundException("Element not found in nested iframe: " + elementSelector, elementSelector);
        }

        // Absolute position = outer content origin + nested content origin (local) + element (local)
        BoundingBox outerBox = outerFrame.boundingBox();
        BoundingBox absoluteBox = new BoundingBox(
                outerBox.getX() + nestedContentArea.getX() + elementBox.getX(),
                outerBox.getY() + nestedContentArea.getY() + elementBox.getY(),
                elementBox.getWidth(),
                elementBox.getHeight()
        );

        System.out.println("[Page] Clicking in nested iframe at absolute position: " + absoluteBox);
        page.clickAtBox(absoluteBox);
    }

    /**
     * Gets the content-area bounding box of an iframe element within a parent frame.
     * Accounts for the nested iframe's border and padding.
     *
     * @return bounding box relative to the parent frame's content area, or null
     */
    private BoundingBox getIframeContentAreaInFrame(IframeInfo parentFrame, String nestedIframeSelector) {
        String script = String.format(
                "(function() {" +
                        "  var iframe = document.querySelector(\"%s\");" +
                        "  if (!iframe) return null;" +
                        "  var rect = iframe.getBoundingClientRect();" +
                        "  var s = window.getComputedStyle(iframe);" +
                        "  var bL = parseFloat(s.borderLeftWidth)||0;" +
                        "  var bT = parseFloat(s.borderTopWidth)||0;" +
                        "  var bR = parseFloat(s.borderRightWidth)||0;" +
                        "  var bB = parseFloat(s.borderBottomWidth)||0;" +
                        "  var pL = parseFloat(s.paddingLeft)||0;" +
                        "  var pT = parseFloat(s.paddingTop)||0;" +
                        "  var pR = parseFloat(s.paddingRight)||0;" +
                        "  var pB = parseFloat(s.paddingBottom)||0;" +
                        "  return JSON.stringify({" +
                        "    x: rect.x + bL + pL," +
                        "    y: rect.y + bT + pT," +
                        "    width: rect.width - bL - pL - bR - pR," +
                        "    height: rect.height - bT - pT - bB - pB" +
                        "  });" +
                        "})()",
                ElementQuery.escapeCss(nestedIframeSelector)
        );

        String result = evaluateInFrame(parentFrame, script);
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
     * Discovers a nested iframe's metadata from within a parent frame.
     * Uses the parent frame's execution context to describe the nested iframe node.
     */
    private IframeInfo getNestedIframeInfo(IframeInfo parentFrame, String nestedIframeSelector) {
        // Get the nested iframe's frameId by evaluating DOM commands in the parent frame context
        String script = String.format(
                "(function() {" +
                        "  var iframe = document.querySelector(\"%s\");" +
                        "  if (!iframe) return null;" +
                        "  var rect = iframe.getBoundingClientRect();" +
                        "  return JSON.stringify({x: rect.x, y: rect.y, width: rect.width, height: rect.height});" +
                        "})()",
                ElementQuery.escapeCss(nestedIframeSelector)
        );

        String result = evaluateInFrame(parentFrame, script);
        if (result == null || result.equals("null")) {
            throw new FrameException("Nested iframe not found: " + nestedIframeSelector);
        }

        // We can't easily get the frameId from within a frame's JS context.
        // Use the parent frame's session to describe the node via CDP.
        // For now, construct an IframeInfo that supports evaluation via the
        // parent frame's contentDocument chain. Use the parent's frameId
        // as a marker — evaluateInFrame will resolve through contentDocument.
        try {
            JsonObject obj = JsonParser.parseString(result).getAsJsonObject();
            BoundingBox box = new BoundingBox(
                    obj.get("x").getAsDouble(),
                    obj.get("y").getAsDouble(),
                    obj.get("width").getAsDouble(),
                    obj.get("height").getAsDouble()
            );

            // Resolve the nested iframe's frameId via DOM operations in the main page
            // Walk the parent frame's DOM to find the nested iframe
            String frameIdScript = String.format(
                    "(function() {" +
                            "  var iframe = document.querySelector(\"%s\");" +
                            "  return iframe ? iframe.src : null;" +
                            "})()",
                    ElementQuery.escapeCss(nestedIframeSelector)
            );
            String nestedUrl = evaluateInFrame(parentFrame, frameIdScript);

            // Use the frame tree to find the nested frame's ID
            page.ensurePageEnabled();
            JsonObject frameTree = page.cdpSession().send("Page.getFrameTree", null);
            String nestedFrameId = findNestedFrameId(
                    frameTree.getAsJsonObject("frameTree"), parentFrame.frameId(), nestedUrl);

            if (nestedFrameId == null) {
                throw new FrameException("Could not resolve nested iframe frameId for: " + nestedIframeSelector);
            }

            return new IframeInfo(nestedFrameId, -1, box, nestedUrl);
        } catch (TimeoutException e) {
            throw new FrameException("Failed to resolve nested iframe: " + nestedIframeSelector, e);
        }
    }

    /**
     * Searches the frame tree for a child frame of the given parent whose URL matches.
     */
    private String findNestedFrameId(JsonObject frameTree, String parentFrameId, String targetUrl) {
        JsonObject frame = frameTree.getAsJsonObject("frame");
        String currentId = frame.get("id").getAsString();

        if (frameTree.has("childFrames")) {
            for (JsonElement child : frameTree.getAsJsonArray("childFrames")) {
                JsonObject childTree = child.getAsJsonObject();
                JsonObject childFrame = childTree.getAsJsonObject("frame");
                String childParentId = childFrame.has("parentId")
                        ? childFrame.get("parentId").getAsString() : "";

                // If this child's parent is our target parent frame
                if (parentFrameId != null && parentFrameId.equals(childParentId)) {
                    String childUrl = childFrame.has("url") ? childFrame.get("url").getAsString() : "";
                    if (targetUrl != null && childUrl.contains(targetUrl)) {
                        return childFrame.get("id").getAsString();
                    }
                }

                // Recurse
                String found = findNestedFrameId(childTree, parentFrameId, targetUrl);
                if (found != null) return found;
            }
        }

        return null;
    }

    // ==================== In-Frame Screenshot ====================

    byte[] screenshotElementInFrame(IframeInfo iframeInfo, String selector) {
        BoundingBox iframeBox = iframeInfo.boundingBox();
        BoundingBox elementBox = querySelectorInFrame(iframeInfo, selector);
        if (elementBox == null) {
            throw new ElementNotFoundException("Element not found in iframe: " + selector, selector);
        }

        BoundingBox absoluteBox = new BoundingBox(
                iframeBox.getX() + elementBox.getX(),
                iframeBox.getY() + elementBox.getY(),
                elementBox.getWidth(),
                elementBox.getHeight()
        );

        // Scroll the target element into view, then re-query
        page.scrollIntoViewIfNeeded(absoluteBox);

        iframeBox = refreshIframeBox(iframeInfo);
        elementBox = querySelectorInFrame(iframeInfo, selector);
        if (elementBox == null) {
            throw new ElementNotFoundException("Element not found in iframe: " + selector, selector);
        }

        absoluteBox = new BoundingBox(
                iframeBox.getX() + elementBox.getX(),
                iframeBox.getY() + elementBox.getY(),
                elementBox.getWidth(),
                elementBox.getHeight()
        );

        return page.screenshotRegionBytes(absoluteBox);
    }

    /**
     * Re-queries the iframe's content-area bounding box after a scroll.
     * Falls back to the original box if the re-query fails.
     */
    private BoundingBox refreshIframeBox(IframeInfo iframeInfo) {
        if (iframeInfo.backendNodeId() > 0) {
            BoundingBox fresh = getNodeBoundingBox(iframeInfo.backendNodeId());
            if (fresh != null) return fresh;
        }
        return iframeInfo.boundingBox();
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

    /**
     * Gets the iframe's <em>content area</em> bounding box via JS.
     *
     * <p>Subtracts border and padding from the border box returned by
     * {@code getBoundingClientRect} so that element coordinates within the
     * iframe (which are relative to the content area) map correctly to
     * viewport coordinates. CSS transforms are handled implicitly because
     * {@code getBoundingClientRect} returns the visual (post-transform) rect.</p>
     */
    private BoundingBox getIframeBoundingBoxViaJs(String iframeSelector, int index) {
        try {
            page.ensureRuntimeEnabled();

            String script = String.format(
                    "(function() {" +
                            "  var iframes = document.querySelectorAll(\"%s\");" +
                            "  if (!iframes || iframes.length <= %d) return null;" +
                            "  var iframe = iframes[%d];" +
                            "  var rect = iframe.getBoundingClientRect();" +
                            "  var s = window.getComputedStyle(iframe);" +
                            "  var bL = parseFloat(s.borderLeftWidth)||0;" +
                            "  var bT = parseFloat(s.borderTopWidth)||0;" +
                            "  var bR = parseFloat(s.borderRightWidth)||0;" +
                            "  var bB = parseFloat(s.borderBottomWidth)||0;" +
                            "  var pL = parseFloat(s.paddingLeft)||0;" +
                            "  var pT = parseFloat(s.paddingTop)||0;" +
                            "  var pR = parseFloat(s.paddingRight)||0;" +
                            "  var pB = parseFloat(s.paddingBottom)||0;" +
                            "  return JSON.stringify({" +
                            "    x: rect.x + bL + pL," +
                            "    y: rect.y + bT + pT," +
                            "    width: rect.width - bL - pL - bR - pR," +
                            "    height: rect.height - bT - pT - bB - pB" +
                            "  });" +
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
