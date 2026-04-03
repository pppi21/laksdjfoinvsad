package org.nodriver4j.core;

import org.nodriver4j.math.ActionIntent;
import org.nodriver4j.math.MovementPersona;
import org.nodriver4j.math.PauseDistribution;
import org.nodriver4j.math.Vector;

import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Mutable, per-session state tracker that records every action and computes timing
 * adjustments for the next action based on accumulated history.
 *
 * <p>This class addresses session-level detection signals: click density, movement-to-idle
 * ratio, keyboard-mouse coordination, and time-on-page before first interaction. It does
 * NOT dispatch events or sleep — it computes recommended delays that {@code InputController}
 * applies during integration.</p>
 *
 * <h3>What it tracks</h3>
 * <ul>
 *   <li>Last action type and timestamp</li>
 *   <li>Rolling window of recent action timestamps (last 30 seconds)</li>
 *   <li>Total mouse distance moved and click count</li>
 *   <li>Session start time and last page navigation time</li>
 *   <li>Whether typing is currently active</li>
 * </ul>
 *
 * <h3>Timing adjustments it computes</h3>
 * <ul>
 *   <li><b>Activity density throttling</b> — if recent actions exceed the persona's
 *       threshold, recommends 500–2000ms additional pause</li>
 *   <li><b>Input mode transition</b> — keyboard→mouse or mouse→keyboard delays</li>
 *   <li><b>First interaction delay</b> — minimum delay after page navigation before
 *       first action (simulates page scanning)</li>
 *   <li><b>Post-idle re-engagement signal</b> — flags that the next movement should
 *       start with lower initial acceleration</li>
 * </ul>
 *
 * <p>Thread-safe — {@code InputController} and future {@code IdleDriftController} may
 * query from different threads.</p>
 *
 * <p>Toggleable via {@code InteractionOptions.sessionContextEnabled} (applied by the
 * caller, not checked internally).</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * SessionContext ctx = new SessionContext(persona);
 *
 * // Before each action:
 * double delay = ctx.recommendedDelay(SessionContext.ActionType.CLICK);
 * Thread.sleep((long) delay);
 *
 * // After each action:
 * ctx.recordAction(SessionContext.ActionType.CLICK);
 *
 * // After mouse movement:
 * ctx.recordMouseDistance(pathLength);
 *
 * // After page navigation:
 * ctx.recordPageNavigation();
 * }</pre>
 *
 * @see MovementPersona#activityDensityThreshold()
 * @see MovementPersona#keyboardToMouseDelayMs()
 */
public final class SessionContext {

    /**
     * Types of actions tracked by the session context.
     */
    public enum ActionType {
        CLICK, TYPE, SCROLL, HOVER, IDLE
    }

    // ==================== Constants ====================

    /**
     * Rolling window duration for timestamp retention.
     */
    private static final long WINDOW_MS = 30_000;

    /**
     * Window over which activity density is measured.
     */
    private static final long DENSITY_WINDOW_MS = 10_000;

    /**
     * Minimum throttling delay when density threshold is exceeded.
     */
    private static final double MIN_THROTTLE_DELAY_MS = 500.0;

    /**
     * Maximum throttling delay.
     */
    private static final double MAX_THROTTLE_DELAY_MS = 2000.0;

    // ==================== Dependencies ====================

    private final MovementPersona persona;
    private final PauseDistribution basePause;
    private final Random random;

    // ==================== Immutable Session State ====================

    private final long sessionStartTimeMs;

    // ==================== Rolling Window (self-pruning) ====================

    private final ConcurrentLinkedDeque<Long> recentTimestamps = new ConcurrentLinkedDeque<>();

    // ==================== Mutable State (guarded by lock) ====================

    private final Object lock = new Object();
    private ActionType lastActionType;
    private long lastActionTimestampMs;
    private boolean typing;
    private boolean awaitingFirstAction;
    private long lastNavigationTimeMs;
    private double totalDistanceMoved;
    private int totalClicks;
    private int totalActions;

    /**
     * Creates a new session context.
     *
     * @param persona the movement persona providing session timing parameters
     * @param random  seeded Random for deterministic pause sampling
     */
    public SessionContext(MovementPersona persona, Random random) {
        this.persona = persona;
        this.basePause = PauseDistribution.fromPersona(persona);
        this.random = random;
        long now = System.currentTimeMillis();
        this.sessionStartTimeMs = now;
        this.lastNavigationTimeMs = now;
        this.lastActionTimestampMs = now;
        this.awaitingFirstAction = true;
    }

    // ==================== Recording ====================

    /**
     * Records that an action of the given type has completed.
     * Updates last action state, rolling window, and counters.
     *
     * @param type the type of action that was performed
     */
    public void recordAction(ActionType type) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            lastActionType = type;
            lastActionTimestampMs = now;
            totalActions++;
            if (type == ActionType.CLICK) {
                totalClicks++;
            }
            typing = (type == ActionType.TYPE);
            awaitingFirstAction = false;
        }
        recentTimestamps.addLast(now);
        pruneOldTimestamps();
    }

    /**
     * Records cursor distance traveled during a mouse movement.
     *
     * @param pixels distance in pixels
     */
    public void recordMouseDistance(double pixels) {
        synchronized (lock) {
            totalDistanceMoved += pixels;
        }
    }

    /**
     * Records that a page navigation has occurred.
     * Resets the first-interaction timer so the next action will observe
     * the first-interaction delay.
     */
    public void recordPageNavigation() {
        synchronized (lock) {
            lastNavigationTimeMs = System.currentTimeMillis();
            awaitingFirstAction = true;
        }
    }

    // ==================== Delay Computation ====================

    /**
     * Computes the total recommended delay (in milliseconds) before the next action,
     * using {@link ActionIntent#CASUAL} for the base pause.
     *
     * <p>Always includes a human-plausible base pause (log-normal), plus any applicable
     * session-level adjustments.</p>
     *
     * @param nextAction the type of action about to be performed
     * @return recommended delay in milliseconds (always ≥ 30ms)
     */
    public double recommendedDelay(ActionType nextAction) {
        return recommendedDelay(nextAction, ActionIntent.CASUAL);
    }

    /**
     * Computes the total recommended delay (in milliseconds) before the next action,
     * with intent-driven pause modifiers applied.
     *
     * <p>Components (summed):</p>
     * <ol>
     *   <li>Base pause — log-normal sample with intent multipliers applied
     *       ({@code CONFIRM} adds 200–800ms, {@code NAVIGATE} suppresses to 0–50ms)</li>
     *   <li>First-interaction delay (after page navigation)</li>
     *   <li>Activity density throttling (if action rate exceeds threshold)</li>
     *   <li>Input mode transition delay (keyboard↔mouse switch)</li>
     * </ol>
     *
     * @param nextAction the type of action about to be performed
     * @param intent     the semantic intent of the action
     * @return recommended delay in milliseconds (always ≥ 30ms)
     */
    public double recommendedDelay(ActionType nextAction, ActionIntent intent) {
        double delay = basePause.sample(random, intent);
        delay += firstInteractionDelay();
        delay += activityDensityDelay();
        delay += inputModeTransitionDelay(nextAction);
        return delay;
    }

    // ==================== Query Methods ====================

    /**
     * Whether keyboard input is currently active.
     * When true, idle cursor drift should be paused (hand is on keyboard).
     *
     * @return true if the last recorded action was TYPE
     */
    public boolean isTyping() {
        synchronized (lock) {
            return typing;
        }
    }

    /**
     * Whether the cursor has been idle beyond the given threshold.
     *
     * @param thresholdMs idle threshold in milliseconds
     * @return true if no action has occurred within the threshold
     */
    public boolean isIdle(long thresholdMs) {
        return timeSinceLastAction() > thresholdMs;
    }

    /**
     * Milliseconds elapsed since the last recorded action.
     *
     * @return time since last action in milliseconds
     */
    public long timeSinceLastAction() {
        synchronized (lock) {
            return System.currentTimeMillis() - lastActionTimestampMs;
        }
    }

    /**
     * Whether the next mouse movement should start with lower initial acceleration,
     * because the cursor has been idle beyond the persona's re-engagement threshold.
     * Simulates a hand returning to the mouse after being away.
     *
     * @return true if post-idle slowdown should be applied
     */
    public boolean shouldSlowReEngagement() {
        return timeSinceLastAction() > persona.postIdleSlowdownThresholdMs();
    }

    /**
     * The type of the last recorded action, or null if no actions have been recorded.
     *
     * @return the last action type
     */
    public ActionType lastActionType() {
        synchronized (lock) {
            return lastActionType;
        }
    }

    /**
     * Total number of actions recorded this session.
     *
     * @return the total action count
     */
    public int totalActions() {
        synchronized (lock) {
            return totalActions;
        }
    }

    /**
     * Total number of clicks recorded this session.
     *
     * @return the total click count
     */
    public int totalClicks() {
        synchronized (lock) {
            return totalClicks;
        }
    }

    /**
     * Total cursor distance traveled this session in pixels.
     *
     * @return the cumulative mouse distance
     */
    public double totalDistanceMoved() {
        synchronized (lock) {
            return totalDistanceMoved;
        }
    }

    /**
     * Milliseconds elapsed since the session started.
     *
     * @return session age in milliseconds
     */
    public long sessionAgeMs() {
        return System.currentTimeMillis() - sessionStartTimeMs;
    }

    // ==================== Private Delay Components ====================

    /**
     * Computes the remaining first-interaction delay after a page navigation.
     * Returns 0 if an action has already been performed since the last navigation,
     * or if enough time has already elapsed.
     */
    private double firstInteractionDelay() {
        synchronized (lock) {
            if (!awaitingFirstAction) {
                return 0;
            }
            long sinceNavigation = System.currentTimeMillis() - lastNavigationTimeMs;
            return Math.max(0, persona.firstInteractionDelayMs() - sinceNavigation);
        }
    }

    /**
     * Computes throttling delay if recent action density exceeds the persona's threshold.
     * Scales linearly from 500ms at the threshold to 2000ms at 2× the threshold.
     */
    private double activityDensityDelay() {
        int recentCount = countRecentActions(DENSITY_WINDOW_MS);
        int threshold = (int) persona.activityDensityThreshold();
        if (recentCount <= threshold) {
            return 0;
        }
        double overageFraction = (double) (recentCount - threshold) / threshold;
        return Vector.clamp(
                MIN_THROTTLE_DELAY_MS + overageFraction * (MAX_THROTTLE_DELAY_MS - MIN_THROTTLE_DELAY_MS),
                MIN_THROTTLE_DELAY_MS, MAX_THROTTLE_DELAY_MS);
    }

    /**
     * Computes the delay for switching between input modes (keyboard↔mouse).
     * Returns 0 if no mode switch is occurring.
     */
    private double inputModeTransitionDelay(ActionType nextAction) {
        synchronized (lock) {
            if (lastActionType == null) {
                return 0;
            }

            boolean lastWasKeyboard = lastActionType == ActionType.TYPE;
            boolean lastWasMouse = lastActionType == ActionType.CLICK
                    || lastActionType == ActionType.HOVER
                    || lastActionType == ActionType.SCROLL;
            boolean nextIsMouse = nextAction == ActionType.CLICK
                    || nextAction == ActionType.HOVER
                    || nextAction == ActionType.SCROLL;
            boolean nextIsKeyboard = nextAction == ActionType.TYPE;

            if (lastWasKeyboard && nextIsMouse) {
                return persona.keyboardToMouseDelayMs();
            }
            if (lastWasMouse && nextIsKeyboard) {
                return persona.mouseToKeyboardDelayMs();
            }
            return 0;
        }
    }

    // ==================== Rolling Window ====================

    /**
     * Counts actions in the rolling window within the given time span.
     */
    private int countRecentActions(long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        int count = 0;
        for (long ts : recentTimestamps) {
            if (ts >= cutoff) {
                count++;
            }
        }
        return count;
    }

    /**
     * Removes timestamps older than the rolling window from the deque.
     */
    private void pruneOldTimestamps() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        while (!recentTimestamps.isEmpty()) {
            Long first = recentTimestamps.peekFirst();
            if (first != null && first < cutoff) {
                recentTimestamps.pollFirst();
            } else {
                break;
            }
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return String.format(
                    "SessionContext{actions=%d, clicks=%d, distance=%.0f px, last=%s, age=%d ms}",
                    totalActions, totalClicks, totalDistanceMoved, lastActionType,
                    sessionAgeMs());
        }
    }
}
