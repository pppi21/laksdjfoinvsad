package org.nodriver4j.ui.windows;

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
 * <h2>Aspect Ratio Enforcement</h2>
 * <p>The window's aspect ratio is locked to match the screencast output. The
 * ratio is determined by the <b>first frame</b> received via {@link #updateFrame(byte[])}.
 * During resize, listeners on the stage's width and height properties enforce
 * the ratio by adjusting the opposite dimension. A guard flag prevents recursive
 * listener invocations.</p>
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

    // ==================== Aspect Ratio State ====================

    /**
     * The locked aspect ratio (width / height) of the <b>content area</b>.
     * Set from the first received frame. Zero until the first frame arrives.
     */
    private double aspectRatio;

    /**
     * Whether the aspect ratio has been determined from the first frame.
     */
    private boolean aspectRatioLocked;

    /**
     * Horizontal inset: the difference between stage width and scene (content) width.
     * Accounts for window border decorations on the left and right.
     */
    private double insetH;

    /**
     * Vertical inset: the difference between stage height and scene (content) height.
     * Accounts for the title bar and any bottom border.
     */
    private double insetV;

    /**
     * Guard flag to prevent recursive listener invocations during resize.
     *
     * <p>When a width change triggers a height adjustment, the height listener
     * would fire and try to adjust width again. This flag breaks the cycle.</p>
     */
    private boolean adjusting;

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

        // Aspect ratio enforcement listeners
        attachResizeListeners();
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
     * it in the image view. On the first frame, the aspect ratio is locked
     * based on the image dimensions.</p>
     *
     * <p><b>Must be called on the JavaFX Application Thread.</b></p>
     *
     * @param imageBytes the raw image data (JPEG or PNG)
     */
    public void updateFrame(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return;
        }

        Image image = new Image(new ByteArrayInputStream(imageBytes));

        // Lock aspect ratio from the first valid frame
        if (!aspectRatioLocked && image.getWidth() > 0 && image.getHeight() > 0) {
            lockAspectRatio(image.getWidth(), image.getHeight());
        }

        imageView.setImage(image);
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

    // ==================== Aspect Ratio Enforcement ====================

    /**
     * Locks the aspect ratio based on the first frame's dimensions.
     *
     * <p>After this is called, all resize operations will maintain this
     * aspect ratio on the <b>content area</b>. Window decoration insets
     * (title bar, borders) are computed once and factored into all
     * subsequent resize calculations.</p>
     *
     * @param frameWidth  the frame width in pixels
     * @param frameHeight the frame height in pixels
     */
    private void lockAspectRatio(double frameWidth, double frameHeight) {
        aspectRatio = frameWidth / frameHeight;

        // Compute window decoration insets (title bar + borders).
        // These are the differences between stage size and scene (content) size.
        insetH = stage.getWidth() - stage.getScene().getWidth();
        insetV = stage.getHeight() - stage.getScene().getHeight();

        aspectRatioLocked = true;

        // Adjust minimum dimensions to respect the aspect ratio.
        // Minimums apply to content area, then add insets for stage minimums.
        double minContentW = MIN_WIDTH;
        double minContentH = MIN_WIDTH / aspectRatio;
        if (minContentH < MIN_HEIGHT) {
            minContentH = MIN_HEIGHT;
            minContentW = MIN_HEIGHT * aspectRatio;
        }
        stage.setMinWidth(minContentW + insetH);
        stage.setMinHeight(minContentH + insetV);

        // Snap current window size to the aspect ratio
        adjusting = true;
        double contentWidth = stage.getWidth() - insetH;
        stage.setHeight(contentWidth / aspectRatio + insetV);
        adjusting = false;

        System.out.println("[ViewBrowserWindow] Aspect ratio locked: " +
                String.format("%.4f", aspectRatio) +
                " (frame: " + (int) frameWidth + "×" + (int) frameHeight +
                ", insets: " + (int) insetH + "×" + (int) insetV + ")");
    }

    /**
     * Attaches resize listeners that enforce the locked aspect ratio on
     * the <b>content area</b> (excluding window decoration insets).
     *
     * <p>Two listeners are registered:</p>
     * <ul>
     *   <li><b>Width listener:</b> When stage width changes, computes the
     *       content width (stage width − horizontal inset), then sets
     *       stage height to {@code (contentWidth / aspectRatio) + verticalInset}</li>
     *   <li><b>Height listener:</b> When stage height changes, computes the
     *       content height (stage height − vertical inset), then sets
     *       stage width to {@code (contentHeight × aspectRatio) + horizontalInset}</li>
     * </ul>
     *
     * <p>The {@code adjusting} guard flag prevents recursive invocations.
     * Whichever listener fires first adjusts the other dimension, and the
     * resulting change is ignored by the second listener.</p>
     */
    private void attachResizeListeners() {
        stage.widthProperty().addListener((obs, oldVal, newVal) -> {
            if (!aspectRatioLocked || adjusting) {
                return;
            }
            adjusting = true;
            double contentWidth = newVal.doubleValue() - insetH;
            stage.setHeight(contentWidth / aspectRatio + insetV);
            adjusting = false;
        });

        stage.heightProperty().addListener((obs, oldVal, newVal) -> {
            if (!aspectRatioLocked || adjusting) {
                return;
            }
            adjusting = true;
            double contentHeight = newVal.doubleValue() - insetV;
            stage.setWidth(contentHeight * aspectRatio + insetH);
            adjusting = false;
        });
    }
}