package org.nodriver4j.services;

import org.nodriver4j.captcha.GridSize;

/**
 * Response from the AutoSolve AI captcha solving service.
 *
 * <p>Contains the solution for a reCAPTCHA v2 image challenge as a grid
 * where {@code true} indicates the tile should be selected.</p>
 *
 * <h2>Supported Grid Sizes</h2>
 * <ul>
 *   <li><strong>3x3 grid</strong>: 9 tiles, tile IDs 0-8</li>
 *   <li><strong>4x4 grid</strong>: 16 tiles, tile IDs 0-15</li>
 * </ul>
 *
 * <h2>Grid Layout (4x4 example)</h2>
 * <pre>
 * [0][0] [0][1] [0][2] [0][3]
 * [1][0] [1][1] [1][2] [1][3]
 * [2][0] [2][1] [2][2] [2][3]
 * [3][0] [3][1] [3][2] [3][3]
 * </pre>
 *
 * <h2>Tile ID Mapping (4x4 example)</h2>
 * <p>reCAPTCHA tiles have IDs 0-15 (or 0-8 for 3x3), which map to grid positions as:</p>
 * <pre>
 * td#0  td#1  td#2  td#3
 * td#4  td#5  td#6  td#7
 * td#8  td#9  td#10 td#11
 * td#12 td#13 td#14 td#15
 * </pre>
 * <p>Use {@link #shouldSelectTileById(int, GridSize)} for tile selection.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * AutoSolveAIResponse response = service.solve(description, imageBase64);
 * GridSize gridSize = GridSize.THREE_BY_THREE; // or FOUR_BY_FOUR
 *
 * if (response.success()) {
 *     for (int tileId = 0; tileId < gridSize.tileCount(); tileId++) {
 *         if (response.shouldSelectTileById(tileId, gridSize)) {
 *             // Click tile with id="tileId"
 *         }
 *     }
 * }
 * }</pre>
 *
 * @param id        unique identifier for this solve request
 * @param squares   grid indicating which tiles to select (true = select)
 * @param success   whether the solve request was successful
 * @param message   error message if unsuccessful, null otherwise
 * @param remaining remaining API credits/balance
 *
 * @see AutoSolveAIService
 * @see GridSize
 */
public record AutoSolveAIResponse(
        String id,
        boolean[][] squares,
        boolean success,
        String message,
        long remaining
) {

    /**
     * Returns the number of tiles that should be selected.
     *
     * @return count of tiles marked as true in the grid
     */
    public int selectedTileCount() {
        if (squares == null) {
            return 0;
        }

        int count = 0;
        for (boolean[] row : squares) {
            if (row != null) {
                for (boolean tile : row) {
                    if (tile) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Checks if a specific tile should be selected using row and column indices.
     *
     * @param row the row index (0-based, top to bottom)
     * @param col the column index (0-based, left to right)
     * @return true if the tile should be selected
     * @throws IndexOutOfBoundsException if row or col is outside the grid bounds
     */
    public boolean shouldSelectTile(int row, int col) {
        if (squares == null) {
            return false;
        }

        int gridDimension = squares.length;

        if (row < 0 || row >= gridDimension) {
            throw new IndexOutOfBoundsException(
                    "Row must be between 0 and " + (gridDimension - 1) + ", got: " + row);
        }

        if (squares[row] == null) {
            return false;
        }

        if (col < 0 || col >= squares[row].length) {
            throw new IndexOutOfBoundsException(
                    "Column must be between 0 and " + (squares[row].length - 1) + ", got: " + col);
        }

        return squares[row][col];
    }

    /**
     * Checks if a tile should be selected by its HTML element ID.
     *
     * <p>reCAPTCHA tiles have sequential IDs that map to grid positions based on
     * the grid dimension:</p>
     * <ul>
     *   <li>For 3x3: Row = tileId / 3, Col = tileId % 3</li>
     *   <li>For 4x4: Row = tileId / 4, Col = tileId % 4</li>
     * </ul>
     *
     * @param tileId   the tile ID (0-based)
     * @param gridSize the grid size to use for coordinate calculation
     * @return true if the tile should be selected
     * @throws IndexOutOfBoundsException if tileId is outside the valid range for the grid size
     * @throws IllegalArgumentException if gridSize is null
     */
    public boolean shouldSelectTileById(int tileId, GridSize gridSize) {
        if (gridSize == null) {
            throw new IllegalArgumentException("GridSize cannot be null");
        }

        gridSize.validateTileId(tileId);

        int row = gridSize.rowFromTileId(tileId);
        int col = gridSize.colFromTileId(tileId);

        return shouldSelectTile(row, col);
    }

    /**
     * Checks if a tile should be selected by its HTML element ID.
     *
     * <p>This method infers the grid size from the response's squares array dimensions.
     * If the grid size cannot be determined, it defaults to 4x4 for backward compatibility.</p>
     *
     * <p><strong>Prefer using {@link #shouldSelectTileById(int, GridSize)} when the grid size
     * is known</strong>, as it provides explicit control and clearer error messages.</p>
     *
     * @param tileId the tile ID (0-based)
     * @return true if the tile should be selected
     * @throws IndexOutOfBoundsException if tileId is outside the valid range
     */
    public boolean shouldSelectTileById(int tileId) {
        GridSize gridSize = inferGridSize();
        return shouldSelectTileById(tileId, gridSize);
    }

    /**
     * Infers the grid size from the squares array dimensions.
     *
     * @return the inferred GridSize, defaults to FOUR_BY_FOUR if unable to determine
     */
    private GridSize inferGridSize() {
        if (squares == null || squares.length == 0) {
            return GridSize.FOUR_BY_FOUR; // Default for backward compatibility
        }

        int dimension = squares.length;

        try {
            return GridSize.fromDimension(dimension);
        } catch (IllegalArgumentException e) {
            // Unknown dimension, default to 4x4
            System.err.println("[AutoSolveAIResponse] Warning: Unknown grid dimension " + dimension + ", defaulting to 4x4");
            return GridSize.FOUR_BY_FOUR;
        }
    }

    /**
     * Checks if the response contains a valid solution grid.
     *
     * <p>A valid grid is:</p>
     * <ul>
     *   <li>Non-null</li>
     *   <li>Either 3x3 or 4x4 in dimensions</li>
     *   <li>All rows are non-null and have the correct length</li>
     * </ul>
     *
     * @return true if squares is non-null and properly sized
     */
    public boolean hasValidGrid() {
        if (squares == null) {
            return false;
        }

        int dimension = squares.length;

        // Must be 3x3 or 4x4
        if (dimension != 3 && dimension != 4) {
            return false;
        }

        // All rows must exist and have correct length
        for (boolean[] row : squares) {
            if (row == null || row.length != dimension) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns the grid dimension (3 or 4) if valid, or -1 if invalid.
     *
     * @return the grid dimension, or -1 if the grid is invalid
     */
    public int gridDimension() {
        if (!hasValidGrid()) {
            return -1;
        }
        return squares.length;
    }

    /**
     * Returns the GridSize of the response if valid.
     *
     * @return the GridSize, or null if the grid is invalid
     */
    public GridSize gridSize() {
        int dimension = gridDimension();
        if (dimension == -1) {
            return null;
        }

        try {
            return GridSize.fromDimension(dimension);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AutoSolveAIResponse{success=").append(success);
        sb.append(", id=").append(id);

        if (success && hasValidGrid()) {
            GridSize size = gridSize();
            sb.append(", gridSize=").append(size != null ? size : "unknown");
            sb.append(", tiles=[");
            boolean first = true;
            int dimension = squares.length;
            for (int row = 0; row < dimension; row++) {
                for (int col = 0; col < dimension; col++) {
                    if (squares[row][col]) {
                        if (!first) {
                            sb.append(",");
                        }
                        sb.append("(").append(row).append(",").append(col).append(")");
                        first = false;
                    }
                }
            }
            sb.append("]");
        } else if (message != null) {
            sb.append(", message=").append(message);
        }

        sb.append("}");
        return sb.toString();
    }
}