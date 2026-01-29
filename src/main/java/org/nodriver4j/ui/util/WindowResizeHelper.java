package org.nodriver4j.ui.util;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * Utility class that enables edge-drag resizing for undecorated stages.
 *
 * <p>Attach this to a scene to enable resizing from any edge or corner
 * of the window. The cursor automatically changes to indicate resize
 * direction when near the edges.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Detect mouse position near window edges</li>
 *   <li>Update cursor to indicate resize direction</li>
 *   <li>Handle drag-to-resize operations</li>
 *   <li>Enforce minimum window dimensions</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Window dragging (handled by title bar)</li>
 *   <li>Maximize/minimize (handled by title bar)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Stage stage = ...;
 * Scene scene = ...;
 * WindowResizeHelper.attach(scene, stage, 6, 900, 600);
 * }</pre>
 */
public final class WindowResizeHelper {

    // ==================== Resize Directions ====================

    private enum ResizeDirection {
        NONE,
        NORTH, SOUTH, EAST, WEST,
        NORTH_EAST, NORTH_WEST, SOUTH_EAST, SOUTH_WEST
    }

    // ==================== State ====================

    private final Scene scene;
    private final Stage stage;
    private final int borderWidth;
    private final double minWidth;
    private final double minHeight;

    // Resize tracking
    private ResizeDirection direction = ResizeDirection.NONE;
    private double startX;
    private double startY;
    private double startWidth;
    private double startHeight;
    private double startStageX;
    private double startStageY;

    // ==================== Constructor ====================

    private WindowResizeHelper(Scene scene, Stage stage, int borderWidth, double minWidth, double minHeight) {
        this.scene = scene;
        this.stage = stage;
        this.borderWidth = borderWidth;
        this.minWidth = minWidth;
        this.minHeight = minHeight;
    }

    // ==================== Public API ====================

    /**
     * Attaches resize functionality to a scene/stage.
     *
     * @param scene       the scene to attach handlers to
     * @param stage       the stage to resize
     * @param borderWidth the width of the resize border in pixels (typically 5-8)
     * @param minWidth    minimum window width
     * @param minHeight   minimum window height
     */
    public static void attach(Scene scene, Stage stage, int borderWidth, double minWidth, double minHeight) {
        WindowResizeHelper helper = new WindowResizeHelper(scene, stage, borderWidth, minWidth, minHeight);
        helper.attachHandlers();
    }

    /**
     * Attaches resize functionality with default border width of 6 pixels.
     *
     * @param scene     the scene to attach handlers to
     * @param stage     the stage to resize
     * @param minWidth  minimum window width
     * @param minHeight minimum window height
     */
    public static void attach(Scene scene, Stage stage, double minWidth, double minHeight) {
        attach(scene, stage, 6, minWidth, minHeight);
    }

    // ==================== Event Handlers ====================

    private void attachHandlers() {
        scene.setOnMouseMoved(this::onMouseMoved);
        scene.setOnMousePressed(this::onMousePressed);
        scene.setOnMouseDragged(this::onMouseDragged);
        scene.setOnMouseReleased(this::onMouseReleased);
    }

    private void onMouseMoved(MouseEvent event) {
        if (stage.isMaximized()) {
            scene.setCursor(Cursor.DEFAULT);
            return;
        }

        ResizeDirection dir = detectDirection(event.getSceneX(), event.getSceneY());
        scene.setCursor(getCursor(dir));
    }

    private void onMousePressed(MouseEvent event) {
        if (stage.isMaximized()) {
            direction = ResizeDirection.NONE;
            return;
        }

        direction = detectDirection(event.getSceneX(), event.getSceneY());

        if (direction != ResizeDirection.NONE) {
            startX = event.getScreenX();
            startY = event.getScreenY();
            startWidth = stage.getWidth();
            startHeight = stage.getHeight();
            startStageX = stage.getX();
            startStageY = stage.getY();
        }
    }

    private void onMouseDragged(MouseEvent event) {
        if (direction == ResizeDirection.NONE || stage.isMaximized()) {
            return;
        }

        double deltaX = event.getScreenX() - startX;
        double deltaY = event.getScreenY() - startY;

        // Horizontal resizing
        switch (direction) {
            case EAST, NORTH_EAST, SOUTH_EAST -> resizeEast(deltaX);
            case WEST, NORTH_WEST, SOUTH_WEST -> resizeWest(deltaX);
            default -> {}
        }

        // Vertical resizing
        switch (direction) {
            case SOUTH, SOUTH_EAST, SOUTH_WEST -> resizeSouth(deltaY);
            case NORTH, NORTH_EAST, NORTH_WEST -> resizeNorth(deltaY);
            default -> {}
        }
    }

    private void onMouseReleased(MouseEvent event) {
        direction = ResizeDirection.NONE;
    }

    // ==================== Resize Operations ====================

    private void resizeEast(double deltaX) {
        double newWidth = startWidth + deltaX;
        if (newWidth >= minWidth) {
            stage.setWidth(newWidth);
        }
    }

    private void resizeWest(double deltaX) {
        double newWidth = startWidth - deltaX;
        if (newWidth >= minWidth) {
            stage.setWidth(newWidth);
            stage.setX(startStageX + deltaX);
        }
    }

    private void resizeSouth(double deltaY) {
        double newHeight = startHeight + deltaY;
        if (newHeight >= minHeight) {
            stage.setHeight(newHeight);
        }
    }

    private void resizeNorth(double deltaY) {
        double newHeight = startHeight - deltaY;
        if (newHeight >= minHeight) {
            stage.setHeight(newHeight);
            stage.setY(startStageY + deltaY);
        }
    }

    // ==================== Direction Detection ====================

    private ResizeDirection detectDirection(double x, double y) {
        double width = scene.getWidth();
        double height = scene.getHeight();

        boolean north = y < borderWidth;
        boolean south = y > height - borderWidth;
        boolean east = x > width - borderWidth;
        boolean west = x < borderWidth;

        // Corners first (they take priority)
        if (north && east) return ResizeDirection.NORTH_EAST;
        if (north && west) return ResizeDirection.NORTH_WEST;
        if (south && east) return ResizeDirection.SOUTH_EAST;
        if (south && west) return ResizeDirection.SOUTH_WEST;

        // Then edges
        if (north) return ResizeDirection.NORTH;
        if (south) return ResizeDirection.SOUTH;
        if (east) return ResizeDirection.EAST;
        if (west) return ResizeDirection.WEST;

        return ResizeDirection.NONE;
    }

    private Cursor getCursor(ResizeDirection direction) {
        return switch (direction) {
            case NORTH -> Cursor.N_RESIZE;
            case SOUTH -> Cursor.S_RESIZE;
            case EAST -> Cursor.E_RESIZE;
            case WEST -> Cursor.W_RESIZE;
            case NORTH_EAST -> Cursor.NE_RESIZE;
            case NORTH_WEST -> Cursor.NW_RESIZE;
            case SOUTH_EAST -> Cursor.SE_RESIZE;
            case SOUTH_WEST -> Cursor.SW_RESIZE;
            case NONE -> Cursor.DEFAULT;
        };
    }
}