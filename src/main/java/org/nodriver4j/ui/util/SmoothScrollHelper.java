package org.nodriver4j.ui.util;

import javafx.animation.AnimationTimer;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.input.ScrollEvent;

import java.util.function.DoubleConsumer;

/**
 * Utility class that replaces JavaFX's default single-frame scroll jumps
 * with smooth animated scrolling using framerate-independent exponential
 * interpolation.
 *
 * <p>Works by intercepting scroll events during the capturing phase via
 * event filters, computing a target scroll position, and using an
 * {@link AnimationTimer} to smoothly chase the target over several frames.
 * The result is a brief, natural slide that completes in roughly 80–130ms
 * at 60fps — fast enough to feel instant but slow enough that the eye
 * can track the content movement.</p>
 *
 * <h2>How It Works</h2>
 * <p>Two values are maintained per control: a <em>target</em> position and
 * a <em>current</em> position. When a scroll event arrives, the target jumps
 * immediately. Each frame, the current position closes a fraction of the
 * remaining gap to the target (exponential decay). When the gap drops below
 * epsilon, the timer stops.</p>
 *
 * <h2>Framerate Independence</h2>
 * <p>The lerp factor is normalized so that it represents "proportion to
 * close per frame at 60fps" regardless of actual framerate:</p>
 * <pre>{@code
 * frameFactor = 1.0 - Math.pow(1.0 - lerpFactor, deltaSeconds * 60)
 * }</pre>
 *
 * <h2>Scroll Limit Passthrough</h2>
 * <p>When a control is already scrolled to its limit and the user scrolls
 * further in the same direction, the event is NOT consumed. This allows
 * it to bubble up to a parent ScrollPane, enabling natural nested
 * scrolling behavior (e.g. a ListView inside a ScrollPane).</p>
 *
 * <h2>TextArea Awareness</h2>
 * <p>ScrollPane filters detect when the scroll target is inside a
 * {@link TextInputControl}. If the text control has scrollable content,
 * the event is passed through so the TextArea handles it. If the text
 * control has no scrollable content (everything fits), the event is
 * handled by the parent ScrollPane instead.</p>
 *
 * <h2>Speed Multiplier</h2>
 * <p>Each {@code apply()} overload accepts an optional {@code speedMultiplier}
 * that scales how far each scroll wheel increment moves the content.
 * Default is {@code 1.0}. Values like {@code 1.5} or {@code 2.0} make
 * scrolling cover more distance per wheel notch.</p>
 *
 * <h2>Supported Controls</h2>
 * <ul>
 *   <li>{@link ScrollPane} — drives {@code vvalueProperty()} directly</li>
 *   <li>{@link ListView} — drives internal vertical {@link ScrollBar}</li>
 *   <li>{@link TextArea} — drives internal vertical {@link ScrollBar}</li>
 *   <li>{@link ComboBox} — applies to popup {@link ListView} when shown</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SmoothScrollHelper.apply(myScrollPane);                  // defaults
 * SmoothScrollHelper.apply(myScrollPane, 0.4, 1.5);       // faster scroll
 * SmoothScrollHelper.apply(myTextArea, 0.4, 2.0);         // TextArea 2x speed
 * SmoothScrollHelper.apply(myListView);                    // defaults
 * SmoothScrollHelper.apply(myComboBox);                    // defaults
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Intercept scroll events and consume native single-frame jumps</li>
 *   <li>Maintain per-control target/current animation state</li>
 *   <li>Run framerate-independent AnimationTimer for interpolation</li>
 *   <li>Pass through scroll events at limits for nested scrolling</li>
 *   <li>Detect scrollable text inputs and yield control appropriately</li>
 *   <li>Apply per-control speed multipliers to scroll delta</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Deciding which controls to apply to (callers do that)</li>
 *   <li>Horizontal scrolling</li>
 *   <li>Momentum/inertial scrolling or shaped bezier easing</li>
 * </ul>
 *
 * @see WindowResizeHelper
 */
public final class SmoothScrollHelper {

    // ==================== Constants ====================

    /**
     * Default lerp factor: proportion of the remaining gap to close per
     * frame at 60fps. 0.4 completes the scroll in roughly 5–8 frames
     * (80–130ms), which is fast but visually trackable.
     */
    private static final double DEFAULT_LERP_FACTOR = 0.3;

    /**
     * Default speed multiplier: no scaling applied to scroll delta.
     */
    private static final double DEFAULT_SPEED_MULTIPLIER = 1.3;

    /**
     * Threshold below which the animation snaps to target and stops.
     */
    private static final double EPSILON = 0.0001;

    /**
     * Approximate pixel delta for one mouse wheel notch. Used to
     * normalize deltaY into discrete "notch" units when computing
     * scroll bar shifts.
     */
    private static final double PIXELS_PER_NOTCH = 40.0;

    // ==================== Animation State ====================

    /** Where the scroll should end up. */
    private double targetValue;

    /** Where the scroll visually is right now. */
    private double currentValue;

    /** Lerp aggression factor (normalized to 60fps). */
    private final double lerpFactor;

    /** Nanosecond timestamp of the previous frame, or 0 if not yet started. */
    private long lastNanos;

    /** Whether the animation timer is currently running. */
    private boolean active;

    /** The timer that drives per-frame interpolation. */
    private final AnimationTimer timer;

    /** Callback to apply the interpolated value to the control. */
    private final DoubleConsumer valueSetter;

    // ==================== Constructor ====================

    private SmoothScrollHelper(double lerpFactor, DoubleConsumer valueSetter) {
        this.lerpFactor = lerpFactor;
        this.valueSetter = valueSetter;
        this.timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                onFrame(now);
            }
        };
    }

    // ==================== Public API — ScrollPane ====================

    /**
     * Applies smooth scrolling to a {@link ScrollPane} with default settings.
     *
     * @param scrollPane the scroll pane to enhance
     */
    public static void apply(ScrollPane scrollPane) {
        apply(scrollPane, DEFAULT_LERP_FACTOR, DEFAULT_SPEED_MULTIPLIER);
    }

    /**
     * Applies smooth scrolling to a {@link ScrollPane} with custom lerp
     * factor and speed multiplier.
     *
     * @param scrollPane      the scroll pane to enhance
     * @param lerpFactor      proportion of gap to close per frame at 60fps (0.3–0.5 recommended)
     * @param speedMultiplier scales scroll distance per wheel notch (1.0 = default)
     */
    public static void apply(ScrollPane scrollPane, double lerpFactor, double speedMultiplier) {
        SmoothScrollHelper helper = new SmoothScrollHelper(lerpFactor, scrollPane::setVvalue);

        scrollPane.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() == 0) {
                return;
            }

            // Let nested scrollable controls handle their own scrolling
            if (isInsideNestedScrollable(event.getTarget(), scrollPane)) {
                return;
            }

            Node content = scrollPane.getContent();
            if (content == null) {
                return;
            }

            double contentHeight = content.getBoundsInLocal().getHeight();
            double viewportHeight = scrollPane.getViewportBounds().getHeight();
            double scrollableRange = contentHeight - viewportHeight;
            if (scrollableRange <= 0) {
                return;
            }

            // Pass through at scroll limits for nested scrolling
            boolean scrollingUp = event.getDeltaY() > 0;
            boolean scrollingDown = event.getDeltaY() < 0;
            if ((scrollingUp && helper.targetValue <= scrollPane.getVmin())
                    || (scrollingDown && helper.targetValue >= scrollPane.getVmax())) {
                return;
            }

            event.consume();

            double shift = (-event.getDeltaY() / scrollableRange) * speedMultiplier;
            double newTarget = clamp(
                    helper.targetValue + shift,
                    scrollPane.getVmin(),
                    scrollPane.getVmax()
            );

            helper.scrollTo(newTarget, scrollPane.getVvalue());
        });

        // Keep in sync when vvalue changes externally (programmatic scroll, layout)
        scrollPane.vvalueProperty().addListener((obs, oldVal, newVal) ->
                helper.syncPosition(newVal.doubleValue()));
    }

    // ==================== Public API — ListView ====================

    /**
     * Applies smooth scrolling to a {@link ListView} with default settings.
     *
     * @param listView the list view to enhance
     */
    public static void apply(ListView<?> listView) {
        apply(listView, DEFAULT_LERP_FACTOR, DEFAULT_SPEED_MULTIPLIER);
    }

    /**
     * Applies smooth scrolling to a {@link ListView} with custom lerp
     * factor and speed multiplier.
     *
     * @param listView        the list view to enhance
     * @param lerpFactor      proportion of gap to close per frame at 60fps (0.3–0.5 recommended)
     * @param speedMultiplier scales scroll distance per wheel notch (1.0 = default)
     */
    public static void apply(ListView<?> listView, double lerpFactor, double speedMultiplier) {
        attachToScrollBarControl(listView, lerpFactor, speedMultiplier);
    }

    // ==================== Public API — TextArea ====================

    /**
     * Applies smooth scrolling to a {@link TextArea} with default settings.
     *
     * @param textArea the text area to enhance
     */
    public static void apply(TextArea textArea) {
        apply(textArea, DEFAULT_LERP_FACTOR, DEFAULT_SPEED_MULTIPLIER);
    }

    /**
     * Applies smooth scrolling to a {@link TextArea} with custom lerp
     * factor and speed multiplier.
     *
     * @param textArea        the text area to enhance
     * @param lerpFactor      proportion of gap to close per frame at 60fps (0.3–0.5 recommended)
     * @param speedMultiplier scales scroll distance per wheel notch (1.0 = default)
     */
    public static void apply(TextArea textArea, double lerpFactor, double speedMultiplier) {
        attachToScrollBarControl(textArea, lerpFactor, speedMultiplier);
    }

    // ==================== Public API — ComboBox ====================

    /**
     * Applies smooth scrolling to a {@link ComboBox}'s popup list
     * with default settings.
     *
     * @param comboBox the combo box to enhance
     */
    public static void apply(ComboBox<?> comboBox) {
        apply(comboBox, DEFAULT_LERP_FACTOR, DEFAULT_SPEED_MULTIPLIER);
    }

    /**
     * Applies smooth scrolling to a {@link ComboBox}'s popup list
     * with custom lerp factor and speed multiplier.
     *
     * @param comboBox        the combo box to enhance
     * @param lerpFactor      proportion of gap to close per frame at 60fps (0.3–0.5 recommended)
     * @param speedMultiplier scales scroll distance per wheel notch (1.0 = default)
     */
    public static void apply(ComboBox<?> comboBox, double lerpFactor, double speedMultiplier) {
        comboBox.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin instanceof ComboBoxListViewSkin<?> skin) {
                Node popupContent = skin.getPopupContent();
                if (popupContent instanceof ListView<?> popupListView) {
                    attachToScrollBarControl(popupListView, lerpFactor, speedMultiplier);
                }
            }
        });

        // Handle case where skin is already applied
        if (comboBox.getSkin() instanceof ComboBoxListViewSkin<?> skin) {
            Node popupContent = skin.getPopupContent();
            if (popupContent instanceof ListView<?> popupListView) {
                attachToScrollBarControl(popupListView, lerpFactor, speedMultiplier);
            }
        }
    }

    // ==================== Internal — ScrollBar-Based Controls ====================

    /**
     * Attaches smooth scrolling to any {@link Control} that contains an
     * internal vertical {@link ScrollBar} (ListView, TextArea, etc.).
     *
     * <p>The ScrollBar is resolved lazily: on the first scroll event, a
     * CSS lookup is performed. If the skin hasn't been applied yet
     * (ScrollBar not found), the event passes through unmodified so
     * native scrolling still works until the helper can take over.</p>
     *
     * @param control         the control to enhance
     * @param lerpFactor      proportion of gap to close per frame at 60fps
     * @param speedMultiplier scales scroll distance per wheel notch
     */
    private static void attachToScrollBarControl(Control control, double lerpFactor,
                                                 double speedMultiplier) {
        // Holders for lazy initialization — populated on first successful lookup
        final ScrollBar[] barHolder = {null};
        final SmoothScrollHelper[] helperHolder = {null};

        control.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() == 0) {
                return;
            }

            // Lazy ScrollBar resolution
            if (barHolder[0] == null) {
                barHolder[0] = findVerticalScrollBar(control);
                if (barHolder[0] == null) {
                    return; // Skin not ready — let native handling work
                }

                SmoothScrollHelper helper = new SmoothScrollHelper(lerpFactor, barHolder[0]::setValue);
                helperHolder[0] = helper;

                // Sync when scroll bar value changes externally
                barHolder[0].valueProperty().addListener((obs, oldVal, newVal) ->
                        helper.syncPosition(newVal.doubleValue()));
            }

            ScrollBar bar = barHolder[0];
            SmoothScrollHelper helper = helperHolder[0];

            double range = bar.getMax() - bar.getMin();
            if (range <= 0) {
                return;
            }

            // Pass through at scroll limits for nested scrolling
            boolean scrollingUp = event.getDeltaY() > 0;
            boolean scrollingDown = event.getDeltaY() < 0;
            if ((scrollingUp && helper.targetValue <= bar.getMin())
                    || (scrollingDown && helper.targetValue >= bar.getMax())) {
                return;
            }

            event.consume();

            // Convert pixel delta to scroll bar value units.
            // One wheel notch (~40px of deltaY) scrolls by one unitIncrement.
            double notches = -event.getDeltaY() / PIXELS_PER_NOTCH;
            double shift = notches * bar.getUnitIncrement() * speedMultiplier;
            double newTarget = clamp(helper.targetValue + shift, bar.getMin(), bar.getMax());

            helper.scrollTo(newTarget, bar.getValue());
        });
    }

    // ==================== Internal — TextInput Detection ====================

    /**
     * Checks whether the event target is inside a nested scrollable control
     * that should handle its own scroll events.
     *
     * <p>Walks up the scene graph from the target node. If a
     * {@link TextInputControl}, {@link ListView}, or another
     * {@link ScrollPane} is found before reaching the owning ScrollPane,
     * returns {@code true} so the owner's filter yields control to the
     * nested scrollable.</p>
     *
     * @param target the event target (may be any Object from the scene graph)
     * @param owner  the ScrollPane whose filter is performing the check
     * @return {@code true} if the target is inside a nested scrollable control
     */
    private static boolean isInsideNestedScrollable(Object target, ScrollPane owner) {
        if (!(target instanceof Node node)) {
            return false;
        }

        while (node != null) {
            if (node == owner) {
                return false; // Reached the owner without finding a nested scrollable
            }
            if (node instanceof TextInputControl
                    || node instanceof ListView<?>
                    || node instanceof ScrollPane) {
                return true;
            }
            node = node.getParent();
        }

        return false;
    }

    /**
     * Finds the vertical {@link ScrollBar} inside a control's skin
     * via CSS lookup.
     *
     * @param control the control to search
     * @return the vertical ScrollBar, or {@code null} if not found
     */
    private static ScrollBar findVerticalScrollBar(Control control) {
        for (Node node : control.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                return bar;
            }
        }
        return null;
    }

    // ==================== Animation Core ====================

    /**
     * Sets a new target and starts the animation if not already running.
     *
     * @param newTarget  the desired scroll position
     * @param currentPos the control's current scroll position (used to
     *                   initialize the animation if it's not already active)
     */
    private void scrollTo(double newTarget, double currentPos) {
        targetValue = newTarget;
        if (!active) {
            currentValue = currentPos;
            lastNanos = 0;
            active = true;
            timer.start();
        }
    }

    /**
     * Synchronizes internal state with an externally-changed scroll position.
     *
     * <p>Only takes effect when the animation is NOT running, so that
     * programmatic or layout-driven scroll changes don't fight the
     * animation. When the animation IS running, external changes are
     * ignored because the helper is actively driving the value.</p>
     *
     * @param pos the new external scroll position
     */
    private void syncPosition(double pos) {
        if (!active) {
            currentValue = pos;
            targetValue = pos;
        }
    }

    /**
     * Called once per frame by the {@link AnimationTimer}.
     *
     * <p>Computes framerate-independent interpolation and applies the
     * result to the control. Stops the timer when the current position
     * is within epsilon of the target.</p>
     *
     * @param now the current frame timestamp in nanoseconds
     */
    private void onFrame(long now) {
        if (lastNanos == 0) {
            // First frame after start — just record the timestamp
            lastNanos = now;
            return;
        }

        double deltaSeconds = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;

        // Framerate-independent lerp: normalize factor to 60fps behavior
        double frameFactor = 1.0 - Math.pow(1.0 - lerpFactor, deltaSeconds * 60.0);
        currentValue += (targetValue - currentValue) * frameFactor;

        // Snap and stop when close enough
        if (Math.abs(targetValue - currentValue) < EPSILON) {
            currentValue = targetValue;
            valueSetter.accept(currentValue);
            active = false;
            timer.stop();
            return;
        }

        valueSetter.accept(currentValue);
    }

    // ==================== Utility ====================

    /**
     * Clamps a value to the given range.
     *
     * @param value the value to clamp
     * @param min   the minimum bound
     * @param max   the maximum bound
     * @return the clamped value
     */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}