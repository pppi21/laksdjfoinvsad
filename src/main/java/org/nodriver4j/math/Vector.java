package org.nodriver4j.math;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Represents a 2D vector/point with x and y coordinates.
 * Provides vector math operations for mouse movement calculations.
 *
 * <p>This class is immutable - all operations return new Vector instances.</p>
 *
 * <p>Ported from ghost-cursor's math.ts implementation.</p>
 */
public final class Vector {

    /**
     * The origin point (0, 0).
     */
    public static final Vector ORIGIN = new Vector(0, 0);

    private final double x;
    private final double y;

    /**
     * Creates a new Vector with the specified coordinates.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     */
    public Vector(double x, double y) {
        this.x = x;
        this.y = y;
    }

    /**
     * Creates a new Vector from integer coordinates.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @return a new Vector
     */
    public static Vector of(int x, int y) {
        return new Vector(x, y);
    }

    /**
     * Creates a new Vector from double coordinates.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @return a new Vector
     */
    public static Vector of(double x, double y) {
        return new Vector(x, y);
    }

    // ==================== Basic Getters ====================

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    /**
     * Returns the x coordinate as an integer (rounded).
     *
     * @return x coordinate rounded to nearest integer
     */
    public int getXInt() {
        return (int) Math.round(x);
    }

    /**
     * Returns the y coordinate as an integer (rounded).
     *
     * @return y coordinate rounded to nearest integer
     */
    public int getYInt() {
        return (int) Math.round(y);
    }

    // ==================== Vector Operations ====================

    /**
     * Adds another vector to this vector.
     *
     * @param other the vector to add
     * @return a new Vector representing the sum
     */
    public Vector add(Vector other) {
        return new Vector(this.x + other.x, this.y + other.y);
    }

    /**
     * Subtracts another vector from this vector.
     *
     * @param other the vector to subtract
     * @return a new Vector representing the difference
     */
    public Vector subtract(Vector other) {
        return new Vector(this.x - other.x, this.y - other.y);
    }

    /**
     * Multiplies this vector by a scalar.
     *
     * @param scalar the scalar to multiply by
     * @return a new Vector scaled by the scalar
     */
    public Vector multiply(double scalar) {
        return new Vector(this.x * scalar, this.y * scalar);
    }

    /**
     * Divides this vector by a scalar.
     *
     * @param scalar the scalar to divide by
     * @return a new Vector divided by the scalar
     * @throws ArithmeticException if scalar is zero
     */
    public Vector divide(double scalar) {
        if (scalar == 0) {
            throw new ArithmeticException("Cannot divide vector by zero");
        }
        return new Vector(this.x / scalar, this.y / scalar);
    }

    /**
     * Calculates the magnitude (length) of this vector.
     *
     * @return the magnitude of the vector
     */
    public double magnitude() {
        return Math.sqrt(x * x + y * y);
    }

    /**
     * Returns the unit vector (normalized) of this vector.
     * A unit vector has magnitude 1 and points in the same direction.
     *
     * @return a new Vector with magnitude 1
     * @throws ArithmeticException if this vector has zero magnitude
     */
    public Vector unit() {
        double mag = magnitude();
        if (mag == 0) {
            throw new ArithmeticException("Cannot normalize zero vector");
        }
        return divide(mag);
    }

    /**
     * Returns a vector perpendicular to this vector.
     * The perpendicular is rotated 90 degrees clockwise.
     *
     * @return a new Vector perpendicular to this one
     */
    public Vector perpendicular() {
        return new Vector(y, -x);
    }

    /**
     * Returns a vector with the same direction but specified magnitude.
     *
     * @param newMagnitude the desired magnitude
     * @return a new Vector with the specified magnitude
     */
    public Vector withMagnitude(double newMagnitude) {
        return unit().multiply(newMagnitude);
    }

    /**
     * Calculates the direction vector from this point to another point.
     *
     * @param target the target point
     * @return a new Vector representing the direction
     */
    public Vector directionTo(Vector target) {
        return target.subtract(this);
    }

    /**
     * Calculates the distance from this point to another point.
     *
     * @param other the other point
     * @return the Euclidean distance
     */
    public double distanceTo(Vector other) {
        return directionTo(other).magnitude();
    }

    /**
     * Extrapolates a point beyond this vector, continuing the line from another point.
     * Given points A and B, returns C such that B is the midpoint of A and C.
     *
     * @param from the starting point of the line
     * @return a new Vector extrapolated beyond this point
     */
    public Vector extrapolateFrom(Vector from) {
        Vector direction = from.directionTo(this);
        return this.add(direction);
    }

    /**
     * Linearly interpolates between this vector and another.
     *
     * @param target the target vector
     * @param t      interpolation factor (0 = this, 1 = target)
     * @return a new Vector interpolated between this and target
     */
    public Vector lerp(Vector target, double t) {
        return new Vector(
                this.x + (target.x - this.x) * t,
                this.y + (target.y - this.y) * t
        );
    }

    // ==================== Static Utility Methods ====================

    /**
     * Generates a random number within a range.
     *
     * @param min the minimum value (inclusive)
     * @param max the maximum value (exclusive)
     * @return a random double in the range [min, max)
     */
    public static double randomInRange(double min, double max) {
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    /**
     * Generates a random integer within a range.
     *
     * @param min the minimum value (inclusive)
     * @param max the maximum value (inclusive)
     * @return a random integer in the range [min, max]
     */
    public static int randomIntInRange(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * Generates a random point on the line segment between two points.
     *
     * @param start the start point of the line
     * @param end   the end point of the line
     * @return a random Vector on the line segment
     */
    public static Vector randomOnLine(Vector start, Vector end) {
        double t = ThreadLocalRandom.current().nextDouble();
        return start.lerp(end, t);
    }

    /**
     * Clamps a value between a minimum and maximum.
     *
     * @param value the value to clamp
     * @param min   the minimum value
     * @param max   the maximum value
     * @return the clamped value
     */
    public static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    /**
     * Clamps an integer value between a minimum and maximum.
     *
     * @param value the value to clamp
     * @param min   the minimum value
     * @param max   the maximum value
     * @return the clamped value
     */
    public static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    /**
     * Maps a value from one range to another (linear interpolation).
     * For example, scale(50, 0, 100, 0, 1) returns 0.5
     *
     * @param value     the value to map
     * @param fromMin   the minimum of the source range
     * @param fromMax   the maximum of the source range
     * @param toMin     the minimum of the target range
     * @param toMax     the maximum of the target range
     * @return the value mapped to the target range
     */
    public static double scale(double value, double fromMin, double fromMax, double toMin, double toMax) {
        return (value - fromMin) * (toMax - toMin) / (fromMax - fromMin) + toMin;
    }

    /**
     * Generates an overshoot point near a target coordinate.
     * Creates a random point within a circle around the target.
     *
     * @param target the target point to overshoot
     * @param radius the maximum overshoot radius
     * @return a new Vector representing the overshoot point
     */
    public static Vector overshoot(Vector target, double radius) {
        double angle = ThreadLocalRandom.current().nextDouble() * 2 * Math.PI;
        double distance = radius * Math.sqrt(ThreadLocalRandom.current().nextDouble());
        double offsetX = distance * Math.cos(angle);
        double offsetY = distance * Math.sin(angle);
        return target.add(new Vector(offsetX, offsetY));
    }

    /**
     * Generates a random point with a normal line from a line segment.
     * Used for generating Bezier curve control points.
     *
     * @param start the start point
     * @param end   the end point
     * @param range the perpendicular offset range
     * @return an array containing [randomMidpoint, normalVector]
     */
    public static Vector[] randomNormalLine(Vector start, Vector end, double range) {
        Vector randomMid = randomOnLine(start, end);
        Vector direction = start.directionTo(randomMid);
        Vector normalVector = direction.perpendicular();

        // Normalize and scale to range
        if (normalVector.magnitude() > 0) {
            normalVector = normalVector.withMagnitude(range);
        }

        return new Vector[]{randomMid, normalVector};
    }

    /**
     * Adds Gaussian noise (jitter) to this vector.
     *
     * @param maxDeviation the maximum deviation in pixels
     * @return a new Vector with added noise
     */
    public Vector addJitter(double maxDeviation) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double noiseX = random.nextGaussian() * (maxDeviation / 2);
        double noiseY = random.nextGaussian() * (maxDeviation / 2);
        return new Vector(
                x + clamp(noiseX, -maxDeviation, maxDeviation),
                y + clamp(noiseY, -maxDeviation, maxDeviation)
        );
    }

    /**
     * Ensures both coordinates are non-negative.
     *
     * @return a new Vector with coordinates clamped to >= 0
     */
    public Vector clampPositive() {
        return new Vector(Math.max(0, x), Math.max(0, y));
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Vector vector = (Vector) o;
        return Double.compare(vector.x, x) == 0 && Double.compare(vector.y, y) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }

    @Override
    public String toString() {
        return String.format("Vector(%.2f, %.2f)", x, y);
    }
}