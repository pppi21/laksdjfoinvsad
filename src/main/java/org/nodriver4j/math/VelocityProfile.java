package org.nodriver4j.math;

import java.util.Random;

/**
 * Computes per-step time deltas that distribute a total movement duration across
 * path points according to a human-like velocity profile.
 *
 * <p>The profile is bell-shaped with configurable asymmetry: it starts at zero velocity
 * (rest), accelerates to a peak, then decelerates back to zero. The peak occurs at a
 * configurable position (default ~40% of movement time), producing the faster-acceleration /
 * slower-deceleration pattern observed in real human aimed movements.</p>
 *
 * <h3>Mathematical basis</h3>
 *
 * <p>The velocity at normalized time τ ∈ [0,1] is:</p>
 * <pre>
 *   v(τ) = τ^a · (1−τ)^b
 * </pre>
 * <p>where {@code a = concentration × peakPosition} and {@code b = concentration × (1 − peakPosition)}.
 * The peak of this function occurs at τ = a/(a+b) = peakPosition.</p>
 *
 * <p>This generalizes the minimum-jerk velocity profile from Flash &amp; Hogan (1985).
 * The symmetric minimum-jerk profile {@code v(τ) = 30τ²(1−τ)²} is exactly this formula
 * with a=b=2 (peakPosition=0.5, concentration=4). Shifting the peak to 0.40 changes
 * the exponents to create natural asymmetry — steeper acceleration, gentler deceleration
 * — matching the Plamondon (1995) sigma-lognormal observations.</p>
 *
 * <h3>How it works</h3>
 *
 * <p>Given N path points and total duration T milliseconds, this class computes N−1 time
 * deltas (one per interval between consecutive points). Intervals where velocity is high
 * get short deltas (fast traversal); intervals where velocity is low get long deltas
 * (slow traversal). The sum of all deltas equals T.</p>
 *
 * <h3>Design for reuse</h3>
 *
 * <p>This class is purely temporal — it knows nothing about path shape or spatial
 * coordinates. It can be used for primary movements, corrective sub-movements
 * (Component 4), or any situation that needs a bell-shaped speed distribution.
 * Each use case creates its own instance with appropriate parameters.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // From persona (typical)
 * VelocityProfile profile = VelocityProfile.fromPersona(persona);
 * double totalMs = VelocityProfile.fittsDuration(distance, targetWidth, persona.speedFactor(), random);
 * double[] deltas = profile.computeDeltas(path.size(), totalMs);
 *
 * // Custom (e.g., corrective sub-movement with symmetric profile)
 * VelocityProfile corrective = new VelocityProfile(0.50, 4.0);
 * double[] corrDeltas = corrective.computeDeltas(subPath.size(), correctionMs);
 * }</pre>
 *
 * @see MovementPersona#peakVelocityPosition()
 * @see MovementPersona#speedFactor()
 */
public final class VelocityProfile {

    /**
     * Default concentration parameter. Controls how peaked the velocity bell curve is.
     * 4.0 matches the symmetric minimum-jerk profile exactly. Higher values produce a
     * sharper peak (higher peak-to-average ratio). 4.5 yields peak/avg ≈ 1.8 for
     * typical asymmetric profiles.
     */
    private static final double DEFAULT_CONCENTRATION = 4.5;

    /**
     * Velocity floor as a fraction of peak velocity. Prevents infinite time deltas
     * at the very start and end of movement where the theoretical velocity is zero.
     */
    private static final double VELOCITY_FLOOR_FRACTION = 0.01;

    // ==================== Fitts's Law Constants ====================

    private static final double FITTS_A = 200.0;
    private static final double FITTS_B = 150.0;
    private static final double FITTS_NOISE_SD_FRACTION = 0.12;
    private static final double FITTS_MIN_DURATION_MS = 50.0;

    // ==================== Fields ====================

    private final double peakPosition;
    private final double concentration;
    private final double exponentA;
    private final double exponentB;
    private final double velocityFloor;

    // ==================== Constructors ====================

    /**
     * Creates a VelocityProfile with the specified peak position and default concentration.
     *
     * @param peakPosition where peak velocity occurs in normalized time (0–1).
     *                     Typical human value: 0.38–0.42.
     */
    public VelocityProfile(double peakPosition) {
        this(peakPosition, DEFAULT_CONCENTRATION);
    }

    /**
     * Creates a VelocityProfile with full control over shape parameters.
     *
     * @param peakPosition  where peak velocity occurs in normalized time (0–1)
     * @param concentration controls how peaked the bell curve is. Higher = sharper.
     *                      4.0 matches symmetric minimum-jerk. Minimum 2.1.
     */
    public VelocityProfile(double peakPosition, double concentration) {
        this.peakPosition = Vector.clamp(peakPosition, 0.05, 0.95);
        this.concentration = Math.max(concentration, 2.1);

        this.exponentA = this.concentration * this.peakPosition;
        this.exponentB = this.concentration * (1.0 - this.peakPosition);

        double peakVelocity = Math.pow(this.peakPosition, exponentA)
                * Math.pow(1.0 - this.peakPosition, exponentB);
        this.velocityFloor = peakVelocity * VELOCITY_FLOOR_FRACTION;
    }

    /**
     * Creates a VelocityProfile from a persona's peak velocity position.
     *
     * @param persona the movement persona
     * @return a VelocityProfile tuned to this persona
     */
    public static VelocityProfile fromPersona(MovementPersona persona) {
        return new VelocityProfile(persona.peakVelocityPosition());
    }

    // ==================== Core API ====================

    /**
     * Computes per-interval time deltas that distribute the total duration according
     * to this velocity profile.
     *
     * <p>Returns an array of length {@code points - 1}. Each element is the time in
     * milliseconds between consecutive path points. Shorter deltas in the middle
     * (high velocity) and longer deltas at the start and end (low velocity).</p>
     *
     * <p>The sum of all returned deltas equals {@code totalDurationMs} (within
     * floating-point precision).</p>
     *
     * @param points          number of path points (must be ≥ 2)
     * @param totalDurationMs total movement duration in milliseconds
     * @return array of time deltas in milliseconds, length = points − 1
     */
    public double[] computeDeltas(int points, double totalDurationMs) {
        if (points <= 1) {
            return new double[0];
        }

        int intervals = points - 1;
        double[] inverseVelocities = new double[intervals];
        double sum = 0;

        for (int i = 0; i < intervals; i++) {
            // Evaluate velocity at the midpoint of each interval
            double tau = (i + 0.5) / intervals;
            double v = Math.max(velocityAt(tau), velocityFloor);
            inverseVelocities[i] = 1.0 / v;
            sum += inverseVelocities[i];
        }

        // Normalize so deltas sum to totalDurationMs
        double[] deltas = new double[intervals];
        double scale = totalDurationMs / sum;
        for (int i = 0; i < intervals; i++) {
            deltas[i] = inverseVelocities[i] * scale;
        }

        return deltas;
    }

    /**
     * Evaluates the unnormalized velocity at normalized time τ.
     *
     * <p>The returned value is proportional to instantaneous speed — higher values
     * mean faster movement. The absolute scale doesn't matter since
     * {@link #computeDeltas} normalizes internally.</p>
     *
     * @param tau normalized time (0–1), where 0 is movement start and 1 is movement end
     * @return the velocity value (always ≥ 0)
     */
    public double velocityAt(double tau) {
        tau = Vector.clamp(tau, 0.0, 1.0);
        return Math.pow(tau, exponentA) * Math.pow(1.0 - tau, exponentB);
    }

    // ==================== Fitts's Law Utility ====================

    /**
     * Computes a human-like movement duration using Fitts's Law with noise.
     *
     * <p>Base formula: {@code MT = a + b · log₂(D/W + 1)} with a ≈ 200ms, b ≈ 150ms
     * (throughput ~4.0 bits/s). The result is scaled by the persona's speed factor
     * and perturbed with Gaussian noise (SD ≈ 12% of scaled MT).</p>
     *
     * <p>This is a static utility — it computes the total duration that should be
     * passed to {@link #computeDeltas}.</p>
     *
     * @param distance    Euclidean distance to target in pixels
     * @param targetWidth width of the target element in pixels
     * @param speedFactor persona's speed multiplier (typically from {@link MovementPersona#speedFactor()})
     * @param random      seeded Random for deterministic noise
     * @return movement duration in milliseconds (minimum 50ms)
     */
    public static double fittsDuration(double distance, double targetWidth,
                                       double speedFactor, Random random) {
        double id = Math.log(distance / targetWidth + 1.0) / Math.log(2);
        double baseMt = FITTS_A + FITTS_B * id;
        double scaledMt = baseMt * speedFactor;
        double noise = random.nextGaussian() * scaledMt * FITTS_NOISE_SD_FRACTION;
        return Math.max(FITTS_MIN_DURATION_MS, scaledMt + noise);
    }

    // ==================== Accessors ====================

    /**
     * The normalized time at which peak velocity occurs (0–1).
     *
     * @return the peak position
     */
    public double peakPosition() {
        return peakPosition;
    }

    /**
     * The concentration parameter controlling bell curve sharpness.
     *
     * @return the concentration
     */
    public double concentration() {
        return concentration;
    }

    @Override
    public String toString() {
        return String.format("VelocityProfile{peak=%.0f%%, concentration=%.1f, a=%.2f, b=%.2f}",
                peakPosition * 100, concentration, exponentA, exponentB);
    }
}
