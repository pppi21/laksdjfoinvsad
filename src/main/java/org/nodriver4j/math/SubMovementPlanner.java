package org.nodriver4j.math;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plans point-to-point mouse movements, deciding whether to execute a single smooth
 * movement or decompose into a primary ballistic submovement followed by a corrective
 * submovement.
 *
 * <p>Real human aimed movements consist of a fast initial ballistic phase covering
 * ~75–85% of the distance, followed by 0–2 slower corrective submovements that refine
 * the final position. Movements that always land perfectly on the first attempt are a
 * detection signal. This class models that structure based on Meyer et al. (1988)
 * stochastic optimized submovement theory.</p>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Compute Index of Difficulty: {@code ID = log₂(D/W + 1)}</li>
 *   <li>Decide if correction is needed using a sigmoid probability that ramps with ID,
 *       scaled by the persona's overshoot tendency</li>
 *   <li>If no correction: generate a single trajectory with the persona's velocity profile</li>
 *   <li>If correction needed:
 *     <ul>
 *       <li>Primary submovement to an intentionally imprecise endpoint (~80% of distance,
 *           with Gaussian noise per Meyer et al.)</li>
 *       <li>Brief transition (20–50ms of slow movement)</li>
 *       <li>Corrective submovement from the imprecise endpoint to the actual target</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>The output is always a flat {@code List<TimedPoint>} — the consumer does not need
 * to know whether corrections occurred internally.</p>
 *
 * <p>This class composes {@link TrajectoryGenerator} and {@link VelocityProfile} rather
 * than reimplementing path or timing logic.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * MovementPersona persona = new MovementPersona(seed);
 * Random random = new Random(seed);
 * TrajectoryGenerator gen = new TrajectoryGenerator(persona, random);
 * SubMovementPlanner planner = new SubMovementPlanner(persona, gen, random);
 *
 * List<TimedPoint> movement = planner.plan(cursorPos, targetCenter, targetWidth);
 * for (TimedPoint tp : movement) {
 *     Thread.sleep((long) tp.deltaMs());
 *     dispatchMouseMove(tp.position());
 * }
 * }</pre>
 *
 * @see TrajectoryGenerator
 * @see VelocityProfile
 * @see MovementPersona#overshootProbability()
 */
public final class SubMovementPlanner {

    /**
     * A path point with the time delta (in milliseconds) to wait before dispatching
     * this position. The first point in a movement has {@code deltaMs = 0}.
     */
    public record TimedPoint(Vector position, double deltaMs) {}

    // ==================== Correction Decision Constants ====================

    /**
     * ID threshold below which corrections almost never occur.
     * ~3.5 bits corresponds to moderately difficult targets.
     */
    private static final double ID_THRESHOLD = 3.5;

    /**
     * Steepness of the sigmoid that ramps correction probability with ID.
     */
    private static final double SIGMOID_STEEPNESS = 2.0;

    /**
     * Additive boost to persona's overshoot probability, mapping the persona range
     * [0.1, 0.4] to effective correction range [0.2, 0.5] at max sigmoid.
     */
    private static final double CORRECTION_PROBABILITY_BOOST = 0.1;

    // ==================== Primary Submovement Constants ====================

    /**
     * Mean fraction of total distance covered by the primary submovement.
     */
    private static final double PRIMARY_DISTANCE_MEAN = 0.80;

    /**
     * SD of primary endpoint as a fraction of total distance (5%).
     */
    private static final double PRIMARY_ENDPOINT_NOISE_FRACTION = 0.05;

    /**
     * Perpendicular error is smaller than along-path error.
     */
    private static final double PERPENDICULAR_NOISE_SCALE = 0.5;

    // ==================== Time Allocation Constants ====================

    /**
     * Minimum total duration (ms) required to attempt a corrective submovement.
     */
    private static final double MIN_DURATION_FOR_CORRECTION = 100.0;

    /**
     * Minimum corrective submovement duration (ms).
     */
    private static final double MIN_CORRECTION_DURATION_MS = 30.0;

    /**
     * Minimum distance (px) to attempt any sub-movement decomposition.
     */
    private static final double MIN_DISTANCE_FOR_CORRECTION = 10.0;

    // ==================== Corrective Profile Shape ====================

    /**
     * Corrective submovements use a symmetric velocity profile (deliberate, controlled).
     */
    private static final double CORRECTIVE_PEAK_POSITION = 0.50;

    /**
     * Standard minimum-jerk concentration for corrective movements.
     */
    private static final double CORRECTIVE_CONCENTRATION = 4.0;

    // ==================== Fields ====================

    private final MovementPersona persona;
    private final TrajectoryGenerator trajectoryGen;
    private final VelocityProfile primaryProfile;
    private final VelocityProfile correctiveProfile;
    private final Random random;

    /**
     * Creates a SubMovementPlanner that composes the given trajectory generator and
     * persona-derived velocity profiles.
     *
     * @param persona       the movement persona for behavioral parameters
     * @param trajectoryGen the trajectory generator for spatial paths
     * @param random        seeded Random for deterministic planning
     */
    public SubMovementPlanner(MovementPersona persona, TrajectoryGenerator trajectoryGen, Random random) {
        this.persona = persona;
        this.trajectoryGen = trajectoryGen;
        this.random = random;
        this.primaryProfile = VelocityProfile.fromPersona(persona);
        this.correctiveProfile = new VelocityProfile(CORRECTIVE_PEAK_POSITION, CORRECTIVE_CONCENTRATION);
    }

    // ==================== Public API ====================

    /**
     * Plans a movement from start to target, computing duration from Fitts's Law.
     *
     * @param start       the current cursor position
     * @param target      the target position
     * @param targetWidth width of the target element in pixels (for Fitts's Law and ID)
     * @return timed path points; first point has deltaMs=0
     */
    public List<TimedPoint> plan(Vector start, Vector target, double targetWidth) {
        double distance = start.distanceTo(target);
        double totalDurationMs = VelocityProfile.fittsDuration(
                distance, targetWidth, persona.speedFactor(), random);
        return plan(start, target, targetWidth, totalDurationMs);
    }

    /**
     * Plans a movement from start to target with a caller-provided duration.
     *
     * <p>Use this overload when the total duration is already known or when the caller
     * needs to control timing (e.g., scaled by a speed multiplier).</p>
     *
     * @param start          the current cursor position
     * @param target         the target position
     * @param targetWidth    width of the target element in pixels (for ID calculation)
     * @param totalDurationMs total movement duration in milliseconds
     * @return timed path points; first point has deltaMs=0
     */
    public List<TimedPoint> plan(Vector start, Vector target, double targetWidth, double totalDurationMs) {
        double distance = start.distanceTo(target);

        if (distance >= MIN_DISTANCE_FOR_CORRECTION && totalDurationMs >= MIN_DURATION_FOR_CORRECTION) {
            double id = indexOfDifficulty(distance, targetWidth);
            if (shouldCorrect(id)) {
                List<TimedPoint> result = planWithCorrection(start, target, distance, totalDurationMs);
                if (result != null) {
                    return result;
                }
            }
        }

        return planSingleMovement(start, target, totalDurationMs);
    }

    // ==================== Decision Logic ====================

    /**
     * Index of Difficulty per Fitts's Law: {@code ID = log₂(D/W + 1)}.
     */
    private static double indexOfDifficulty(double distance, double targetWidth) {
        return Math.log(distance / targetWidth + 1.0) / Math.log(2);
    }

    /**
     * Decides if a corrective submovement should occur based on task difficulty
     * and the persona's overshoot tendency.
     *
     * <p>Uses a sigmoid that ramps from ~0 for easy targets (ID &lt; 2) to ~1 for
     * hard targets (ID &gt; 5), scaled by the persona's overshoot probability.</p>
     */
    private boolean shouldCorrect(double id) {
        double sigmoid = 1.0 / (1.0 + Math.exp(-SIGMOID_STEEPNESS * (id - ID_THRESHOLD)));
        double probability = sigmoid * (persona.overshootProbability() + CORRECTION_PROBABILITY_BOOST);
        return random.nextDouble() < probability;
    }

    // ==================== Movement Planning ====================

    /**
     * Plans a single smooth movement with no correction phase.
     */
    private List<TimedPoint> planSingleMovement(Vector start, Vector target, double durationMs) {
        List<Vector> path = trajectoryGen.generate(start, target);
        double[] deltas = primaryProfile.computeDeltas(path.size(), durationMs);
        return assembleTimedPath(path, deltas);
    }

    /**
     * Plans a primary submovement followed by a corrective submovement.
     *
     * @return the combined timed path, or null if there isn't enough time for a correction
     */
    private List<TimedPoint> planWithCorrection(Vector start, Vector target,
                                                double distance, double totalDurationMs) {
        // Time allocation: primary gets 60–70%, transition 20–50ms, correction gets the rest
        double primaryFraction = 0.60 + random.nextDouble() * 0.10;
        double primaryDurationMs = totalDurationMs * primaryFraction;
        double transitionMs = 20 + random.nextDouble() * 30;
        double correctionDurationMs = totalDurationMs - primaryDurationMs - transitionMs;

        if (correctionDurationMs < MIN_CORRECTION_DURATION_MS) {
            return null; // caller falls back to single movement
        }

        // Primary endpoint: intentionally imprecise per Meyer et al.
        Vector primaryEnd = computePrimaryEndpoint(start, target, distance);

        // Generate primary submovement (fast, asymmetric velocity profile)
        List<Vector> primaryPath = trajectoryGen.generate(start, primaryEnd);
        double[] primaryDeltas = primaryProfile.computeDeltas(primaryPath.size(), primaryDurationMs);
        List<TimedPoint> result = new ArrayList<>(assembleTimedPath(primaryPath, primaryDeltas));

        // Generate corrective submovement (slower, symmetric velocity profile)
        List<Vector> correctionPath = trajectoryGen.generate(primaryEnd, target);
        double[] correctionDeltas = correctiveProfile.computeDeltas(correctionPath.size(), correctionDurationMs);

        // Merge: skip first point of correction (duplicates last primary point),
        // add transition delay to the first corrective interval
        for (int i = 1; i < correctionPath.size(); i++) {
            double delta = correctionDeltas[i - 1];
            if (i == 1) {
                delta += transitionMs;
            }
            result.add(new TimedPoint(correctionPath.get(i), delta));
        }

        return result;
    }

    /**
     * Computes the primary submovement's endpoint with intentional Gaussian error.
     *
     * <p>The endpoint falls at approximately 80% of the distance along the movement
     * direction, with noise SD = 5% of distance (per Meyer et al. stochastic optimized
     * submovement model). A smaller perpendicular error is also added.</p>
     */
    private Vector computePrimaryEndpoint(Vector start, Vector target, double distance) {
        double noiseSd = distance * PRIMARY_ENDPOINT_NOISE_FRACTION;

        // Along-path position: Normal(0.80, 0.05) of total distance
        double fraction = PRIMARY_DISTANCE_MEAN + random.nextGaussian() * PRIMARY_ENDPOINT_NOISE_FRACTION;
        fraction = Vector.clamp(fraction, 0.65, 0.95);

        Vector endpoint = start.lerp(target, fraction);

        // Perpendicular error: smaller component, simulates lateral hand imprecision
        Vector direction = start.directionTo(target);
        if (direction.magnitude() > 1.0) {
            Vector perp = direction.perpendicular().unit();
            double perpError = random.nextGaussian() * noiseSd * PERPENDICULAR_NOISE_SCALE;
            endpoint = endpoint.add(perp.multiply(perpError));
        }

        return endpoint.clampPositive();
    }

    // ==================== Assembly ====================

    /**
     * Converts a spatial path and time deltas into a list of timed points.
     * The first point gets deltaMs = 0 (starting position).
     */
    private static List<TimedPoint> assembleTimedPath(List<Vector> path, double[] deltas) {
        List<TimedPoint> result = new ArrayList<>(path.size());
        result.add(new TimedPoint(path.getFirst(), 0));
        for (int i = 1; i < path.size(); i++) {
            result.add(new TimedPoint(path.get(i), deltas[i - 1]));
        }
        return result;
    }

    @Override
    public String toString() {
        return String.format("SubMovementPlanner{persona=%s}", persona);
    }
}
