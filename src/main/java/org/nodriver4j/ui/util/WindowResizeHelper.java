package org.nodriver4j.ui.util;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * Utility class that enables edge-drag resizing for undecorated stages.
 *
 * <p>This implementation uses EVENT FILTERS (not handlers) to intercept mouse
 * events during the capturing phase. This is critical because it allows resize
 * operations to take priority over other handlers (like title bar dragging).</p>
 *
 * <h2>Why Event Filters?</h2>
 * <p>JavaFX event processing has two phases:</p>
 * <ol>
 *   <li><b>Capturing phase</b>: Events travel from root to target, filters fire</li>
 *   <li><b>Bubbling phase</b>: Events travel from target to root, handlers fire</li>
 * </ol>
 * <p>By using filters and consuming events when resizing, we prevent the title bar's
 * drag handlers from interfering with resize operations.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Detect mouse position near window edges</li>
 *   <li>Update cursor to indicate resize direction</li>
 *   <li>Handle drag-to-resize operations</li>
 *   <li>Consume events during resize to prevent drag interference</li>
 *   <li>Enforce minimum window dimensions</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Window dragging (handled by title bar when not resizing)</li>
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

    // ==================== Configuration ====================

    private final Scene scene;
    private final Stage stage;
    private final int borderWidth;
    private final double minWidth;
    private final double minHeight;

    // ==================== Resize State ====================

    /**
     * Whether a resize operation is currently in progress.
     * This is set to true on mouse press in resize zone and false on release.
     */
    private boolean resizing = false;

    /**
     * Whether horizontal resizing is active.
     */
    private boolean resizeH = false;

    /**
     * Whether vertical resizing is active.
     */
    private boolean resizeV = false;

    /**
     * Whether resizing from left edge (requires window X position adjustment).
     */
    private boolean moveH = false;

    /**
     * Whether resizing from top edge (requires window Y position adjustment).
     */
    private boolean moveV = false;

    /**
     * Stored stage width at the start of resize operation.
     */
    private double startWidth;

    /**
     * Stored stage height at the start of resize operation.
     */
    private double startHeight;

    /**
     * Stored stage X position at the start of resize operation.
     */
    private double startX;

    /**
     * Stored stage Y position at the start of resize operation.
     */
    private double startY;

    /**
     * Stored mouse screen X at the start of resize operation.
     */
    private double startScreenX;

    /**
     * Stored mouse screen Y at the start of resize operation.
     */
    private double startScreenY;

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
        helper.attachEventFilters();
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

    // ==================== Event Filter Attachment ====================

    /**
     * Attaches event FILTERS (not handlers) to the scene.
     * Filters fire during the capturing phase, before handlers.
     */
    private void attachEventFilters() {
        // Mouse move - update cursor (doesn't need to consume)
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, this::handleMouseMoved);

        // Mouse press - start resize if in zone (consume to prevent drag)
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, this::handleMousePressed);

        // Mouse drag - perform resize (consume to prevent drag)
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::handleMouseDragged);

        // Mouse release - end resize
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, this::handleMouseReleased);
    }

    // ==================== Mouse Move - Cursor Update ====================

    /**
     * Handles mouse movement to update cursor based on position.
     * Does NOT consume the event - other components can still track mouse position.
     */
    private void handleMouseMoved(MouseEvent event) {
        // Don't change cursor when maximized
        if (stage.isMaximized()) {
            scene.setCursor(Cursor.DEFAULT);
            return;
        }

        // Update cursor and internal state based on position
        updateResizeState(event.getSceneX(), event.getSceneY());
    }

    // ==================== Mouse Press - Start Resize ====================

    /**
     * Handles mouse press to potentially start a resize operation.
     * CONSUMES the event if starting a resize to prevent title bar drag.
     */
    private void handleMousePressed(MouseEvent event) {
        // Don't resize when maximized
        if (stage.isMaximized()) {
            return;
        }

        // Check if we're in a resize zone
        updateResizeState(event.getSceneX(), event.getSceneY());

        if (resizeH || resizeV) {
            // We're starting a resize operation
            resizing = true;

            // Store initial state
            startWidth = stage.getWidth();
            startHeight = stage.getHeight();
            startX = stage.getX();
            startY = stage.getY();
            startScreenX = event.getScreenX();
            startScreenY = event.getScreenY();

            // CRITICAL: Consume the event to prevent title bar from starting a drag
            event.consume();
        }
    }

    // ==================== Mouse Drag - Perform Resize ====================

    /**
     * Handles mouse drag to perform resize operations.
     * CONSUMES the event if resizing to prevent title bar drag.
     */
    private void handleMouseDragged(MouseEvent event) {
        // Only process if we started a resize operation
        if (!resizing) {
            return;
        }

        // Calculate how far the mouse has moved from the start position
        double deltaX = event.getScreenX() - startScreenX;
        double deltaY = event.getScreenY() - startScreenY;

        // Perform horizontal resize
        if (resizeH) {
            if (moveH) {
                // Resizing from LEFT edge
                resizeFromLeft(deltaX);
            } else {
                // Resizing from RIGHT edge
                resizeFromRight(deltaX);
            }
        }

        // Perform vertical resize
        if (resizeV) {
            if (moveV) {
                // Resizing from TOP edge
                resizeFromTop(deltaY);
            } else {
                // Resizing from BOTTOM edge
                resizeFromBottom(deltaY);
            }
        }

        // CRITICAL: Consume the event to prevent title bar drag
        event.consume();
    }

    // ==================== Mouse Release - End Resize ====================

    /**
     * Handles mouse release to end resize operation.
     */
    private void handleMouseReleased(MouseEvent event) {
        if (resizing) {
            resizing = false;
            // Consume to complete the resize gesture cleanly
            event.consume();
        }
    }

    // ==================== Resize State Management ====================

    /**
     * Updates the resize state and cursor based on mouse position.
     */
    private void updateResizeState(double x, double y) {
        double width = scene.getWidth();
        double height = scene.getHeight();

        // Determine which edges the cursor is near
        boolean nearLeft = x >= 0 && x < borderWidth;
        boolean nearRight = x <= width && x > width - borderWidth;
        boolean nearTop = y >= 0 && y < borderWidth;
        boolean nearBottom = y <= height && y > height - borderWidth;

        // Reset state
        resizeH = false;
        resizeV = false;
        moveH = false;
        moveV = false;

        // Handle corners first (they have priority)
        if (nearLeft && nearTop) {
            scene.setCursor(Cursor.NW_RESIZE);
            resizeH = true;
            resizeV = true;
            moveH = true;
            moveV = true;
        } else if (nearLeft && nearBottom) {
            scene.setCursor(Cursor.SW_RESIZE);
            resizeH = true;
            resizeV = true;
            moveH = true;
            moveV = false;
        } else if (nearRight && nearTop) {
            scene.setCursor(Cursor.NE_RESIZE);
            resizeH = true;
            resizeV = true;
            moveH = false;
            moveV = true;
        } else if (nearRight && nearBottom) {
            scene.setCursor(Cursor.SE_RESIZE);
            resizeH = true;
            resizeV = true;
            moveH = false;
            moveV = false;
        }
        // Then handle edges
        else if (nearLeft) {
            scene.setCursor(Cursor.W_RESIZE);
            resizeH = true;
            moveH = true;
        } else if (nearRight) {
            scene.setCursor(Cursor.E_RESIZE);
            resizeH = true;
            moveH = false;
        } else if (nearTop) {
            scene.setCursor(Cursor.N_RESIZE);
            resizeV = true;
            moveV = true;
        } else if (nearBottom) {
            scene.setCursor(Cursor.S_RESIZE);
            resizeV = true;
            moveV = false;
        }
        // Not near any edge
        else {
            scene.setCursor(Cursor.DEFAULT);
        }
    }

    // ==================== Resize Operations ====================

    /**
     * Resize from the right edge.
     * Simple case: just change width based on mouse delta.
     */
    private void resizeFromRight(double deltaX) {
        double newWidth = startWidth + deltaX;

        if (newWidth >= minWidth) {
            stage.setWidth(newWidth);
        } else {
            stage.setWidth(minWidth);
        }
    }

    /**
     * Resize from the left edge.
     * Complex case: move window X AND change width to keep right edge fixed.
     *
     * <p>When dragging left edge:</p>
     * <ul>
     *   <li>Moving cursor LEFT (negative deltaX) = window gets WIDER, X moves LEFT</li>
     *   <li>Moving cursor RIGHT (positive deltaX) = window gets NARROWER, X moves RIGHT</li>
     * </ul>
     */
    private void resizeFromLeft(double deltaX) {
        // New width = original width MINUS the delta (moving left = negative delta = larger width)
        double newWidth = startWidth - deltaX;

        if (newWidth >= minWidth) {
            stage.setWidth(newWidth);
            stage.setX(startX + deltaX);
        } else {
            // Clamp to minimum width
            stage.setWidth(minWidth);
            // Adjust X so right edge stays at original position
            stage.setX(startX + startWidth - minWidth);
        }
    }

    /**
     * Resize from the bottom edge.
     * Simple case: just change height based on mouse delta.
     */
    private void resizeFromBottom(double deltaY) {
        double newHeight = startHeight + deltaY;

        if (newHeight >= minHeight) {
            stage.setHeight(newHeight);
        } else {
            stage.setHeight(minHeight);
        }
    }

    /**
     * Resize from the top edge.
     * Complex case: move window Y AND change height to keep bottom edge fixed.
     *
     * <p>When dragging top edge:</p>
     * <ul>
     *   <li>Moving cursor UP (negative deltaY) = window gets TALLER, Y moves UP</li>
     *   <li>Moving cursor DOWN (positive deltaY) = window gets SHORTER, Y moves DOWN</li>
     * </ul>
     */
    private void resizeFromTop(double deltaY) {
        // New height = original height MINUS the delta (moving up = negative delta = larger height)
        double newHeight = startHeight - deltaY;

        if (newHeight >= minHeight) {
            stage.setHeight(newHeight);
            stage.setY(startY + deltaY);
        } else {
            // Clamp to minimum height
            stage.setHeight(minHeight);
            // Adjust Y so bottom edge stays at original position
            stage.setY(startY + startHeight - minHeight);
        }
    }
}