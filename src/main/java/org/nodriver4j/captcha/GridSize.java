package org.nodriver4j.captcha;

/**
 * Represents the grid size of a reCAPTCHA v2 image challenge.
 *
 * <p>reCAPTCHA v2 presents challenges in two formats:</p>
 * <ul>
 *   <li><strong>3x3 grid</strong>: 9 tiles, single composite image at 300x300 pixels</li>
 *   <li><strong>4x4 grid</strong>: 16 tiles, single composite image at 450x450 pixels</li>
 * </ul>
 *
 * <p>This enum provides utility methods for converting between tile IDs (0-8 or 0-15)
 * and grid coordinates (row, column).</p>
 *
 * @see ReCaptchaSolver
 */
public enum GridSize {

    /**
     * 3x3 grid challenge with 9 tiles.
     * Tile IDs range from 0 to 8.
     * Native image size: 300x300 pixels.
     */
    THREE_BY_THREE(3, 300),

    /**
     * 4x4 grid challenge with 16 tiles.
     * Tile IDs range from 0 to 15.
     * Native image size: 450x450 pixels.
     */
    FOUR_BY_FOUR(4, 450);

    private final int dimension;
    private final int imageSize;

    GridSize(int dimension, int imageSize) {
        this.dimension = dimension;
        this.imageSize = imageSize;
    }

    /**
     * Returns the grid dimension (3 or 4).
     *
     * @return the dimension
     */
    public int dimension() {
        return dimension;
    }

    /**
     * Returns the total number of tiles in this grid.
     *
     * @return 9 for 3x3, 16 for 4x4
     */
    public int tileCount() {
        return dimension * dimension;
    }

    /**
     * Returns the expected native image size in pixels.
     *
     * <p>reCAPTCHA serves images at specific sizes:</p>
     * <ul>
     *   <li>3x3 challenges: 300x300 pixels</li>
     *   <li>4x4 challenges: 450x450 pixels</li>
     * </ul>
     *
     * @return 300 for 3x3, 450 for 4x4
     */
    public int expectedImageSize() {
        return imageSize;
    }

    /**
     * Calculates the row index for a given tile ID.
     *
     * @param tileId the tile ID (0-based)
     * @return the row index (0-based, top to bottom)
     * @throws IllegalArgumentException if tileId is out of range
     */
    public int rowFromTileId(int tileId) {
        validateTileId(tileId);
        return tileId / dimension;
    }

    /**
     * Calculates the column index for a given tile ID.
     *
     * @param tileId the tile ID (0-based)
     * @return the column index (0-based, left to right)
     * @throws IllegalArgumentException if tileId is out of range
     */
    public int colFromTileId(int tileId) {
        validateTileId(tileId);
        return tileId % dimension;
    }

    /**
     * Calculates the tile ID from row and column indices.
     *
     * @param row the row index (0-based)
     * @param col the column index (0-based)
     * @return the tile ID
     * @throws IllegalArgumentException if row or col is out of range
     */
    public int tileIdFromPosition(int row, int col) {
        if (row < 0 || row >= dimension) {
            throw new IllegalArgumentException(
                    "Row must be between 0 and " + (dimension - 1) + ", got: " + row);
        }
        if (col < 0 || col >= dimension) {
            throw new IllegalArgumentException(
                    "Column must be between 0 and " + (dimension - 1) + ", got: " + col);
        }
        return row * dimension + col;
    }

    /**
     * Validates that a tile ID is within the valid range for this grid size.
     *
     * @param tileId the tile ID to validate
     * @throws IllegalArgumentException if tileId is out of range
     */
    public void validateTileId(int tileId) {
        if (tileId < 0 || tileId >= tileCount()) {
            throw new IllegalArgumentException(
                    "Tile ID must be between 0 and " + (tileCount() - 1) + " for " + this + ", got: " + tileId);
        }
    }

    /**
     * Determines the grid size from the number of tiles.
     *
     * @param tileCount the number of tiles (9 or 16)
     * @return the corresponding GridSize
     * @throws IllegalArgumentException if tileCount is not 9 or 16
     */
    public static GridSize fromTileCount(int tileCount) {
        return switch (tileCount) {
            case 9 -> THREE_BY_THREE;
            case 16 -> FOUR_BY_FOUR;
            default -> throw new IllegalArgumentException(
                    "Unsupported tile count: " + tileCount + ". Expected 9 (3x3) or 16 (4x4).");
        };
    }

    /**
     * Determines the grid size from the grid dimension.
     *
     * @param dimension the grid dimension (3 or 4)
     * @return the corresponding GridSize
     * @throws IllegalArgumentException if dimension is not 3 or 4
     */
    public static GridSize fromDimension(int dimension) {
        return switch (dimension) {
            case 3 -> THREE_BY_THREE;
            case 4 -> FOUR_BY_FOUR;
            default -> throw new IllegalArgumentException(
                    "Unsupported grid dimension: " + dimension + ". Expected 3 or 4.");
        };
    }

    @Override
    public String toString() {
        return dimension + "x" + dimension;
    }
}