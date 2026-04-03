package org.nodriver4j.math;

/**
 * Provides direction-dependent multipliers for movement duration, path curvature,
 * and correction probability based on the biomechanics of mouse pointing.
 *
 * <p>Leftward movements are faster with less curvature (elbow-dominant single-joint
 * coordination), while upward movements are slower with more curvature and higher
 * correction probability. These anisotropies are well-documented in motor control
 * literature (PMC3304147) and their absence is a detection signal.</p>
 *
 * <p>The multiplier table uses 8 compass directions as anchor points. For any movement
 * angle, multipliers are smoothly interpolated between adjacent anchors — there are
 * no discontinuities or snapping to discrete directions.</p>
 *
 * <p>This class is stateless. The biomechanical basis is universal (not persona-specific),
 * but an optional {@code anisotropy} parameter scales how pronounced the direction
 * effects are (some users show more anisotropy than others).</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * double angle = DirectionDynamics.movementAngle(start, end);
 * DirectionMultipliers m = DirectionDynamics.multipliers(angle);
 *
 * double adjustedDuration = baseDuration * m.durationMultiplier();
 * double adjustedCurvature = baseCurvature * m.curvatureMultiplier();
 * double adjustedCorrectionProb = baseProb * m.correctionMultiplier();
 * }</pre>
 *
 * @see FittsLawTiming
 * @see SubMovementPlanner
 * @see TrajectoryGenerator
 */
public final class DirectionDynamics {

    /**
     * Interpolated multipliers for a specific movement direction.
     *
     * @param durationMultiplier   scale factor for movement duration
     *                             (&lt;1.0 = faster, &gt;1.0 = slower)
     * @param curvatureMultiplier  scale factor for path curvature magnitude
     *                             (&lt;1.0 = straighter, &gt;1.0 = more curved)
     * @param correctionMultiplier scale factor for sub-movement correction probability
     *                             (&lt;1.0 = fewer corrections, &gt;1.0 = more)
     */
    public record DirectionMultipliers(
            double durationMultiplier,
            double curvatureMultiplier,
            double correctionMultiplier
    ) {}

    // ==================== Compass Direction Tables ====================
    // Indexed 0–7: Right, Down-Right, Down, Down-Left, Left, Up-Left, Up, Up-Right
    // Angles: 0°, 45°, 90°, 135°, 180°, 225°, 270°, 315°
    // Based on PMC3304147 and the plan's starting estimates.

    private static final int NUM_DIRECTIONS = 8;
    private static final double SECTOR_SIZE_DEG = 360.0 / NUM_DIRECTIONS;

    /**
     * Duration multipliers. Leftward (180°) is fastest (0.90×), upward (270°) is
     * slowest (1.15×).
     */
    private static final double[] DURATION = {
            1.00,  // Right     (0°)
            1.05,  // Down-right (45°)
            1.10,  // Down      (90°)
            1.00,  // Down-left (135°)
            0.90,  // Left      (180°)
            1.05,  // Up-left   (225°)
            1.15,  // Up        (270°)
            1.10   // Up-right  (315°)
    };

    /**
     * Curvature multipliers. Leftward (180°) is straightest (0.85×), upward (270°)
     * is most curved (1.20×).
     */
    private static final double[] CURVATURE = {
            1.00,  // Right
            0.90,  // Down-right
            1.10,  // Down
            1.00,  // Down-left
            0.85,  // Left
            1.00,  // Up-left
            1.20,  // Up
            1.05   // Up-right
    };

    /**
     * Correction multipliers. Derived from duration: slower directions have more
     * corrections. Formula: {@code 1 + (duration - 1) × 1.5}.
     */
    private static final double[] CORRECTION;

    static {
        CORRECTION = new double[NUM_DIRECTIONS];
        for (int i = 0; i < NUM_DIRECTIONS; i++) {
            CORRECTION[i] = 1.0 + (DURATION[i] - 1.0) * 1.5;
        }
    }

    private DirectionDynamics() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ==================== Public API ====================

    /**
     * Returns direction-dependent multipliers for the given movement angle,
     * smoothly interpolated between the 8 compass anchor directions.
     *
     * @param angleRadians movement angle from {@code Math.atan2(dy, dx)}, using
     *                     screen coordinates (positive Y downward)
     * @return interpolated multipliers for duration, curvature, and correction probability
     */
    public static DirectionMultipliers multipliers(double angleRadians) {
        return multipliers(angleRadians, 1.0);
    }

    /**
     * Returns direction-dependent multipliers with adjustable anisotropy strength.
     *
     * <p>At {@code anisotropy = 1.0}, full table values apply. At {@code 0.0}, all
     * multipliers are 1.0 (direction has no effect). Intermediate values scale the
     * deviation from 1.0 linearly.</p>
     *
     * @param angleRadians movement angle from {@code Math.atan2(dy, dx)}
     * @param anisotropy   strength of direction effects (0.0–1.0)
     * @return interpolated and scaled multipliers
     */
    public static DirectionMultipliers multipliers(double angleRadians, double anisotropy) {
        anisotropy = Vector.clamp(anisotropy, 0.0, 1.0);

        double degrees = Math.toDegrees(angleRadians);
        degrees = ((degrees % 360) + 360) % 360; // normalize to [0, 360)

        // Find sector and interpolation fraction
        int sector = (int) (degrees / SECTOR_SIZE_DEG);
        sector = Math.min(sector, NUM_DIRECTIONS - 1); // guard for degrees == 360
        double fraction = (degrees - sector * SECTOR_SIZE_DEG) / SECTOR_SIZE_DEG;

        int next = (sector + 1) % NUM_DIRECTIONS;

        double duration = lerp(DURATION[sector], DURATION[next], fraction);
        double curvature = lerp(CURVATURE[sector], CURVATURE[next], fraction);
        double correction = lerp(CORRECTION[sector], CORRECTION[next], fraction);

        // Apply anisotropy scaling: blend toward 1.0 as anisotropy decreases
        duration = 1.0 + (duration - 1.0) * anisotropy;
        curvature = 1.0 + (curvature - 1.0) * anisotropy;
        correction = 1.0 + (correction - 1.0) * anisotropy;

        return new DirectionMultipliers(duration, curvature, correction);
    }

    /**
     * Computes the movement angle in radians from start to end, using screen
     * coordinates (positive Y downward).
     *
     * <p>Returns 0 for rightward, π/2 for downward, π for leftward, -π/2 for upward.
     * Result range is [-π, π].</p>
     *
     * @param start the starting point
     * @param end   the ending point
     * @return the movement angle in radians
     */
    public static double movementAngle(Vector start, Vector end) {
        return Math.atan2(end.getY() - start.getY(), end.getX() - start.getX());
    }

    // ==================== Internals ====================

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
