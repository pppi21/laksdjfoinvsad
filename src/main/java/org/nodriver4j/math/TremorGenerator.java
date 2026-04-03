package org.nodriver4j.math;

import java.util.Random;

/**
 * Generates physiological tremor — the involuntary micro-oscillation present in all
 * human hand movements at 8–12 Hz. Its absence in cursor data is a known detection
 * vector (Nature Digital Medicine, 2019).
 *
 * <p>The tremor signal is the sum of 3 sine wave harmonics per axis (x and y independently),
 * plus a small broadband Gaussian noise component. Each axis has its own set of frequencies,
 * amplitudes, and phases so the tremor traces an irregular 2D path, not a line.</p>
 *
 * <pre>
 *   tremor_x(t) = Σᵢ Aᵢ · sin(2π · fᵢ · t + φᵢ) · scale(v)  +  noise
 *   tremor_y(t) = Σᵢ Bᵢ · sin(2π · gᵢ · t + ψᵢ) · scale(v)  +  noise
 * </pre>
 *
 * <p>Amplitude is modulated by cursor velocity — tremor is most visible when stationary
 * or moving slowly, and masked during fast ballistic movement. The scale function
 * decays linearly from 1.0 at rest to 0.1 at ≥500 px/s.</p>
 *
 * <p>All harmonic parameters (frequencies, amplitudes, phases) are deterministically
 * derived from the persona seed at construction time and remain fixed for the session,
 * matching how a real individual's tremor characteristics are consistent.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * TremorGenerator tremor = new TremorGenerator(persona);
 *
 * // During movement dispatch:
 * double elapsedSec = (System.nanoTime() - startNano) / 1_000_000_000.0;
 * double velocity = distance / (deltaMs / 1000.0);
 * Vector adjustedPos = position.add(tremor.offset(elapsedSec, velocity));
 * }</pre>
 *
 * @see MovementPersona#tremorAmplitude()
 * @see MovementPersona#tremorFrequencyCenter()
 */
public final class TremorGenerator {

    /**
     * Number of sine wave harmonics per axis.
     */
    private static final int NUM_HARMONICS = 3;

    /**
     * Minimum allowed tremor frequency (Hz). Lower bound of the physiological range.
     */
    private static final double FREQ_MIN = 8.0;

    /**
     * Maximum allowed tremor frequency (Hz). Upper bound of the physiological range.
     */
    private static final double FREQ_MAX = 12.0;

    /**
     * Standard deviation for spreading individual harmonics around the center frequency.
     */
    private static final double FREQ_SPREAD_SD = 0.8;

    /**
     * Velocity (px/s) at which tremor amplitude reaches its minimum scale.
     */
    private static final double REFERENCE_VELOCITY = 500.0;

    /**
     * Minimum amplitude scale factor — tremor never fully disappears.
     */
    private static final double MIN_AMPLITUDE_SCALE = 0.1;

    /**
     * Broadband noise amplitude as a fraction of total tremor amplitude.
     */
    private static final double BROADBAND_FRACTION = 0.1;

    /**
     * Salt mixed into the persona seed to derive tremor-specific random state,
     * avoiding correlation with other persona-derived parameters.
     */
    private static final long SEED_SALT = 0x5452454D4F52L;

    // ==================== Per-Axis Harmonic Parameters ====================

    private final double[] xFrequencies;
    private final double[] xAmplitudes;
    private final double[] xPhases;

    private final double[] yFrequencies;
    private final double[] yAmplitudes;
    private final double[] yPhases;

    private final double broadbandScale;
    private final Random noiseRandom;

    /**
     * Creates a TremorGenerator with harmonic parameters derived from the persona.
     *
     * <p>The persona's {@link MovementPersona#tremorFrequencyCenter() tremorFrequencyCenter}
     * seeds the 3 per-axis frequencies (spread with Gaussian noise around the center).
     * The persona's {@link MovementPersona#tremorAmplitude() tremorAmplitude} is distributed
     * across the harmonics with random weighting. Phases are uniformly random in [0, 2π].</p>
     *
     * @param persona the movement persona providing tremor parameters
     */
    public TremorGenerator(MovementPersona persona) {
        Random rng = new Random(persona.seed() ^ SEED_SALT);

        double center = persona.tremorFrequencyCenter();
        double totalAmp = persona.tremorAmplitude();

        // X-axis harmonics
        xFrequencies = generateFrequencies(rng, center);
        xAmplitudes = generateAmplitudes(rng, totalAmp);
        xPhases = generatePhases(rng);

        // Y-axis harmonics (independent from X)
        yFrequencies = generateFrequencies(rng, center);
        yAmplitudes = generateAmplitudes(rng, totalAmp);
        yPhases = generatePhases(rng);

        broadbandScale = totalAmp * BROADBAND_FRACTION;
        noiseRandom = new Random(rng.nextLong());
    }

    // ==================== Public API ====================

    /**
     * Computes the tremor offset to add to a cursor position.
     *
     * <p>The offset is a small displacement (typically sub-pixel to ~2 pixels) that
     * oscillates at physiological tremor frequencies. Amplitude is modulated by cursor
     * velocity — full tremor at rest, reduced during fast movement.</p>
     *
     * @param timeSeconds              elapsed time in seconds (monotonic, from session start)
     * @param velocityPixelsPerSecond  current cursor speed in pixels per second (0 = at rest)
     * @return the tremor offset vector to add to the cursor position
     */
    public Vector offset(double timeSeconds, double velocityPixelsPerSecond) {
        double scale = amplitudeScale(velocityPixelsPerSecond);

        double x = 0;
        for (int i = 0; i < NUM_HARMONICS; i++) {
            x += xAmplitudes[i] * Math.sin(2 * Math.PI * xFrequencies[i] * timeSeconds + xPhases[i]);
        }

        double y = 0;
        for (int i = 0; i < NUM_HARMONICS; i++) {
            y += yAmplitudes[i] * Math.sin(2 * Math.PI * yFrequencies[i] * timeSeconds + yPhases[i]);
        }

        // Apply velocity-dependent amplitude scaling and add broadband noise
        x = x * scale + noiseRandom.nextGaussian() * broadbandScale * scale;
        y = y * scale + noiseRandom.nextGaussian() * broadbandScale * scale;

        return new Vector(x, y);
    }

    /**
     * Convenience: returns the given position with tremor applied.
     *
     * @param position                 the base cursor position
     * @param timeSeconds              elapsed time in seconds
     * @param velocityPixelsPerSecond  current cursor speed in pixels per second
     * @return the position offset by tremor
     */
    public Vector apply(Vector position, double timeSeconds, double velocityPixelsPerSecond) {
        return position.add(offset(timeSeconds, velocityPixelsPerSecond));
    }

    // ==================== Amplitude Modulation ====================

    /**
     * Computes the velocity-dependent amplitude scale factor.
     *
     * <p>Linear decay from 1.0 at rest to {@value MIN_AMPLITUDE_SCALE} at
     * ≥{@value REFERENCE_VELOCITY} px/s. Tremor never fully disappears.</p>
     *
     * @param velocity cursor speed in pixels per second
     * @return scale factor in [{@value MIN_AMPLITUDE_SCALE}, 1.0]
     */
    private static double amplitudeScale(double velocity) {
        return Math.max(MIN_AMPLITUDE_SCALE,
                1.0 - (1.0 - MIN_AMPLITUDE_SCALE) * velocity / REFERENCE_VELOCITY);
    }

    // ==================== Parameter Generation ====================

    /**
     * Generates harmonic frequencies spread around the center with Gaussian noise,
     * clamped to the physiological tremor band [8, 12] Hz.
     */
    private static double[] generateFrequencies(Random rng, double center) {
        double[] freqs = new double[NUM_HARMONICS];
        for (int i = 0; i < NUM_HARMONICS; i++) {
            freqs[i] = Vector.clamp(
                    center + rng.nextGaussian() * FREQ_SPREAD_SD,
                    FREQ_MIN, FREQ_MAX);
        }
        return freqs;
    }

    /**
     * Distributes the total tremor amplitude across harmonics with random weighting.
     * The dominant harmonic gets roughly half the amplitude; the others share the rest.
     */
    private static double[] generateAmplitudes(Random rng, double totalAmplitude) {
        double[] weights = new double[NUM_HARMONICS];
        double sum = 0;
        for (int i = 0; i < NUM_HARMONICS; i++) {
            weights[i] = 0.5 + rng.nextDouble();
            sum += weights[i];
        }
        double[] amps = new double[NUM_HARMONICS];
        for (int i = 0; i < NUM_HARMONICS; i++) {
            amps[i] = totalAmplitude * weights[i] / sum;
        }
        return amps;
    }

    /**
     * Generates random phases uniformly in [0, 2π].
     */
    private static double[] generatePhases(Random rng) {
        double[] phases = new double[NUM_HARMONICS];
        for (int i = 0; i < NUM_HARMONICS; i++) {
            phases[i] = rng.nextDouble() * 2 * Math.PI;
        }
        return phases;
    }

    @Override
    public String toString() {
        return String.format("TremorGenerator{xFreqs=[%.1f, %.1f, %.1f] Hz, yFreqs=[%.1f, %.1f, %.1f] Hz}",
                xFrequencies[0], xFrequencies[1], xFrequencies[2],
                yFrequencies[0], yFrequencies[1], yFrequencies[2]);
    }
}
