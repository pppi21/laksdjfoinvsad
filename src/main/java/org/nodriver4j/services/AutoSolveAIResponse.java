package org.nodriver4j.services;

/**
 * Response from the AutoSolve AI captcha solving service.
 *
 * <p>Contains the solution for a reCAPTCHA v2 image challenge as a 4x4 grid
 * where {@code true} indicates the tile should be selected.</p>
 *
 * <h2>Grid Layout</h2>
 * <pre>
 * [0][0] [0][1] [0][2] [0][3]
 * [1][0] [1][1] [1][2] [1][3]
 * [2][0] [2][1] [2][2] [2][3]
 * [3][0] [3][1] [3][2] [3][3]
 * </pre>
 *
 * <h2>Tile ID Mapping</h2>
 * <p>reCAPTCHA uses tile IDs 0-15, which map to grid positions as:</p>
 * <pre>
 * td#0  td#1  td#2  td#3
 * td#4  td#5  td#6  td#7
 * td#8  td#9  td#10 td#11
 * td#12 td#13 td#14 td#15
 * </pre>
 * <p>Use {@link #shouldSelectTileById(int)} for convenience.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * AutoSolveAIResponse response = service.solve(description, imageBase64);
 * if (response.success()) {
 *     // Click tiles using ID (0-15)
 *     for (int tileId = 0; tileId < 16; tileId++) {
 *         if (response.shouldSelectTileById(tileId)) {
 *             // Click tile with id="tileId"
 *         }
 *     }
 * }
 * }</pre>
 *
 * @param id        unique identifier for this solve request
 * @param squares   4x4 grid indicating which tiles to select (true = select)
 * @param success   whether the solve request was successful
 * @param message   error message if unsuccessful, null otherwise
 * @param remaining remaining API credits/balance
 *
 * @see AutoSolveAIService
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
     * Checks if a specific tile should be selected.
     *
     * @param row the row index (0-3, top to bottom)
     * @param col the column index (0-3, left to right)
     * @return true if the tile should be selected
     * @throws IndexOutOfBoundsException if row or col is outside 0-3 range
     */
    public boolean shouldSelectTile(int row, int col) {
        if (row < 0 || row > 3 || col < 0 || col > 3) {
            throw new IndexOutOfBoundsException(
                    "Row and column must be between 0 and 3, got row=" + row + ", col=" + col);
        }
        if (squares == null || squares.length <= row || squares[row] == null || squares[row].length <= col) {
            return false;
        }
        return squares[row][col];
    }

    /**
     * Checks if a tile should be selected by its HTML element ID (0-15).
     *
     * <p>reCAPTCHA tiles have IDs 0-15, which map to the grid as:</p>
     * <ul>
     *   <li>Row = tileId / 4</li>
     *   <li>Col = tileId % 4</li>
     * </ul>
     *
     * @param tileId the tile ID (0-15)
     * @return true if the tile should be selected
     * @throws IndexOutOfBoundsException if tileId is outside 0-15 range
     */
    public boolean shouldSelectTileById(int tileId) {
        if (tileId < 0 || tileId > 15) {
            throw new IndexOutOfBoundsException("Tile ID must be between 0 and 15, got: " + tileId);
        }
        int row = tileId / 4;
        int col = tileId % 4;
        return shouldSelectTile(row, col);
    }

    /**
     * Checks if the response contains a valid solution grid.
     *
     * @return true if squares is non-null and properly sized (4x4)
     */
    public boolean hasValidGrid() {
        if (squares == null || squares.length != 4) {
            return false;
        }
        for (boolean[] row : squares) {
            if (row == null || row.length != 4) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AutoSolveAIResponse{success=").append(success);
        sb.append(", id=").append(id);

        if (success && hasValidGrid()) {
            sb.append(", tiles=[");
            boolean first = true;
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 4; col++) {
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