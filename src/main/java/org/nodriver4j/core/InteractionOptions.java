package org.nodriver4j.core;

import org.nodriver4j.math.HumanBehavior;

/**
 * Configuration options for human-like browser interactions.
 *
 * <p>This class provides fine-grained control over timing, movement patterns,
 * and behavior for all automated interactions. Default values are based on
 * human behavior research and anti-bot evasion best practices.</p>
 *
 * <p>Instances are immutable and created via the {@link Builder} pattern:</p>
 * <pre>{@code
 * InteractionOptions options = InteractionOptions.builder()
 *     .moveSpeed(50)
 *     .clickHoldDurationMin(80)
 *     .clickHoldDurationMax(150)
 *     .build();
 * }</pre>
 *
 * <p>Use {@link #defaults()} for standard human-like behavior:</p>
 * <pre>{@code
 * InteractionOptions options = InteractionOptions.defaults();
 * }</pre>
 */
public final class InteractionOptions {

    // ==================== Mouse Movement ====================

    private final boolean simulateMousePath;
    private final int moveSpeed;
    private final boolean overshootEnabled;
    private final double overshootThreshold;
    private final double overshootRadius;
    private final boolean jitterEnabled;
    private final double jitterAmount;

    // ==================== Click ====================

    private final int preClickDelayMin;
    private final int preClickDelayMax;
    private final int clickHoldDurationMin;
    private final int clickHoldDurationMax;
    private final int positionOffsetMax;
    private final double paddingPercentage;

    // ==================== Typing ====================

    private final int keystrokeDelayMin;
    private final int keystrokeDelayMax;
    private final boolean contextAwareTyping;
    private final double thinkingPauseProbability;
    private final int thinkingPauseMin;
    private final int thinkingPauseMax;

    // ==================== Scrolling ====================

    private final int scrollTickPixels;
    private final int scrollTickVariance;
    private final int scrollDelayMin;
    private final int scrollDelayMax;
    private final int scrollSpeed;

    // ==================== Waiting ====================

    private final int defaultTimeout;
    private final int retryInterval;
    private final int maxRetries;

    // ==================== Move Delay ====================

    private final int moveDelayMin;
    private final int moveDelayMax;
    private final boolean randomizeMoveDelay;

    private InteractionOptions(Builder builder) {
        // Mouse Movement
        this.simulateMousePath = builder.simulateMousePath;
        this.moveSpeed = builder.moveSpeed;
        this.overshootEnabled = builder.overshootEnabled;
        this.overshootThreshold = builder.overshootThreshold;
        this.overshootRadius = builder.overshootRadius;
        this.jitterEnabled = builder.jitterEnabled;
        this.jitterAmount = builder.jitterAmount;

        // Click
        this.preClickDelayMin = builder.preClickDelayMin;
        this.preClickDelayMax = builder.preClickDelayMax;
        this.clickHoldDurationMin = builder.clickHoldDurationMin;
        this.clickHoldDurationMax = builder.clickHoldDurationMax;
        this.positionOffsetMax = builder.positionOffsetMax;
        this.paddingPercentage = builder.paddingPercentage;

        // Typing
        this.keystrokeDelayMin = builder.keystrokeDelayMin;
        this.keystrokeDelayMax = builder.keystrokeDelayMax;
        this.contextAwareTyping = builder.contextAwareTyping;
        this.thinkingPauseProbability = builder.thinkingPauseProbability;
        this.thinkingPauseMin = builder.thinkingPauseMin;
        this.thinkingPauseMax = builder.thinkingPauseMax;

        // Scrolling
        this.scrollTickPixels = builder.scrollTickPixels;
        this.scrollTickVariance = builder.scrollTickVariance;
        this.scrollDelayMin = builder.scrollDelayMin;
        this.scrollDelayMax = builder.scrollDelayMax;
        this.scrollSpeed = builder.scrollSpeed;

        // Waiting
        this.defaultTimeout = builder.defaultTimeout;
        this.retryInterval = builder.retryInterval;
        this.maxRetries = builder.maxRetries;

        // Move Delay
        this.moveDelayMin = builder.moveDelayMin;
        this.moveDelayMax = builder.moveDelayMax;
        this.randomizeMoveDelay = builder.randomizeMoveDelay;
    }

    /**
     * Creates a new builder for InteractionOptions.
     *
     * @return a new Builder instance with default values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns InteractionOptions with default human-like settings.
     *
     * @return default InteractionOptions
     */
    public static InteractionOptions defaults() {
        return new Builder().build();
    }

    /**
     * Returns InteractionOptions optimized for speed (less human-like).
     *
     * <p>Use this for testing or when stealth is not required.</p>
     *
     * @return fast InteractionOptions
     */
    public static InteractionOptions fast() {
        return builder()
                .simulateMousePath(false)
                .overshootEnabled(false)
                .jitterEnabled(false)
                .preClickDelayMin(0)
                .preClickDelayMax(10)
                .clickHoldDurationMin(10)
                .clickHoldDurationMax(30)
                .keystrokeDelayMin(5)
                .keystrokeDelayMax(20)
                .contextAwareTyping(false)
                .thinkingPauseProbability(0)
                .scrollSpeed(100)
                .scrollDelayMin(0)
                .scrollDelayMax(50)
                .moveDelayMin(0)
                .moveDelayMax(50)
                .build();
    }

    /**
     * Creates a builder initialized with this instance's values.
     * Useful for creating modified copies.
     *
     * @return a Builder with current values
     */
    public Builder toBuilder() {
        return new Builder()
                // Mouse Movement
                .simulateMousePath(simulateMousePath)
                .moveSpeed(moveSpeed)
                .overshootEnabled(overshootEnabled)
                .overshootThreshold(overshootThreshold)
                .overshootRadius(overshootRadius)
                .jitterEnabled(jitterEnabled)
                .jitterAmount(jitterAmount)
                // Click
                .preClickDelayMin(preClickDelayMin)
                .preClickDelayMax(preClickDelayMax)
                .clickHoldDurationMin(clickHoldDurationMin)
                .clickHoldDurationMax(clickHoldDurationMax)
                .positionOffsetMax(positionOffsetMax)
                .paddingPercentage(paddingPercentage)
                // Typing
                .keystrokeDelayMin(keystrokeDelayMin)
                .keystrokeDelayMax(keystrokeDelayMax)
                .contextAwareTyping(contextAwareTyping)
                .thinkingPauseProbability(thinkingPauseProbability)
                .thinkingPauseMin(thinkingPauseMin)
                .thinkingPauseMax(thinkingPauseMax)
                // Scrolling
                .scrollTickPixels(scrollTickPixels)
                .scrollTickVariance(scrollTickVariance)
                .scrollDelayMin(scrollDelayMin)
                .scrollDelayMax(scrollDelayMax)
                .scrollSpeed(scrollSpeed)
                // Waiting
                .defaultTimeout(defaultTimeout)
                .retryInterval(retryInterval)
                .maxRetries(maxRetries)
                // Move Delay
                .moveDelayMin(moveDelayMin)
                .moveDelayMax(moveDelayMax)
                .randomizeMoveDelay(randomizeMoveDelay);
    }

    // ==================== Mouse Movement Getters ====================

    /**
     * Whether to simulate realistic mouse path movement using Bezier curves.
     * If false, mouse moves directly to target (faster but detectable).
     *
     * @return true if mouse path simulation is enabled
     */
    public boolean isSimulateMousePath() {
        return simulateMousePath;
    }

    /**
     * Mouse movement speed (1-100).
     * Higher values = faster movement, fewer path points.
     * 0 means random speed.
     *
     * @return the movement speed
     */
    public int getMoveSpeed() {
        return moveSpeed;
    }

    /**
     * Whether mouse should overshoot the target and correct.
     * Mimics natural human behavior for distant targets.
     *
     * @return true if overshoot is enabled
     */
    public boolean isOvershootEnabled() {
        return overshootEnabled;
    }

    /**
     * Minimum distance (pixels) required for overshoot to occur.
     *
     * @return the overshoot threshold in pixels
     */
    public double getOvershootThreshold() {
        return overshootThreshold;
    }

    /**
     * Maximum radius (pixels) for overshoot deviation from target.
     *
     * @return the overshoot radius in pixels
     */
    public double getOvershootRadius() {
        return overshootRadius;
    }

    /**
     * Whether to add micro-jitter to mouse movements.
     * Simulates natural hand tremor.
     *
     * @return true if jitter is enabled
     */
    public boolean isJitterEnabled() {
        return jitterEnabled;
    }

    /**
     * Maximum jitter deviation in pixels.
     *
     * @return the jitter amount in pixels
     */
    public double getJitterAmount() {
        return jitterAmount;
    }

    // ==================== Click Getters ====================

    /**
     * Minimum delay before clicking after reaching target (ms).
     *
     * @return minimum pre-click delay
     */
    public int getPreClickDelayMin() {
        return preClickDelayMin;
    }

    /**
     * Maximum delay before clicking after reaching target (ms).
     *
     * @return maximum pre-click delay
     */
    public int getPreClickDelayMax() {
        return preClickDelayMax;
    }

    /**
     * Minimum duration between mousedown and mouseup (ms).
     *
     * @return minimum click hold duration
     */
    public int getClickHoldDurationMin() {
        return clickHoldDurationMin;
    }

    /**
     * Maximum duration between mousedown and mouseup (ms).
     *
     * @return maximum click hold duration
     */
    public int getClickHoldDurationMax() {
        return clickHoldDurationMax;
    }

    /**
     * Maximum random offset from element center for click position (pixels).
     *
     * @return maximum position offset
     */
    public int getPositionOffsetMax() {
        return positionOffsetMax;
    }

    /**
     * Padding percentage for random click position within element.
     * 0 = anywhere in element, 100 = exact center.
     *
     * @return the padding percentage
     */
    public double getPaddingPercentage() {
        return paddingPercentage;
    }

    // ==================== Typing Getters ====================

    /**
     * Minimum delay between keystrokes (ms).
     *
     * @return minimum keystroke delay
     */
    public int getKeystrokeDelayMin() {
        return keystrokeDelayMin;
    }

    /**
     * Maximum delay between keystrokes (ms).
     *
     * @return maximum keystroke delay
     */
    public int getKeystrokeDelayMax() {
        return keystrokeDelayMax;
    }

    /**
     * Whether to adjust typing speed based on character context.
     * Common bigrams are typed faster, pauses after punctuation.
     *
     * @return true if context-aware typing is enabled
     */
    public boolean isContextAwareTyping() {
        return contextAwareTyping;
    }

    /**
     * Probability (0-1) that a thinking pause occurs while typing.
     *
     * @return thinking pause probability
     */
    public double getThinkingPauseProbability() {
        return thinkingPauseProbability;
    }

    /**
     * Minimum duration for thinking pauses (ms).
     *
     * @return minimum thinking pause duration
     */
    public int getThinkingPauseMin() {
        return thinkingPauseMin;
    }

    /**
     * Maximum duration for thinking pauses (ms).
     *
     * @return maximum thinking pause duration
     */
    public int getThinkingPauseMax() {
        return thinkingPauseMax;
    }

    // ==================== Scrolling Getters ====================

    /**
     * Base pixels scrolled per wheel tick.
     *
     * @return scroll tick pixels
     */
    public int getScrollTickPixels() {
        return scrollTickPixels;
    }

    /**
     * Random variance added to scroll tick amount (pixels).
     *
     * @return scroll tick variance
     */
    public int getScrollTickVariance() {
        return scrollTickVariance;
    }

    /**
     * Minimum delay between scroll ticks (ms).
     *
     * @return minimum scroll delay
     */
    public int getScrollDelayMin() {
        return scrollDelayMin;
    }

    /**
     * Maximum delay between scroll ticks (ms).
     *
     * @return maximum scroll delay
     */
    public int getScrollDelayMax() {
        return scrollDelayMax;
    }

    /**
     * Scroll speed (1-100). Higher = faster scrolling.
     * 100 = instant scroll.
     *
     * @return scroll speed
     */
    public int getScrollSpeed() {
        return scrollSpeed;
    }

    // ==================== Waiting Getters ====================

    /**
     * Default timeout for wait operations (ms).
     *
     * @return default timeout
     */
    public int getDefaultTimeout() {
        return defaultTimeout;
    }

    /**
     * Interval between retry attempts (ms).
     *
     * @return retry interval
     */
    public int getRetryInterval() {
        return retryInterval;
    }

    /**
     * Maximum number of retry attempts.
     *
     * @return max retries
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    // ==================== Move Delay Getters ====================

    /**
     * Minimum delay after moving the mouse (ms).
     *
     * @return minimum move delay
     */
    public int getMoveDelayMin() {
        return moveDelayMin;
    }

    /**
     * Maximum delay after moving the mouse (ms).
     *
     * @return maximum move delay
     */
    public int getMoveDelayMax() {
        return moveDelayMax;
    }

    /**
     * Whether to randomize move delay between min and max.
     * If false, always uses max delay.
     *
     * @return true if move delay is randomized
     */
    public boolean isRandomizeMoveDelay() {
        return randomizeMoveDelay;
    }

    // ==================== Builder ====================

    /**
     * Builder for creating InteractionOptions instances.
     *
     * <p>All values have sensible defaults based on human behavior patterns.</p>
     */
    public static class Builder {

        // Mouse Movement - defaults
        private boolean simulateMousePath = true;
        private int moveSpeed = 0; // 0 = random
        private boolean overshootEnabled = true;
        private double overshootThreshold = HumanBehavior.DEFAULT_OVERSHOOT_THRESHOLD;
        private double overshootRadius = HumanBehavior.DEFAULT_OVERSHOOT_RADIUS;
        private boolean jitterEnabled = true;
        private double jitterAmount = 2.0;

        // Click - defaults based on human studies
        private int preClickDelayMin = 50;
        private int preClickDelayMax = 200;
        private int clickHoldDurationMin = 50;
        private int clickHoldDurationMax = 150;
        private int positionOffsetMax = 5;
        private double paddingPercentage = 20;

        // Typing - defaults based on average typing speed
        private int keystrokeDelayMin = 50;
        private int keystrokeDelayMax = 180;
        private boolean contextAwareTyping = true;
        private double thinkingPauseProbability = 0.02; // 2% chance per keystroke
        private int thinkingPauseMin = 300;
        private int thinkingPauseMax = 800;

        // Scrolling - defaults based on mouse wheel behavior
        private int scrollTickPixels = 110;
        private int scrollTickVariance = 20;
        private int scrollDelayMin = 30;
        private int scrollDelayMax = 100;
        private int scrollSpeed = 50;

        // Waiting - defaults
        private int defaultTimeout = 30000; // 30 seconds
        private int retryInterval = 500;
        private int maxRetries = 1;

        // Move Delay - defaults
        private int moveDelayMin = 0;
        private int moveDelayMax = 100;
        private boolean randomizeMoveDelay = true;

        private Builder() {}

        // ==================== Mouse Movement ====================

        /**
         * Sets whether to simulate realistic mouse paths.
         *
         * @param simulateMousePath true to enable path simulation
         * @return this builder
         */
        public Builder simulateMousePath(boolean simulateMousePath) {
            this.simulateMousePath = simulateMousePath;
            return this;
        }

        /**
         * Sets the mouse movement speed (1-100, 0 for random).
         *
         * @param moveSpeed the movement speed
         * @return this builder
         */
        public Builder moveSpeed(int moveSpeed) {
            this.moveSpeed = moveSpeed;
            return this;
        }

        /**
         * Sets whether overshoot is enabled.
         *
         * @param overshootEnabled true to enable overshoot
         * @return this builder
         */
        public Builder overshootEnabled(boolean overshootEnabled) {
            this.overshootEnabled = overshootEnabled;
            return this;
        }

        /**
         * Sets the minimum distance for overshoot to occur.
         *
         * @param overshootThreshold threshold in pixels
         * @return this builder
         */
        public Builder overshootThreshold(double overshootThreshold) {
            this.overshootThreshold = overshootThreshold;
            return this;
        }

        /**
         * Sets the maximum overshoot radius.
         *
         * @param overshootRadius radius in pixels
         * @return this builder
         */
        public Builder overshootRadius(double overshootRadius) {
            this.overshootRadius = overshootRadius;
            return this;
        }

        /**
         * Sets whether jitter is enabled.
         *
         * @param jitterEnabled true to enable jitter
         * @return this builder
         */
        public Builder jitterEnabled(boolean jitterEnabled) {
            this.jitterEnabled = jitterEnabled;
            return this;
        }

        /**
         * Sets the maximum jitter amount.
         *
         * @param jitterAmount jitter in pixels
         * @return this builder
         */
        public Builder jitterAmount(double jitterAmount) {
            this.jitterAmount = jitterAmount;
            return this;
        }

        // ==================== Click ====================

        /**
         * Sets the minimum pre-click delay.
         *
         * @param preClickDelayMin delay in milliseconds
         * @return this builder
         */
        public Builder preClickDelayMin(int preClickDelayMin) {
            this.preClickDelayMin = preClickDelayMin;
            return this;
        }

        /**
         * Sets the maximum pre-click delay.
         *
         * @param preClickDelayMax delay in milliseconds
         * @return this builder
         */
        public Builder preClickDelayMax(int preClickDelayMax) {
            this.preClickDelayMax = preClickDelayMax;
            return this;
        }

        /**
         * Sets the minimum click hold duration.
         *
         * @param clickHoldDurationMin duration in milliseconds
         * @return this builder
         */
        public Builder clickHoldDurationMin(int clickHoldDurationMin) {
            this.clickHoldDurationMin = clickHoldDurationMin;
            return this;
        }

        /**
         * Sets the maximum click hold duration.
         *
         * @param clickHoldDurationMax duration in milliseconds
         * @return this builder
         */
        public Builder clickHoldDurationMax(int clickHoldDurationMax) {
            this.clickHoldDurationMax = clickHoldDurationMax;
            return this;
        }

        /**
         * Sets the maximum click position offset from center.
         *
         * @param positionOffsetMax offset in pixels
         * @return this builder
         */
        public Builder positionOffsetMax(int positionOffsetMax) {
            this.positionOffsetMax = positionOffsetMax;
            return this;
        }

        /**
         * Sets the padding percentage for click position.
         *
         * @param paddingPercentage percentage (0-100)
         * @return this builder
         */
        public Builder paddingPercentage(double paddingPercentage) {
            this.paddingPercentage = paddingPercentage;
            return this;
        }

        // ==================== Typing ====================

        /**
         * Sets the minimum keystroke delay.
         *
         * @param keystrokeDelayMin delay in milliseconds
         * @return this builder
         */
        public Builder keystrokeDelayMin(int keystrokeDelayMin) {
            this.keystrokeDelayMin = keystrokeDelayMin;
            return this;
        }

        /**
         * Sets the maximum keystroke delay.
         *
         * @param keystrokeDelayMax delay in milliseconds
         * @return this builder
         */
        public Builder keystrokeDelayMax(int keystrokeDelayMax) {
            this.keystrokeDelayMax = keystrokeDelayMax;
            return this;
        }

        /**
         * Sets whether context-aware typing is enabled.
         *
         * @param contextAwareTyping true to enable
         * @return this builder
         */
        public Builder contextAwareTyping(boolean contextAwareTyping) {
            this.contextAwareTyping = contextAwareTyping;
            return this;
        }

        /**
         * Sets the probability of thinking pauses.
         *
         * @param thinkingPauseProbability probability (0-1)
         * @return this builder
         */
        public Builder thinkingPauseProbability(double thinkingPauseProbability) {
            this.thinkingPauseProbability = thinkingPauseProbability;
            return this;
        }

        /**
         * Sets the minimum thinking pause duration.
         *
         * @param thinkingPauseMin duration in milliseconds
         * @return this builder
         */
        public Builder thinkingPauseMin(int thinkingPauseMin) {
            this.thinkingPauseMin = thinkingPauseMin;
            return this;
        }

        /**
         * Sets the maximum thinking pause duration.
         *
         * @param thinkingPauseMax duration in milliseconds
         * @return this builder
         */
        public Builder thinkingPauseMax(int thinkingPauseMax) {
            this.thinkingPauseMax = thinkingPauseMax;
            return this;
        }

        // ==================== Scrolling ====================

        /**
         * Sets the base scroll tick pixels.
         *
         * @param scrollTickPixels pixels per tick
         * @return this builder
         */
        public Builder scrollTickPixels(int scrollTickPixels) {
            this.scrollTickPixels = scrollTickPixels;
            return this;
        }

        /**
         * Sets the scroll tick variance.
         *
         * @param scrollTickVariance variance in pixels
         * @return this builder
         */
        public Builder scrollTickVariance(int scrollTickVariance) {
            this.scrollTickVariance = scrollTickVariance;
            return this;
        }

        /**
         * Sets the minimum scroll delay.
         *
         * @param scrollDelayMin delay in milliseconds
         * @return this builder
         */
        public Builder scrollDelayMin(int scrollDelayMin) {
            this.scrollDelayMin = scrollDelayMin;
            return this;
        }

        /**
         * Sets the maximum scroll delay.
         *
         * @param scrollDelayMax delay in milliseconds
         * @return this builder
         */
        public Builder scrollDelayMax(int scrollDelayMax) {
            this.scrollDelayMax = scrollDelayMax;
            return this;
        }

        /**
         * Sets the scroll speed (1-100).
         *
         * @param scrollSpeed speed value
         * @return this builder
         */
        public Builder scrollSpeed(int scrollSpeed) {
            this.scrollSpeed = scrollSpeed;
            return this;
        }

        // ==================== Waiting ====================

        /**
         * Sets the default timeout for wait operations.
         *
         * @param defaultTimeout timeout in milliseconds
         * @return this builder
         */
        public Builder defaultTimeout(int defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
            return this;
        }

        /**
         * Sets the retry interval.
         *
         * @param retryInterval interval in milliseconds
         * @return this builder
         */
        public Builder retryInterval(int retryInterval) {
            this.retryInterval = retryInterval;
            return this;
        }

        /**
         * Sets the maximum number of retries.
         *
         * @param maxRetries number of retries
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        // ==================== Move Delay ====================

        /**
         * Sets the minimum move delay.
         *
         * @param moveDelayMin delay in milliseconds
         * @return this builder
         */
        public Builder moveDelayMin(int moveDelayMin) {
            this.moveDelayMin = moveDelayMin;
            return this;
        }

        /**
         * Sets the maximum move delay.
         *
         * @param moveDelayMax delay in milliseconds
         * @return this builder
         */
        public Builder moveDelayMax(int moveDelayMax) {
            this.moveDelayMax = moveDelayMax;
            return this;
        }

        /**
         * Sets whether move delay is randomized.
         *
         * @param randomizeMoveDelay true to randomize
         * @return this builder
         */
        public Builder randomizeMoveDelay(boolean randomizeMoveDelay) {
            this.randomizeMoveDelay = randomizeMoveDelay;
            return this;
        }

        /**
         * Builds the InteractionOptions instance.
         *
         * @return a new InteractionOptions
         */
        public InteractionOptions build() {
            validate();
            return new InteractionOptions(this);
        }

        private void validate() {
            if (preClickDelayMin > preClickDelayMax) {
                throw new IllegalArgumentException("preClickDelayMin cannot be greater than preClickDelayMax");
            }
            if (clickHoldDurationMin > clickHoldDurationMax) {
                throw new IllegalArgumentException("clickHoldDurationMin cannot be greater than clickHoldDurationMax");
            }
            if (keystrokeDelayMin > keystrokeDelayMax) {
                throw new IllegalArgumentException("keystrokeDelayMin cannot be greater than keystrokeDelayMax");
            }
            if (thinkingPauseMin > thinkingPauseMax) {
                throw new IllegalArgumentException("thinkingPauseMin cannot be greater than thinkingPauseMax");
            }
            if (scrollDelayMin > scrollDelayMax) {
                throw new IllegalArgumentException("scrollDelayMin cannot be greater than scrollDelayMax");
            }
            if (moveDelayMin > moveDelayMax) {
                throw new IllegalArgumentException("moveDelayMin cannot be greater than moveDelayMax");
            }
            if (thinkingPauseProbability < 0 || thinkingPauseProbability > 1) {
                throw new IllegalArgumentException("thinkingPauseProbability must be between 0 and 1");
            }
            if (paddingPercentage < 0 || paddingPercentage > 100) {
                throw new IllegalArgumentException("paddingPercentage must be between 0 and 100");
            }
            if (scrollSpeed < 1 || scrollSpeed > 100) {
                throw new IllegalArgumentException("scrollSpeed must be between 1 and 100");
            }
        }
    }

    @Override
    public String toString() {
        return String.format(
                "InteractionOptions{mouseSpeed=%d, overshoot=%s, jitter=%s, click=[%d-%d], typing=[%d-%d], scroll=%d}",
                moveSpeed,
                overshootEnabled ? "on" : "off",
                jitterEnabled ? "on" : "off",
                clickHoldDurationMin, clickHoldDurationMax,
                keystrokeDelayMin, keystrokeDelayMax,
                scrollSpeed
        );
    }
}