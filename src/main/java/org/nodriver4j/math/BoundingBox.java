package org.nodriver4j.math;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Represents a bounding box (rectangle) with position and dimensions.
 * Used for element bounds and click target calculations.
 *
 * <p>This class is immutable - all operations return new instances.</p>
 *
 * <p>Coordinate system follows browser conventions:</p>
 * <ul>
 *   <li>Origin (0,0) is at top-left</li>
 *   <li>X increases rightward</li>
 *   <li>Y increases downward</li>
 * </ul>
 */
public final class BoundingBox {

    private final double x;
    private final double y;
    private final double width;
    private final double height;

    /**
     * Creates a new BoundingBox with the specified position and dimensions.
     *
     * @param x      the x coordinate of the top-left corner
     * @param y      the y coordinate of the top-left corner
     * @param width  the width of the box
     * @param height the height of the box
     */
    public BoundingBox(double x, double y, double width, double height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /**
     * Creates a BoundingBox from integer values.
     *
     * @param x      the x coordinate
     * @param y      the y coordinate
     * @param width  the width
     * @param height the height
     * @return a new BoundingBox
     */
    public static BoundingBox of(int x, int y, int width, int height) {
        return new BoundingBox(x, y, width, height);
    }

    /**
     * Creates a BoundingBox from double values.
     *
     * @param x      the x coordinate
     * @param y      the y coordinate
     * @param width  the width
     * @param height the height
     * @return a new BoundingBox
     */
    public static BoundingBox of(double x, double y, double width, double height) {
        return new BoundingBox(x, y, width, height);
    }

    /**
     * Creates a BoundingBox from a top-left Vector and dimensions.
     *
     * @param topLeft the top-left corner position
     * @param width   the width
     * @param height  the height
     * @return a new BoundingBox
     */
    public static BoundingBox fromVector(Vector topLeft, double width, double height) {
        return new BoundingBox(topLeft.getX(), topLeft.getY(), width, height);
    }

    // ==================== Position Getters ====================

    /**
     * Gets the x coordinate of the top-left corner.
     *
     * @return the x coordinate
     */
    public double getX() {
        return x;
    }

    /**
     * Gets the y coordinate of the top-left corner.
     *
     * @return the y coordinate
     */
    public double getY() {
        return y;
    }

    /**
     * Gets the width of the box.
     *
     * @return the width
     */
    public double getWidth() {
        return width;
    }

    /**
     * Gets the height of the box.
     *
     * @return the height
     */
    public double getHeight() {
        return height;
    }

    // ==================== Edge Getters ====================

    /**
     * Gets the y coordinate of the top edge.
     *
     * @return the top edge y coordinate
     */
    public double getTop() {
        return y;
    }

    /**
     * Gets the x coordinate of the left edge.
     *
     * @return the left edge x coordinate
     */
    public double getLeft() {
        return x;
    }

    /**
     * Gets the y coordinate of the bottom edge.
     *
     * @return the bottom edge y coordinate
     */
    public double getBottom() {
        return y + height;
    }

    /**
     * Gets the x coordinate of the right edge.
     *
     * @return the right edge x coordinate
     */
    public double getRight() {
        return x + width;
    }

    // ==================== Point Calculations ====================

    /**
     * Gets the top-left corner as a Vector.
     *
     * @return the top-left corner position
     */
    public Vector getTopLeft() {
        return new Vector(x, y);
    }

    /**
     * Gets the top-right corner as a Vector.
     *
     * @return the top-right corner position
     */
    public Vector getTopRight() {
        return new Vector(x + width, y);
    }

    /**
     * Gets the bottom-left corner as a Vector.
     *
     * @return the bottom-left corner position
     */
    public Vector getBottomLeft() {
        return new Vector(x, y + height);
    }

    /**
     * Gets the bottom-right corner as a Vector.
     *
     * @return the bottom-right corner position
     */
    public Vector getBottomRight() {
        return new Vector(x + width, y + height);
    }

    /**
     * Calculates the center point of the box.
     *
     * @return the center point as a Vector
     */
    public Vector getCenter() {
        return new Vector(x + width / 2, y + height / 2);
    }

    // ==================== Random Point Generation ====================

    /**
     * Generates a random point within this bounding box.
     *
     * @return a random Vector inside the box
     */
    public Vector getRandomPoint() {
        return getRandomPoint(0);
    }

    /**
     * Generates a random point within this bounding box with padding.
     *
     * <p>The padding percentage creates an inner region where the point will be placed:</p>
     * <ul>
     *   <li>0% = point may be anywhere within the element</li>
     *   <li>50% = point will be within the center 50% of the element</li>
     *   <li>100% = point will always be exactly at center</li>
     * </ul>
     *
     * <p>This mimics human clicking behavior, which tends toward the center of elements.</p>
     *
     * @param paddingPercentage the percentage of padding (0-100)
     * @return a random Vector inside the padded region
     */
    public Vector getRandomPoint(double paddingPercentage) {
        double paddingWidth = 0;
        double paddingHeight = 0;

        if (paddingPercentage > 0 && paddingPercentage <= 100) {
            paddingWidth = (width * paddingPercentage) / 100;
            paddingHeight = (height * paddingPercentage) / 100;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();

        double randomX = x + paddingWidth / 2 + random.nextDouble() * (width - paddingWidth);
        double randomY = y + paddingHeight / 2 + random.nextDouble() * (height - paddingHeight);

        return new Vector(randomX, randomY);
    }

    /**
     * Generates a random point with an offset from center, following a normal distribution.
     * Points are more likely to be near the center, mimicking natural human click patterns.
     *
     * @param maxOffsetX maximum horizontal offset from center
     * @param maxOffsetY maximum vertical offset from center
     * @return a random Vector biased toward the center
     */
    public Vector getRandomPointNearCenter(double maxOffsetX, double maxOffsetY) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vector center = getCenter();

        // Use Gaussian distribution for more natural clustering around center
        double offsetX = random.nextGaussian() * (maxOffsetX / 2);
        double offsetY = random.nextGaussian() * (maxOffsetY / 2);

        // Clamp to stay within bounds
        offsetX = Vector.clamp(offsetX, -maxOffsetX, maxOffsetX);
        offsetY = Vector.clamp(offsetY, -maxOffsetY, maxOffsetY);

        double newX = Vector.clamp(center.getX() + offsetX, x, x + width);
        double newY = Vector.clamp(center.getY() + offsetY, y, y + height);

        return new Vector(newX, newY);
    }

    // ==================== Intersection Checks ====================

    /**
     * Checks if a point is inside this bounding box.
     *
     * @param point the point to check
     * @return true if the point is inside the box (exclusive of edges)
     */
    public boolean contains(Vector point) {
        return point.getX() > x &&
                point.getX() < x + width &&
                point.getY() > y &&
                point.getY() < y + height;
    }

    /**
     * Checks if a point is inside or on the edge of this bounding box.
     *
     * @param point the point to check
     * @return true if the point is inside or on the box edge
     */
    public boolean containsInclusive(Vector point) {
        return point.getX() >= x &&
                point.getX() <= x + width &&
                point.getY() >= y &&
                point.getY() <= y + height;
    }

    /**
     * Checks if this box intersects with another box.
     *
     * @param other the other bounding box
     * @return true if the boxes overlap
     */
    public boolean intersects(BoundingBox other) {
        return !(other.getRight() < this.x ||
                other.getLeft() > this.getRight() ||
                other.getBottom() < this.y ||
                other.getTop() > this.getBottom());
    }

    // ==================== Transformations ====================

    /**
     * Creates a new BoundingBox expanded by the specified margin on all sides.
     *
     * @param margin the margin to add (can be negative to shrink)
     * @return a new expanded BoundingBox
     */
    public BoundingBox expand(double margin) {
        return new BoundingBox(
                x - margin,
                y - margin,
                width + margin * 2,
                height + margin * 2
        );
    }

    /**
     * Creates a new BoundingBox with the position offset by the specified amounts.
     *
     * @param offsetX the x offset
     * @param offsetY the y offset
     * @return a new offset BoundingBox
     */
    public BoundingBox offset(double offsetX, double offsetY) {
        return new BoundingBox(x + offsetX, y + offsetY, width, height);
    }

    /**
     * Creates a new BoundingBox with the position offset by a vector.
     *
     * @param offset the offset vector
     * @return a new offset BoundingBox
     */
    public BoundingBox offset(Vector offset) {
        return offset(offset.getX(), offset.getY());
    }

    // ==================== Utility Methods ====================

    /**
     * Calculates the area of this bounding box.
     *
     * @return the area (width * height)
     */
    public double getArea() {
        return width * height;
    }

    /**
     * Checks if this bounding box has valid dimensions (positive width and height).
     *
     * @return true if width > 0 and height > 0
     */
    public boolean isValid() {
        return width > 0 && height > 0;
    }

    /**
     * Returns the diagonal length of the bounding box.
     *
     * @return the diagonal length
     */
    public double getDiagonal() {
        return Math.sqrt(width * width + height * height);
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BoundingBox that = (BoundingBox) o;
        return Double.compare(that.x, x) == 0 &&
                Double.compare(that.y, y) == 0 &&
                Double.compare(that.width, width) == 0 &&
                Double.compare(that.height, height) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, width, height);
    }

    @Override
    public String toString() {
        return String.format("BoundingBox(x=%.2f, y=%.2f, width=%.2f, height=%.2f)", x, y, width, height);
    }
}