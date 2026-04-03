package org.nodriver4j.math;

/**
 * Signals the semantic context of a user action so the framework can apply
 * context-appropriate timing, precision, and hesitation.
 *
 * <p>Script authors pass an intent to click methods to influence movement behavior
 * without manually tuning timing parameters. The framework does NOT attempt to infer
 * intent from selectors — that would be fragile and inaccurate.</p>
 *
 * <p>Each intent carries multipliers that consuming components ({@link ClickBehavior},
 * {@link SubMovementPlanner}, {@link VelocityProfile}, {@link ApproachHesitation})
 * query and apply to their respective parameters.</p>
 *
 * <p>Usage in scripts:</p>
 * <pre>{@code
 * page.click("#add-to-cart", ActionIntent.CONFIRM);
 * page.click("#next-page", ActionIntent.NAVIGATE);
 * page.click(".date-picker-day", ActionIntent.PRECISE);
 * page.click("#hamburger-menu", ActionIntent.CASUAL);
 *
 * // Default when omitted — equivalent to CASUAL
 * page.click("#some-button");
 * }</pre>
 */
public enum ActionIntent {

    /**
     * Default intent, equivalent to {@link #CASUAL}. Standard persona timing,
     * no special modifications. Used when no intent is specified.
     */
    DEFAULT(1.0, 1.0, 1.0, 0, 1.0, 0, 0),

    /**
     * Confirming a significant action (form submit, purchase, delete).
     * Adds 200–800ms pre-click hesitation simulating review/confirmation.
     * Slightly slower final approach.
     */
    CONFIRM(1.10, 1.0, 1.0, 0, 1.0, 200, 800),

    /**
     * Navigating between pages or sections (next page, menu link, tab switch).
     * Minimal hesitation (0–50ms), confident faster movement, lower correction
     * probability.
     */
    NAVIGATE(0.85, 0.5, 1.0, 0, 0.0, 0, 50),

    /**
     * Casual interaction (opening menus, toggling options, dismissing dialogs).
     * Standard persona timing with natural variance. No special modifications.
     */
    CASUAL(1.0, 1.0, 1.0, 0, 1.0, 0, 0),

    /**
     * Precise interaction (small targets, sliders, date pickers, color pickers).
     * 25% slower movement, 1.5× correction probability, tighter click positioning,
     * extra micro-adjustments in approach phase.
     */
    PRECISE(1.25, 1.5, 0.6, 2, 1.0, 0, 0);

    private final double movementDurationMultiplier;
    private final double correctionProbabilityMultiplier;
    private final double clickScatterMultiplier;
    private final int extraApproachAdjustments;
    private final double hesitationMultiplier;
    private final double additionalHesitationMinMs;
    private final double additionalHesitationMaxMs;

    ActionIntent(double movementDurationMultiplier,
                 double correctionProbabilityMultiplier,
                 double clickScatterMultiplier,
                 int extraApproachAdjustments,
                 double hesitationMultiplier,
                 double additionalHesitationMinMs,
                 double additionalHesitationMaxMs) {
        this.movementDurationMultiplier = movementDurationMultiplier;
        this.correctionProbabilityMultiplier = correctionProbabilityMultiplier;
        this.clickScatterMultiplier = clickScatterMultiplier;
        this.extraApproachAdjustments = extraApproachAdjustments;
        this.hesitationMultiplier = hesitationMultiplier;
        this.additionalHesitationMinMs = additionalHesitationMinMs;
        this.additionalHesitationMaxMs = additionalHesitationMaxMs;
    }

    // ==================== Movement ====================

    /**
     * Multiplier on total movement duration.
     * Applied to Fitts's Law MT before passing to {@link VelocityProfile}.
     *
     * <p>Values &gt; 1.0 produce slower movements (more time).
     * Values &lt; 1.0 produce faster movements (less time).</p>
     *
     * @return the movement duration multiplier
     */
    public double movementDurationMultiplier() {
        return movementDurationMultiplier;
    }

    // ==================== Corrections ====================

    /**
     * Multiplier on sub-movement correction probability.
     * Applied to the sigmoid output in {@link SubMovementPlanner}.
     *
     * <p>Values &gt; 1.0 make corrections more likely.
     * Values &lt; 1.0 make corrections less likely.</p>
     *
     * @return the correction probability multiplier
     */
    public double correctionProbabilityMultiplier() {
        return correctionProbabilityMultiplier;
    }

    // ==================== Click Precision ====================

    /**
     * Multiplier on click position scatter SD.
     * Applied in {@link ClickBehavior} to the Gaussian endpoint distribution.
     *
     * <p>Values &lt; 1.0 produce tighter (more precise) click positioning.
     * Values &gt; 1.0 produce looser positioning.</p>
     *
     * @return the click scatter multiplier
     */
    public double clickScatterMultiplier() {
        return clickScatterMultiplier;
    }

    // ==================== Approach ====================

    /**
     * Number of extra perpendicular micro-adjustments to add in the approach phase.
     * Applied in {@link ApproachHesitation} on top of the persona's base count.
     *
     * @return the number of extra approach adjustments (0 for most intents)
     */
    public int extraApproachAdjustments() {
        return extraApproachAdjustments;
    }

    // ==================== Hesitation ====================

    /**
     * Multiplier on the persona's base pre-click hesitation.
     *
     * <p>Set to 0.0 for {@link #NAVIGATE} to suppress persona hesitation entirely
     * (replaced by the intent's own additional hesitation range).</p>
     *
     * @return the hesitation multiplier (0.0–1.0)
     */
    public double hesitationMultiplier() {
        return hesitationMultiplier;
    }

    /**
     * Minimum additional hesitation in milliseconds, added on top of the
     * persona-scaled base hesitation.
     *
     * <p>For {@link #CONFIRM}: 200ms (simulating review before committing).
     * For {@link #NAVIGATE}: 0ms (with base suppressed, total range is 0–50ms).</p>
     *
     * @return the minimum additional hesitation
     */
    public double additionalHesitationMinMs() {
        return additionalHesitationMinMs;
    }

    /**
     * Maximum additional hesitation in milliseconds.
     *
     * <p>For {@link #CONFIRM}: 800ms.
     * For {@link #NAVIGATE}: 50ms.</p>
     *
     * @return the maximum additional hesitation
     */
    public double additionalHesitationMaxMs() {
        return additionalHesitationMaxMs;
    }
}
