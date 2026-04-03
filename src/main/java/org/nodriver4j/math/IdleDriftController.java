package org.nodriver4j.math;

import java.util.Random;

/**
 * Generates small, slow cursor movements during idle periods to eliminate the
 * "dead cursor" problem — long gaps with zero mousemove events, which is a known
 * detection signal.
 *
 * <p>Real users' cursors drift slightly even when reading, thinking, or waiting. This
 * class models that drift using a <b>Brownian motion</b> model where each step's direction
 * is correlated with the previous step, producing smooth meandering rather than jittery
 * noise. Drift stays within a persona-derived radius of the rest position (where the
 * last intentional movement ended).</p>
 *
 * <h3>Drift behavior</h3>
 * <ul>
 *   <li><b>Small steps</b>: 0.5–3 pixels every 80–150ms (persona-derived), direction
 *       correlated with previous step (Brownian motion with damping)</li>
 *   <li><b>Boundary constraint</b>: when approaching the drift radius, direction is
 *       smoothly biased back toward the rest position</li>
 *   <li><b>Grip shifts</b>: every 5–15 seconds, a larger 5–15 pixel displacement
 *       simulating the user adjusting their hand on the mouse. The rest position
 *       updates to the new location.</li>
 *   <li><b>Stops during typing</b>: drift pauses when {@code SessionContext.isTyping()}
 *       is true (hand is on keyboard)</li>
 * </ul>
 *
 * <p>This class does NOT run its own thread or dispatch events. It's a generator —
 * the caller (future integration in InputController) polls it for the next drift step.
 * Threading decisions stay with the caller.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * IdleDriftController drift = new IdleDriftController(persona, random);
 * drift.resetRestPosition(cursorPosition);
 *
 * // In idle loop:
 * if (drift.shouldDrift(sessionContext.isTyping())) {
 *     DriftStep step = drift.nextDrift(currentPosition);
 *     Vector newPos = currentPosition.add(step.offset());
 *     dispatchMouseMove(newPos);
 *     Thread.sleep((long) step.delayUntilNextMs());
 * }
 *
 * // When intentional movement ends:
 * drift.resetRestPosition(finalPosition);
 * }</pre>
 *
 * @see MovementPersona#idleDriftAmplitude()
 * @see MovementPersona#driftCorrelation()
 * @see SessionContext#isTyping()
 */
public final class IdleDriftController {

    /**
     * A single drift step: an offset to apply to the cursor position and the
     * recommended delay before the next drift call.
     *
     * @param offset           displacement vector to add to the current cursor position
     * @param delayUntilNextMs recommended milliseconds to wait before the next call
     *                         to {@link IdleDriftController#nextDrift}
     */
    public record DriftStep(Vector offset, double delayUntilNextMs) {}

    // ==================== Constants ====================

    /**
     * Fraction of drift radius at which boundary pull-back begins.
     * Below this, drift wanders freely. Above this, direction biases toward center.
     */
    private static final double BOUNDARY_ONSET_FRACTION = 0.7;

    /**
     * Variance factor for drift interval (±30% of base).
     */
    private static final double INTERVAL_VARIANCE = 0.3;

    // ==================== Persona-Derived Parameters ====================

    private final double stepSize;
    private final double driftIntervalMs;
    private final double driftRadius;
    private final double driftCorrelation;
    private final double gripShiftIntervalMs;
    private final double gripShiftSize;
    private final double angularSpread;

    private final Random random;

    // ==================== Mutable State ====================

    private Vector restPosition;
    private double lastDirectionAngle;
    private long lastGripShiftTimeMs;

    /**
     * Creates an IdleDriftController with parameters derived from the persona.
     *
     * @param persona the movement persona providing drift parameters
     * @param random  seeded Random for deterministic drift generation
     */
    public IdleDriftController(MovementPersona persona, Random random) {
        this.random = random;

        this.stepSize = persona.idleDriftAmplitude();
        this.driftIntervalMs = persona.driftIntervalMs();
        this.driftRadius = persona.driftRadius();
        this.driftCorrelation = persona.driftCorrelation();
        this.gripShiftIntervalMs = persona.gripShiftIntervalMs();
        this.gripShiftSize = persona.gripShiftSize();

        // Higher correlation → smaller angular spread → smoother drift
        this.angularSpread = (1.0 - driftCorrelation) * Math.PI;

        this.restPosition = Vector.ORIGIN;
        this.lastDirectionAngle = random.nextDouble() * 2 * Math.PI;
        this.lastGripShiftTimeMs = System.currentTimeMillis();
    }

    // ==================== Public API ====================

    /**
     * Checks whether idle drift should be active.
     *
     * <p>Drift should be paused when the user is typing (hand is on keyboard).
     * The caller is also responsible for pausing drift during intentional mouse
     * movements — that state is not visible to this method.</p>
     *
     * @param isTyping whether keyboard input is currently active
     * @return true if drift should be generated
     */
    public boolean shouldDrift(boolean isTyping) {
        return !isTyping;
    }

    /**
     * Computes the next drift step: either a small Brownian drift or a larger
     * periodic grip shift.
     *
     * <p>The returned offset should be added to the current cursor position.
     * After a grip shift, the internal rest position updates automatically.</p>
     *
     * @param currentPosition the cursor's current position
     * @return the drift offset and recommended delay before the next call
     */
    public DriftStep nextDrift(Vector currentPosition) {
        // Check if it's time for a grip shift
        long now = System.currentTimeMillis();
        if (now - lastGripShiftTimeMs >= gripShiftIntervalMs) {
            return generateGripShift(currentPosition, now);
        }

        return generateBrownianStep(currentPosition);
    }

    /**
     * Sets the rest position — the center around which drift wanders.
     * Call this when an intentional mouse movement completes.
     *
     * @param position the new rest position
     */
    public void resetRestPosition(Vector position) {
        this.restPosition = position;
    }

    /**
     * The current rest position (center of drift wandering).
     *
     * @return the rest position
     */
    public Vector restPosition() {
        return restPosition;
    }

    // ==================== Brownian Drift ====================

    /**
     * Generates a small drift step using correlated Brownian motion.
     *
     * <p>Direction is based on the previous step's direction plus Gaussian angular noise
     * (scaled by {@code 1 - driftCorrelation}). When the cursor approaches the drift
     * radius boundary, direction is smoothly biased back toward the rest position.</p>
     */
    private DriftStep generateBrownianStep(Vector currentPosition) {
        // Correlated direction: previous angle + Gaussian angular noise
        double newAngle = lastDirectionAngle + random.nextGaussian() * angularSpread;

        // Boundary pull-back: bias toward rest position when near the edge
        double distFromRest = currentPosition.distanceTo(restPosition);
        double boundaryOnset = driftRadius * BOUNDARY_ONSET_FRACTION;

        if (distFromRest > boundaryOnset && distFromRest > 0) {
            double toRestAngle = Math.atan2(
                    restPosition.getY() - currentPosition.getY(),
                    restPosition.getX() - currentPosition.getX());

            // Pull strength ramps from 0 at boundary onset to 1 at full radius
            double pullStrength = Math.min(1.0,
                    (distFromRest - boundaryOnset) / (driftRadius - boundaryOnset));

            newAngle = angularBlend(newAngle, toRestAngle, pullStrength);
        }

        lastDirectionAngle = newAngle;

        // Step size with variance (50–150% of base)
        double size = stepSize * (0.5 + random.nextDouble());

        Vector offset = new Vector(size * Math.cos(newAngle), size * Math.sin(newAngle));

        // Delay with ±30% variance around base interval
        double delay = driftIntervalMs * (1.0 - INTERVAL_VARIANCE + random.nextDouble() * 2 * INTERVAL_VARIANCE);

        return new DriftStep(offset, delay);
    }

    // ==================== Grip Shift ====================

    /**
     * Generates a larger "grip shift" displacement simulating the user adjusting
     * their hand on the mouse. Updates the rest position to the new location.
     */
    private DriftStep generateGripShift(Vector currentPosition, long now) {
        lastGripShiftTimeMs = now;

        double angle = random.nextDouble() * 2 * Math.PI;
        double size = gripShiftSize * (0.7 + random.nextDouble() * 0.3);

        Vector offset = new Vector(size * Math.cos(angle), size * Math.sin(angle));

        // Update rest position: hand has physically moved
        restPosition = currentPosition.add(offset);

        // Reset direction to point roughly away from where we came
        lastDirectionAngle = angle;

        // Slightly longer delay after a grip shift (settling pause)
        double delay = driftIntervalMs * 1.5;

        return new DriftStep(offset, delay);
    }

    // ==================== Utility ====================

    /**
     * Blends two angles on the unit circle, respecting wraparound.
     *
     * @param from   the source angle (radians)
     * @param to     the target angle (radians)
     * @param weight blend weight (0 = all from, 1 = all to)
     * @return the blended angle
     */
    private static double angularBlend(double from, double to, double weight) {
        // Compute shortest angular difference
        double diff = to - from;
        // Normalize to [-π, π]
        diff = diff - 2 * Math.PI * Math.floor((diff + Math.PI) / (2 * Math.PI));
        return from + diff * weight;
    }

    @Override
    public String toString() {
        return String.format(
                "IdleDriftController{step=%.1f px, interval=%.0f ms, radius=%.0f px, " +
                        "correlation=%.2f, gripShift=%.0f px / %.0f s}",
                stepSize, driftIntervalMs, driftRadius,
                driftCorrelation, gripShiftSize, gripShiftIntervalMs / 1000);
    }
}
