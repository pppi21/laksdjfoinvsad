package org.nodriver4j.captcha;

import org.nodriver4j.core.Page;
import org.nodriver4j.core.Page.IframeInfo;
import org.nodriver4j.math.BoundingBox;
import org.nodriver4j.services.AutoSolveAIResponse;
import org.nodriver4j.services.AutoSolveAIService;
import org.nodriver4j.services.exceptions.AutoSolveAIException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Static utility class for solving reCAPTCHA v2 image challenges.
 *
 * <p>This solver orchestrates the complete reCAPTCHA v2 flow:</p>
 * <ol>
 *   <li>Click the "I'm not a robot" checkbox</li>
 *   <li>Wait for the image challenge to appear</li>
 *   <li>Extract the challenge description</li>
 *   <li>Screenshot the image grid</li>
 *   <li>Send to AutoSolve AI for solving</li>
 *   <li>Click the indicated tiles</li>
 *   <li>Click verify and check result</li>
 *   <li>Handle multi-round challenges and expiration</li>
 * </ol>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * AutoSolveAIService aiService = browserManager.autoSolveAIService();
 *
 * ReCaptchaSolver.SolveResult result = ReCaptchaSolver.solve(page, aiService);
 *
 * if (result.success()) {
 *     System.out.println("Captcha solved in " + result.roundsCompleted() + " rounds");
 * } else {
 *     System.out.println("Failed: " + result.failureReason());
 * }
 * }</pre>
 *
 * <h2>reCAPTCHA Structure</h2>
 * <p>reCAPTCHA v2 uses two cross-origin iframes:</p>
 * <ul>
 *   <li><strong>Checkbox iframe</strong>: Contains the "I'm not a robot" checkbox</li>
 *   <li><strong>Challenge iframe</strong>: Contains the image grid and verify button</li>
 * </ul>
 *
 * @see AutoSolveAIService
 * @see AutoSolveAIResponse
 */
public final class ReCaptchaSolver {

    // ==================== Selectors ====================

    /** Selector for checkbox iframe (first match of this selector) */
    private static final String CHECKBOX_IFRAME_SELECTOR = "iframe[title='reCAPTCHA']";

    /** Selector for challenge iframe */
    private static final String CHALLENGE_IFRAME_SELECTOR = "iframe[title='recaptcha challenge expires in two minutes']";

    /** Checkbox element inside checkbox iframe */
    private static final String CHECKBOX_SELECTOR = "#recaptcha-anchor";

    /** Image challenge container (what to screenshot) */
    private static final String CHALLENGE_IMAGE_SELECTOR = ".rc-imageselect-challenge";

    /** Challenge description text */
    private static final String DESCRIPTION_SELECTOR = "div.rc-imageselect-desc-wrapper > div > strong";

    /** Verify/submit button */
    private static final String VERIFY_BUTTON_SELECTOR = "#recaptcha-verify-button";

    /** Tile selector pattern (use String.format with tile ID 0-15) */
    private static final String TILE_SELECTOR_PATTERN = "td#%d";

    // ==================== Status Classes ====================

    /** Class present when captcha is solved */
    private static final String SOLVED_CLASS = "recaptcha-checkbox-checked";

    /** Class present when captcha has expired */
    private static final String EXPIRED_CLASS = "recaptcha-checkbox-expired";

    // ==================== Timing Constants ====================

    /** Default timeout waiting for elements (ms) */
    private static final int DEFAULT_ELEMENT_TIMEOUT_MS = 10000;

    /** Delay after clicking checkbox before checking for challenge */
    private static final int POST_CHECKBOX_DELAY_MS = 1500;

    /** Delay after clicking a tile */
    private static final int POST_TILE_CLICK_DELAY_MS = 150;

    /** Delay after clicking verify before checking status */
    private static final int POST_VERIFY_DELAY_MS = 2000;

    /** Maximum rounds (image challenges) before giving up */
    private static final int MAX_ROUNDS = 10;

    /** Maximum full attempts (from checkbox) before giving up */
    private static final int MAX_FULL_ATTEMPTS = 3;

    // ==================== Private Constructor ====================

    private ReCaptchaSolver() {
        // Static utility class - prevent instantiation
    }

    // ==================== Public API ====================

    /**
     * Attempts to solve a reCAPTCHA v2 challenge on the page.
     *
     * <p>This method handles the complete flow including:</p>
     * <ul>
     *   <li>Clicking the checkbox</li>
     *   <li>Solving multiple image challenge rounds</li>
     *   <li>Retrying if the captcha expires</li>
     * </ul>
     *
     * @param page      the Page containing the reCAPTCHA
     * @param aiService the AutoSolve AI service for solving challenges
     * @return the solve result indicating success or failure
     * @throws IllegalArgumentException if page or aiService is null
     */
    public static SolveResult solve(Page page, AutoSolveAIService aiService) {
        return solve(page, aiService, SolveOptions.defaults());
    }

    /**
     * Attempts to solve a reCAPTCHA v2 challenge with custom options.
     *
     * @param page      the Page containing the reCAPTCHA
     * @param aiService the AutoSolve AI service for solving challenges
     * @param options   custom solve options
     * @return the solve result indicating success or failure
     * @throws IllegalArgumentException if any parameter is null
     */
    public static SolveResult solve(Page page, AutoSolveAIService aiService, SolveOptions options) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        if (aiService == null) {
            throw new IllegalArgumentException("AutoSolveAIService cannot be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("SolveOptions cannot be null");
        }

        System.out.println("[ReCaptchaSolver] Starting reCAPTCHA solve attempt...");

        int fullAttempts = 0;
        int totalRounds = 0;
        List<String> warnings = new ArrayList<>();

        while (fullAttempts < options.maxFullAttempts()) {
            fullAttempts++;
            System.out.println("[ReCaptchaSolver] Full attempt " + fullAttempts + "/" + options.maxFullAttempts());

            try {
                // Step 1: Find and click the checkbox
                ClickCheckboxResult checkboxResult = clickCheckbox(page, options);

                if (checkboxResult.alreadySolved()) {
                    System.out.println("[ReCaptchaSolver] Captcha was already solved!");
                    return SolveResult.success(totalRounds, warnings);
                }

                if (checkboxResult.solvedWithoutChallenge()) {
                    System.out.println("[ReCaptchaSolver] Captcha solved without image challenge!");
                    return SolveResult.success(totalRounds, warnings);
                }

                // Step 2: Solve image challenge rounds
                int roundsThisAttempt = 0;

                while (roundsThisAttempt < options.maxRounds()) {
                    roundsThisAttempt++;
                    totalRounds++;

                    System.out.println("[ReCaptchaSolver] Image challenge round " + roundsThisAttempt);

                    RoundResult roundResult = solveOneRound(page, aiService, options);

                    if (roundResult.status() == RoundStatus.SOLVED) {
                        System.out.println("[ReCaptchaSolver] ✓ Captcha solved successfully!");
                        return SolveResult.success(totalRounds, warnings);
                    }

                    if (roundResult.status() == RoundStatus.EXPIRED) {
                        System.out.println("[ReCaptchaSolver] Captcha expired, restarting from checkbox...");
                        warnings.add("Captcha expired after " + roundsThisAttempt + " rounds");
                        break; // Break inner loop, continue outer loop
                    }

                    if (roundResult.status() == RoundStatus.ERROR) {
                        warnings.add("Round " + roundsThisAttempt + " error: " + roundResult.errorMessage());
                        // Continue trying more rounds unless we hit max
                    }

                    // Status is NEEDS_MORE_ROUNDS - continue loop
                    System.out.println("[ReCaptchaSolver] Challenge requires another round...");
                }

                // If we exit the round loop without solving, we've hit max rounds
                if (roundsThisAttempt >= options.maxRounds()) {
                    warnings.add("Hit max rounds (" + options.maxRounds() + ") in attempt " + fullAttempts);
                }

            } catch (TimeoutException e) {
                String msg = "Timeout in attempt " + fullAttempts + ": " + e.getMessage();
                System.err.println("[ReCaptchaSolver] " + msg);
                warnings.add(msg);
            } catch (Exception e) {
                String msg = "Error in attempt " + fullAttempts + ": " + e.getMessage();
                System.err.println("[ReCaptchaSolver] " + msg);
                e.printStackTrace();
                warnings.add(msg);
            }
        }

        // Exhausted all attempts
        System.err.println("[ReCaptchaSolver] ✗ Failed after " + fullAttempts + " attempts and " + totalRounds + " total rounds");
        return SolveResult.failure("Exhausted all attempts", totalRounds, warnings);
    }

    /**
     * Checks if a reCAPTCHA is present on the page.
     *
     * @param page the Page to check
     * @return true if a reCAPTCHA checkbox iframe is found
     */
    public static boolean isPresent(Page page) {
        try {
            return page.exists(CHECKBOX_IFRAME_SELECTOR);
        } catch (TimeoutException e) {
            return false;
        }
    }

    /**
     * Checks if a reCAPTCHA on the page is already solved.
     *
     * @param page the Page to check
     * @return true if the reCAPTCHA checkbox shows as solved
     */
    public static boolean isSolved(Page page) {
        try {
            IframeInfo checkboxIframe = page.getIframeInfo(CHECKBOX_IFRAME_SELECTOR, 0);
            return page.hasClassInFrame(checkboxIframe, CHECKBOX_SELECTOR, SOLVED_CLASS)
;
        } catch (TimeoutException e) {
            return false;
        }
    }

    // ==================== Internal Flow Methods ====================

    /**
     * Clicks the reCAPTCHA checkbox and determines the result.
     */
    private static ClickCheckboxResult clickCheckbox(Page page, SolveOptions options) throws TimeoutException {
        // Check if already solved
        IframeInfo checkboxIframe = page.getIframeInfo(CHECKBOX_IFRAME_SELECTOR, 0);

        if (page.hasClassInFrame(checkboxIframe, CHECKBOX_SELECTOR, SOLVED_CLASS)
) {
            return ClickCheckboxResult.ofAlreadySolved();
        }

        // Click the checkbox
        System.out.println("[ReCaptchaSolver] Clicking checkbox...");
        page.clickInFrame(checkboxIframe, CHECKBOX_SELECTOR);

        // Wait a moment for response
        page.sleep(POST_CHECKBOX_DELAY_MS);

        // Re-fetch iframe info (position may have changed)
        checkboxIframe = page.getIframeInfo(CHECKBOX_IFRAME_SELECTOR, 0);

        // Check if solved without challenge (sometimes happens)
        if (page.hasClassInFrame(checkboxIframe, CHECKBOX_SELECTOR, SOLVED_CLASS)
) {
            return ClickCheckboxResult.ofSolvedWithoutChallenge();
        }

        // Check if challenge iframe appeared
        if (!page.exists(CHALLENGE_IFRAME_SELECTOR)) {
            // Wait a bit more for challenge
            page.sleep(1000);
            if (!page.exists(CHALLENGE_IFRAME_SELECTOR)) {
                throw new TimeoutException("Challenge iframe did not appear after clicking checkbox");
            }
        }

        return ClickCheckboxResult.ofChallengeAppeared();
    }

    /**
     * Solves one round of the image challenge.
     */
    private static RoundResult solveOneRound(Page page, AutoSolveAIService aiService, SolveOptions options) {
        try {
            // Get challenge iframe
            IframeInfo challengeIframe = page.getIframeInfo(CHALLENGE_IFRAME_SELECTOR);

            // Extract description
            String description = page.getTextInFrame(challengeIframe, DESCRIPTION_SELECTOR);
            if (description == null || description.isBlank()) {
                return RoundResult.error("Could not extract challenge description");
            }
            System.out.println("[ReCaptchaSolver] Challenge description: " + description);

            byte[] imageBytes = page.screenshotElementInFrame(challengeIframe, CHALLENGE_IMAGE_SELECTOR);
            String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);

            System.out.println("[DEBUG] Image bytes length: " + imageBytes.length);
            System.out.println("[DEBUG] Base64 length: " + imageBase64.length());
            System.out.println("[DEBUG] Base64 starts with: " + imageBase64.substring(0, Math.min(50, imageBase64.length())));
            System.out.println("[DEBUG] PNG header check: " + (imageBytes[0] == (byte)0x89 && imageBytes[1] == 'P' && imageBytes[2] == 'N' && imageBytes[3] == 'G'));
            System.out.println("[ReCaptchaSolver] Captured challenge image (" + imageBytes.length + " bytes)");

            // Send to AutoSolve AI
            AutoSolveAIResponse aiResponse;
            try {
                aiResponse = aiService.solve(description, imageBase64);
            } catch (AutoSolveAIException e) {
                return RoundResult.error("AutoSolve AI error: " + e.getMessage());
            }

            if (!aiResponse.success()) {
                return RoundResult.error("AutoSolve AI failed: " + aiResponse.message());
            }

            if (!aiResponse.hasValidGrid()) {
                return RoundResult.error("AutoSolve AI returned invalid grid");
            }

            // Click the indicated tiles
            int tilesClicked = clickTiles(page, challengeIframe, aiResponse, options);
            System.out.println("[ReCaptchaSolver] Clicked " + tilesClicked + " tiles");

            // Click verify button
            System.out.println("[ReCaptchaSolver] Clicking verify button...");
            page.clickInFrame(challengeIframe, VERIFY_BUTTON_SELECTOR);

            // Wait for response
            page.sleep(POST_VERIFY_DELAY_MS);

            // Check status
            return checkSolveStatus(page);

        } catch (TimeoutException e) {
            return RoundResult.error("Timeout: " + e.getMessage());
        } catch (Exception e) {
            return RoundResult.error("Exception: " + e.getMessage());
        }
    }

    /**
     * Clicks all tiles indicated by the AI response.
     */
    private static int clickTiles(Page page, IframeInfo challengeIframe, AutoSolveAIResponse response, SolveOptions options) throws TimeoutException {
        int clickedCount = 0;

        for (int tileId = 0; tileId < 16; tileId++) {
            if (response.shouldSelectTileById(tileId)) {
                String tileSelector = String.format(TILE_SELECTOR_PATTERN, tileId);

                // Verify tile exists before clicking
                if (page.existsInFrame(challengeIframe, tileSelector)) {
                    page.clickInFrame(challengeIframe, tileSelector);
                    clickedCount++;

                    // Small delay between tile clicks
                    page.sleep(POST_TILE_CLICK_DELAY_MS);
                } else {
                    System.err.println("[ReCaptchaSolver] Warning: Tile " + tileId + " not found");
                }
            }
        }

        return clickedCount;
    }

    /**
     * Checks the captcha status after clicking verify.
     */
    private static RoundResult checkSolveStatus(Page page) throws TimeoutException {
        IframeInfo checkboxIframe = page.getIframeInfo(CHECKBOX_IFRAME_SELECTOR, 0);

        // Check if solved
        if (page.hasClassInFrame(checkboxIframe, CHECKBOX_SELECTOR, SOLVED_CLASS)
) {
            return RoundResult.solved();
        }

        // Check if expired
        if (page.hasClassInFrame(checkboxIframe, CHECKBOX_SELECTOR, EXPIRED_CLASS)) {
            return RoundResult.expired();
        }

        // Check if challenge iframe still exists (means more rounds needed)
        if (page.exists(CHALLENGE_IFRAME_SELECTOR)) {
            return RoundResult.needsMoreRounds();
        }

        // Challenge gone but not solved - unusual state
        return RoundResult.error("Challenge disappeared but captcha not solved");
    }

    // ==================== Result Types ====================

    /**
     * Result of a complete solve attempt.
     *
     * @param success         whether the captcha was solved
     * @param failureReason   reason for failure (null if success)
     * @param roundsCompleted total number of image challenge rounds attempted
     * @param warnings        any warnings encountered during solving
     */
    public record SolveResult(
            boolean success,
            String failureReason,
            int roundsCompleted,
            List<String> warnings
    ) {
        static SolveResult success(int rounds, List<String> warnings) {
            return new SolveResult(true, null, rounds, warnings);
        }

        static SolveResult failure(String reason, int rounds, List<String> warnings) {
            return new SolveResult(false, reason, rounds, warnings);
        }

        public boolean hasWarnings() {
            return warnings != null && !warnings.isEmpty();
        }
    }

    /**
     * Options for customizing solve behavior.
     */
    public record SolveOptions(
            int maxRounds,
            int maxFullAttempts,
            int elementTimeoutMs
    ) {
        public static SolveOptions defaults() {
            return new SolveOptions(MAX_ROUNDS, MAX_FULL_ATTEMPTS, DEFAULT_ELEMENT_TIMEOUT_MS);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int maxRounds = MAX_ROUNDS;
            private int maxFullAttempts = MAX_FULL_ATTEMPTS;
            private int elementTimeoutMs = DEFAULT_ELEMENT_TIMEOUT_MS;

            public Builder maxRounds(int maxRounds) {
                this.maxRounds = maxRounds;
                return this;
            }

            public Builder maxFullAttempts(int maxFullAttempts) {
                this.maxFullAttempts = maxFullAttempts;
                return this;
            }

            public Builder elementTimeoutMs(int elementTimeoutMs) {
                this.elementTimeoutMs = elementTimeoutMs;
                return this;
            }

            public SolveOptions build() {
                return new SolveOptions(maxRounds, maxFullAttempts, elementTimeoutMs);
            }
        }
    }

    // ==================== Internal Result Types ====================

    private enum RoundStatus {
        SOLVED,
        NEEDS_MORE_ROUNDS,
        EXPIRED,
        ERROR
    }

    private record RoundResult(RoundStatus status, String errorMessage) {
        static RoundResult solved() {
            return new RoundResult(RoundStatus.SOLVED, null);
        }

        static RoundResult needsMoreRounds() {
            return new RoundResult(RoundStatus.NEEDS_MORE_ROUNDS, null);
        }

        static RoundResult expired() {
            return new RoundResult(RoundStatus.EXPIRED, null);
        }

        static RoundResult error(String message) {
            return new RoundResult(RoundStatus.ERROR, message);
        }
    }

    private record ClickCheckboxResult(boolean alreadySolved, boolean solvedWithoutChallenge, boolean challengeAppeared) {
        // Factory methods use different names to avoid shadowing auto-generated accessors
        static ClickCheckboxResult ofAlreadySolved() {
            return new ClickCheckboxResult(true, false, false);
        }

        static ClickCheckboxResult ofSolvedWithoutChallenge() {
            return new ClickCheckboxResult(false, true, false);
        }

        static ClickCheckboxResult ofChallengeAppeared() {
            return new ClickCheckboxResult(false, false, true);
        }
    }
}