package org.nodriver4j.math;

import java.util.Random;

/**
 * Computes realistic click parameters — position, drift, hesitation, and hold duration
 * — for a given target element.
 *
 * <p>This class answers "where exactly should I click, how long should I wait, and how
 * long should I hold?" It does not dispatch events itself — {@code InputController}
 * consumes its output during the click sequence.</p>
 *
 * <h3>What it computes</h3>
 * <ul>
 *   <li><b>Click position</b> — Gaussian distribution around target center (replacing the
 *       current uniform {@code getRandomPoint}). Includes a consistent per-persona bias
 *       offset and velocity-dependent scatter (faster approach = less precise).</li>
 *   <li><b>Pre-click drift</b> — a small 0.5–2 pixel displacement between the cursor's
 *       final resting position and the actual mousedown point, simulating the hand
 *       settling on the mouse before pressing.</li>
 *   <li><b>Pre-click hesitation</b> — a persona-scaled pause after the cursor arrives
 *       but before the click, derived from the persona's pause characteristics.</li>
 *   <li><b>Hold duration</b> — Gaussian-distributed press-to-release timing centered at
 *       a persona-derived mean (80–120ms), never exactly the same twice.</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * ClickBehavior click = new ClickBehavior(persona, random);
 * ClickParams params = click.compute(targetBox, approachVelocity);
 *
 * // Use as movement target:
 * List<TimedPoint> path = planner.plan(cursorPos, params.clickPosition(), targetWidth);
 *
 * // Execute click:
 * sleep(params.hesitationMs());
 * dispatchMouseDown(params.mousedownPosition());
 * sleep(params.holdDurationMs());
 * dispatchMouseUp(params.mousedownPosition());
 * }</pre>
 *
 * @see MovementPersona#clickOffsetBiasX()
 * @see MovementPersona#clickDriftAmplitude()
 * @see MovementPersona#clickHoldMeanMs()
 */
public final class ClickBehavior {

    /**
     * Computed click parameters for a single click event.
     *
     * @param clickPosition  the Gaussian-distributed aim point within the target
     *                       (use as the movement path's destination)
     * @param driftOffset    small displacement from clickPosition to the actual mousedown
     *                       point (simulates hand settling)
     * @param hesitationMs   pre-click pause in milliseconds (after cursor arrives,
     *                       before mousedown)
     * @param holdDurationMs time between mousedown and mouseup in milliseconds
     */
    public record ClickParams(
            Vector clickPosition,
            Vector driftOffset,
            double hesitationMs,
            double holdDurationMs
    ) {
        /**
         * The actual mousedown/mouseup position (clickPosition + drift).
         *
         * @return the position where mouse button events should be dispatched
         */
        public Vector mousedownPosition() {
            return clickPosition.add(driftOffset);
        }
    }

    // ==================== Constants ====================

    /**
     * Minimum Gaussian scatter SD (pixels) even at zero approach velocity.
     */
    private static final double BASE_SCATTER_SD = 1.0;

    /**
     * Scatter SD increase per unit of approach velocity (px/s).
     * At 300 px/s: additional scatter = 300 × 0.015 = 4.5 px SD.
     */
    private static final double VELOCITY_SCATTER_COEFFICIENT = 0.015;

    /**
     * Minimum click hold duration (ms).
     */
    private static final double MIN_HOLD_MS = 30.0;

    /**
     * Minimum pre-click hesitation (ms).
     */
    private static final double MIN_HESITATION_MS = 10.0;

    /**
     * Pre-click hesitation mean is this fraction of the persona's inter-action pause mean.
     */
    private static final double HESITATION_PAUSE_FRACTION = 0.30;

    /**
     * Hesitation SD as a fraction of hesitation mean.
     */
    private static final double HESITATION_CV = 0.30;

    // ==================== Fields ====================

    private final MovementPersona persona;
    private final Random random;
    private final double hesitationMeanMs;
    private final double hesitationSdMs;

    /**
     * Creates a ClickBehavior for the given persona.
     *
     * @param persona the movement persona providing click parameters
     * @param random  seeded Random for per-click variation
     */
    public ClickBehavior(MovementPersona persona, Random random) {
        this.persona = persona;
        this.random = random;
        this.hesitationMeanMs = persona.pauseMeanMs() * HESITATION_PAUSE_FRACTION;
        this.hesitationSdMs = hesitationMeanMs * HESITATION_CV;
    }

    // ==================== Public API ====================

    /**
     * Computes click parameters for a target with a known approach velocity.
     *
     * <p>Higher approach velocity produces wider endpoint scatter (speed-accuracy
     * tradeoff per Fitts's Law effective width: {@code We = 4.133 × SD}).</p>
     *
     * @param targetBox                the bounding box of the target element
     * @param approachVelocityPxPerSec average cursor velocity during the approach phase
     *                                 (pixels per second). Use 0 for stationary clicks.
     * @return computed click parameters
     */
    public ClickParams compute(BoundingBox targetBox, double approachVelocityPxPerSec) {
        Vector clickPos = computeClickPosition(targetBox, approachVelocityPxPerSec);
        Vector drift = computeDrift();
        double hesitation = computeHesitation();
        double hold = computeHoldDuration();
        return new ClickParams(clickPos, drift, hesitation, hold);
    }

    /**
     * Computes click parameters assuming a stationary cursor (zero approach velocity).
     *
     * @param targetBox the bounding box of the target element
     * @return computed click parameters with minimal scatter
     */
    public ClickParams compute(BoundingBox targetBox) {
        return compute(targetBox, 0);
    }

    // ==================== Position ====================

    /**
     * Computes the click aim point using Gaussian distribution around target center,
     * with persona bias and velocity-dependent scatter.
     */
    private Vector computeClickPosition(BoundingBox box, double approachVelocity) {
        Vector center = box.getCenter();

        // Velocity-dependent scatter: faster approach = wider distribution
        double scatterSd = BASE_SCATTER_SD + VELOCITY_SCATTER_COEFFICIENT * approachVelocity;

        // Bound scatter by target dimensions (stay within the element)
        scatterSd = Math.min(scatterSd, box.getWidth() / 4.0);
        scatterSd = Math.min(scatterSd, box.getHeight() / 4.0);

        // Gaussian offset + consistent persona bias
        double offsetX = random.nextGaussian() * scatterSd + persona.clickOffsetBiasX();
        double offsetY = random.nextGaussian() * scatterSd + persona.clickOffsetBiasY();

        // Clamp within target bounds
        double clickX = Vector.clamp(
                center.getX() + offsetX,
                box.getX(), box.getX() + box.getWidth());
        double clickY = Vector.clamp(
                center.getY() + offsetY,
                box.getY(), box.getY() + box.getHeight());

        return new Vector(clickX, clickY);
    }

    // ==================== Drift ====================

    /**
     * Computes a small random drift offset between the cursor's final position
     * and the actual mousedown point.
     */
    private Vector computeDrift() {
        double angle = random.nextDouble() * 2 * Math.PI;
        double magnitude = persona.clickDriftAmplitude() * (0.5 + random.nextDouble() * 0.5);
        return new Vector(magnitude * Math.cos(angle), magnitude * Math.sin(angle));
    }

    // ==================== Timing ====================

    /**
     * Computes pre-click hesitation: a pause after the cursor arrives at the target
     * but before the mousedown fires.
     */
    private double computeHesitation() {
        double value = hesitationMeanMs + random.nextGaussian() * hesitationSdMs;
        return Math.max(MIN_HESITATION_MS, value);
    }

    /**
     * Computes click hold duration: the time between mousedown and mouseup.
     * Gaussian with persona-derived mean and SD.
     */
    private double computeHoldDuration() {
        double value = persona.clickHoldMeanMs() + random.nextGaussian() * persona.clickHoldSdMs();
        return Math.max(MIN_HOLD_MS, value);
    }

    @Override
    public String toString() {
        return String.format("ClickBehavior{hesitation=%.0f±%.0f ms, hold=%.0f±%.0f ms, drift=%.1f px}",
                hesitationMeanMs, hesitationSdMs,
                persona.clickHoldMeanMs(), persona.clickHoldSdMs(),
                persona.clickDriftAmplitude());
    }
}
