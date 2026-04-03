package org.nodriver4j.math;

import java.util.Random;

/**
 * Samples inter-action pause durations from a log-normal distribution, producing
 * the long-tailed timing pattern observed in real human browsing behavior.
 *
 * <p>Most pauses are short (200–400ms), but occasional longer pauses (1–2 seconds)
 * occur naturally as users think, read, or hesitate. A uniform or Gaussian distribution
 * cannot reproduce this pattern — the log-normal is the correct model for human
 * inter-action timing (Boğaziçi dataset analysis).</p>
 *
 * <h3>Mathematical basis</h3>
 * <pre>
 *   pause = exp(μ + σ × gaussian())
 * </pre>
 * <p>where μ (log-space mean) is derived from the persona's {@code pauseMeanMs} to
 * ensure the distribution's expected value matches the persona's target:</p>
 * <pre>
 *   μ = ln(pauseMeanMs) − σ²/2
 * </pre>
 *
 * <p>This class also integrates with {@link ActionIntent} — CONFIRM adds extra
 * hesitation, NAVIGATE suppresses the base pause, etc.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * PauseDistribution pauses = PauseDistribution.fromPersona(persona);
 *
 * // Simple sample:
 * double pauseMs = pauses.sample(random);
 *
 * // With intent modifiers:
 * double pauseMs = pauses.sample(random, ActionIntent.CONFIRM);
 * }</pre>
 *
 * @see MovementPersona#pauseMeanMs()
 * @see MovementPersona#pauseLogSd()
 * @see ActionIntent#hesitationMultiplier()
 */
public final class PauseDistribution {

    /**
     * Minimum pause duration (ms). Even the fastest human actions have measurable gaps.
     */
    private static final double MIN_PAUSE_MS = 30.0;

    /**
     * Maximum pause duration (ms). Prevents extreme outliers from the log-normal tail.
     */
    private static final double MAX_PAUSE_MS = 5000.0;

    private final double mu;
    private final double sigma;

    /**
     * Creates a PauseDistribution with explicit log-space parameters.
     *
     * @param logMean log-space mean (μ)
     * @param logSd   log-space standard deviation (σ), typical range 0.3–0.6
     */
    public PauseDistribution(double logMean, double logSd) {
        this.mu = logMean;
        this.sigma = Math.max(logSd, 0.01);
    }

    /**
     * Creates a PauseDistribution from a persona's pause parameters.
     *
     * <p>The log-space mean μ is derived so that the distribution's expected value
     * equals {@code persona.pauseMeanMs()}: {@code μ = ln(pauseMeanMs) − σ²/2}.</p>
     *
     * @param persona the movement persona
     * @return a PauseDistribution tuned to this persona
     */
    public static PauseDistribution fromPersona(MovementPersona persona) {
        double sigma = persona.pauseLogSd();
        double mu = Math.log(persona.pauseMeanMs()) - sigma * sigma / 2.0;
        return new PauseDistribution(mu, sigma);
    }

    // ==================== Sampling ====================

    /**
     * Samples a pause duration from the log-normal distribution.
     *
     * <p>Clamped to [{@value MIN_PAUSE_MS}, {@value MAX_PAUSE_MS}] ms.</p>
     *
     * @param random seeded Random for deterministic sampling
     * @return pause duration in milliseconds
     */
    public double sample(Random random) {
        double raw = Math.exp(mu + sigma * random.nextGaussian());
        return Vector.clamp(raw, MIN_PAUSE_MS, MAX_PAUSE_MS);
    }

    /**
     * Samples a pause duration with {@link ActionIntent} modifiers applied.
     *
     * <p>The base log-normal sample is scaled by the intent's
     * {@code hesitationMultiplier}, then the intent's additional hesitation range
     * is added on top. For example:</p>
     * <ul>
     *   <li>{@code CONFIRM}: full base pause + 200–800ms additional</li>
     *   <li>{@code NAVIGATE}: base suppressed (×0.0) + 0–50ms additional</li>
     *   <li>{@code CASUAL}: full base pause, no additional</li>
     * </ul>
     *
     * @param random seeded Random for deterministic sampling
     * @param intent the action intent modifying pause behavior
     * @return pause duration in milliseconds
     */
    public double sample(Random random, ActionIntent intent) {
        double base = sample(random) * intent.hesitationMultiplier();

        double additionalMin = intent.additionalHesitationMinMs();
        double additionalMax = intent.additionalHesitationMaxMs();
        if (additionalMax > additionalMin) {
            base += additionalMin + random.nextDouble() * (additionalMax - additionalMin);
        }

        return Vector.clamp(base, MIN_PAUSE_MS, MAX_PAUSE_MS);
    }

    // ==================== Accessors ====================

    /**
     * The log-space mean parameter.
     *
     * @return μ
     */
    public double logMean() {
        return mu;
    }

    /**
     * The log-space standard deviation parameter.
     *
     * @return σ
     */
    public double logSd() {
        return sigma;
    }

    /**
     * The expected value (mean) of the distribution in milliseconds.
     *
     * @return E[pause] = exp(μ + σ²/2)
     */
    public double expectedMeanMs() {
        return Math.exp(mu + sigma * sigma / 2.0);
    }

    /**
     * The median of the distribution in milliseconds.
     *
     * @return median = exp(μ)
     */
    public double medianMs() {
        return Math.exp(mu);
    }

    @Override
    public String toString() {
        return String.format("PauseDistribution{μ=%.2f, σ=%.2f, mean=%.0f ms, median=%.0f ms}",
                mu, sigma, expectedMeanMs(), medianMs());
    }
}
