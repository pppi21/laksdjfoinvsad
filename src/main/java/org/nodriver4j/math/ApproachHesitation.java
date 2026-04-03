package org.nodriver4j.math;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Refines the final portion of a mouse movement to add the micro-adjustments and
 * brief hesitation that occur as a cursor approaches its target under visual guidance.
 *
 * <p>Real human aimed movements don't end with a smooth monotonic glide into the target.
 * In the last ~15% of the path (or last ~30 pixels), the cursor exhibits:</p>
 * <ul>
 *   <li><b>Perpendicular micro-adjustments</b> — 2–4 small direction changes (1–3 pixels)
 *       as visual feedback drives corrections</li>
 *   <li><b>Progressive deceleration</b> — complementing the velocity profile's built-in
 *       slowdown with additional approach-specific braking</li>
 *   <li><b>Brief near-stop</b> — 5–15ms of near-zero velocity in the last ~7 pixels,
 *       just before the click point</li>
 * </ul>
 *
 * <p>Inspired by the WindMouse near-target regime (EventNazi.java), where wind force
 * decays, step sizes shrink, and the approach has controlled perturbation rather than
 * perfect smoothness. This class adapts that concept for post-hoc path refinement.</p>
 *
 * <p>This class is composable — it takes the output of {@link SubMovementPlanner} and
 * returns a refined version. It modifies only the approach zone; all earlier points
 * pass through unchanged. The final point is always preserved exactly at the target.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * SubMovementPlanner planner = new SubMovementPlanner(persona, gen, random);
 * ApproachHesitation approach = new ApproachHesitation(persona, random);
 *
 * List<TimedPoint> raw = planner.plan(start, target, targetWidth);
 * List<TimedPoint> refined = approach.refine(raw);
 * }</pre>
 *
 * @see SubMovementPlanner
 */
public final class ApproachHesitation {

    /**
     * Salt for deriving per-persona approach parameters from the seed,
     * avoiding correlation with other persona-derived random sequences.
     */
    private static final long SEED_SALT = 0x415050524F4143L;

    /**
     * Fraction of total path length that constitutes the approach zone.
     */
    private static final double APPROACH_FRACTION = 0.15;

    /**
     * Minimum approach zone length in pixels (used when 15% of path is shorter).
     */
    private static final double APPROACH_MIN_PX = 30.0;

    /**
     * Distance from target (pixels) at which the near-stop is inserted.
     */
    private static final double NEAR_STOP_DISTANCE_PX = 7.0;

    /**
     * Maximum additional deceleration applied to approach zone deltas (fraction).
     * Applied quadratically — most effect near the target.
     */
    private static final double DECELERATION_BOOST = 0.15;

    /**
     * Minimum path points required for approach refinement to be worthwhile.
     */
    private static final int MIN_PATH_SIZE = 10;

    // ==================== Per-Persona Approach Parameters ====================

    private final int numAdjustments;
    private final double adjustmentAmplitude;
    private final double nearStopMs;
    private final Random random;

    /**
     * Creates an ApproachHesitation with approach parameters derived from the persona.
     *
     * @param persona the movement persona (seed determines per-profile approach style)
     * @param random  seeded Random for per-call variation
     */
    public ApproachHesitation(MovementPersona persona, Random random) {
        this.random = random;

        Random rng = new Random(persona.seed() ^ SEED_SALT);
        this.numAdjustments = 2 + rng.nextInt(3);               // 2, 3, or 4
        this.adjustmentAmplitude = 1.0 + rng.nextDouble() * 2.0; // 1.0–3.0 px
        this.nearStopMs = 5.0 + rng.nextDouble() * 10.0;         // 5–15 ms
    }

    // ==================== Public API ====================

    /**
     * Refines the approach zone (tail end) of a timed path.
     *
     * <p>Points before the approach zone are returned unchanged. Points within the
     * approach zone receive perpendicular micro-adjustments, gentle additional
     * deceleration, and a brief near-stop. The final point is preserved exactly
     * at its original position (the target).</p>
     *
     * <p>For paths shorter than {@value MIN_PATH_SIZE} points, the original path
     * is returned unmodified.</p>
     *
     * @param path the timed path from {@link SubMovementPlanner}
     * @return a refined path with approach hesitation applied
     */
    public List<SubMovementPlanner.TimedPoint> refine(List<SubMovementPlanner.TimedPoint> path) {
        if (path.size() < MIN_PATH_SIZE) {
            return new ArrayList<>(path);
        }

        Vector target = path.getLast().position();
        int approachIndex = findApproachStart(path, target);

        // Need at least 3 approach zone points to refine meaningfully
        if (approachIndex >= path.size() - 2) {
            return new ArrayList<>(path);
        }

        List<SubMovementPlanner.TimedPoint> result = new ArrayList<>(path.size());

        // Pre-approach: pass through unchanged
        for (int i = 0; i < approachIndex; i++) {
            result.add(path.get(i));
        }

        // Approach zone: refine
        result.addAll(refineApproachZone(path, approachIndex, target));

        return result;
    }

    // ==================== Approach Zone Detection ====================

    /**
     * Finds the index where the approach zone begins by walking backward from the
     * target until the accumulated path distance reaches the approach threshold.
     */
    private static int findApproachStart(List<SubMovementPlanner.TimedPoint> path, Vector target) {
        double totalLength = 0;
        for (int i = 1; i < path.size(); i++) {
            totalLength += path.get(i - 1).position().distanceTo(path.get(i).position());
        }

        double approachLength = Math.max(APPROACH_MIN_PX, totalLength * APPROACH_FRACTION);

        double accumulated = 0;
        for (int i = path.size() - 1; i > 0; i--) {
            accumulated += path.get(i).position().distanceTo(path.get(i - 1).position());
            if (accumulated >= approachLength) {
                return i;
            }
        }
        return 1;
    }

    // ==================== Approach Zone Refinement ====================

    /**
     * Generates refined approach zone points with micro-adjustments, additional
     * deceleration, and a near-stop.
     *
     * <p>Micro-adjustments use a sinusoidal perpendicular offset that produces
     * {@code numAdjustments} direction changes (zero-crossings) over the approach zone.
     * The amplitude fades linearly to zero at the target, so offsets are largest at
     * the zone entry and vanish before the final point.</p>
     *
     * <p>Inspired by WindMouse's near-target regime: decaying perturbation + shrinking
     * steps, not a perfectly smooth slide.</p>
     */
    private List<SubMovementPlanner.TimedPoint> refineApproachZone(
            List<SubMovementPlanner.TimedPoint> fullPath, int approachIndex, Vector target) {

        int zoneSize = fullPath.size() - approachIndex;

        // Approach direction: from zone entry to target
        Vector entry = fullPath.get(approachIndex).position();
        Vector direction = entry.directionTo(target);
        Vector perp = (direction.magnitude() > 1.0)
                ? direction.perpendicular().unit()
                : new Vector(1, 0);

        // Sinusoidal frequency: numAdjustments half-cycles over the zone
        double freq = numAdjustments / 2.0;

        // Per-call phase offset so each movement has unique adjustment timing
        double phaseOffset = random.nextDouble() * Math.PI;

        List<SubMovementPlanner.TimedPoint> result = new ArrayList<>(zoneSize);
        boolean nearStopInserted = false;

        for (int z = 0; z < zoneSize; z++) {
            int pathIndex = approachIndex + z;
            SubMovementPlanner.TimedPoint original = fullPath.get(pathIndex);

            // Fraction through the approach zone: 0 at entry, 1 at target
            double fraction = (double) z / (zoneSize - 1);

            // Last point: exact target, no modification
            if (z == zoneSize - 1) {
                result.add(original);
                break;
            }

            // --- Spatial: perpendicular micro-adjustments ---

            // Linear fadeout: full amplitude at entry, zero at target
            double fadeout = 1.0 - fraction;

            // Sinusoidal wave producing direction changes
            double wave = Math.sin(2 * Math.PI * freq * fraction + phaseOffset);

            // Tiny per-point jitter so the wave isn't mechanically perfect
            double jitter = random.nextGaussian() * 0.15 * fadeout;

            double perpOffset = adjustmentAmplitude * wave * fadeout + jitter;
            Vector adjustedPos = original.position().add(perp.multiply(perpOffset));

            // --- Temporal: gentle additional deceleration ---

            // Quadratic boost: minimal at zone entry, up to DECELERATION_BOOST at end
            double timeScale = 1.0 + DECELERATION_BOOST * fraction * fraction;
            double adjustedDelta = original.deltaMs() * timeScale;

            // --- Temporal: near-stop insertion ---

            // Insert once, at the first point within NEAR_STOP_DISTANCE_PX of target
            if (!nearStopInserted) {
                double distToTarget = original.position().distanceTo(target);
                if (distToTarget <= NEAR_STOP_DISTANCE_PX) {
                    adjustedDelta += nearStopMs;
                    nearStopInserted = true;
                }
            }

            result.add(new SubMovementPlanner.TimedPoint(adjustedPos, adjustedDelta));
        }

        return result;
    }

    @Override
    public String toString() {
        return String.format("ApproachHesitation{adjustments=%d, amplitude=%.1f px, nearStop=%.0f ms}",
                numAdjustments, adjustmentAmplitude, nearStopMs);
    }
}
