package org.nodriver4j.math;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Static utility class for generating human-like mouse movements, typing patterns,
 * and scroll behavior.
 *
 * <p>This class provides algorithms for:</p>
 * <ul>
 *   <li>Bezier curve path generation for realistic mouse movement</li>
 *   <li>Fitts's Law timing calculations</li>
 *   <li>Overshoot and correction simulation</li>
 *   <li>Human-like keystroke timing</li>
 *   <li>Natural scroll patterns</li>
 * </ul>
 *
 * <p>Ported from ghost-cursor (https://github.com/Xetera/ghost-cursor)</p>
 */
public final class HumanBehavior {

    // ==================== Constants ====================

    /**
     * Minimum spread for Bezier control points.
     */
    private static final double MIN_SPREAD = 2.0;

    /**
     * Maximum spread for Bezier control points.
     */
    private static final double MAX_SPREAD = 200.0;

    /**
     * Default element width for Fitts's Law when not specified.
     */
    private static final double DEFAULT_WIDTH = 100.0;

    /**
     * Minimum number of steps in a mouse path.
     */
    private static final int MIN_STEPS = 25;

    /**
     * Default overshoot radius in pixels.
     */
    public static final double DEFAULT_OVERSHOOT_RADIUS = 120.0;

    /**
     * Default spread for overshoot correction movement.
     */
    public static final double OVERSHOOT_SPREAD = 10.0;

    /**
     * Default threshold distance above which overshoot occurs.
     */
    public static final double DEFAULT_OVERSHOOT_THRESHOLD = 500.0;

    // Private constructor - static utility class
    private HumanBehavior() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ==================== Path Generation ====================

    /**
     * Generates a human-like mouse path between two points.
     *
     * <p>Uses cubic Bezier curves with randomized control points to create
     * natural-looking mouse movement paths.</p>
     *
     * @param start the starting point
     * @param end   the ending point
     * @return list of points along the path
     */
    public static List<Vector> generatePath(Vector start, Vector end) {
        return generatePath(start, end, null, null);
    }

    /**
     * Generates a human-like mouse path between two points.
     *
     * @param start     the starting point
     * @param end       the ending point
     * @param moveSpeed movement speed (1-100), null for random
     * @return list of points along the path
     */
    public static List<Vector> generatePath(Vector start, Vector end, Integer moveSpeed) {
        return generatePath(start, end, moveSpeed, null);
    }

    /**
     * Generates a human-like mouse path between a point and a target box.
     *
     * @param start     the starting point
     * @param targetBox the target bounding box (width used for Fitts's Law)
     * @param moveSpeed movement speed (1-100), null for random
     * @return list of points along the path
     */
    public static List<Vector> generatePath(Vector start, BoundingBox targetBox, Integer moveSpeed) {
        return generatePath(start, targetBox.getCenter(), moveSpeed, targetBox.getWidth());
    }

    /**
     * Generates a human-like mouse path between two points.
     *
     * <p>The path is generated using a cubic Bezier curve with randomized
     * control points. The number of points in the path is determined by
     * Fitts's Law based on distance and target width.</p>
     *
     * @param start          the starting point
     * @param end            the ending point
     * @param moveSpeed      movement speed (1-100), null for random speed
     * @param targetWidth    width of target element for Fitts's Law, null for default
     * @return list of points along the path
     */
    public static List<Vector> generatePath(Vector start, Vector end, Integer moveSpeed, Double targetWidth) {
        return generatePath(start, end, moveSpeed, targetWidth, null);
    }

    /**
     * Generates a human-like mouse path between two points with optional spread override.
     *
     * @param start          the starting point
     * @param end            the ending point
     * @param moveSpeed      movement speed (1-100), null for random speed
     * @param targetWidth    width of target element for Fitts's Law, null for default
     * @param spreadOverride override for Bezier curve spread, null for auto-calculated
     * @return list of points along the path
     */
    public static List<Vector> generatePath(Vector start, Vector end, Integer moveSpeed,
                                            Double targetWidth, Double spreadOverride) {
        double width = (targetWidth != null && targetWidth > 0) ? targetWidth : DEFAULT_WIDTH;

        // Create Bezier curve
        CubicBezier curve = createBezierCurve(start, end, spreadOverride);

        // Calculate effective length (ghost-cursor uses 0.8 multiplier)
        double length = curve.getLength() * 0.8;

        // Calculate speed factor
        // Higher moveSpeed = smaller speed factor = fewer steps = faster movement
        double speed;
        if (moveSpeed != null && moveSpeed > 0) {
            speed = 25.0 / moveSpeed;
        } else {
            speed = ThreadLocalRandom.current().nextDouble();
        }

        // Calculate number of steps using Fitts's Law
        double baseTime = speed * MIN_STEPS;
        double fittsValue = fitts(length, width);
        int steps = (int) Math.ceil((Math.log(fittsValue + 1) / Math.log(2) + baseTime) * 3);
        steps = Math.max(steps, MIN_STEPS);

        // Generate lookup table (evenly spaced points along curve)
        List<Vector> path = curve.getLookupTable(steps);

        // Clamp all points to positive coordinates
        List<Vector> clampedPath = new ArrayList<>(path.size());
        for (Vector point : path) {
            clampedPath.add(point.clampPositive());
        }

        return clampedPath;
    }

    /**
     * Creates a cubic Bezier curve between two points with human-like control points.
     *
     * @param start          the starting point
     * @param end            the ending point
     * @param spreadOverride optional override for spread, null for auto-calculated
     * @return a CubicBezier curve
     */
    public static CubicBezier createBezierCurve(Vector start, Vector end, Double spreadOverride) {
        double distance = start.distanceTo(end);
        double spread = (spreadOverride != null) ? spreadOverride : Vector.clamp(distance, MIN_SPREAD, MAX_SPREAD);

        Vector[] anchors = generateBezierAnchors(start, end, spread);
        return new CubicBezier(start, anchors[0], anchors[1], end);
    }

    /**
     * Generates two control points for a cubic Bezier curve.
     *
     * <p>Both control points are on the same side of the line, creating a
     * smooth curve that bends in one direction (not S-shaped).</p>
     *
     * @param start  the start point
     * @param end    the end point
     * @param spread the maximum perpendicular distance for control points
     * @return array of two control points [cp1, cp2], sorted by x coordinate
     */
    private static Vector[] generateBezierAnchors(Vector start, Vector end, double spread) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Choose which side of the line to place control points
        // Both points go on the same side for a smooth curve
        int side = random.nextBoolean() ? 1 : -1;

        Vector cp1 = calculateControlPoint(start, end, spread, side);
        Vector cp2 = calculateControlPoint(start, end, spread, side);

        // Sort by x coordinate (as in ghost-cursor)
        if (cp1.getX() > cp2.getX()) {
            return new Vector[]{cp2, cp1};
        }
        return new Vector[]{cp1, cp2};
    }

    /**
     * Calculates a single control point for a Bezier curve.
     *
     * @param start  the start point of the line
     * @param end    the end point of the line
     * @param spread the perpendicular spread
     * @param side   which side of the line (-1 or 1)
     * @return a control point
     */
    private static Vector calculateControlPoint(Vector start, Vector end, double spread, int side) {
        // Get random point on the line between start and end
        Vector randomMid = Vector.randomOnLine(start, end);

        // Get direction from start to the random midpoint
        Vector direction = start.directionTo(randomMid);

        // Calculate perpendicular vector with magnitude = spread
        Vector perpendicular;
        if (direction.magnitude() > 0) {
            perpendicular = direction.perpendicular().withMagnitude(spread);
        } else {
            // If start and randomMid are the same point, use direction to end
            direction = start.directionTo(end);
            if (direction.magnitude() > 0) {
                perpendicular = direction.perpendicular().withMagnitude(spread);
            } else {
                // Start and end are the same point
                perpendicular = new Vector(spread, 0);
            }
        }

        // Apply side multiplier
        Vector offset = perpendicular.multiply(side);

        // Return random point on line from midpoint to offset point
        return Vector.randomOnLine(randomMid, randomMid.add(offset));
    }

    // ==================== Fitts's Law ====================

    /**
     * Calculates movement time index using Fitts's Law.
     *
     * <p>Fitts's Law models the time required to move to a target as a function
     * of distance and target width. Larger/closer targets are faster to reach.</p>
     *
     * @param distance the distance to the target
     * @param width    the width of the target
     * @return the index of difficulty
     */
    public static double fitts(double distance, double width) {
        // Fitts's Law: ID = log2(D/W + 1)
        // We use a=0, b=2 as coefficients
        double indexOfDifficulty = Math.log(distance / width + 1) / Math.log(2);
        return 2 * indexOfDifficulty;
    }

    // ==================== Overshoot ====================

    /**
     * Checks if the distance between two points exceeds the overshoot threshold.
     *
     * @param from      the starting point
     * @param to        the target point
     * @param threshold the distance threshold
     * @return true if overshoot should occur
     */
    public static boolean shouldOvershoot(Vector from, Vector to, double threshold) {
        return from.distanceTo(to) > threshold;
    }

    /**
     * Generates an overshoot point near the target.
     *
     * <p>The overshoot point is randomly distributed within a circle around
     * the target, using uniform distribution (sqrt for radius).</p>
     *
     * @param target the target point
     * @param radius the maximum overshoot radius
     * @return an overshoot point near the target
     */
    public static Vector calculateOvershoot(Vector target, double radius) {
        return Vector.overshoot(target, radius);
    }

    // ==================== Timing Calculations ====================

    /**
     * Calculates a random delay within a range.
     *
     * @param minMs minimum delay in milliseconds
     * @param maxMs maximum delay in milliseconds
     * @return random delay in milliseconds
     */
    public static int randomDelay(int minMs, int maxMs) {
        if (minMs >= maxMs) {
            return minMs;
        }
        return ThreadLocalRandom.current().nextInt(minMs, maxMs + 1);
    }

    /**
     * Calculates a delay using Gaussian distribution centered in a range.
     *
     * @param minMs minimum delay in milliseconds
     * @param maxMs maximum delay in milliseconds
     * @return delay in milliseconds, biased toward center
     */
    public static int randomDelayGaussian(int minMs, int maxMs) {
        if (minMs >= maxMs) {
            return minMs;
        }
        double center = (minMs + maxMs) / 2.0;
        double stdDev = (maxMs - minMs) / 4.0; // 95% within range
        double value = ThreadLocalRandom.current().nextGaussian() * stdDev + center;
        return (int) Vector.clamp(value, minMs, maxMs);
    }

    /**
     * Calculates the delay between mouse movement steps for smooth animation.
     *
     * @param pathLength      total number of points in the path
     * @param totalDurationMs desired total movement duration in milliseconds
     * @return delay between steps in milliseconds
     */
    public static int calculateStepDelay(int pathLength, int totalDurationMs) {
        if (pathLength <= 1) {
            return 0;
        }
        return totalDurationMs / (pathLength - 1);
    }

    // ==================== Keystroke Timing ====================

    /**
     * Calculates a human-like delay between keystrokes.
     *
     * @param minMs base minimum delay
     * @param maxMs base maximum delay
     * @return delay in milliseconds
     */
    public static int keystrokeDelay(int minMs, int maxMs) {
        return randomDelayGaussian(minMs, maxMs);
    }

    /**
     * Calculates keystroke delay with character context.
     *
     * <p>Typing speed varies based on character sequences:</p>
     * <ul>
     *   <li>Common bigrams (th, he, in, er) are faster</li>
     *   <li>After punctuation/space, slightly slower</li>
     *   <li>Repeated characters are faster</li>
     * </ul>
     *
     * @param currentChar  the character about to be typed
     * @param previousChar the previously typed character (or null)
     * @param minMs        base minimum delay
     * @param maxMs        base maximum delay
     * @return adjusted delay in milliseconds
     */
    public static int keystrokeDelay(char currentChar, Character previousChar, int minMs, int maxMs) {
        double multiplier = 1.0;

        if (previousChar != null) {
            String bigram = "" + Character.toLowerCase(previousChar) + Character.toLowerCase(currentChar);

            // Common fast bigrams
            if (isCommonBigram(bigram)) {
                multiplier = 0.7; // 30% faster
            }
            // Repeated character
            else if (previousChar == currentChar) {
                multiplier = 0.8; // 20% faster
            }
            // After space or punctuation - slight pause
            else if (previousChar == ' ' || isPunctuation(previousChar)) {
                multiplier = 1.2; // 20% slower
            }
        }

        int adjustedMin = (int) (minMs * multiplier);
        int adjustedMax = (int) (maxMs * multiplier);

        return randomDelayGaussian(adjustedMin, adjustedMax);
    }

    /**
     * Checks if a bigram is commonly typed quickly.
     */
    private static boolean isCommonBigram(String bigram) {
        // Most common English bigrams
        String[] common = {"th", "he", "in", "er", "an", "re", "on", "at", "en", "nd",
                "ti", "es", "or", "te", "of", "ed", "is", "it", "al", "ar",
                "st", "to", "nt", "ng", "se", "ha", "as", "ou", "io", "le"};
        for (String c : common) {
            if (c.equals(bigram)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a character is punctuation.
     */
    private static boolean isPunctuation(char c) {
        return c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':' ||
                c == '\'' || c == '"' || c == '-' || c == '(' || c == ')';
    }

    /**
     * Calculates a "thinking pause" that occasionally occurs while typing.
     *
     * @param probability probability (0-1) that a thinking pause occurs
     * @param minMs       minimum pause duration
     * @param maxMs       maximum pause duration
     * @return pause duration in milliseconds (0 if no pause)
     */
    public static int thinkingPause(double probability, int minMs, int maxMs) {
        if (ThreadLocalRandom.current().nextDouble() < probability) {
            return randomDelay(minMs, maxMs);
        }
        return 0;
    }

    // ==================== Scroll Calculations ====================

    /**
     * Calculates scroll tick amount with human-like variance.
     *
     * @param basePixels base pixels per scroll tick
     * @param variance   maximum variance in pixels
     * @return actual scroll amount for this tick
     */
    public static int scrollTickAmount(int basePixels, int variance) {
        int min = basePixels - variance;
        int max = basePixels + variance;
        return randomDelay(Math.max(1, min), max);
    }

    /**
     * Calculates the number of scroll ticks needed for a distance.
     *
     * @param totalDistance  total distance to scroll
     * @param pixelsPerTick  average pixels per tick
     * @return number of ticks
     */
    public static int scrollTickCount(int totalDistance, int pixelsPerTick) {
        if (pixelsPerTick <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil((double) Math.abs(totalDistance) / pixelsPerTick));
    }

    // ==================== Click Timing ====================

    /**
     * Calculates human-like click hold duration (time between mousedown and mouseup).
     *
     * @param minMs minimum hold duration
     * @param maxMs maximum hold duration
     * @return hold duration in milliseconds
     */
    public static int clickHoldDuration(int minMs, int maxMs) {
        // Slightly biased toward shorter holds
        return randomDelayGaussian(minMs, maxMs);
    }

    /**
     * Calculates hesitation delay before clicking.
     *
     * @param minMs minimum hesitation
     * @param maxMs maximum hesitation
     * @return hesitation duration in milliseconds
     */
    public static int hesitationDelay(int minMs, int maxMs) {
        return randomDelay(minMs, maxMs);
    }

    // ==================== Cubic Bezier Inner Class ====================

    /**
     * Represents a cubic Bezier curve defined by four control points.
     *
     * <p>A cubic Bezier curve is defined by:</p>
     * <pre>B(t) = (1-t)³P0 + 3(1-t)²tP1 + 3(1-t)t²P2 + t³P3</pre>
     * <p>where t ∈ [0,1], P0 is start, P3 is end, P1 and P2 are control points.</p>
     */
    public static class CubicBezier {

        private final Vector p0; // Start point
        private final Vector p1; // Control point 1
        private final Vector p2; // Control point 2
        private final Vector p3; // End point

        /**
         * Creates a cubic Bezier curve.
         *
         * @param p0 start point
         * @param p1 first control point
         * @param p2 second control point
         * @param p3 end point
         */
        public CubicBezier(Vector p0, Vector p1, Vector p2, Vector p3) {
            this.p0 = p0;
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
        }

        /**
         * Calculates the point on the curve at parameter t.
         *
         * @param t parameter value [0, 1]
         * @return the point on the curve
         */
        public Vector getPoint(double t) {
            double u = 1 - t;
            double tt = t * t;
            double uu = u * u;
            double uuu = uu * u;
            double ttt = tt * t;

            // B(t) = (1-t)³P0 + 3(1-t)²tP1 + 3(1-t)t²P2 + t³P3
            double x = uuu * p0.getX() +
                    3 * uu * t * p1.getX() +
                    3 * u * tt * p2.getX() +
                    ttt * p3.getX();

            double y = uuu * p0.getY() +
                    3 * uu * t * p1.getY() +
                    3 * u * tt * p2.getY() +
                    ttt * p3.getY();

            return new Vector(x, y);
        }

        /**
         * Calculates the derivative (velocity) at parameter t.
         *
         * @param t parameter value [0, 1]
         * @return the velocity vector at t
         */
        public Vector getDerivative(double t) {
            double u = 1 - t;

            // B'(t) = 3(1-t)²(P1-P0) + 6(1-t)t(P2-P1) + 3t²(P3-P2)
            double x = 3 * u * u * (p1.getX() - p0.getX()) +
                    6 * u * t * (p2.getX() - p1.getX()) +
                    3 * t * t * (p3.getX() - p2.getX());

            double y = 3 * u * u * (p1.getY() - p0.getY()) +
                    6 * u * t * (p2.getY() - p1.getY()) +
                    3 * t * t * (p3.getY() - p2.getY());

            return new Vector(x, y);
        }

        /**
         * Calculates the speed (magnitude of velocity) at parameter t.
         *
         * @param t parameter value [0, 1]
         * @return the speed at t
         */
        public double getSpeed(double t) {
            return getDerivative(t).magnitude();
        }

        /**
         * Approximates the arc length of the curve.
         *
         * <p>Uses numerical integration with 100 sample points.</p>
         *
         * @return approximate arc length
         */
        public double getLength() {
            return getLength(100);
        }

        /**
         * Approximates the arc length of the curve.
         *
         * @param samples number of samples for approximation
         * @return approximate arc length
         */
        public double getLength(int samples) {
            double length = 0;
            Vector previous = p0;

            for (int i = 1; i <= samples; i++) {
                double t = (double) i / samples;
                Vector current = getPoint(t);
                length += previous.distanceTo(current);
                previous = current;
            }

            return length;
        }

        /**
         * Generates a lookup table of evenly spaced points along the curve.
         *
         * <p>Note: Points are evenly spaced by parameter t, not by arc length.
         * This is consistent with ghost-cursor's behavior.</p>
         *
         * @param steps number of points to generate
         * @return list of points along the curve
         */
        public List<Vector> getLookupTable(int steps) {
            List<Vector> points = new ArrayList<>(steps + 1);

            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                points.add(getPoint(t));
            }

            return points;
        }

        /**
         * Gets the start point.
         *
         * @return the start point (P0)
         */
        public Vector getStart() {
            return p0;
        }

        /**
         * Gets the end point.
         *
         * @return the end point (P3)
         */
        public Vector getEnd() {
            return p3;
        }

        /**
         * Gets the first control point.
         *
         * @return the first control point (P1)
         */
        public Vector getControlPoint1() {
            return p1;
        }

        /**
         * Gets the second control point.
         *
         * @return the second control point (P2)
         */
        public Vector getControlPoint2() {
            return p2;
        }
    }
}