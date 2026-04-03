package org.nodriver4j.math;

import java.util.Random;

/**
 * Computes realistic total movement duration using the Shannon formulation of
 * Fitts's Law with per-persona coefficients and Gaussian noise.
 *
 * <p>Fitts's Law (MacKenzie 1992):</p>
 * <pre>
 *   MT = a + b · log₂(D/W + 1)
 * </pre>
 * <p>where {@code a} is the intercept (reaction/initiation time), {@code b} is the slope
 * (time cost per bit of difficulty), {@code D} is the distance to the target, and
 * {@code W} is the target width.</p>
 *
 * <p>The base MT is then scaled by the persona's {@link MovementPersona#speedFactor()
 * speedFactor} and perturbed with Gaussian noise (SD = {@code fittsNoise × MT}). This
 * produces realistic timing variability — the same movement repeated won't take exactly
 * the same time, matching natural human motor behavior.</p>
 *
 * <h3>What this class does NOT do</h3>
 * <ul>
 *   <li>Global speed multiplier from {@code InteractionOptions} — applied by the caller,
 *       not here. This class computes the base human-realistic duration only.</li>
 *   <li>Velocity profile distribution — that's {@link VelocityProfile#computeDeltas}.
 *       This class provides the total duration that feeds into it.</li>
 *   <li>Sub-movement decomposition — that's {@link SubMovementPlanner}. It consumes
 *       the duration from this class.</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * FittsLawTiming timing = FittsLawTiming.fromPersona(persona);
 * double durationMs = timing.duration(distance, targetWidth, random);
 *
 * // Feed into velocity profile:
 * double[] deltas = velocityProfile.computeDeltas(path.size(), durationMs);
 * }</pre>
 *
 * @see MovementPersona#fittsA()
 * @see MovementPersona#fittsB()
 * @see MovementPersona#fittsNoise()
 * @see MovementPersona#speedFactor()
 */
public final class FittsLawTiming {

    /**
     * Minimum movement duration (ms). Even trivial movements take some time.
     */
    private static final double MIN_DURATION_MS = 50.0;

    private final double a;
    private final double b;
    private final double noiseFraction;
    private final double speedFactor;

    /**
     * Creates a FittsLawTiming with explicit coefficients.
     *
     * <p>Use this constructor for non-standard situations like corrective submovements
     * that need different timing parameters.</p>
     *
     * @param a             intercept in milliseconds (typical: 150–250)
     * @param b             slope in milliseconds per bit (typical: 120–180)
     * @param noiseFraction Gaussian noise SD as a fraction of MT (typical: 0.08–0.15)
     * @param speedFactor   multiplier on MT (1.0 = normal, &gt;1.0 = slower, &lt;1.0 = faster)
     */
    public FittsLawTiming(double a, double b, double noiseFraction, double speedFactor) {
        this.a = a;
        this.b = b;
        this.noiseFraction = noiseFraction;
        this.speedFactor = speedFactor;
    }

    /**
     * Creates a FittsLawTiming from a persona's Fitts's Law parameters.
     *
     * @param persona the movement persona
     * @return a FittsLawTiming tuned to this persona
     */
    public static FittsLawTiming fromPersona(MovementPersona persona) {
        return new FittsLawTiming(
                persona.fittsA(),
                persona.fittsB(),
                persona.fittsNoise(),
                persona.speedFactor());
    }

    /**
     * Computes the total movement duration for the given distance and target width.
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>Compute Index of Difficulty: {@code ID = log₂(D/W + 1)}</li>
     *   <li>Base movement time: {@code MT = a + b · ID}</li>
     *   <li>Scale by persona speed factor: {@code MT × speedFactor}</li>
     *   <li>Add Gaussian noise: {@code MT + N(0, MT × noiseFraction)}</li>
     *   <li>Clamp to minimum {@value MIN_DURATION_MS} ms</li>
     * </ol>
     *
     * @param distance    Euclidean distance to target in pixels
     * @param targetWidth width of the target element in pixels
     * @param random      seeded Random for deterministic noise
     * @return movement duration in milliseconds (minimum {@value MIN_DURATION_MS})
     */
    public double duration(double distance, double targetWidth, Random random) {
        double id = indexOfDifficulty(distance, targetWidth);
        double baseMt = a + b * id;
        double scaledMt = baseMt * speedFactor;
        double noise = random.nextGaussian() * scaledMt * noiseFraction;
        return Math.max(MIN_DURATION_MS, scaledMt + noise);
    }

    /**
     * Computes the Index of Difficulty (Shannon formulation).
     *
     * <p>{@code ID = log₂(D/W + 1)}, measured in bits. Higher values indicate harder
     * targets (farther away and/or smaller). Typical mouse pointing tasks range from
     * ~1 bit (large nearby target) to ~6 bits (small distant target).</p>
     *
     * @param distance    Euclidean distance to target in pixels
     * @param targetWidth width of the target element in pixels
     * @return the Index of Difficulty in bits
     */
    public static double indexOfDifficulty(double distance, double targetWidth) {
        return Math.log(distance / targetWidth + 1.0) / Math.log(2);
    }

    // ==================== Accessors ====================

    /**
     * Fitts's Law intercept (ms).
     *
     * @return the 'a' coefficient
     */
    public double a() {
        return a;
    }

    /**
     * Fitts's Law slope (ms/bit).
     *
     * @return the 'b' coefficient
     */
    public double b() {
        return b;
    }

    /**
     * Noise SD as a fraction of computed MT.
     *
     * @return the noise fraction
     */
    public double noiseFraction() {
        return noiseFraction;
    }

    /**
     * Speed factor applied to base MT.
     *
     * @return the speed factor
     */
    public double speedFactor() {
        return speedFactor;
    }

    @Override
    public String toString() {
        return String.format("FittsLawTiming{a=%.0f ms, b=%.0f ms/bit, noise=%.0f%%, speed=%.2f}",
                a, b, noiseFraction * 100, speedFactor);
    }
}
