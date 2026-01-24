package org.nodriver4j.services;

/**
 * Response from the AutoSolve AI captcha solving service.
 *
 * <p>Contains the solution for a reCAPTCHA v2 image challenge as a 3x3 grid
 * where {@code true} indicates the tile should be selected.</p>
 *
 * <h2>Grid Layout</h2>
 * <pre>
 * [0][0] [0][1] [0][2]
 * [1][0] [1][1] [1][2]
 * [2][0] [2][1] [2][2]
 * </pre>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * AutoSolveAIResponse response = service.solve(description, imageBase64);
 * if (response.success()) {
 *     boolean[][] grid = response.squares();
 *     for (int row = 0; row < 3; row++) {
 *         for (int col = 0; col < 3; col++) {
 *             if (grid[row][col]) {
 *                 // Click this tile
 *             }
 *         }
 *     }
 * }
 * }</pre>
 *
 * @param id        unique identifier for this solve request
 * @param squares   3x3 grid indicating which tiles to select (true = select)
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
     * @param row the row index (0-2, top to bottom)
     * @param col the column index (0-2, left to right)
     * @return true if the tile should be selected
     * @throws IndexOutOfBoundsException if row or col is outside 0-2 range
     */
    public boolean shouldSelectTile(int row, int col) {
        if (row < 0 || row > 2 || col < 0 || col > 2) {
            throw new IndexOutOfBoundsException(
                    "Row and column must be between 0 and 2, got row=" + row + ", col=" + col);
        }
        if (squares == null || squares[row] == null) {
            return false;
        }
        return squares[row][col];
    }

    /**
     * Checks if the response contains a valid solution grid.
     *
     * @return true if squares is non-null and properly sized
     */
    public boolean hasValidGrid() {
        if (squares == null || squares.length != 3) {
            return false;
        }
        for (boolean[] row : squares) {
            if (row == null || row.length != 3) {
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
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    if (squares[row][col]) {
                        sb.append("(").append(row).append(",").append(col).append(")");
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