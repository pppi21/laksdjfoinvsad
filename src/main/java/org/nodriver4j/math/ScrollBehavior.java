package org.nodriver4j.math;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Computes realistic scroll event plans with momentum deceleration, burst-pause
 * patterning, per-tick timing variance, and lateral cursor drift during scrolling.
 *
 * <p>Real users don't scroll at constant speed with a motionless cursor. Their scrolling
 * shows:</p>
 * <ul>
 *   <li><b>Momentum</b> — initial ticks are large and fast, subsequent ticks shrink
 *       as the scroll decelerates</li>
 *   <li><b>Burst-pause</b> — long scrolls are broken into 2–3 bursts with 100–300ms
 *       pauses in between (reading/scanning between bursts)</li>
 *   <li><b>Cursor drift</b> — the cursor shifts laterally 1–5 pixels during scrolling
 *       because the hand moves on the mouse while operating the scroll wheel</li>
 *   <li><b>Timing variance</b> — delays between ticks vary naturally, shorter during
 *       fast initial burst and longer during deceleration</li>
 * </ul>
 *
 * <p>This class computes a plan (list of {@link ScrollTick} records) — it does not
 * dispatch events. {@code InputController} consumes the plan during integration.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * ScrollBehavior scroll = new ScrollBehavior(persona, random);
 * List<ScrollTick> plan = scroll.plan(0, 500, 110, 30, 100);
 *
 * for (ScrollTick tick : plan) {
 *     dispatchScroll(tick.scrollDeltaX(), tick.scrollDeltaY());
 *     dispatchMouseMove(cursor.add(tick.cursorOffsetX(), tick.cursorOffsetY()));
 *     Thread.sleep((long) tick.delayMs());
 * }
 * }</pre>
 *
 * @see MovementPersona#scrollMomentumDecay()
 * @see MovementPersona#scrollBurstLength()
 * @see MovementPersona#scrollCursorDriftScale()
 */
public final class ScrollBehavior {

    /**
     * A single scroll tick with scroll amount, cursor offset, and delay.
     *
     * @param scrollDeltaX  horizontal scroll amount in pixels (signed)
     * @param scrollDeltaY  vertical scroll amount in pixels (signed)
     * @param cursorOffsetX lateral cursor displacement in pixels (applied alongside scroll)
     * @param cursorOffsetY lateral cursor displacement in pixels
     * @param delayMs       time to wait after this tick before the next
     */
    public record ScrollTick(
            int scrollDeltaX,
            int scrollDeltaY,
            double cursorOffsetX,
            double cursorOffsetY,
            double delayMs
    ) {}

    // ==================== Constants ====================

    /**
     * Minimum burst pause duration (ms).
     */
    private static final double BURST_PAUSE_MIN_MS = 100.0;

    /**
     * Maximum burst pause duration (ms).
     */
    private static final double BURST_PAUSE_MAX_MS = 300.0;

    /**
     * Directional correlation for lateral cursor drift during scrolling (fixed).
     */
    private static final double DRIFT_CORRELATION = 0.7;

    /**
     * Per-tick timing variance: ±15% of computed delay.
     */
    private static final double TIMING_VARIANCE = 0.15;

    /**
     * Per-tick scroll amount variance: ±10% of computed amount.
     */
    private static final double AMOUNT_VARIANCE = 0.10;

    // ==================== Fields ====================

    private final double momentumDecay;
    private final int burstLength;
    private final double cursorDriftScale;
    private final Random random;

    /**
     * Creates a ScrollBehavior with persona-derived scroll parameters.
     *
     * @param persona the movement persona providing scroll characteristics
     * @param random  seeded Random for per-scroll variation
     */
    public ScrollBehavior(MovementPersona persona, Random random) {
        this.momentumDecay = persona.scrollMomentumDecay();
        this.burstLength = persona.scrollBurstLength();
        this.cursorDriftScale = persona.scrollCursorDriftScale();
        this.random = random;
    }

    // ==================== Public API ====================

    /**
     * Computes a scroll plan for the given distance.
     *
     * <p>The plan distributes the total scroll across ticks following a momentum
     * decay model. Long scrolls are broken into bursts with micro-pauses.
     * Each tick includes a lateral cursor offset and a variable delay.</p>
     *
     * @param deltaX         total horizontal scroll in pixels (signed)
     * @param deltaY         total vertical scroll in pixels (signed)
     * @param baseTickPixels base pixels per scroll tick (from InteractionOptions)
     * @param delayMinMs     minimum inter-tick delay in milliseconds
     * @param delayMaxMs     maximum inter-tick delay in milliseconds
     * @return ordered list of scroll ticks to execute
     */
    public List<ScrollTick> plan(int deltaX, int deltaY,
                                 int baseTickPixels, int delayMinMs, int delayMaxMs) {
        int totalDistance = Math.max(Math.abs(deltaX), Math.abs(deltaY));
        if (totalDistance == 0) {
            return List.of();
        }

        int tickCount = Math.max(1, (int) Math.ceil((double) totalDistance / baseTickPixels));

        // Compute momentum weights: first tick = 1.0, each subsequent *= decay ± variance
        double[] weights = computeMomentumWeights(tickCount);

        // Distribute scroll distances across ticks, preserving exact totals
        int[] tickAmountsX = distributeDistance(deltaX, weights);
        int[] tickAmountsY = distributeDistance(deltaY, weights);

        // Determine which axis is primary (for cursor drift direction)
        boolean primaryVertical = Math.abs(deltaY) >= Math.abs(deltaX);

        // Build tick list with timing, burst pauses, and cursor drift
        List<ScrollTick> ticks = new ArrayList<>(tickCount);
        double lateralDrift = 0;

        for (int i = 0; i < tickCount; i++) {
            // Cursor drift: correlated random walk perpendicular to scroll direction
            lateralDrift = lateralDrift * DRIFT_CORRELATION
                    + random.nextGaussian() * cursorDriftScale * (1.0 - DRIFT_CORRELATION);

            double offsetX = primaryVertical ? lateralDrift : 0;
            double offsetY = primaryVertical ? 0 : lateralDrift;

            // Timing: base delay scaled by momentum (faster at start, slower at end)
            double speedFraction = weights[i] / weights[0];
            double baseDelay = delayMinMs + (delayMaxMs - delayMinMs) * (1.0 - speedFraction * 0.5);
            double delay = baseDelay * (1.0 - TIMING_VARIANCE + random.nextDouble() * 2 * TIMING_VARIANCE);

            // Burst pause: insert a longer pause at burst boundaries
            boolean isBurstEnd = burstLength > 0
                    && (i + 1) % burstLength == 0
                    && i < tickCount - 1;
            if (isBurstEnd) {
                delay += BURST_PAUSE_MIN_MS + random.nextDouble() * (BURST_PAUSE_MAX_MS - BURST_PAUSE_MIN_MS);
            }

            ticks.add(new ScrollTick(tickAmountsX[i], tickAmountsY[i], offsetX, offsetY, delay));
        }

        return ticks;
    }

    // ==================== Momentum Weights ====================

    /**
     * Computes per-tick momentum weights starting at 1.0 and decaying by
     * {@code momentumDecay} each tick, with ±10% random variance.
     */
    private double[] computeMomentumWeights(int tickCount) {
        double[] weights = new double[tickCount];
        weights[0] = 1.0;
        for (int i = 1; i < tickCount; i++) {
            double variance = 1.0 - AMOUNT_VARIANCE + random.nextDouble() * 2 * AMOUNT_VARIANCE;
            weights[i] = weights[i - 1] * momentumDecay * variance;
            weights[i] = Math.max(weights[i], 0.1); // floor to prevent near-zero ticks
        }
        return weights;
    }

    // ==================== Distance Distribution ====================

    /**
     * Distributes a total signed distance across ticks according to weights,
     * rounding to integers while preserving the exact sum.
     */
    private static int[] distributeDistance(int totalSigned, double[] weights) {
        int tickCount = weights.length;
        int[] amounts = new int[tickCount];
        int total = Math.abs(totalSigned);
        int sign = totalSigned >= 0 ? 1 : -1;

        if (total == 0) {
            return amounts;
        }

        double weightSum = 0;
        for (double w : weights) {
            weightSum += w;
        }

        // Distribute proportionally, tracking rounding error
        int allocated = 0;
        for (int i = 0; i < tickCount - 1; i++) {
            int amount = Math.max(0, (int) Math.round((double) total * weights[i] / weightSum));
            amount = Math.min(amount, total - allocated); // don't exceed remaining
            amounts[i] = amount * sign;
            allocated += amount;
        }
        // Last tick gets the remainder (exact sum guaranteed)
        amounts[tickCount - 1] = (total - allocated) * sign;

        return amounts;
    }

    @Override
    public String toString() {
        return String.format("ScrollBehavior{decay=%.2f, burst=%d, drift=%.1f px}",
                momentumDecay, burstLength, cursorDriftScale);
    }
}
