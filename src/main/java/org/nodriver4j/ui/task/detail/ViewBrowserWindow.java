package org.nodriver4j.ui.task.detail;

import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;

/**
 * A read-only window that displays live screencast frames from a headless browser.
 *
 * <p>This window acts as a visual monitoring/debugging tool. It shows what the
 * automation script is doing in the headless browser without making the browser
 * headed. The user cannot interact with the page — they can only observe and
 * close the window.</p>
 *
 * <h2>Resizing</h2>
 * <p>The window uses standard OS resize behavior. The {@link ImageView} has
 * {@code preserveRatio} enabled, so the displayed image scales to fit the
 * window without distortion. Black bars appear if the window proportions
 * differ from the frame proportions.</p>
 *
 * <h2>Window Behavior</h2>
 * <ul>
 *   <li>Uses standard OS window decorations (title bar, resize handles)</li>
 *   <li>Fully resizable with locked aspect ratio</li>
 *   <li>Does NOT stay on top of the main window</li>
 *   <li>Multiple instances can be open simultaneously (one per running task)</li>
 *   <li>Closing the window fires the {@code onClose} callback</li>
 *   <li>Black background until the first frame arrives</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>{@link #updateFrame(byte[])} must be called on the JavaFX Application Thread.
 * The caller (typically the controller) is responsible for marshalling via
 * {@code Platform.runLater()}.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ViewBrowserWindow window = new ViewBrowserWindow("Task #42 — View Browser");
 * window.setOnClose(() -> {
 *     screencastService.stop();
 *     // cleanup...
 * });
 * window.show();
 *
 * // On each frame (called from controller via Platform.runLater):
 * window.updateFrame(jpegBytes);
 *
 * // To close programmatically:
 * window.close();
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display screencast frames in an ImageView</li>
 *   <li>Convert raw {@code byte[]} to JavaFX {@link Image}</li>
 *   <li>Scale the displayed image to fill the window</li>
 *   <li>Enforce aspect-ratio-locked resizing</li>
 *   <li>Fire a close callback when the window is closed</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>CDP communication (delegated to {@code ScreencastService})</li>
 *   <li>Task tracking or browser lifecycle (delegated to {@code TaskExecutionService})</li>
 *   <li>Starting or stopping the screencast session (delegated to controller)</li>
 *   <li>Thread marshalling to the FX thread (caller's responsibility)</li>
 * </ul>
 */
public class ViewBrowserWindow {

    // ==================== Layout Constants ====================

    /** Default window width on first open, before any frames arrive. */
    private static final double DEFAULT_WIDTH = 960;

    /** Default window height on first open, before any frames arrive. */
    private static final double DEFAULT_HEIGHT = 540;

    /** Minimum window width to prevent the window from becoming unusably small. */
    private static final double MIN_WIDTH = 480;

    /** Minimum window height to prevent the window from becoming unusably small. */
    private static final double MIN_HEIGHT = 270;

    // ==================== UI Components ====================

    private final Stage stage;
    private final ImageView imageView;

    // ==================== Callbacks ====================

    private Runnable onClose;

    // ==================== Constructor ====================

    /**
     * Creates a new view browser window with the given title.
     *
     * <p>The window is created but not shown. Call {@link #show()} to display it.
     * The window opens at a default size of 960×540 with a black background.
     * Aspect ratio locking activates once the first frame is received.</p>
     *
     * @param title the window title (e.g., "Task #42 — View Browser")
     */
    public ViewBrowserWindow(String title) {
        // Image view — scales to fill the window
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        // Root container — centers the image view and provides black background
        StackPane root = new StackPane(imageView);
        root.setBackground(Background.fill(Color.BLACK));

        // Scene
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.setFill(Color.BLACK);

        // Bind image view size to scene size so it scales with the window
        imageView.fitWidthProperty().bind(scene.widthProperty());
        imageView.fitHeightProperty().bind(scene.heightProperty());

        // Stage — uses standard OS decorations
        stage = new Stage();
        stage.setTitle(title);
        stage.setScene(scene);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);

        // Close handler — fire callback when user closes via title bar X
        stage.setOnCloseRequest(event -> {
            if (onClose != null) {
                onClose.run();
            }
        });
    }

    // ==================== Public API ====================

    /**
     * Shows the window.
     */
    public void show() {
        stage.show();
    }

    /**
     * Closes the window programmatically.
     *
     * <p>This does NOT fire the {@code onClose} callback, since programmatic
     * close is initiated by the controller which handles its own cleanup.
     * Only user-initiated close (via the title bar X) fires the callback.</p>
     */
    public void close() {
        // Clear the close handler to prevent it from firing during stage.close()
        stage.setOnCloseRequest(null);
        stage.close();
    }

    /**
     * Updates the displayed frame.
     *
     * <p>Converts the raw image bytes to a JavaFX {@link Image} and displays
     * it in the image view. The image scales to fit the window while
     * preserving its aspect ratio (via {@code ImageView.preserveRatio}).</p>
     *
     * <p><b>Must be called on the JavaFX Application Thread.</b></p>
     *
     * @param imageBytes the raw image data (JPEG or PNG)
     */
    public void updateFrame(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return;
        }
        imageView.setImage(new Image(new ByteArrayInputStream(imageBytes)));
    }

    /**
     * Sets the callback invoked when the user closes the window via the
     * title bar X button.
     *
     * <p>This callback is NOT fired when {@link #close()} is called
     * programmatically — only on user-initiated close.</p>
     *
     * @param onClose the close callback
     */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /**
     * Checks if the window is currently showing.
     *
     * @return true if the window is visible
     */
    public boolean isShowing() {
        return stage.isShowing();
    }

}