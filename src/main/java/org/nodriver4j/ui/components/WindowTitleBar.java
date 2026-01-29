package org.nodriver4j.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

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
 *   <li>Window resizing (handled by WindowResizeHelper which consumes resize events)</li>
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

    private static final int ICON_SIZE = 12;
    private static final Color ICON_COLOR_DEFAULT = Color.web("#a3a3a3");
    private static final Color ICON_COLOR_HOVER = Color.web("#e5e5e5");
    private static final Color ICON_COLOR_CLOSE_HOVER = Color.WHITE;

    // ==================== UI Components ====================

    private final Label titleLabel;
    private final Button minimizeButton;
    private final Button maximizeButton;
    private final Button closeButton;

    // FontIcon references for dynamic updates
    private final FontIcon minimizeIcon;
    private final FontIcon maximizeIcon;
    private final FontIcon closeIcon;

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

        // Create icons
        minimizeIcon = createIcon(FontAwesomeSolid.WINDOW_MINIMIZE);
        maximizeIcon = createIcon(FontAwesomeSolid.WINDOW_MAXIMIZE);
        closeIcon = createIcon(FontAwesomeSolid.WINDOW_CLOSE);

        // Create components
        titleLabel = createTitleLabel(title);
        minimizeButton = createMinimizeButton();
        maximizeButton = createMaximizeButton();
        closeButton = createCloseButton();

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

    // ==================== Icon Factory ====================

    /**
     * Creates a FontIcon with default styling.
     *
     * @param iconType the FontAwesome icon type
     * @return configured FontIcon
     */
    private FontIcon createIcon(FontAwesomeSolid iconType) {
        FontIcon icon = new FontIcon(iconType);
        icon.setIconSize(ICON_SIZE);
        icon.setIconColor(ICON_COLOR_DEFAULT);
        return icon;
    }

    // ==================== UI Building ====================

    private Label createTitleLabel(String title) {
        Label label = new Label(title);
        label.getStyleClass().add("title-bar-title");
        return label;
    }

    private Button createMinimizeButton() {
        Button button = new Button();
        button.getStyleClass().add("window-control-button");
        button.setGraphic(minimizeIcon);
        button.setOnAction(e -> stage.setIconified(true));

        // Hover color handling
        button.setOnMouseEntered(e -> minimizeIcon.setIconColor(ICON_COLOR_HOVER));
        button.setOnMouseExited(e -> minimizeIcon.setIconColor(ICON_COLOR_DEFAULT));

        return button;
    }

    private Button createMaximizeButton() {
        Button button = new Button();
        button.getStyleClass().add("window-control-button");
        button.setGraphic(maximizeIcon);
        button.setOnAction(e -> toggleMaximize());

        // Hover color handling
        button.setOnMouseEntered(e -> maximizeIcon.setIconColor(ICON_COLOR_HOVER));
        button.setOnMouseExited(e -> maximizeIcon.setIconColor(ICON_COLOR_DEFAULT));

        return button;
    }

    private Button createCloseButton() {
        Button button = new Button();
        button.getStyleClass().addAll("window-control-button", "close-button");
        button.setGraphic(closeIcon);
        button.setOnAction(e -> stage.close());

        // Hover color handling (white on red background)
        button.setOnMouseEntered(e -> closeIcon.setIconColor(ICON_COLOR_CLOSE_HOVER));
        button.setOnMouseExited(e -> closeIcon.setIconColor(ICON_COLOR_DEFAULT));

        return button;
    }

    // ==================== Drag Handling ====================

    /**
     * Sets up drag handlers for window movement.
     *
     * <p>Note: WindowResizeHelper uses event FILTERS which fire before these handlers.
     * When a resize operation is in progress, WindowResizeHelper consumes the events
     * so these handlers won't receive them.</p>
     */
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
        FontIcon newIcon = isMaximized
                ? createIcon(FontAwesomeSolid.WINDOW_RESTORE)
                : createIcon(FontAwesomeSolid.WINDOW_MAXIMIZE);

        maximizeButton.setGraphic(newIcon);

        // Re-attach hover handlers to new icon
        maximizeButton.setOnMouseEntered(e -> newIcon.setIconColor(ICON_COLOR_HOVER));
        maximizeButton.setOnMouseExited(e -> newIcon.setIconColor(ICON_COLOR_DEFAULT));
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