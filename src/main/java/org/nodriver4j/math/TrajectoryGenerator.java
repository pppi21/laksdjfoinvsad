package org.nodriver4j.math;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates enhanced human-like mouse movement trajectories.
 *
 * <p>Uses the existing {@link HumanBehavior.CubicBezier} infrastructure for gross path
 * shape, then layers {@link PathPerturbation micro-perturbations} on top. Paths are
 * shaped by a {@link MovementPersona} for per-profile behavioral consistency.</p>
 *
 * <p>Enhancements over the current {@link HumanBehavior#generatePath} approach:</p>
 * <ol>
 *   <li><b>Asymmetric control point placement</b> — CP1 is positioned in the first
 *       third of the path with stronger spread, creating more curvature in the departure
 *       phase. This matches research showing human aimed movements curve more in the
 *       first half.</li>
 *   <li><b>Persona-influenced curvature</b> — the persona's {@code curvatureBias}
 *       biases which side paths tend to curve toward, creating a consistent handedness
 *       signature across movements for the same profile.</li>
 *   <li><b>Micro-perturbations</b> — a damped random walk applied perpendicular to the
 *       movement direction adds organic irregularity without affecting endpoints.</li>
 * </ol>
 *
 * <p>Output is a {@code List<Vector>} of spatial points with no timing information.
 * The velocity profile (Component 3) assigns timing to these points separately.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * MovementPersona persona = new MovementPersona(profile.movementSeed());
 * Random random = new Random(persona.seed());
 * TrajectoryGenerator gen = new TrajectoryGenerator(persona, random);
 *
 * List<Vector> path = gen.generate(start, end);
 * List<Vector> pathWithSteps = gen.generate(start, end, 100);
 * }</pre>
 *
 * @see MovementPersona
 * @see PathPerturbation
 * @see HumanBehavior.CubicBezier
 */
public final class TrajectoryGenerator {

    private static final double MIN_SPREAD = 2.0;
    private static final double MAX_SPREAD = 200.0;
    private static final int MIN_STEPS = 25;

    /**
     * Default density: 1 point per 5 pixels of Euclidean distance.
     */
    private static final double POINTS_PER_PIXEL = 0.2;

    private final MovementPersona persona;
    private final PathPerturbation perturbation;
    private final Random random;

    /**
     * Creates a TrajectoryGenerator with default perturbation settings.
     *
     * @param persona the movement persona for behavioral parameters
     * @param random  seeded Random for deterministic trajectory generation
     */
    public TrajectoryGenerator(MovementPersona persona, Random random) {
        this(persona, PathPerturbation.defaults(), random);
    }

    /**
     * Creates a TrajectoryGenerator with a custom perturbation configuration.
     *
     * @param persona      the movement persona for behavioral parameters
     * @param perturbation the perturbation strategy for micro-noise
     * @param random       seeded Random for deterministic trajectory generation
     */
    public TrajectoryGenerator(MovementPersona persona, PathPerturbation perturbation, Random random) {
        this.persona = persona;
        this.perturbation = perturbation;
        this.random = random;
    }

    /**
     * Generates a trajectory with automatic point density based on distance.
     *
     * <p>Point count is calculated as {@code max(25, distance * 0.2)}, providing
     * roughly 1 point per 5 pixels with a minimum of {@value MIN_STEPS} points.</p>
     *
     * @param start the starting point
     * @param end   the ending point
     * @return list of points forming the trajectory
     */
    public List<Vector> generate(Vector start, Vector end) {
        double distance = start.distanceTo(end);
        int steps = Math.max(MIN_STEPS, (int) Math.ceil(distance * POINTS_PER_PIXEL));
        return generate(start, end, steps);
    }

    /**
     * Generates a trajectory with the specified number of points.
     *
     * <p>The path is built in three stages:</p>
     * <ol>
     *   <li>Asymmetric Bézier curve (gross shape with persona-influenced curvature)</li>
     *   <li>Micro-perturbations (organic irregularity via damped random walk)</li>
     *   <li>Coordinate clamping (no negative positions)</li>
     * </ol>
     *
     * @param start the starting point
     * @param end   the ending point
     * @param steps number of points to generate (minimum {@value MIN_STEPS})
     * @return list of points forming the trajectory
     */
    public List<Vector> generate(Vector start, Vector end, int steps) {
        steps = Math.max(steps, MIN_STEPS);

        // 1. Build asymmetric Bézier curve influenced by persona
        HumanBehavior.CubicBezier curve = createAsymmetricCurve(start, end);

        // 2. Sample evenly-spaced points along the curve parameter
        List<Vector> basePath = curve.getLookupTable(steps);

        // 3. Apply micro-perturbations
        List<Vector> perturbedPath = perturbation.apply(basePath, random);

        // 4. Clamp to positive coordinates
        List<Vector> result = new ArrayList<>(perturbedPath.size());
        for (Vector point : perturbedPath) {
            result.add(point.clampPositive());
        }
        return result;
    }

    /**
     * Creates a cubic Bézier curve with asymmetric control point placement.
     *
     * <p>The first control point (CP1) is positioned in the first portion of the path
     * (t ~ 0.10–0.35) with stronger perpendicular spread. The second control point (CP2)
     * is near the midpoint (t ~ 0.40–0.65) with weaker spread. Both are placed on the
     * same side, determined by the persona's curvature bias with added per-movement
     * randomness.</p>
     *
     * <p>This produces paths that curve more in the departure phase and straighten toward
     * the target — matching the asymmetric velocity/curvature profiles observed in real
     * human aimed movements.</p>
     */
    private HumanBehavior.CubicBezier createAsymmetricCurve(Vector start, Vector end) {
        double distance = start.distanceTo(end);

        // Degenerate case: start and end are the same (or nearly so)
        if (distance < 1.0) {
            return new HumanBehavior.CubicBezier(start, start, end, end);
        }

        double spread = Vector.clamp(distance, MIN_SPREAD, MAX_SPREAD);

        // Perpendicular direction for control point offsets
        Vector direction = start.directionTo(end);
        Vector perp = direction.perpendicular().unit();

        // Side selection: biased by persona's curvatureBias with per-movement randomness.
        // A persona with curvatureBias = 0.6 will curve right ~75% of the time.
        // Gaussian noise (σ=0.5) ensures the bias is a tendency, not a hard rule.
        double sideRoll = random.nextGaussian() * 0.5 + persona.curvatureBias();
        int side = sideRoll >= 0 ? 1 : -1;

        // Asymmetric placement along the path:
        // CP1 early (t ∈ [0.10, 0.35]) — controls departure curvature
        // CP2 mid-path (t ∈ [0.40, 0.65]) — controls approach curvature
        double t1 = 0.10 + random.nextDouble() * 0.25;
        double t2 = 0.40 + random.nextDouble() * 0.25;

        Vector cp1Base = start.lerp(end, t1);
        Vector cp2Base = start.lerp(end, t2);

        // CP1 gets stronger spread (more curvature in departure phase)
        // CP2 gets weaker spread (path straightens toward target)
        double cp1Offset = spread * (0.5 + random.nextDouble() * 0.4);
        double cp2Offset = spread * (0.2 + random.nextDouble() * 0.3);

        Vector cp1 = cp1Base.add(perp.multiply(side * cp1Offset));
        Vector cp2 = cp2Base.add(perp.multiply(side * cp2Offset));

        return new HumanBehavior.CubicBezier(start, cp1, cp2, end);
    }

    /**
     * The movement persona driving this generator's behavioral parameters.
     *
     * @return the persona
     */
    public MovementPersona persona() {
        return persona;
    }

    @Override
    public String toString() {
        return String.format("TrajectoryGenerator{persona=%s, perturbation=%s}", persona, perturbation);
    }
}
