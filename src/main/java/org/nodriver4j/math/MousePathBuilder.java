package org.nodriver4j.math;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Single orchestrator that composes all movement framework components into final
 * {@code {x, y, deltaMs}} paths ready for dispatch.
 *
 * <p>Given a start position, end position, target width, persona, and optional action
 * intent, it produces a complete movement plan by running this pipeline:</p>
 * <ol>
 *   <li>Compute movement angle → {@link DirectionDynamics} multipliers</li>
 *   <li>Compute base duration via {@link FittsLawTiming}, apply direction and intent
 *       duration multipliers</li>
 *   <li>Run {@link SubMovementPlanner} → ballistic + corrective path with timing</li>
 *   <li>Run {@link ApproachHesitation} → refine the tail end with micro-adjustments</li>
 *   <li>Apply {@link TremorGenerator} → physiological micro-oscillation on every point</li>
 *   <li>Apply global speed multiplier → final divisor on all time deltas</li>
 * </ol>
 *
 * <p>This class also provides methods for click planning (movement + click parameters
 * combined) and scroll event planning. Consumers interact only with MousePathBuilder
 * — the internal component instances are not exposed.</p>
 *
 * <p>All randomness flows through a single seeded {@link Random} from the persona seed,
 * ensuring deterministic output for the same seed.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * MousePathBuilder builder = new MousePathBuilder(persona);
 *
 * // Mouse movement:
 * List<PathPoint> path = builder.planMovement(start, end, targetWidth,
 *     ActionIntent.CASUAL, 1.0);
 *
 * // Click (movement + click params):
 * ClickResult click = builder.planClick(start, targetBox,
 *     ActionIntent.CONFIRM, 1.0);
 *
 * // Scroll:
 * List<ScrollStep> scroll = builder.planScroll(0, 500, 110, 30, 100, 1.0);
 * }</pre>
 *
 * @see MovementPersona
 * @see ActionIntent
 */
public final class MousePathBuilder {

    // ==================== Output Types ====================

    /**
     * A point in the final movement path: position + time delta before dispatch.
     * The first point has {@code deltaMs = 0}.
     */
    public record PathPoint(Vector position, double deltaMs) {}

    /**
     * Complete click plan: the movement path to the target plus all click timing
     * parameters.
     *
     * @param path              timed movement path to the click target
     * @param mousedownPosition where to dispatch mousedown/mouseup (click position + drift)
     * @param hesitationMs      pre-click pause after cursor arrives (speed-scaled)
     * @param holdDurationMs    mousedown-to-mouseup duration (speed-scaled)
     */
    public record ClickResult(
            List<PathPoint> path,
            Vector mousedownPosition,
            double hesitationMs,
            double holdDurationMs
    ) {}

    /**
     * A single scroll tick with scroll amount, cursor offset, and delay.
     *
     * @param scrollDeltaX  horizontal scroll amount (signed pixels)
     * @param scrollDeltaY  vertical scroll amount (signed pixels)
     * @param cursorOffsetX lateral cursor displacement alongside scroll
     * @param cursorOffsetY lateral cursor displacement alongside scroll
     * @param delayMs       time after this tick before the next (speed-scaled)
     */
    public record ScrollStep(
            int scrollDeltaX,
            int scrollDeltaY,
            double cursorOffsetX,
            double cursorOffsetY,
            double delayMs
    ) {}

    // ==================== Internal Components ====================

    private final FittsLawTiming fittsTiming;
    private final SubMovementPlanner subMovementPlanner;
    private final ApproachHesitation approachHesitation;
    private final TremorGenerator tremorGenerator;
    private final ClickBehavior clickBehavior;
    private final ScrollBehavior scrollBehavior;
    private final Random random;

    /**
     * Creates a MousePathBuilder with all components derived from the persona.
     *
     * <p>A single seeded {@link Random} is shared across all components for
     * deterministic output.</p>
     *
     * @param persona the movement persona defining all behavioral parameters
     */
    public MousePathBuilder(MovementPersona persona) {
        this.random = new Random(persona.seed());
        this.fittsTiming = FittsLawTiming.fromPersona(persona);
        TrajectoryGenerator trajectoryGen = new TrajectoryGenerator(persona, random);
        this.subMovementPlanner = new SubMovementPlanner(persona, trajectoryGen, random);
        this.approachHesitation = new ApproachHesitation(persona, random);
        this.tremorGenerator = new TremorGenerator(persona);
        this.clickBehavior = new ClickBehavior(persona, random);
        this.scrollBehavior = new ScrollBehavior(persona, random);
    }

    // ==================== Movement Planning ====================

    /**
     * Plans a complete mouse movement from start to end.
     *
     * <p>Runs the full pipeline: Fitts's Law timing → direction/intent multipliers →
     * sub-movement decomposition → approach hesitation → tremor overlay → speed scaling.</p>
     *
     * @param start           the current cursor position
     * @param end             the target position
     * @param targetWidth     width of the target element in pixels
     * @param intent          the semantic intent of this action
     * @param speedMultiplier global speed multiplier (1.0 = normal, 2.0 = double speed)
     * @return timed path points ready for dispatch
     */
    public List<PathPoint> planMovement(Vector start, Vector end, double targetWidth,
                                        ActionIntent intent, double speedMultiplier) {
        double distance = start.distanceTo(end);
        if (distance < 1.0) {
            return List.of(new PathPoint(end, 0));
        }

        // 1. Direction-dependent multipliers
        double angle = DirectionDynamics.movementAngle(start, end);
        DirectionDynamics.DirectionMultipliers dirMul = DirectionDynamics.multipliers(angle);

        // 2. Fitts's Law duration with direction and intent scaling
        double baseDuration = fittsTiming.duration(distance, targetWidth, random);
        double adjustedDuration = baseDuration
                * dirMul.durationMultiplier()
                * intent.movementDurationMultiplier();

        // 3. Sub-movement planning (ballistic + optional correction)
        List<SubMovementPlanner.TimedPoint> rawPath =
                subMovementPlanner.plan(start, end, targetWidth, adjustedDuration);

        // 4. Approach hesitation (micro-adjustments + near-stop)
        List<SubMovementPlanner.TimedPoint> refinedPath = approachHesitation.refine(rawPath);

        // 5. Tremor overlay on every point
        List<PathPoint> tremoredPath = applyTremor(refinedPath);

        // 6. Global speed multiplier
        return applySpeedMultiplier(tremoredPath, speedMultiplier);
    }

    // ==================== Click Planning ====================

    /**
     * Plans a complete click: mouse movement to target + click parameters.
     *
     * <p>Computes the Gaussian-distributed click position within the target box (with
     * persona bias and velocity-dependent scatter), plans the movement path to that
     * position, and returns combined click timing parameters.</p>
     *
     * @param start           the current cursor position
     * @param targetBox       bounding box of the target element
     * @param intent          the semantic intent of this click
     * @param speedMultiplier global speed multiplier
     * @return complete click plan (path + mousedown position + hesitation + hold)
     */
    public ClickResult planClick(Vector start, BoundingBox targetBox,
                                  ActionIntent intent, double speedMultiplier) {
        double targetWidth = targetBox.getWidth();
        double distance = start.distanceTo(targetBox.getCenter());

        // Estimate approach velocity from Fitts's Law parameters (avoids circular dependency)
        double baseDuration = fittsTiming.duration(distance, targetWidth, random);
        double adjustedDuration = baseDuration * intent.movementDurationMultiplier();
        double estimatedApproachVelocity = distance / (adjustedDuration / 1000.0) * 0.3;

        // Compute click parameters (position with Gaussian scatter + bias + drift)
        ClickBehavior.ClickParams clickParams =
                clickBehavior.compute(targetBox, estimatedApproachVelocity);

        // Plan movement to the click position
        // Note: we use a fresh Fitts duration since duration() was already called above
        // and the random state has advanced. This is intentional — the second call
        // produces the actual movement timing (the first was just for velocity estimation).
        List<PathPoint> path = planMovement(
                start, clickParams.clickPosition(), targetWidth, intent, speedMultiplier);

        // Apply speed multiplier to click timing
        double hesitation = clickParams.hesitationMs() / speedMultiplier;
        double hold = clickParams.holdDurationMs() / speedMultiplier;

        return new ClickResult(path, clickParams.mousedownPosition(), hesitation, hold);
    }

    // ==================== Scroll Planning ====================

    /**
     * Plans a scroll event sequence with momentum, burst-pauses, and cursor drift.
     *
     * @param deltaX          total horizontal scroll in pixels (signed)
     * @param deltaY          total vertical scroll in pixels (signed)
     * @param baseTickPixels  base pixels per scroll tick (from InteractionOptions)
     * @param delayMinMs      minimum inter-tick delay (from InteractionOptions)
     * @param delayMaxMs      maximum inter-tick delay (from InteractionOptions)
     * @param speedMultiplier global speed multiplier
     * @return ordered list of scroll steps to execute
     */
    public List<ScrollStep> planScroll(int deltaX, int deltaY,
                                       int baseTickPixels, int delayMinMs, int delayMaxMs,
                                       double speedMultiplier) {
        List<ScrollBehavior.ScrollTick> rawTicks =
                scrollBehavior.plan(deltaX, deltaY, baseTickPixels, delayMinMs, delayMaxMs);

        List<ScrollStep> result = new ArrayList<>(rawTicks.size());
        for (ScrollBehavior.ScrollTick tick : rawTicks) {
            result.add(new ScrollStep(
                    tick.scrollDeltaX(), tick.scrollDeltaY(),
                    tick.cursorOffsetX(), tick.cursorOffsetY(),
                    tick.delayMs() / speedMultiplier));
        }
        return result;
    }

    // ==================== Tremor Application ====================

    /**
     * Applies physiological tremor to every point in the path.
     *
     * <p>Tremor amplitude is modulated by instantaneous velocity — full tremor at
     * rest, reduced during fast movement. Time is accumulated from the start of
     * the movement for phase continuity within the path.</p>
     */
    private List<PathPoint> applyTremor(List<SubMovementPlanner.TimedPoint> path) {
        List<PathPoint> result = new ArrayList<>(path.size());
        double accumulatedTimeMs = 0;
        Vector previousPos = null;

        for (SubMovementPlanner.TimedPoint point : path) {
            accumulatedTimeMs += point.deltaMs();
            double timeSec = accumulatedTimeMs / 1000.0;

            // Estimate instantaneous velocity (px/s) from this step
            double velocity = 0;
            if (previousPos != null && point.deltaMs() > 0) {
                double dist = previousPos.distanceTo(point.position());
                velocity = dist / point.deltaMs() * 1000.0;
            }

            Vector tremoredPos = tremorGenerator.apply(point.position(), timeSec, velocity);
            result.add(new PathPoint(tremoredPos, point.deltaMs()));
            previousPos = point.position();
        }

        return result;
    }

    // ==================== Speed Multiplier ====================

    /**
     * Applies the global speed multiplier as a divisor on all time deltas.
     * Path shape is unaffected — only timing changes.
     */
    private static List<PathPoint> applySpeedMultiplier(List<PathPoint> path, double speedMultiplier) {
        if (speedMultiplier == 1.0) {
            return path;
        }
        List<PathPoint> result = new ArrayList<>(path.size());
        for (PathPoint point : path) {
            result.add(new PathPoint(point.position(), point.deltaMs() / speedMultiplier));
        }
        return result;
    }
}
