package org.nodriver4j.math;

import java.util.Objects;
import java.util.Random;

/**
 * Defines a unique behavioral profile for mouse movement, clicking, and pausing.
 *
 * <p>Each MovementPersona is deterministically derived from a long seed, ensuring that
 * the same profile produces identical behavioral parameters across sessions while
 * naturally varying between profiles. This models the fact that real humans have
 * individual motor signatures — some move fast with little curvature, others move
 * slowly with wide arcs.</p>
 *
 * <p>All parameters fall within human-plausible ranges based on motor behavior research
 * (SapiMouse dataset, Boğaziçi dataset). Parameters are sampled uniformly within
 * their ranges using a seeded {@link Random} for deterministic reproducibility.</p>
 *
 * <p>This class is immutable — all parameters are derived at construction time.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * MovementPersona persona = new MovementPersona(profile.movementSeed());
 * double speed = persona.speedFactor();
 * double tremor = persona.tremorAmplitude();
 * }</pre>
 *
 * @see HumanBehavior
 */
public final class MovementPersona {

    private final long seed;

    // ==================== Movement Parameters ====================

    private final double speedFactor;
    private final double curvatureBias;
    private final double tremorAmplitude;
    private final double tremorFrequencyCenter;
    private final double clickOffsetBiasX;
    private final double clickOffsetBiasY;
    private final double pauseMeanMs;
    private final double pauseLogSd;
    private final double overshootProbability;
    private final double idleDriftAmplitude;
    private final double peakVelocityPosition;
    private final double fittsA;
    private final double fittsB;
    private final double fittsNoise;
    private final double clickDriftAmplitude;
    private final double clickHoldMeanMs;
    private final double clickHoldSdMs;
    private final double activityDensityThreshold;
    private final double keyboardToMouseDelayMs;
    private final double mouseToKeyboardDelayMs;
    private final double postIdleSlowdownThresholdMs;
    private final double firstInteractionDelayMs;
    private final double driftIntervalMs;
    private final double driftRadius;
    private final double driftCorrelation;
    private final double gripShiftIntervalMs;
    private final double gripShiftSize;
    private final double scrollMomentumDecay;
    private final int scrollBurstLength;
    private final double scrollCursorDriftScale;

    /**
     * Creates a MovementPersona from the given seed.
     *
     * <p>The same seed always produces identical parameters. Parameters are sampled
     * within human-plausible ranges using a seeded {@link Random}.</p>
     *
     * @param seed the deterministic seed (typically from a profile entity)
     */
    public MovementPersona(long seed) {
        this.seed = seed;
        Random random = new Random(seed);

        this.speedFactor = sampleUniform(random, 0.7, 1.3);
        this.curvatureBias = sampleUniform(random, -1.0, 1.0);
        this.tremorAmplitude = sampleUniform(random, 0.3, 1.5);
        this.tremorFrequencyCenter = sampleUniform(random, 8.0, 12.0);
        this.clickOffsetBiasX = sampleUniform(random, -2.0, 2.0);
        this.clickOffsetBiasY = sampleUniform(random, -2.0, 2.0);
        this.pauseMeanMs = sampleUniform(random, 200.0, 600.0);
        this.pauseLogSd = sampleUniform(random, 0.3, 0.6);
        this.overshootProbability = sampleUniform(random, 0.1, 0.4);
        this.idleDriftAmplitude = sampleUniform(random, 0.5, 3.0);
        this.peakVelocityPosition = Vector.clamp(
                0.40 + random.nextGaussian() * 0.03, 0.35, 0.45);
        this.fittsA = sampleUniform(random, 150.0, 250.0);
        this.fittsB = sampleUniform(random, 120.0, 180.0);
        this.fittsNoise = sampleUniform(random, 0.08, 0.15);
        this.clickDriftAmplitude = sampleUniform(random, 0.5, 2.0);
        this.clickHoldMeanMs = sampleUniform(random, 80.0, 120.0);
        this.clickHoldSdMs = sampleUniform(random, 15.0, 30.0);
        this.activityDensityThreshold = sampleUniform(random, 8.0, 15.0);
        this.keyboardToMouseDelayMs = sampleUniform(random, 150.0, 400.0);
        this.mouseToKeyboardDelayMs = sampleUniform(random, 100.0, 300.0);
        this.postIdleSlowdownThresholdMs = sampleUniform(random, 2000.0, 5000.0);
        this.firstInteractionDelayMs = sampleUniform(random, 500.0, 2000.0);
        this.driftIntervalMs = sampleUniform(random, 80.0, 150.0);
        this.driftRadius = sampleUniform(random, 20.0, 50.0);
        this.driftCorrelation = sampleUniform(random, 0.6, 0.9);
        this.gripShiftIntervalMs = sampleUniform(random, 5000.0, 15000.0);
        this.gripShiftSize = sampleUniform(random, 5.0, 15.0);
        this.scrollMomentumDecay = sampleUniform(random, 0.85, 0.95);
        this.scrollBurstLength = 3 + random.nextInt(4);
        this.scrollCursorDriftScale = sampleUniform(random, 0.5, 2.0);
    }

    // ==================== Accessors ====================

    /**
     * The seed used to derive this persona's parameters.
     *
     * @return the seed value
     */
    public long seed() {
        return seed;
    }

    /**
     * Multiplier on Fitts's Law movement time (0.7–1.3).
     * Values below 1.0 produce faster movements, above 1.0 produce slower movements.
     *
     * @return the speed factor
     */
    public double speedFactor() {
        return speedFactor;
    }

    /**
     * Preferred curvature tendency (-1.0 to 1.0).
     * Negative values bias paths to curve left, positive values bias right.
     *
     * @return the curvature bias
     */
    public double curvatureBias() {
        return curvatureBias;
    }

    /**
     * Physiological tremor amplitude in pixels (0.3–1.5).
     *
     * @return the tremor amplitude
     */
    public double tremorAmplitude() {
        return tremorAmplitude;
    }

    /**
     * Center frequency for physiological tremor in Hz (8.0–12.0).
     *
     * @return the tremor frequency center
     */
    public double tremorFrequencyCenter() {
        return tremorFrequencyCenter;
    }

    /**
     * Consistent horizontal click offset bias in pixels (-2.0 to 2.0).
     * Simulates handedness — e.g., a right-handed user may consistently click
     * slightly to the right of computed targets.
     *
     * @return the horizontal click offset bias
     */
    public double clickOffsetBiasX() {
        return clickOffsetBiasX;
    }

    /**
     * Consistent vertical click offset bias in pixels (-2.0 to 2.0).
     *
     * @return the vertical click offset bias
     */
    public double clickOffsetBiasY() {
        return clickOffsetBiasY;
    }

    /**
     * Mean inter-action pause duration in milliseconds (200–600).
     *
     * @return the mean pause duration
     */
    public double pauseMeanMs() {
        return pauseMeanMs;
    }

    /**
     * Log-space standard deviation for inter-action pause distribution (0.3–0.6).
     * Higher values produce a longer tail (more occasional long pauses).
     * Used with {@link PauseDistribution} to sample log-normal pauses.
     *
     * @return the log-space SD
     */
    public double pauseLogSd() {
        return pauseLogSd;
    }

    /**
     * Probability of overshooting the target for movements above threshold distance (0.1–0.4).
     *
     * @return the overshoot probability
     */
    public double overshootProbability() {
        return overshootProbability;
    }

    /**
     * Amplitude of idle cursor drift in pixels (0.5–3.0).
     *
     * @return the idle drift amplitude
     */
    public double idleDriftAmplitude() {
        return idleDriftAmplitude;
    }

    /**
     * Where peak velocity occurs in normalized movement time (0.35–0.45).
     * Sampled from Normal(0.40, 0.03) — most personas peak near 40% of movement
     * duration, matching the asymmetric velocity profiles observed in human aimed
     * movements (faster acceleration, slower deceleration).
     *
     * @return the peak velocity position as a fraction of total movement time
     */
    public double peakVelocityPosition() {
        return peakVelocityPosition;
    }

    /**
     * Fitts's Law intercept in milliseconds (150–250).
     * The base reaction/movement initiation time before distance-dependent cost.
     *
     * @return the Fitts's Law 'a' coefficient
     */
    public double fittsA() {
        return fittsA;
    }

    /**
     * Fitts's Law slope in milliseconds per bit (120–180).
     * The time cost per unit of Index of Difficulty.
     *
     * @return the Fitts's Law 'b' coefficient
     */
    public double fittsB() {
        return fittsB;
    }

    /**
     * Gaussian noise SD as a fraction of computed movement time (0.08–0.15).
     * Applied after computing base MT to add natural timing variability.
     *
     * @return the noise fraction
     */
    public double fittsNoise() {
        return fittsNoise;
    }

    /**
     * Magnitude of pre-click drift in pixels (0.5–2.0).
     * The small displacement between the last mousemove and the mousedown,
     * simulating the hand settling on the mouse before pressing.
     *
     * @return the click drift amplitude
     */
    public double clickDriftAmplitude() {
        return clickDriftAmplitude;
    }

    /**
     * Mean click hold duration in milliseconds (80–120).
     * The time between mousedown and mouseup events.
     *
     * @return the click hold mean
     */
    public double clickHoldMeanMs() {
        return clickHoldMeanMs;
    }

    /**
     * Standard deviation of click hold duration in milliseconds (15–30).
     *
     * @return the click hold SD
     */
    public double clickHoldSdMs() {
        return clickHoldSdMs;
    }

    /**
     * Actions per 10-second window before activity density throttling kicks in (8–15).
     *
     * @return the activity density threshold
     */
    public double activityDensityThreshold() {
        return activityDensityThreshold;
    }

    /**
     * Delay when switching from keyboard to mouse input in milliseconds (150–400).
     *
     * @return the keyboard-to-mouse transition delay
     */
    public double keyboardToMouseDelayMs() {
        return keyboardToMouseDelayMs;
    }

    /**
     * Delay when switching from mouse to keyboard input in milliseconds (100–300).
     *
     * @return the mouse-to-keyboard transition delay
     */
    public double mouseToKeyboardDelayMs() {
        return mouseToKeyboardDelayMs;
    }

    /**
     * Idle duration threshold before post-idle re-engagement slowdown applies (2000–5000 ms).
     *
     * @return the post-idle slowdown threshold in milliseconds
     */
    public double postIdleSlowdownThresholdMs() {
        return postIdleSlowdownThresholdMs;
    }

    /**
     * Minimum delay before the first action after a page navigation (500–2000 ms).
     * Simulates the user scanning/reading the page before interacting.
     *
     * @return the first interaction delay in milliseconds
     */
    public double firstInteractionDelayMs() {
        return firstInteractionDelayMs;
    }

    /**
     * Mean interval between idle drift events in milliseconds (80–150).
     *
     * @return the drift interval
     */
    public double driftIntervalMs() {
        return driftIntervalMs;
    }

    /**
     * Maximum wandering radius from the rest position in pixels (20–50).
     *
     * @return the drift radius
     */
    public double driftRadius() {
        return driftRadius;
    }

    /**
     * Directional correlation between consecutive drift steps (0.6–0.9).
     * Higher values produce smoother, more meandering drift.
     *
     * @return the drift correlation factor
     */
    public double driftCorrelation() {
        return driftCorrelation;
    }

    /**
     * Interval between larger "grip shift" adjustments in milliseconds (5000–15000).
     *
     * @return the grip shift interval
     */
    public double gripShiftIntervalMs() {
        return gripShiftIntervalMs;
    }

    /**
     * Size of grip shift adjustments in pixels (5–15).
     *
     * @return the grip shift size
     */
    public double gripShiftSize() {
        return gripShiftSize;
    }

    /**
     * Scroll momentum decay factor per tick (0.85–0.95).
     * Each scroll tick amount is the previous tick's amount multiplied by this factor.
     * Higher values produce more sustained scrolling; lower values decelerate faster.
     *
     * @return the scroll momentum decay factor
     */
    public double scrollMomentumDecay() {
        return scrollMomentumDecay;
    }

    /**
     * Number of scroll ticks per burst before a micro-pause (3–6).
     *
     * @return the scroll burst length
     */
    public int scrollBurstLength() {
        return scrollBurstLength;
    }

    /**
     * Lateral cursor drift scale during scrolling in pixels per tick (0.5–2.0).
     *
     * @return the scroll cursor drift scale
     */
    public double scrollCursorDriftScale() {
        return scrollCursorDriftScale;
    }

    // ==================== Sampling ====================

    /**
     * Samples a value uniformly in [min, max).
     *
     * @param random the seeded Random instance
     * @param min    the minimum value (inclusive)
     * @param max    the maximum value (exclusive)
     * @return a uniformly sampled value
     */
    private static double sampleUniform(Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MovementPersona that = (MovementPersona) o;
        return seed == that.seed;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(seed);
    }

    @Override
    public String toString() {
        return String.format(
                "MovementPersona{seed=%d, speed=%.2f, curvature=%.2f, tremor=%.2f px @ %.1f Hz, " +
                        "clickBias=(%.2f, %.2f), pause=%.0f ms, overshoot=%.0f%%, drift=%.2f px, " +
                        "peak=%.0f%%, fitts=(%.0f+%.0f·ID ±%.0f%%)}",
                seed, speedFactor, curvatureBias, tremorAmplitude, tremorFrequencyCenter,
                clickOffsetBiasX, clickOffsetBiasY, pauseMeanMs,
                overshootProbability * 100, idleDriftAmplitude, peakVelocityPosition * 100,
                fittsA, fittsB, fittsNoise * 100
        );
    }
}
