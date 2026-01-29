package org.nodriver4j.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * Custom window title bar component for undecorated stages.
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>App title on the left</li>
 *   <li>Minimize, maximize/restore, and close buttons on the right</li>
 *   <li>Drag-to-move functionality</li>
 *   <li>Double-click to maximize/restore</li>
 * </ul>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display window title and control buttons</li>
 *   <li>Handle minimize, maximize, close actions</li>
 *   <li>Handle window dragging</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Window resizing (handled by WindowResizeHelper)</li>
 *   <li>Application logic</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Stage stage = ...;
 * WindowTitleBar titleBar = new WindowTitleBar(stage, "My App");
 * root.setTop(titleBar);
 * }</pre>
 */
public class WindowTitleBar extends HBox {

    // ==================== Constants ====================

    // Bold, clear icons for window controls
    private static final String MINIMIZE_ICON = "─";
    private static final String MAXIMIZE_ICON = "□";
    private static final String RESTORE_ICON = "❐";
    private static final String CLOSE_ICON = "✕";

    // ==================== UI Components ====================

    private final Label titleLabel;
    private final Button minimizeButton;
    private final Button maximizeButton;
    private final Button closeButton;
    private final Label maximizeIcon;

    // ==================== State ====================

    private final Stage stage;
    private double dragOffsetX;
    private double dragOffsetY;

    // For restoring from maximized state
    private double restoreX;
    private double restoreY;
    private double restoreWidth;
    private double restoreHeight;

    // ==================== Constructor ====================

    /**
     * Creates a new WindowTitleBar.
     *
     * @param stage the stage this title bar controls
     * @param title the application title to display
     */
    public WindowTitleBar(Stage stage, String title) {
        this.stage = stage;

        // Apply styling
        getStyleClass().add("title-bar");
        setAlignment(Pos.CENTER_LEFT);

        // Create components
        titleLabel = createTitleLabel(title);
        maximizeIcon = new Label(MAXIMIZE_ICON);
        minimizeButton = createControlButton(MINIMIZE_ICON, false);
        maximizeButton = createMaximizeButton();
        closeButton = createControlButton(CLOSE_ICON, true);

        // Spacer to push buttons to the right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Control buttons container
        HBox controls = new HBox();
        controls.getStyleClass().add("window-controls");
        controls.getChildren().addAll(minimizeButton, maximizeButton, closeButton);

        // Add to title bar
        getChildren().addAll(titleLabel, spacer, controls);

        // Set up drag-to-move
        setupDragHandlers();

        // Listen for maximize state changes
        stage.maximizedProperty().addListener((obs, wasMaximized, isMaximized) -> {
            updateMaximizeIcon(isMaximized);
        });
    }

    // ==================== UI Building ====================

    private Label createTitleLabel(String title) {
        Label label = new Label(title);
        label.getStyleClass().add("title-bar-title");
        return label;
    }

    private Button createControlButton(String iconText, boolean isCloseButton) {
        Button button = new Button();
        button.getStyleClass().add("window-control-button");

        if (isCloseButton) {
            button.getStyleClass().add("close-button");
            button.setOnAction(e -> stage.close());
        } else {
            button.setOnAction(e -> stage.setIconified(true));
        }

        Label icon = new Label(iconText);
        button.setGraphic(icon);

        return button;
    }

    private Button createMaximizeButton() {
        Button button = new Button();
        button.getStyleClass().add("window-control-button");

        button.setGraphic(maximizeIcon);
        button.setOnAction(e -> toggleMaximize());

        return button;
    }

    // ==================== Drag Handling ====================

    private void setupDragHandlers() {
        setOnMousePressed(this::onMousePressed);
        setOnMouseDragged(this::onMouseDragged);
        setOnMouseClicked(this::onMouseClicked);
    }

    private void onMousePressed(MouseEvent event) {
        if (event.getButton() == MouseButton.PRIMARY) {
            if (stage.isMaximized()) {
                // Store relative position for restore-on-drag
                dragOffsetX = event.getScreenX() / stage.getWidth();
                dragOffsetY = event.getY();
            } else {
                dragOffsetX = event.getScreenX() - stage.getX();
                dragOffsetY = event.getScreenY() - stage.getY();
            }
        }
    }

    private void onMouseDragged(MouseEvent event) {
        if (event.getButton() == MouseButton.PRIMARY) {
            if (stage.isMaximized()) {
                // Restore window and position under cursor
                double relativeX = dragOffsetX;
                stage.setMaximized(false);

                // Position window so cursor is at same relative position
                stage.setX(event.getScreenX() - (restoreWidth * relativeX));
                stage.setY(event.getScreenY() - dragOffsetY);

                // Update drag offset for continued dragging
                dragOffsetX = event.getScreenX() - stage.getX();
                dragOffsetY = event.getScreenY() - stage.getY();
            } else {
                stage.setX(event.getScreenX() - dragOffsetX);
                stage.setY(event.getScreenY() - dragOffsetY);
            }
        }
    }

    private void onMouseClicked(MouseEvent event) {
        if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
            toggleMaximize();
        }
    }

    // ==================== Maximize/Restore ====================

    private void toggleMaximize() {
        if (stage.isMaximized()) {
            stage.setMaximized(false);
        } else {
            // Store current bounds for restore
            restoreX = stage.getX();
            restoreY = stage.getY();
            restoreWidth = stage.getWidth();
            restoreHeight = stage.getHeight();

            stage.setMaximized(true);
        }
    }

    private void updateMaximizeIcon(boolean isMaximized) {
        maximizeIcon.setText(isMaximized ? RESTORE_ICON : MAXIMIZE_ICON);
    }

    // ==================== Public API ====================

    /**
     * Sets the window title.
     *
     * @param title the new title
     */
    public void setTitle(String title) {
        titleLabel.setText(title);
    }

    /**
     * Gets the window title.
     *
     * @return the current title
     */
    public String getTitle() {
        return titleLabel.getText();
    }
}