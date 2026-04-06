package org.nodriver4j.core;

import com.google.gson.JsonObject;
import org.nodriver4j.core.exceptions.ElementNotFoundException;
import org.nodriver4j.core.exceptions.ScriptExecutionException;
import org.nodriver4j.math.*;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Mouse, keyboard, and scroll input for a browser page.
 *
 * <p>All mouse interactions use the movement framework: per-profile seeded behavior
 * (MovementPersona), Fitts's Law timing, sub-movement decomposition, approach
 * hesitation, physiological tremor, and inter-action pacing via SessionContext.</p>
 *
 * <p>Falls back to legacy HumanBehavior paths when {@code simulateMousePath} is false.</p>
 *
 * <p>This is an internal implementation class — scripts interact with
 * these operations through {@link Page}'s public API.</p>
 */
class InputController {

    private final Page page;
    private volatile Vector mousePosition;

    // ==================== Movement Framework ====================

    private final MovementPersona persona;
    private final MousePathBuilder pathBuilder;
    private final SessionContext sessionContext;
    private final IdleDriftController driftController;
    private final Random random;

    // ==================== Idle Drift ====================

    private final ScheduledExecutorService driftExecutor;
    private volatile ScheduledFuture<?> driftTask;
    private volatile boolean intentionalMovementInProgress;
    private volatile int cachedViewportWidth = 1280;
    private volatile int cachedViewportHeight = 720;

    InputController(Page page) {
        this.page = page;
        this.mousePosition = Vector.ORIGIN;

        boolean automationEnabled = page.options().isSimulateMousePath();

        if (automationEnabled) {
            // Create persona from a random seed (profile entity integration comes later)
            long seed = System.nanoTime();
            this.persona = new MovementPersona(seed);
            this.random = new Random(seed);

            this.pathBuilder = new MousePathBuilder(persona);
            this.sessionContext = new SessionContext(persona, new Random(seed ^ 0x53455353L));
            this.driftController = new IdleDriftController(new Random(seed ^ 0x4452494654L));

            // Daemon thread for idle drift
            this.driftExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "idle-drift");
                t.setDaemon(true);
                return t;
            });
            scheduleDrift(2000); // initial delay before first drift
        } else {
            // Manual / non-simulated mode — no framework components
            this.persona = null;
            this.random = null;
            this.pathBuilder = null;
            this.sessionContext = null;
            this.driftController = null;
            this.driftExecutor = null;
        }
    }

    Vector mousePosition() {
        return mousePosition;
    }

    /**
     * The session context tracking inter-action state.
     * Exposed for page navigation recording.
     */
    SessionContext sessionContext() {
        return sessionContext;
    }

    // ==================== Mouse Interaction ====================

    void click(String selector) {
        click(selector, false, ActionIntent.DEFAULT);
    }

    void click(String selector, boolean force) {
        click(selector, force, ActionIntent.DEFAULT);
    }

    void click(String selector, boolean force, ActionIntent intent) {
        if (force) {
            scrollIntoView(selector);
            BoundingBox box = page.waitForSelector(selector);
            clickAtBox(box);
            return;
        }

        InteractionOptions options = page.options();

        // Session context delay (inter-action pacing)
        applySessionDelay(SessionContext.ActionType.CLICK, intent);

        Actionability act = page.actionability();

        BoundingBox box = act.waitForActionable(selector,
                new String[]{"visible", "stable", "enabled"},
                options.getDefaultTimeout());

        scrollIntoViewIfNeeded(box);
        box = page.waitForSelector(selector, 2000);

        if (options.isSimulateMousePath()) {
            executeClick(box, intent);
            postClickMovement(options.speedMultiplier());
        } else {
            Vector target = box.getRandomPoint(options.getPaddingPercentage());
            act.verifyHitTarget(selector, target.getX(), target.getY());
            dispatchMouseMove(target);
            mousePosition = target;
            performLegacyClick(target);
        }

        recordAction(SessionContext.ActionType.CLICK);
    }

    void clickAt(double x, double y) {
        Vector target = new Vector(x, y);
        InteractionOptions options = page.options();

        if (options.isSimulateMousePath()) {
            stopDrift();
            double speed = options.speedMultiplier();
            List<MousePathBuilder.PathPoint> path = pathBuilder.planMovement(
                    mousePosition, target, 20, ActionIntent.DEFAULT, speed);
            dispatchPath(path);
            performClickFromPath(target, ActionIntent.DEFAULT, speed);
            postClickMovement(speed);
            driftController.resetForNextIdle();
            startDrift();
        } else {
            moveMouseToLegacy(target);
            performLegacyClick(target);
        }
    }

    void clickAtBox(BoundingBox box) {
        InteractionOptions options = page.options();

        if (options.isSimulateMousePath()) {
            executeClick(box, ActionIntent.DEFAULT);
        } else {
            Vector target = box.getRandomPoint(options.getPaddingPercentage());
            moveMouseToLegacy(target);
            performLegacyClick(target);
        }
    }

    void hover(String selector) {
        Actionability act = page.actionability();
        BoundingBox box = act.waitForActionable(selector,
                new String[]{"visible", "stable"},
                page.options().getDefaultTimeout());
        scrollIntoViewIfNeeded(box);
        box = page.waitForSelector(selector, 2000);
        Vector target = box.getCenter();

        applySessionDelay(SessionContext.ActionType.HOVER, ActionIntent.DEFAULT);
        moveMouseTo(target);
        recordAction(SessionContext.ActionType.HOVER);
    }

    void moveMouseTo(Vector target) {
        InteractionOptions options = page.options();

        if (!options.isSimulateMousePath()) {
            moveMouseToLegacy(target);
            return;
        }

        stopDrift();

        double speed = options.speedMultiplier();
        List<MousePathBuilder.PathPoint> path = pathBuilder.planMovement(
                mousePosition, target, 100, ActionIntent.DEFAULT, speed);

        double totalDistance = dispatchPath(path);

        if (options.isSessionContextEnabled()) {
            sessionContext.recordMouseDistance(totalDistance);
        }

        driftController.resetForNextIdle();
        startDrift();
    }

    // ==================== Click Execution (New Framework) ====================

    /**
     * Full click using MousePathBuilder: plans movement + click, then dispatches.
     */
    private void executeClick(BoundingBox box, ActionIntent intent) {
        stopDrift();

        InteractionOptions options = page.options();
        double speed = options.speedMultiplier();

        MousePathBuilder.ClickResult click = pathBuilder.planClick(
                mousePosition, box, intent, speed);

        dispatchPath(click.path());

        // Hesitation before click
        if (click.hesitationMs() > 0) {
            page.sleep((long) click.hesitationMs());
        }

        // Mousedown → hold → mouseup
        dispatchMouseButton(click.mousedownPosition(), "mousePressed", "left", 1);
        if (click.holdDurationMs() > 0) {
            page.sleep((long) click.holdDurationMs());
        }
        dispatchMouseButton(click.mousedownPosition(), "mouseReleased", "left", 1);

        if (options.isSessionContextEnabled()) {
            sessionContext.recordMouseDistance(mousePosition.distanceTo(box.getCenter()));
        }

        driftController.resetForNextIdle();
        startDrift();
    }

    /**
     * Moves the cursor 100–300px away from the click point in a random direction.
     * This is part of the click behavior (not a separate user action) — no session
     * context delay or action recording.
     */
    private void postClickMovement(double speed) {
        double distance = 100 + random.nextDouble() * 200;
        double angle = random.nextDouble() * 2 * Math.PI;
        Vector target = mousePosition.add(new Vector(
                distance * Math.cos(angle), distance * Math.sin(angle)));

        List<MousePathBuilder.PathPoint> path = pathBuilder.planMovement(
                mousePosition, target, 100, ActionIntent.CASUAL, speed);
        dispatchPath(path);
    }

    /**
     * Click execution after path has already been dispatched (for clickAt).
     */
    private void performClickFromPath(Vector position, ActionIntent intent, double speed) {
        ClickBehavior clickBehavior = new ClickBehavior(persona, new Random(persona.seed() ^ 0x434C4B32L));
        ClickBehavior.ClickParams params = clickBehavior.compute(
                BoundingBox.of(position.getX() - 5, position.getY() - 5, 10, 10), 0);

        long hesitation = (long) (params.hesitationMs() / speed);
        long hold = (long) (params.holdDurationMs() / speed);

        if (hesitation > 0) page.sleep(hesitation);
        dispatchMouseButton(position, "mousePressed", "left", 1);
        if (hold > 0) page.sleep(hold);
        dispatchMouseButton(position, "mouseReleased", "left", 1);
    }

    // ==================== Legacy Mouse Methods ====================

    /**
     * Legacy mouse move using HumanBehavior paths (when simulateMousePath is true
     * but we need the old path for backward-compatible callers).
     */
    private void moveMouseToLegacy(Vector target) {
        InteractionOptions options = page.options();

        if (!options.isSimulateMousePath()) {
            dispatchMouseMove(target);
            mousePosition = target;
            return;
        }

        if (options.isOvershootEnabled() &&
                HumanBehavior.shouldOvershoot(mousePosition, target, options.getOvershootThreshold())) {
            Vector overshootPoint = HumanBehavior.calculateOvershoot(target, options.getOvershootRadius());
            moveAlongLegacyPath(mousePosition, overshootPoint, null);
            moveAlongLegacyPath(overshootPoint, target, HumanBehavior.OVERSHOOT_SPREAD);
        } else {
            moveAlongLegacyPath(mousePosition, target, null);
        }

        if (options.getMoveDelayMax() > 0) {
            int delay = options.isRandomizeMoveDelay()
                    ? HumanBehavior.randomDelay(options.getMoveDelayMin(), options.getMoveDelayMax())
                    : options.getMoveDelayMax();
            page.sleep(delay);
        }
    }

    private void moveAlongLegacyPath(Vector from, Vector to, Double spreadOverride) {
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

    private void performLegacyClick(Vector position) {
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

    // ==================== Path Dispatch ====================

    /**
     * Dispatches a timed path, sleeping between points.
     *
     * @return total distance traveled (for session context recording)
     */
    private double dispatchPath(List<MousePathBuilder.PathPoint> path) {
        double totalDistance = 0;
        Vector prev = mousePosition;

        for (MousePathBuilder.PathPoint point : path) {
            if (point.deltaMs() > 0) {
                page.sleep((long) point.deltaMs());
            }

            dispatchMouseMove(point.position());
            totalDistance += prev.distanceTo(point.position());
            prev = point.position();
            mousePosition = point.position();
        }

        return totalDistance;
    }

    // ==================== Session Context Helpers ====================

    private void applySessionDelay(SessionContext.ActionType type, ActionIntent intent) {
        InteractionOptions options = page.options();
        if (!options.isSessionContextEnabled()) return;

        double delay = sessionContext.recommendedDelay(type, intent);
        if (delay > 0) {
            page.sleep((long) (delay / options.speedMultiplier()));
        }
    }

    private void recordAction(SessionContext.ActionType type) {
        if (page.options().isSessionContextEnabled()) {
            sessionContext.recordAction(type);
        }
    }

    // ==================== CDP Dispatch ====================

    private static final int DISPATCH_RETRIES = 3;
    private static final int DISPATCH_TIMEOUT_SECONDS = 5;

    private void dispatchMouseMove(Vector position) {
        JsonObject params = new JsonObject();
        params.addProperty("type", "mouseMoved");
        params.addProperty("x", position.getX());
        params.addProperty("y", position.getY());

        dispatchWithRetry("Input.dispatchMouseEvent", params, "Failed to dispatch mouse move");
        // Update cursor overlay position — silently ignore failures (e.g. execution
        // context destroyed after navigation). The overlay is purely cosmetic.
        try {
            page.updateCursorOverlay(position.getX(), position.getY());
        } catch (Exception ignored) {
        }
    }

    private void dispatchMouseButton(Vector position, String type, String button, int clickCount) {
        JsonObject params = new JsonObject();
        params.addProperty("type", type);
        params.addProperty("x", position.getX());
        params.addProperty("y", position.getY());
        params.addProperty("button", button);
        params.addProperty("clickCount", clickCount);

        dispatchWithRetry("Input.dispatchMouseEvent", params, "Failed to dispatch mouse button");
    }

    private void dispatchWithRetry(String method, JsonObject params, String errorMessage) {
        for (int attempt = 1; attempt <= DISPATCH_RETRIES; attempt++) {
            try {
                page.cdpSession().send(method, params, DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return;
            } catch (TimeoutException e) {
                if (attempt == DISPATCH_RETRIES) {
                    throw new ScriptExecutionException(errorMessage, e);
                }
                page.sleep(200);
            }
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

        stopDrift(); // Hand moves to keyboard — stop cursor drift
        applySessionDelay(SessionContext.ActionType.TYPE, ActionIntent.DEFAULT);

        InteractionOptions options = page.options();
        double globalSpeed = options.speedMultiplier();
        double effectiveMultiplier = speedMultiplier * globalSpeed;

        int burstSize = Math.max(1, (int) Math.floor(effectiveMultiplier));
        double delayMultiplier = effectiveMultiplier / burstSize;
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

        recordAction(SessionContext.ActionType.TYPE);
        startDrift(); // Typing done — hand returns to mouse, resume drift
    }

    private void insertText(String text) {
        JsonObject params = new JsonObject();
        params.addProperty("text", text);
        dispatchWithRetry("Input.insertText", params, "Failed to insert text");
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

        dispatchWithRetry("Input.dispatchKeyEvent", keyDown, "Failed to dispatch key down");

        JsonObject keyUp = new JsonObject();
        keyUp.addProperty("type", "keyUp");
        keyUp.addProperty("key", key);
        keyUp.addProperty("code", code);
        keyUp.addProperty("windowsVirtualKeyCode", windowsVirtualKeyCode);
        keyUp.addProperty("nativeVirtualKeyCode", windowsVirtualKeyCode);
        keyUp.addProperty("modifiers", modifiers);

        dispatchWithRetry("Input.dispatchKeyEvent", keyUp, "Failed to dispatch key up");
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

        applySessionDelay(SessionContext.ActionType.SCROLL, ActionIntent.DEFAULT);
        stopDrift();

        if (options.isSimulateMousePath()) {
            List<MousePathBuilder.ScrollStep> plan = pathBuilder.planScroll(
                    deltaX, deltaY,
                    options.getScrollTickPixels(),
                    options.getScrollDelayMin(), options.getScrollDelayMax(),
                    options.speedMultiplier());

            for (MousePathBuilder.ScrollStep step : plan) {
                // Lateral cursor drift during scroll
                if (step.cursorOffsetX() != 0 || step.cursorOffsetY() != 0) {
                    Vector drifted = mousePosition.add(
                            new Vector(step.cursorOffsetX(), step.cursorOffsetY()));
                    dispatchMouseMove(drifted);
                    mousePosition = drifted;
                }

                dispatchScroll(step.scrollDeltaX(), step.scrollDeltaY());

                if (step.delayMs() > 0) {
                    page.sleep((long) step.delayMs());
                }
            }
        } else {
            scrollByLegacy(deltaX, deltaY);
        }

        recordAction(SessionContext.ActionType.SCROLL);
        if (driftController != null) {
            driftController.resetForNextIdle();
            startDrift();
        }
    }

    private void scrollByLegacy(int deltaX, int deltaY) {
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
        JsonObject params = new JsonObject();
        params.addProperty("type", "mouseWheel");
        params.addProperty("x", mousePosition.getX());
        params.addProperty("y", mousePosition.getY());
        params.addProperty("deltaX", deltaX);
        params.addProperty("deltaY", deltaY);

        dispatchWithRetry("Input.dispatchMouseEvent", params, "Failed to dispatch scroll");
    }

    // ==================== Idle Drift ====================

    private void stopDrift() {
        intentionalMovementInProgress = true;
        if (driftTask != null) {
            driftTask.cancel(true); // interrupt if sleeping inside a drift movement
            driftTask = null;
        }
    }

    private void startDrift() {
        intentionalMovementInProgress = false;
        if (driftController != null) {
            scheduleDrift(driftController.initialDelayMs());
        }
    }

    private void scheduleDrift(long delayMs) {
        if (driftExecutor != null && !driftExecutor.isShutdown()) {
            driftTask = driftExecutor.schedule(this::executeDriftStep, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Executes one step of the burst-then-rest idle drift cycle.
     *
     * <p>The IdleDriftController returns either a MOVE action (plan and dispatch a path)
     * or a WAIT action (do nothing, just re-schedule). After each action, the returned
     * delay determines when the next step runs — short delays between burst movements,
     * long delays (9–15s) during rest periods.</p>
     */
    private void executeDriftStep() {
        try {
            if (intentionalMovementInProgress) return;
            if (!driftController.shouldDrift(sessionContext.isTyping())) {
                scheduleDrift(1000);
                return;
            }

            IdleDriftController.DriftAction action = driftController.next(
                    mousePosition, cachedViewportWidth, cachedViewportHeight);

            if (action.type() == IdleDriftController.DriftAction.Type.MOVE) {
                double speed = page.options().speedMultiplier();
                List<MousePathBuilder.PathPoint> path = pathBuilder.planMovement(
                        mousePosition, action.target(), 100, ActionIntent.CASUAL, speed);

                for (MousePathBuilder.PathPoint point : path) {
                    if (intentionalMovementInProgress) return;
                    if (point.deltaMs() > 0) {
                        Thread.sleep((long) point.deltaMs());
                    }
                    if (intentionalMovementInProgress) return;
                    dispatchMouseMove(point.position());
                    mousePosition = point.position();
                }
            }

            // Schedule next step with the action's delay
            scheduleDrift(action.delayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            scheduleDrift(2000);
        }
    }

    /**
     * Caches the viewport dimensions for idle drift target selection.
     * Call after page load and after navigation.
     */
    void updateViewportDimensions() {
        try {
            enableRuntime();
            cachedViewportWidth = Integer.parseInt(eval("window.innerWidth"));
            cachedViewportHeight = Integer.parseInt(eval("window.innerHeight"));
        } catch (Exception e) {
            // Keep existing cached values
        }
    }

    // ==================== Compound Methods ====================

    void fillFormField(String selector, String value, long preTypeDelay, long postTypeDelay) {
        fillFormField(selector, value, preTypeDelay, postTypeDelay, 1.0);
    }

    void fillFormField(String selector, String value, long preTypeDelay, long postTypeDelay,
                       double speedMultiplier) {
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

    /**
     * Shuts down the idle drift executor. Called during page/browser teardown.
     */
    void shutdown() {
        stopDrift();
        if (driftExecutor != null) {
            driftExecutor.shutdownNow();
        }
    }
}
