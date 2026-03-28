package org.nodriver4j.core;

import com.google.gson.JsonObject;
import org.nodriver4j.core.exceptions.ElementNotFoundException;
import org.nodriver4j.core.exceptions.ElementNotInteractableException;
import org.nodriver4j.core.exceptions.ScriptExecutionException;
import org.nodriver4j.math.BoundingBox;
import org.nodriver4j.math.HumanBehavior;
import org.nodriver4j.math.Vector;

import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * Mouse, keyboard, and scroll input for a browser page.
 *
 * <p>All mouse interactions use human-like movement (Bezier curves, Fitts's Law)
 * and timing (Gaussian delays). Keyboard input uses realistic keystroke timing
 * with context-aware delays.</p>
 *
 * <p>This is an internal implementation class — scripts interact with
 * these operations through {@link Page}'s public API.</p>
 */
class InputController {

    private final Page page;
    private volatile Vector mousePosition;

    InputController(Page page) {
        this.page = page;
        this.mousePosition = Vector.ORIGIN;
    }

    Vector mousePosition() {
        return mousePosition;
    }

    // ==================== Mouse Interaction ====================

    void click(String selector) {
        click(selector, false);
    }

    void click(String selector, boolean force) {
        if (force) {
            scrollIntoView(selector);
            BoundingBox box = page.waitForSelector(selector);
            clickAtBox(box);
            return;
        }

        Actionability act = page.actionability();

        // Wait for element to be visible, stable, and enabled
        BoundingBox box = act.waitForActionable(selector,
                new String[]{"visible", "stable", "enabled"},
                page.options().getDefaultTimeout());

        // Scroll into view if needed
        scrollIntoViewIfNeeded(box);

        // Re-query position after potential scroll
        box = page.waitForSelector(selector, 2000);

        // Determine click point
        Vector target = box.getRandomPoint(page.options().getPaddingPercentage());

        // Verify hit target
        act.verifyHitTarget(selector, target.getX(), target.getY());

        // Perform the click
        moveMouseTo(target);
        performClick(target);
    }

    void clickAt(double x, double y) {
        Vector target = new Vector(x, y);
        moveMouseTo(target);
        performClick(target);
    }

    void clickAtBox(BoundingBox box) {
        Vector target = box.getRandomPoint(page.options().getPaddingPercentage());
        moveMouseTo(target);
        performClick(target);
    }

    void hover(String selector) {
        Actionability act = page.actionability();
        BoundingBox box = act.waitForActionable(selector,
                new String[]{"visible", "stable"},
                page.options().getDefaultTimeout());
        scrollIntoViewIfNeeded(box);
        box = page.waitForSelector(selector, 2000);
        Vector target = box.getRandomPoint(page.options().getPaddingPercentage());
        moveMouseTo(target);
    }

    void moveMouseTo(Vector target) {
        InteractionOptions options = page.options();

        if (!options.isSimulateMousePath()) {
            dispatchMouseMove(target);
            mousePosition = target;
            return;
        }

        if (options.isOvershootEnabled() &&
                HumanBehavior.shouldOvershoot(mousePosition, target, options.getOvershootThreshold())) {
            Vector overshootPoint = HumanBehavior.calculateOvershoot(target, options.getOvershootRadius());
            moveAlongPath(mousePosition, overshootPoint, null);
            moveAlongPath(overshootPoint, target, HumanBehavior.OVERSHOOT_SPREAD);
        } else {
            moveAlongPath(mousePosition, target, null);
        }

        if (options.getMoveDelayMax() > 0) {
            int delay = options.isRandomizeMoveDelay()
                    ? HumanBehavior.randomDelay(options.getMoveDelayMin(), options.getMoveDelayMax())
                    : options.getMoveDelayMax();
            page.sleep(delay);
        }
    }

    private void moveAlongPath(Vector from, Vector to, Double spreadOverride) {
        InteractionOptions options = page.options();
        Integer moveSpeed = options.getMoveSpeed() > 0 ? options.getMoveSpeed() : null;
        List<Vector> path = HumanBehavior.generatePath(from, to, moveSpeed, null, spreadOverride);

        for (Vector point : path) {
            Vector finalPoint = point;

            if (options.isJitterEnabled()) {
                finalPoint = point.addJitter(options.getJitterAmount());
            }

            dispatchMouseMove(finalPoint);
            mousePosition = finalPoint;
        }
    }

    private void performClick(Vector position) {
        InteractionOptions options = page.options();

        int hesitation = HumanBehavior.hesitationDelay(
                options.getPreClickDelayMin(), options.getPreClickDelayMax());
        page.sleep(hesitation);

        dispatchMouseButton(position, "mousePressed", "left", 1);
        int holdDuration = HumanBehavior.clickHoldDuration(
                options.getClickHoldDurationMin(), options.getClickHoldDurationMax());
        page.sleep(holdDuration);
        dispatchMouseButton(position, "mouseReleased", "left", 1);
    }

    private void dispatchMouseMove(Vector position) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("type", "mouseMoved");
            params.addProperty("x", position.getX());
            params.addProperty("y", position.getY());

            page.cdpSession().send("Input.dispatchMouseEvent", params);
        } catch (TimeoutException e) {
            throw new ScriptExecutionException("Failed to dispatch mouse move", e);
        }
    }

    private void dispatchMouseButton(Vector position, String type, String button, int clickCount) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("type", type);
            params.addProperty("x", position.getX());
            params.addProperty("y", position.getY());
            params.addProperty("button", button);
            params.addProperty("clickCount", clickCount);

            page.cdpSession().send("Input.dispatchMouseEvent", params);
        } catch (TimeoutException e) {
            throw new ScriptExecutionException("Failed to dispatch mouse button", e);
        }
    }

    void mouseDown(Vector position) {
        dispatchMouseButton(position, "mousePressed", "left", 1);
    }

    void mouseUp(Vector position) {
        dispatchMouseButton(position, "mouseReleased", "left", 1);
    }

    void jsClick(String selector) {
        enableRuntime();

        String script;
        if (ElementQuery.isXPath(selector)) {
            script = String.format(
                    "(function() {" +
                            "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                            "  if (!el) return 'NOT_FOUND';" +
                            "  el.click();" +
                            "  return 'OK';" +
                            "})()",
                    ElementQuery.escapeXPath(selector)
            );
        } else {
            script = String.format(
                    "(function() {" +
                            "  var el = document.querySelector(\"%s\");" +
                            "  if (!el) return 'NOT_FOUND';" +
                            "  el.click();" +
                            "  return 'OK';" +
                            "})()",
                    ElementQuery.escapeCss(selector)
            );
        }

        String result = eval(script);

        if (!"OK".equals(result)) {
            throw new ElementNotFoundException("Element not found for jsClick: " + selector, selector);
        }
    }

    // ==================== Keyboard Interaction ====================

    void type(String text) {
        type(text, 1.0);
    }

    void type(String text, double speedMultiplier) {
        if (speedMultiplier <= 0) {
            throw new IllegalArgumentException("speedMultiplier must be positive, got: " + speedMultiplier);
        }

        InteractionOptions options = page.options();
        int burstSize = Math.max(1, (int) Math.floor(speedMultiplier));
        double delayMultiplier = speedMultiplier / burstSize;
        boolean useBurstMode = burstSize >= 2;

        int scaledKeystrokeMin = Math.max(1, (int) (options.getKeystrokeDelayMin() / delayMultiplier));
        int scaledKeystrokeMax = Math.max(scaledKeystrokeMin, (int) (options.getKeystrokeDelayMax() / delayMultiplier));

        Character previousChar = null;
        int i = 0;

        while (i < text.length()) {
            char c = text.charAt(i);

            int delay;
            if (options.isContextAwareTyping()) {
                delay = HumanBehavior.keystrokeDelay(c, previousChar, scaledKeystrokeMin, scaledKeystrokeMax);
            } else {
                delay = HumanBehavior.keystrokeDelay(scaledKeystrokeMin, scaledKeystrokeMax);
            }

            int thinkingPause = HumanBehavior.thinkingPause(
                    options.getThinkingPauseProbability(),
                    options.getThinkingPauseMin(),
                    options.getThinkingPauseMax());
            if (thinkingPause > 0) {
                page.sleep((int) Math.max(1, thinkingPause / delayMultiplier));
            }

            if (c == '\n') {
                pressKey("Enter", false, false, false);
                previousChar = c;
                i++;
            } else if (useBurstMode) {
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
                boolean needsShift = isShiftRequired(c);
                String key = String.valueOf(c);
                pressKey(key, false, false, needsShift);
                previousChar = c;
                i++;
            }

            page.sleep(delay);
        }
    }

    private void insertText(String text) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("text", text);
            page.cdpSession().send("Input.insertText", params);
        } catch (TimeoutException e) {
            throw new ScriptExecutionException("Failed to insert text", e);
        }
    }

    void clear(String selector) {
        click(selector);
        page.sleep(50);
        pressKey("a", true, false, false);
        pressKey("Backspace", false, false, false);
    }

    void focus(String selector) {
        enableRuntime();

        String script;
        if (ElementQuery.isXPath(selector)) {
            script = String.format(
                    "(function() {" +
                            "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                            "  if (el) el.focus();" +
                            "})()",
                    ElementQuery.escapeXPath(selector)
            );
        } else {
            script = String.format(
                    "(function() {" +
                            "  var el = document.querySelector(\"%s\");" +
                            "  if (el) el.focus();" +
                            "})()",
                    ElementQuery.escapeCss(selector)
            );
        }

        eval(script);
    }

    void select(String selector, String value) {
        Actionability act = page.actionability();
        act.waitForActionable(selector,
                new String[]{"visible", "enabled"},
                page.options().getDefaultTimeout());

        enableRuntime();

        String script;
        if (ElementQuery.isXPath(selector)) {
            script = String.format(
                    "(function() {" +
                            "  var el = document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                            "  if (el) {" +
                            "    el.value = '%s';" +
                            "    el.dispatchEvent(new Event('change', { bubbles: true }));" +
                            "  }" +
                            "})()",
                    ElementQuery.escapeXPath(selector), ElementQuery.escapeJs(value)
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
                    ElementQuery.escapeCss(selector), ElementQuery.escapeJs(value)
            );
        }

        eval(script);
    }

    void pressKey(String key, boolean ctrl, boolean alt, boolean shift) {
        try {
            int modifiers = 0;
            if (alt) modifiers |= 1;
            if (ctrl) modifiers |= 2;
            if (shift) modifiers |= 8;

            String code = getKeyCode(key);
            int windowsVirtualKeyCode = getWindowsVirtualKeyCode(key);

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

            if (textToInsert != null) {
                keyDown.addProperty("text", textToInsert);
            }

            page.cdpSession().send("Input.dispatchKeyEvent", keyDown, 3, TimeUnit.SECONDS);

            JsonObject keyUp = new JsonObject();
            keyUp.addProperty("type", "keyUp");
            keyUp.addProperty("key", key);
            keyUp.addProperty("code", code);
            keyUp.addProperty("windowsVirtualKeyCode", windowsVirtualKeyCode);
            keyUp.addProperty("nativeVirtualKeyCode", windowsVirtualKeyCode);
            keyUp.addProperty("modifiers", modifiers);
            page.cdpSession().send("Input.dispatchKeyEvent", keyUp, 3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ScriptExecutionException("Failed to dispatch key event", e);
        }
    }

    private boolean isPrintableCharacter(String key) {
        if (key == null || key.length() != 1) {
            return false;
        }
        char c = key.charAt(0);
        return c >= 32 && c <= 126;
    }

    private String getKeyCode(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }

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
                    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                        yield "Key" + Character.toUpperCase(c);
                    }
                    if (c >= '0' && c <= '9') {
                        yield "Digit" + c;
                    }
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
                yield key;
            }
        };
    }

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

    private int getWindowsVirtualKeyCode(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }

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
                    if (c >= 'a' && c <= 'z') {
                        yield c - 'a' + 65;
                    }
                    if (c >= 'A' && c <= 'Z') {
                        yield c - 'A' + 65;
                    }
                    if (c >= '0' && c <= '9') {
                        yield c;
                    }
                    yield switch (c) {
                        case '!' -> 49;
                        case '@' -> 50;
                        case '#' -> 51;
                        case '$' -> 52;
                        case '%' -> 53;
                        case '^' -> 54;
                        case '&' -> 55;
                        case '*' -> 56;
                        case '(' -> 57;
                        case ')' -> 48;
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

    private boolean isShiftRequired(char c) {
        if (c >= 'A' && c <= 'Z') {
            return true;
        }
        return "~!@#$%^&*()_+{}|:\"<>?".indexOf(c) >= 0;
    }

    // ==================== Scrolling ====================

    void scrollBy(int deltaX, int deltaY) {
        InteractionOptions options = page.options();
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

            int delay = HumanBehavior.randomDelay(
                    options.getScrollDelayMin(), options.getScrollDelayMax());
            page.sleep(delay);
        }
    }

    void scrollTo(int x, int y) {
        enableRuntime();

        String currentX = eval("window.scrollX");
        String currentY = eval("window.scrollY");

        int deltaX = x - (int) Double.parseDouble(currentX);
        int deltaY = y - (int) Double.parseDouble(currentY);

        scrollBy(deltaX, deltaY);
    }

    void scrollIntoView(String selector) {
        BoundingBox box = page.querySelector(selector);
        if (box == null) {
            throw new ElementNotFoundException(selector);
        }

        enableRuntime();
        String viewportHeight = eval("window.innerHeight");
        String viewportWidth = eval("window.innerWidth");

        int vpHeight = Integer.parseInt(viewportHeight);
        int vpWidth = Integer.parseInt(viewportWidth);

        boolean inViewport = box.getTop() >= 0 && box.getLeft() >= 0 &&
                box.getBottom() <= vpHeight && box.getRight() <= vpWidth;

        if (!inViewport) {
            int deltaY = 0;
            int deltaX = 0;

            if (box.getTop() < 0) {
                deltaY = (int) box.getTop() - 50;
            } else if (box.getBottom() > vpHeight) {
                deltaY = (int) (box.getBottom() - vpHeight) + 50;
            }

            if (box.getLeft() < 0) {
                deltaX = (int) box.getLeft() - 50;
            } else if (box.getRight() > vpWidth) {
                deltaX = (int) (box.getRight() - vpWidth) + 50;
            }

            scrollBy(deltaX, deltaY);
            // Allow the browser to finish processing the final wheel event
            page.sleep(150);
        }
    }

    void scrollIntoViewIfNeeded(BoundingBox box) {
        enableRuntime();

        String viewportHeightStr = eval("window.innerHeight");
        String viewportWidthStr = eval("window.innerWidth");

        int vpHeight = Integer.parseInt(viewportHeightStr);
        int vpWidth = Integer.parseInt(viewportWidthStr);

        int margin = 50;
        boolean inViewport = box.getTop() >= margin &&
                box.getLeft() >= margin &&
                box.getBottom() <= vpHeight - margin &&
                box.getRight() <= vpWidth - margin;

        if (!inViewport) {
            System.out.println("[Page] Element not in viewport, scrolling...");

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
                page.sleep(500);
            }
        }
    }

    void scrollToTop() {
        scrollTo(0, 0);
    }

    void scrollToBottom() {
        enableRuntime();
        String height = eval("document.body.scrollHeight");
        scrollTo(0, Integer.parseInt(height));
    }

    private void dispatchScroll(int deltaX, int deltaY) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("type", "mouseWheel");
            params.addProperty("x", mousePosition.getX());
            params.addProperty("y", mousePosition.getY());
            params.addProperty("deltaX", deltaX);
            params.addProperty("deltaY", deltaY);

            page.cdpSession().send("Input.dispatchMouseEvent", params);
        } catch (TimeoutException e) {
            throw new ScriptExecutionException("Failed to dispatch scroll", e);
        }
    }

    // ==================== Compound Methods ====================

    void fillFormField(String selector, String value, long preTypeDelay, long postTypeDelay) {
        fillFormField(selector, value, preTypeDelay, postTypeDelay, 1.0);
    }

    void fillFormField(String selector, String value, long preTypeDelay, long postTypeDelay,
                       double speedMultiplier) {
        // Verify element is editable before attempting to type
        Actionability act = page.actionability();
        act.waitForActionable(selector,
                new String[]{"visible", "stable", "enabled", "editable"},
                page.options().getDefaultTimeout());

        click(selector, true); // force=true since actionability already verified
        clear(selector);
        page.sleep(preTypeDelay);
        type(value, speedMultiplier);
        page.sleep(postTypeDelay);
    }

    boolean isClickable(String selector) {
        return isClickable(selector, page.options().getDefaultTimeout());
    }

    boolean isClickable(String selector, int timeoutMs) {
        try {
            scrollIntoView(selector);

            BoundingBox box;
            if (timeoutMs <= 0) {
                box = page.querySelector(selector, 0);
            } else {
                box = page.waitForSelector(selector, timeoutMs);
            }

            return box != null && box.isValid();

        } catch (Exception e) {
            return false;
        }
    }

    BoundingBox waitForClickable(String selector) {
        return waitForClickable(selector, page.options().getDefaultTimeout());
    }

    BoundingBox waitForClickable(String selector, int timeoutMs) {
        scrollIntoView(selector);
        return page.waitForSelector(selector, timeoutMs);
    }

    // ==================== Internal Helpers ====================

    private String eval(String script) {
        return page.evaluate(script);
    }

    private void enableRuntime() {
        try {
            page.ensureRuntimeEnabled();
        } catch (TimeoutException e) {
            throw new ScriptExecutionException("Failed to enable Runtime domain", e);
        }
    }
}
