package org.nodriver4j.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.nodriver4j.core.exceptions.ElementNotFoundException;
import org.nodriver4j.core.exceptions.ScriptExecutionException;
import org.nodriver4j.math.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Element query, inspection, and wait operations for a browser page.
 *
 * <p>Handles finding elements by selector (XPath or CSS), reading element
 * properties (text, attributes, values, visibility), and polling until
 * elements appear or disappear.</p>
 *
 * <p>This is an internal implementation class — scripts interact with
 * these operations through {@link Page}'s public API.</p>
 */
class ElementQuery {

    private final Page page;

    ElementQuery(Page page) {
        this.page = page;
    }

    // ==================== Selector Type Detection ====================

    /**
     * Determines if a selector is XPath (vs CSS).
     *
     * <p>XPath selectors start with "/" (absolute or descendant) or "(" (for grouped expressions).
     * Everything else is treated as a CSS selector.</p>
     */
    static boolean isXPath(String selector) {
        if (selector == null || selector.isEmpty()) {
            return false;
        }
        String trimmed = selector.trim();
        return trimmed.startsWith("/") || trimmed.startsWith("(");
    }

    // ==================== Core Query Methods ====================

    /**
     * Finds the first element matching the selector using the default timeout.
     *
     * @param selector the XPath or CSS selector
     * @return the element's bounding box, or null if not found
     */
    BoundingBox querySelector(String selector) {
        return querySelector(selector, page.options().getDefaultTimeout());
    }

    /**
     * Finds the first element matching the selector.
     *
     * @param selector  the XPath or CSS selector
     * @param timeoutMs timeout to wait for element
     * @return the element's bounding box, or null if not found
     */
    BoundingBox querySelector(String selector, int timeoutMs) {
        enableRuntime();

        String script = isXPath(selector)
                ? buildXPathScript(selector, false)
                : buildCssScript(selector, false);

        long deadline = System.currentTimeMillis() + timeoutMs;
        int retryCount = 0;

        do {
            try {
                String result = page.evaluate(script);

                if (result != null && !result.equals("null") && !result.isEmpty()) {
                    return parseBoundingBox(result);
                }
            } catch (Exception e) {
                // Element not found yet, retry
            }

            if (timeoutMs == 0 || retryCount >= page.options().getMaxRetries()) {
                break;
            }

            retryCount++;
            page.sleep(page.options().getRetryInterval());
        } while (System.currentTimeMillis() < deadline);

        return null;
    }

    /**
     * Finds all elements matching the selector.
     *
     * @param selector the XPath or CSS selector
     * @return list of element bounding boxes
     */
    List<BoundingBox> querySelectorAll(String selector) {
        enableRuntime();

        String script = isXPath(selector)
                ? buildXPathScript(selector, true)
                : buildCssScript(selector, true);

        String result = eval(script);

        List<BoundingBox> boxes = new ArrayList<>();
        if (result != null && !result.equals("null") && !result.equals("[]")) {
            JsonArray array = JsonParser.parseString(result).getAsJsonArray();
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

    // ==================== Element State Methods ====================

    /**
     * Checks if an element exists.
     *
     * @param selector the XPath or CSS selector
     * @return true if element exists
     */
    boolean exists(String selector) {
        enableRuntime();

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

        String result = eval(script);
        return "true".equals(result);
    }

    /**
     * Checks if an element is visible.
     *
     * @param selector the XPath or CSS selector
     * @return true if element is visible
     */
    boolean isVisible(String selector) {
        enableRuntime();

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

        String result = eval(script);
        return "true".equals(result);
    }

    /**
     * Gets the inner text of an element.
     *
     * @param selector the XPath or CSS selector
     * @return the inner text, or null if not found
     */
    String getText(String selector) {
        enableRuntime();

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

        return eval(script);
    }

    /**
     * Gets an attribute value of an element.
     *
     * @param selector  the XPath or CSS selector
     * @param attribute the attribute name
     * @return the attribute value, or null if not found
     */
    String getAttribute(String selector, String attribute) {
        enableRuntime();

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

        return eval(script);
    }

    /**
     * Gets the value of an input element.
     *
     * @param selector the XPath or CSS selector
     * @return the input value, or null if not found
     */
    String getValue(String selector) {
        enableRuntime();

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

        return eval(script);
    }

    /**
     * Validates that an input field contains the expected value.
     *
     * @param selector      the XPath or CSS selector
     * @param expectedValue the expected value
     * @return true if the actual value matches the expected value
     */
    boolean validateValue(String selector, String expectedValue) {
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
     * @param selector     the XPath or CSS selector
     * @param expectedText the exact text expected
     * @return true if the element's innerText exactly matches expectedText
     */
    boolean containsText(String selector, String expectedText) {
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
     * @param selector     the XPath or CSS selector
     * @param expectedText the exact text expected (will be trimmed for comparison)
     * @return true if the element's trimmed innerText matches trimmed expectedText
     */
    boolean containsTextTrimmed(String selector, String expectedText) {
        String actualText = getText(selector);

        if (actualText == null && expectedText == null) {
            return true;
        }
        if (actualText == null || expectedText == null) {
            return false;
        }

        return actualText.trim().equals(expectedText.trim());
    }

    // ==================== Wait Methods ====================

    /**
     * Waits for an element to appear using the default timeout.
     *
     * @param selector the XPath or CSS selector
     * @return the element's bounding box
     * @throws ElementNotFoundException if element doesn't appear within timeout
     */
    BoundingBox waitForSelector(String selector) {
        return waitForSelector(selector, page.options().getDefaultTimeout());
    }

    /**
     * Waits for an element to appear.
     *
     * @param selector  the XPath or CSS selector
     * @param timeoutMs timeout in milliseconds
     * @return the element's bounding box
     * @throws ElementNotFoundException if element doesn't appear within timeout
     */
    BoundingBox waitForSelector(String selector, int timeoutMs) {
        enableRuntime();

        String predicate;
        if (isXPath(selector)) {
            predicate = String.format(
                    "(function() {" +
                    "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                    "  if (!el) return null;" +
                    "  var rect = el.getBoundingClientRect();" +
                    "  if (rect.width <= 0 || rect.height <= 0) return null;" +
                    "  return {x: rect.x, y: rect.y, width: rect.width, height: rect.height};" +
                    "})()",
                    escapeXPath(selector)
            );
        } else {
            predicate = String.format(
                    "(function() {" +
                    "  var el = document.querySelector(\"%s\");" +
                    "  if (!el) return null;" +
                    "  var rect = el.getBoundingClientRect();" +
                    "  if (rect.width <= 0 || rect.height <= 0) return null;" +
                    "  return {x: rect.x, y: rect.y, width: rect.width, height: rect.height};" +
                    "})()",
                    escapeCss(selector)
            );
        }

        String result = page.pollRaf(predicate, timeoutMs);
        if (result == null) {
            throw new ElementNotFoundException(selector);
        }
        return parseBoundingBox(result);
    }

    /**
     * Waits for an element to disappear using the default timeout.
     *
     * @param selector the XPath or CSS selector
     * @throws ElementNotFoundException if element doesn't disappear within timeout
     */
    void waitForSelectorHidden(String selector) {
        waitForSelectorHidden(selector, page.options().getDefaultTimeout());
    }

    /**
     * Waits for an element to disappear.
     *
     * @param selector  the XPath or CSS selector
     * @param timeoutMs timeout in milliseconds
     * @throws ElementNotFoundException if element doesn't disappear within timeout
     */
    void waitForSelectorHidden(String selector, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (!exists(selector) || !isVisible(selector)) {
                return;
            }
            page.sleep(page.options().getRetryInterval());
        }

        throw new ElementNotFoundException("Element still visible: " + selector, selector);
    }

    /**
     * Waits for an element to become visible using the default timeout.
     *
     * @param selector the XPath or CSS selector
     */
    void waitForVisible(String selector) {
        waitForVisible(selector, page.options().getDefaultTimeout());
    }

    /**
     * Waits for an element to become visible.
     *
     * @param selector  the XPath or CSS selector
     * @param timeoutMs timeout in milliseconds
     */
    void waitForVisible(String selector, int timeoutMs) {
        enableRuntime();

        String predicate;
        if (isXPath(selector)) {
            predicate = String.format(
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
            predicate = String.format(
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

        page.pollRaf(predicate, timeoutMs);
    }

    // ==================== Script Builders ====================

    String buildXPathScript(String xpath, boolean multiple) {
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

    String buildCssScript(String cssSelector, boolean multiple) {
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

    BoundingBox parseBoundingBox(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        return new BoundingBox(
                obj.get("x").getAsDouble(),
                obj.get("y").getAsDouble(),
                obj.get("width").getAsDouble(),
                obj.get("height").getAsDouble()
        );
    }

    // ==================== Escape Helpers (package-private) ====================

    static String escapeXPath(String xpath) {
        return xpath.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String escapeCss(String selector) {
        return selector.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String escapeJs(String str) {
        return str.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
    }

    // ==================== Internal Helpers ====================

    /**
     * Evaluates a script, wrapping TimeoutException as ScriptExecutionException.
     */
    private String eval(String script) {
        return page.evaluate(script);
    }

    /**
     * Enables the Runtime domain, wrapping TimeoutException.
     */
    private void enableRuntime() {
        try {
            page.ensureRuntimeEnabled();
        } catch (TimeoutException e) {
            throw new ScriptExecutionException("Failed to enable Runtime domain", e);
        }
    }
}
