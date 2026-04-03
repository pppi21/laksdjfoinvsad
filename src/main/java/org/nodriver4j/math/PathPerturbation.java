package org.nodriver4j.math;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Applies micro-perturbations to a base mouse path, creating small irregularities
 * that make paths look organic rather than mathematically perfect.
 *
 * <p>Perturbations are applied perpendicular to the overall movement direction using
 * a damped random walk. Each step adds a small Gaussian-distributed offset that
 * accumulates over time but decays toward zero, preventing drift from growing
 * unbounded. This models the small motor imprecision present in real hand movement
 * — the hand doesn't follow a perfectly smooth arc, it wobbles slightly.</p>
 *
 * <p>The effect is shaped by a parabolic envelope that peaks at the path midpoint and
 * fades to zero at both endpoints, ensuring clean departure from the start position
 * and accurate arrival at the target. This mirrors NaturalMouseMotion's
 * {@code effectFadeMultiplier} approach.</p>
 *
 * <p>This is a spatial concern only — it affects path shape, not timing. Physiological
 * tremor (Component 5) is applied separately as a per-frame overlay at dispatch time.</p>
 *
 * @see TrajectoryGenerator
 */
public final class PathPerturbation {

    private static final double DEFAULT_NOISE_SCALE = 0.4;
    private static final double DEFAULT_DAMPENING = 0.75;

    private final double noiseScale;
    private final double dampening;

    /**
     * Creates a PathPerturbation with the specified parameters.
     *
     * @param noiseScale standard deviation of each random walk step in pixels.
     *                   Higher values produce more visible wobble. Typical range: 0.2–1.0.
     * @param dampening  decay factor applied to accumulated noise each step (0–1).
     *                   Higher values produce smoother, more correlated noise.
     *                   Lower values produce more jittery, independent noise.
     *                   Typical range: 0.6–0.9.
     */
    public PathPerturbation(double noiseScale, double dampening) {
        this.noiseScale = noiseScale;
        this.dampening = Vector.clamp(dampening, 0.0, 1.0);
    }

    /**
     * Returns a PathPerturbation with default parameters suitable for most movements.
     *
     * @return default PathPerturbation
     */
    public static PathPerturbation defaults() {
        return new PathPerturbation(DEFAULT_NOISE_SCALE, DEFAULT_DAMPENING);
    }

    /**
     * Returns a new path with micro-perturbations applied perpendicular to the
     * overall movement direction.
     *
     * <p>The first and last points are preserved exactly — perturbations only
     * affect interior points. For paths with fewer than 3 points, the original
     * path is returned unchanged.</p>
     *
     * @param basePath the base path to perturb (typically from a Bézier curve)
     * @param random   seeded Random for deterministic perturbation
     * @return a new list of perturbed points
     */
    public List<Vector> apply(List<Vector> basePath, Random random) {
        int n = basePath.size();
        if (n < 3) {
            return new ArrayList<>(basePath);
        }

        Vector start = basePath.getFirst();
        Vector end = basePath.getLast();
        Vector direction = start.directionTo(end);

        if (direction.magnitude() < 1.0) {
            return new ArrayList<>(basePath);
        }

        Vector perpUnit = direction.perpendicular().unit();

        List<Vector> result = new ArrayList<>(n);
        result.add(start);

        double accumulated = 0;

        for (int i = 1; i < n - 1; i++) {
            double fraction = (double) i / (n - 1);

            // Parabolic envelope: 0 at endpoints, 1 at midpoint
            double envelope = 4.0 * fraction * (1.0 - fraction);

            // Damped random walk: accumulate with decay toward zero
            accumulated = accumulated * dampening + random.nextGaussian() * noiseScale;

            double offset = accumulated * envelope;
            result.add(basePath.get(i).add(perpUnit.multiply(offset)));
        }

        result.add(end);
        return result;
    }

    /**
     * Standard deviation of each random walk step in pixels.
     *
     * @return the noise scale
     */
    public double noiseScale() {
        return noiseScale;
    }

    /**
     * Decay factor for accumulated noise (0–1).
     *
     * @return the dampening factor
     */
    public double dampening() {
        return dampening;
    }

    @Override
    public String toString() {
        return String.format("PathPerturbation{scale=%.2f, dampening=%.2f}", noiseScale, dampening);
    }
}
