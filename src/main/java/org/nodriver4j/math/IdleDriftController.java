package org.nodriver4j.math;

import java.util.Random;

/**
 * Plans cursor movements during idle periods using a burst-then-rest cycle.
 *
 * <p>The cursor is still most of the time, with occasional bursts of repositioning:</p>
 * <ol>
 *   <li><b>Large move</b> — to a random point in the middle 60% of the viewport</li>
 *   <li><b>1–2 small follow-ups</b> — 50–150px each, spaced 1.5–4 seconds apart</li>
 *   <li><b>Rest</b> — completely idle for 9–15 seconds (only tremor, no movement)</li>
 *   <li>Repeat from step 1</li>
 * </ol>
 *
 * <p>This class is a state machine that tells the caller what to do next (move or wait)
 * via {@link #next}. Actual path generation and dispatch are the caller's responsibility
 * using {@link MousePathBuilder}.</p>
 *
 * @see MousePathBuilder
 */
public final class IdleDriftController {

    /**
     * What the caller should do next.
     *
     * @param type    MOVE (dispatch a movement to {@code target}) or WAIT (just sleep)
     * @param target  where to move (only meaningful when type is MOVE)
     * @param delayMs how long to wait AFTER completing this step before calling {@link #next} again
     */
    public record DriftAction(Type type, Vector target, long delayMs) {
        public enum Type { MOVE, WAIT }
    }

    private static final double VIEWPORT_MARGIN = 0.20;

    private final Random random;

    // Burst state
    private boolean startOfIdlePeriod;
    private int followUpsRemaining;

    /**
     * Creates an IdleDriftController.
     *
     * @param random seeded Random for deterministic target selection
     */
    public IdleDriftController(Random random) {
        this.random = random;
        this.startOfIdlePeriod = true;
        this.followUpsRemaining = 0;
    }

    /**
     * Checks whether idle drift should be active.
     *
     * @param isTyping whether keyboard input is currently active
     * @return true if drift should be generated
     */
    public boolean shouldDrift(boolean isTyping) {
        return !isTyping;
    }

    /**
     * Returns the next action in the burst-then-rest cycle.
     *
     * <p>The cycle is: large MOVE → 1–2 small MOVEs → long WAIT (rest) → repeat.</p>
     *
     * @param currentPosition the cursor's current position
     * @param viewportWidth   cached viewport width in pixels
     * @param viewportHeight  cached viewport height in pixels
     * @return what to do next and how long to wait afterward
     */
    public DriftAction next(Vector currentPosition, int viewportWidth, int viewportHeight) {
        // Start of a new burst: large move to neutral viewport area
        if (startOfIdlePeriod || followUpsRemaining <= 0) {
            startOfIdlePeriod = false;
            followUpsRemaining = 1 + random.nextInt(2); // 1 or 2 follow-ups

            Vector target = randomViewportCenter(viewportWidth, viewportHeight);
            long delay = 1500 + (long) (random.nextDouble() * 2500); // 1.5–4s before follow-up
            return new DriftAction(DriftAction.Type.MOVE, target, delay);
        }

        // Follow-up: small movement nearby
        followUpsRemaining--;
        Vector target = smallWander(currentPosition);

        if (followUpsRemaining > 0) {
            // More follow-ups coming — short wait
            long delay = 1500 + (long) (random.nextDouble() * 2500); // 1.5–4s
            return new DriftAction(DriftAction.Type.MOVE, target, delay);
        }

        // Last follow-up in this burst — long rest afterward
        long restMs = 9000 + (long) (random.nextDouble() * 6000); // 9–15s
        return new DriftAction(DriftAction.Type.MOVE, target, restMs);
    }

    /**
     * Returns the delay before the first drift action after idle begins.
     *
     * @return initial delay in milliseconds (1.5–3s)
     */
    public long initialDelayMs() {
        return 1500 + (long) (random.nextDouble() * 1500);
    }

    /**
     * Resets for the next idle period. Call when an intentional action completes.
     */
    public void resetForNextIdle() {
        this.startOfIdlePeriod = true;
        this.followUpsRemaining = 0;
    }

    private Vector randomViewportCenter(int viewportWidth, int viewportHeight) {
        double x = viewportWidth * VIEWPORT_MARGIN
                + random.nextDouble() * viewportWidth * (1 - 2 * VIEWPORT_MARGIN);
        double y = viewportHeight * VIEWPORT_MARGIN
                + random.nextDouble() * viewportHeight * (1 - 2 * VIEWPORT_MARGIN);
        return new Vector(x, y);
    }

    private Vector smallWander(Vector from) {
        double distance = 50 + random.nextDouble() * 100;
        double angle = random.nextDouble() * 2 * Math.PI;
        return from.add(new Vector(distance * Math.cos(angle), distance * Math.sin(angle)));
    }
}
