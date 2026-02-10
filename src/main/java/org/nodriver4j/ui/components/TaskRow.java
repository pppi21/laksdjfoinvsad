package org.nodriver4j.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeBrands;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.nodriver4j.persistence.entity.TaskEntity;

import java.util.List;
import java.util.function.Consumer;

/**
 * A full-width row component that displays a single task's information
 * and action buttons.
 *
 * <p>Each row shows task info on the left (name, status, log) and
 * action buttons on the right. Button order (left to right):
 * Start/Stop, View/Manual Browser, Clone, Edit, Delete.</p>
 *
 * <h2>Button Behavior</h2>
 * <table>
 *   <tr><th>Button</th><th>Idle</th><th>Running</th><th>Finished</th></tr>
 *   <tr><td>Start/Stop</td><td>PLAY</td><td>STOP</td><td>PLAY</td></tr>
 *   <tr><td>Browser slot</td><td>CHROME (manual)</td><td>EYE (view)</td><td>CHROME (manual)</td></tr>
 *   <tr><td>Clone</td><td>enabled</td><td>enabled</td><td>enabled</td></tr>
 *   <tr><td>Edit</td><td>enabled</td><td>disabled</td><td>enabled</td></tr>
 *   <tr><td>Delete</td><td>enabled</td><td>disabled</td><td>enabled</td></tr>
 * </table>
 *
 * <p>The browser slot is a shared space: when the task is running,
 * the View Browser button (eye icon) is shown; when not running,
 * the Manual Browser button (Chrome icon) is shown instead.</p>
 *
 * <h2>Delete Confirmation</h2>
 * <p>Clicking the trash icon reveals inline "Yes" / "No" buttons.
 * The delete area has a fixed width to prevent layout shifts.</p>
 *
 * <h2>Test Mode</h2>
 * <p>When no callback is wired for a button, clicking it will
 * auto-toggle the visual state so the UI can be verified without
 * a controller. Once callbacks are set, state management is
 * deferred to the controller.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Render 6 action buttons with FontAwesome5 icons</li>
 *   <li>Toggle icons based on state (play↔stop, eye↔eye_slash, chrome↔stop)</li>
 *   <li>Inline delete confirmation flow</li>
 *   <li>Disable/hide buttons based on task status</li>
 *   <li>Hold {@code Consumer<Long>} callbacks — invoke them, define no behavior</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Task execution or browser lifecycle (controller handles via callbacks)</li>
 *   <li>Deciding when status changes (controller calls {@link #setStatus(String)})</li>
 *   <li>Database operations or dialog creation</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TaskRow row = new TaskRow(42L, "John Doe (johndoe@gmail.com)", "IDLE");
 * row.setLogText("Waiting to start...");
 *
 * // Wire callbacks (controller)
 * row.setOnStart(id -> taskService.start(id));
 * row.setOnStop(id -> taskService.stop(id));
 *
 * // Or test without callbacks — buttons auto-toggle on click
 * taskList.getChildren().add(row);
 * }</pre>
 */
public class TaskRow extends HBox {

    // ==================== Icon Colors ====================

    private static final String COLOR_START   = "#6aeb8a";
    private static final String COLOR_STOP    = "#d15252";
    private static final String COLOR_VIEW    = "#389deb";
    private static final String COLOR_MANUAL  = "#a3a3a3";
    private static final String COLOR_CLONE   = "#389deb";
    private static final String COLOR_EDIT    = "#f7c82d";
    private static final String COLOR_DELETE  = "#d15252";

    private static final int ICON_SIZE = 14;

    // ==================== Log Color Classes ====================

    private static final List<String> LOG_STYLE_CLASSES = List.of(
            TaskEntity.LOG_ERROR, TaskEntity.LOG_SUCCESS
    );



    // ==================== UI Components — Info ====================

    private final Label nameLabel;
    private final Label statusLabel;
    private final Label logLabel;
    private final Label proxyLabel;

    // ==================== UI Components — Action Buttons ====================

    private final Button startStopButton;
    private final Button viewBrowserButton;
    private final Button manualBrowserButton;
    private final StackPane browserSlot;
    private final Button cloneButton;
    private final Button editButton;
    private final Button deleteButton;
    private final HBox deleteConfirmBox;
    private final StackPane deleteSlot;

    // ==================== Icons (created once, swapped as graphics) ====================

    private final FontIcon playIcon;
    private final FontIcon stopIcon;
    private final FontIcon eyeIcon;
    private final FontIcon eyeSlashIcon;
    private final FontIcon chromeIcon;
    private final FontIcon manualStopIcon;

    // ==================== Data ====================

    private final long taskId;
    private String taskName;
    private String statusText;

    // ==================== Button State ====================

    private boolean running;
    private boolean viewBrowserActive;
    private boolean manualBrowserActive;

    // ==================== Callbacks ====================

    private Consumer<Long> onStart;
    private Consumer<Long> onStop;
    private Consumer<Long> onOpenViewBrowser;
    private Consumer<Long> onCloseViewBrowser;
    private Consumer<Long> onOpenManualBrowser;
    private Consumer<Long> onCloseManualBrowser;
    private Consumer<Long> onClone;
    private Consumer<Long> onEdit;
    private Consumer<Long> onDelete;

    // ==================== Constructor ====================

    /**
     * Creates a new TaskRow with info labels and action buttons.
     *
     * @param taskId     the database ID of the task
     * @param taskName   the display name (e.g., "John Doe (johndoe@gmail.com)")
     * @param statusText the initial status text (e.g., "IDLE", "RUNNING")
     */
    public TaskRow(long taskId, String taskName, String statusText) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.statusText = statusText;
        this.running = "RUNNING".equals(statusText);

        // Row styling — CENTER aligns children vertically
        getStyleClass().add("task-row");
        setAlignment(Pos.CENTER);
        setMaxWidth(Double.MAX_VALUE);

        // ---- Left side: info ----
        nameLabel  = createLabel(taskName, "task-row-name");
        statusLabel = createLabel(statusText, "task-row-status");
        proxyLabel = createLabel("", "task-row-proxy");
        logLabel   = createLabel("", "task-row-log");
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        logLabel.setMaxWidth(Double.MAX_VALUE);

        VBox infoBox = new VBox(4);
        infoBox.setAlignment(Pos.CENTER_LEFT);
        infoBox.getChildren().addAll(nameLabel, statusLabel, proxyLabel, logLabel);
        HBox.setHgrow(infoBox, Priority.ALWAYS);


        // ---- Create swappable icons ----
        playIcon       = createIcon(FontAwesomeSolid.PLAY,      COLOR_START);
        stopIcon       = createIcon(FontAwesomeSolid.STOP,      COLOR_STOP);
        eyeIcon        = createIcon(FontAwesomeSolid.EYE,       COLOR_VIEW);
        eyeSlashIcon   = createIcon(FontAwesomeSolid.EYE_SLASH, COLOR_VIEW);
        chromeIcon     = createIcon(FontAwesomeBrands.CHROME,    COLOR_MANUAL);
        manualStopIcon = createIcon(FontAwesomeSolid.STOP,      COLOR_STOP);

        // ---- 1. Start / Stop ----
        startStopButton = createActionButton(running ? stopIcon : playIcon);
        startStopButton.setOnAction(e -> handleStartStop());

        // ---- 2. Browser slot (shared space for view + manual) ----
        viewBrowserButton = createActionButton(eyeIcon);
        viewBrowserButton.setOnAction(e -> handleViewBrowser());

        manualBrowserButton = createActionButton(chromeIcon);
        manualBrowserButton.setOnAction(e -> handleManualBrowser());

        browserSlot = new StackPane();
        browserSlot.setMinSize(32, 32);
        browserSlot.setMaxSize(32, 32);
        browserSlot.getChildren().addAll(manualBrowserButton, viewBrowserButton);

        // ---- 3. Clone ----
        cloneButton = createActionButton(createIcon(FontAwesomeSolid.CLONE, COLOR_CLONE));
        cloneButton.setOnAction(e -> {
            System.out.println("[TaskRow] Clone clicked — Task #" + taskId);
            if (onClone != null) onClone.accept(taskId);
        });

        // ---- 4. Edit ----
        editButton = createActionButton(createIcon(FontAwesomeSolid.EDIT, COLOR_EDIT));
        editButton.setOnAction(e -> {
            System.out.println("[TaskRow] Edit clicked — Task #" + taskId);
            if (onEdit != null) onEdit.accept(taskId);
        });

        // ---- 5. Delete (with inline confirmation) ----
        deleteButton = createActionButton(createIcon(FontAwesomeSolid.TRASH_ALT, COLOR_DELETE));
        deleteButton.setOnAction(e -> showDeleteConfirmation());

        deleteConfirmBox = buildDeleteConfirmBox();

        deleteSlot = new StackPane();
        deleteSlot.setMinWidth(90);
        deleteSlot.setPrefWidth(90);
        deleteSlot.setMaxWidth(90);
        deleteSlot.setAlignment(Pos.CENTER_RIGHT);
        deleteSlot.getChildren().addAll(deleteConfirmBox, deleteButton);
        hideDeleteConfirmation();

        // ---- Assemble button bar ----
        HBox buttonBar = new HBox(8);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.getChildren().addAll(
                startStopButton,
                browserSlot,
                cloneButton,
                editButton,
                deleteSlot
        );

        // ---- Assemble row ----
        getChildren().addAll(infoBox, buttonBar);

        // Apply initial state
        applyStatusStyle(statusText);
        updateButtonStates();
    }

    // ==================== UI Factory Methods ====================

    /**
     * Creates a styled label.
     *
     * @param text       the label text
     * @param styleClass the CSS class to apply
     * @return the configured label
     */
    private Label createLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    /**
     * Creates a FontAwesome icon with a specific color.
     *
     * @param ikon     the FontAwesome enum value
     * @param hexColor the hex color string (e.g., "#6aeb8a")
     * @return the configured FontIcon
     */
    private FontIcon createIcon(Ikon ikon, String hexColor) {
        FontIcon icon = new FontIcon(ikon);
        icon.setIconSize(ICON_SIZE);
        icon.setIconColor(Color.web(hexColor));
        return icon;
    }

    /**
     * Creates a small transparent action button with an icon.
     *
     * @param icon the FontIcon to display
     * @return the configured button
     */
    private Button createActionButton(FontIcon icon) {
        Button button = new Button();
        button.setGraphic(icon);
        button.getStyleClass().add("task-row-button");
        button.setFocusTraversable(false);
        return button;
    }

    /**
     * Builds the inline delete confirmation box with Yes/No buttons.
     *
     * @return the confirmation HBox (starts hidden)
     */
    private HBox buildDeleteConfirmBox() {
        Button confirmBtn = new Button("Yes");
        confirmBtn.getStyleClass().add("confirm-delete-button");
        confirmBtn.setFocusTraversable(false);
        confirmBtn.setOnAction(e -> {
            System.out.println("[TaskRow] Delete confirmed — Task #" + taskId);
            hideDeleteConfirmation();
            if (onDelete != null) onDelete.accept(taskId);
        });

        Button cancelBtn = new Button("No");
        cancelBtn.getStyleClass().add("cancel-delete-button");
        cancelBtn.setFocusTraversable(false);
        cancelBtn.setOnAction(e -> hideDeleteConfirmation());

        HBox box = new HBox(4);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.getChildren().addAll(confirmBtn, cancelBtn);
        return box;
    }

    // ==================== Delete Confirmation Flow ====================

    /**
     * Shows the inline delete confirmation (Yes / No), hiding the trash icon.
     */
    private void showDeleteConfirmation() {
        deleteButton.setVisible(false);
        deleteButton.setManaged(false);
        deleteConfirmBox.setVisible(true);
        deleteConfirmBox.setManaged(true);
    }

    /**
     * Hides the delete confirmation and restores the trash icon.
     */
    private void hideDeleteConfirmation() {
        deleteConfirmBox.setVisible(false);
        deleteConfirmBox.setManaged(false);
        deleteButton.setVisible(true);
        deleteButton.setManaged(true);
    }

    // ==================== Button Click Handlers ====================

    /**
     * Handles Start/Stop toggle click.
     *
     * <p>When no callback is wired, auto-toggles the status between
     * RUNNING and IDLE for UI testing.</p>
     */
    private void handleStartStop() {
        if (running) {
            System.out.println("[TaskRow] Stop clicked — Task #" + taskId);
            if (onStop != null) {
                onStop.accept(taskId);
            } else {
                setStatus("IDLE");
            }
        } else {
            System.out.println("[TaskRow] Start clicked — Task #" + taskId);
            if (onStart != null) {
                onStart.accept(taskId);
            } else {
                setStatus("RUNNING");
            }
        }
    }

    /**
     * Handles View Browser toggle click (visible only while running).
     *
     * <p>Toggles between eye (open view) and eye-slash (close view).
     * Auto-toggles when no callback is wired.</p>
     */
    private void handleViewBrowser() {
        if (viewBrowserActive) {
            System.out.println("[TaskRow] Close view browser — Task #" + taskId);
            if (onCloseViewBrowser != null) {
                onCloseViewBrowser.accept(taskId);
            } else {
                setViewBrowserActive(false);
            }
        } else {
            System.out.println("[TaskRow] Open view browser — Task #" + taskId);
            if (onOpenViewBrowser != null) {
                onOpenViewBrowser.accept(taskId);
            } else {
                setViewBrowserActive(true);
            }
        }
    }

    /**
     * Handles Manual Browser toggle click (visible only while not running).
     *
     * <p>Toggles between Chrome (open browser) and stop (close browser).
     * Auto-toggles when no callback is wired.</p>
     */
    private void handleManualBrowser() {
        if (manualBrowserActive) {
            System.out.println("[TaskRow] Close manual browser — Task #" + taskId);
            if (onCloseManualBrowser != null) {
                onCloseManualBrowser.accept(taskId);
            } else {
                setManualBrowserActive(false);
            }
        } else {
            System.out.println("[TaskRow] Open manual browser — Task #" + taskId);
            if (onOpenManualBrowser != null) {
                onOpenManualBrowser.accept(taskId);
            } else {
                setManualBrowserActive(true);
            }
        }
    }

    // ==================== Status Styling ====================

    /**
     * Applies the appropriate CSS class based on the status string.
     *
     * @param status the status string
     */
    private void applyStatusStyle(String status) {
        statusLabel.getStyleClass().removeAll(
                "status-idle", "status-running", "status-completed",
                "status-failed", "status-stopped"
        );

        String styleClass = switch (status != null ? status : "") {
            case "RUNNING"   -> "status-running";
            case "COMPLETED" -> "status-completed";
            case "FAILED"    -> "status-failed";
            case "STOPPED"   -> "status-stopped";
            default          -> "status-idle";
        };

        statusLabel.getStyleClass().add(styleClass);
    }

    // ==================== Button State Management ====================

    /**
     * Updates all button states based on the current {@code running} flag.
     *
     * <p>Controls icon swaps, visibility toggling for the browser slot,
     * and disabled states for Edit and Delete.</p>
     */
    private void updateButtonStates() {
        // Start/Stop icon swap
        startStopButton.setGraphic(running ? stopIcon : playIcon);

        // Browser slot: view when running, manual when not
        viewBrowserButton.setVisible(running);
        viewBrowserButton.setManaged(running);
        manualBrowserButton.setVisible(!running);
        manualBrowserButton.setManaged(!running);

        // Refresh browser icon states
        viewBrowserButton.setGraphic(viewBrowserActive ? eyeSlashIcon : eyeIcon);
        manualBrowserButton.setGraphic(manualBrowserActive ? manualStopIcon : chromeIcon);

        // Disable edit and delete while running
        editButton.setDisable(running);
        deleteButton.setDisable(running);

        // Collapse any open delete confirmation when task starts running
        if (running) {
            hideDeleteConfirmation();
        }
    }

    // ==================== Data Updates ====================

    /**
     * Updates the task name display.
     *
     * @param name the new display name
     */
    public void setTaskName(String name) {
        this.taskName = name;
        nameLabel.setText(name);
    }

    /**
     * Updates the status display, applies styling, and refreshes button states.
     *
     * <p>Automatically resets browser states on transitions:
     * view browser resets when stopping, manual browser resets when starting.</p>
     *
     * @param status the new status string
     */
    public void setStatus(String status) {
        boolean wasRunning = this.running;
        this.statusText = status;
        this.running = "RUNNING".equals(status);

        statusLabel.setText(status);
        applyStatusStyle(status);

        // Reset browser states on transitions
        if (wasRunning && !running) {
            this.viewBrowserActive = false;
        }
        if (!wasRunning && running) {
            this.manualBrowserActive = false;
        }

        // Clear log when task returns to idle
        if ("IDLE".equals(status)) {
            clearLog();
        }

        updateButtonStates();
    }

    /**
     * Updates the log text display with the default (white) color.
     *
     * @param text the log message to display
     */
    public void setLogText(String text) {
        setLogText(text, TaskEntity.LOG_DEFAULT);
    }

    /**
     * Updates the log text display with a specific color style.
     *
     * <p>Accepts one of the {@code LOG_*} constants from {@link TaskEntity}:
     * {@link TaskEntity#LOG_DEFAULT} (white), {@link TaskEntity#LOG_ERROR} (red),
     * {@link TaskEntity#LOG_SUCCESS} (green).</p>
     *
     * @param text       the log message to display
     * @param colorClass one of LOG_DEFAULT, LOG_ERROR, LOG_SUCCESS
     */
    public void setLogText(String text, String colorClass) {
        logLabel.setText(text != null ? text : "");
        applyLogStyle(colorClass);

    }

    /**
     * Clears the log label text and resets to default styling.
     */
    public void clearLog() {
        logLabel.setText("");
        applyLogStyle(TaskEntity.LOG_DEFAULT);
    }

    /**
     * Applies a log color CSS class, removing any previous log color class.
     *
     * @param colorClass the CSS class to apply, or LOG_DEFAULT to reset
     */
    private void applyLogStyle(String colorClass) {
        logLabel.getStyleClass().removeAll(LOG_STYLE_CLASSES);
        if (colorClass != null && !TaskEntity.LOG_DEFAULT.equals(colorClass)) {
            logLabel.getStyleClass().add(colorClass);
        }
    }

    /**
     * Updates the proxy display text.
     *
     * <p>Typically shows the full proxy string (e.g., "proxy.example.com:8080:user:pass123")
     * or "No Proxy" if none is assigned. Truncated to 50 characters max.
     * Hidden when text is null or empty.</p>
     *
     * @param text the proxy display text
     */
    public void setProxyText(String text) {
        if (text == null || text.isBlank()) {
            proxyLabel.setText("");
            proxyLabel.setVisible(false);
            proxyLabel.setManaged(false);
        } else {
            String display = text.length() > 50 ? text.substring(0, 50) + "..." : text;
            proxyLabel.setText(display);
            proxyLabel.setVisible(true);
            proxyLabel.setManaged(true);
        }
    }

    /**
     * Sets the view browser active state and updates the icon.
     *
     * <p>When active, shows the eye-slash icon (close view).
     * When inactive, shows the eye icon (open view).
     * Only visually meaningful while the task is running.</p>
     *
     * @param active true if the view browser window is open
     */
    public void setViewBrowserActive(boolean active) {
        this.viewBrowserActive = active;
        viewBrowserButton.setGraphic(active ? eyeSlashIcon : eyeIcon);
    }

    /**
     * Sets the manual browser active state and updates the icon.
     *
     * <p>When active, shows the stop icon (close browser).
     * When inactive, shows the Chrome icon (open browser).
     * Only visually meaningful while the task is not running.</p>
     *
     * @param active true if the manual browser is open
     */
    public void setManualBrowserActive(boolean active) {
        this.manualBrowserActive = active;
        manualBrowserButton.setGraphic(active ? manualStopIcon : chromeIcon);
    }

    // ==================== Callback Setters ====================

    /**
     * Sets the callback invoked when the Start button is clicked.
     *
     * @param callback receives the task ID
     */
    public void setOnStart(Consumer<Long> callback) {
        this.onStart = callback;
    }

    /**
     * Sets the callback invoked when the Stop button is clicked.
     *
     * @param callback receives the task ID
     */
    public void setOnStop(Consumer<Long> callback) {
        this.onStop = callback;
    }

    /**
     * Sets the callback invoked when the View Browser button is clicked
     * to open the view window.
     *
     * @param callback receives the task ID
     */
    public void setOnOpenViewBrowser(Consumer<Long> callback) {
        this.onOpenViewBrowser = callback;
    }

    /**
     * Sets the callback invoked when the View Browser button is clicked
     * to close the view window.
     *
     * @param callback receives the task ID
     */
    public void setOnCloseViewBrowser(Consumer<Long> callback) {
        this.onCloseViewBrowser = callback;
    }

    /**
     * Sets the callback invoked when the Manual Browser button is clicked
     * to open a headed browser session.
     *
     * @param callback receives the task ID
     */
    public void setOnOpenManualBrowser(Consumer<Long> callback) {
        this.onOpenManualBrowser = callback;
    }

    /**
     * Sets the callback invoked when the Manual Browser button is clicked
     * to close the headed browser session.
     *
     * @param callback receives the task ID
     */
    public void setOnCloseManualBrowser(Consumer<Long> callback) {
        this.onCloseManualBrowser = callback;
    }

    /**
     * Sets the callback invoked when the Clone button is clicked.
     *
     * @param callback receives the task ID
     */
    public void setOnClone(Consumer<Long> callback) {
        this.onClone = callback;
    }

    /**
     * Sets the callback invoked when the Edit button is clicked.
     *
     * @param callback receives the task ID
     */
    public void setOnEdit(Consumer<Long> callback) {
        this.onEdit = callback;
    }

    /**
     * Sets the callback invoked when deletion is confirmed via the
     * inline Yes/No prompt.
     *
     * @param callback receives the task ID
     */
    public void setOnDelete(Consumer<Long> callback) {
        this.onDelete = callback;
    }

    // ==================== Getters ====================

    /**
     * Gets the database ID of the task.
     *
     * @return the task ID
     */
    public long taskId() {
        return taskId;
    }

    /**
     * Gets the display name of the task.
     *
     * @return the task name
     */
    public String taskName() {
        return taskName;
    }

    /**
     * Gets the current status text.
     *
     * @return the status text
     */
    public String statusText() {
        return statusText;
    }

    /**
     * Checks if the task is currently in the RUNNING state.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Checks if the view browser window is currently active.
     *
     * @return true if the view browser is open
     */
    public boolean isViewBrowserActive() {
        return viewBrowserActive;
    }

    /**
     * Checks if the manual browser is currently active.
     *
     * @return true if the manual browser is open
     */
    public boolean isManualBrowserActive() {
        return manualBrowserActive;
    }
}