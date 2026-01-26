package org.nodriver4j.captcha;

import com.google.gson.JsonObject;
import org.nodriver4j.core.Page;
import org.nodriver4j.core.Page.IframeInfo;
import org.nodriver4j.services.AutoSolveAIResponse;
import org.nodriver4j.services.AutoSolveAIService;
import org.nodriver4j.services.exceptions.AutoSolveAIException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Static utility class for solving reCAPTCHA v2 image challenges.
 *
 * <p>This solver orchestrates the complete reCAPTCHA v2 flow:</p>
 * <ol>
 *   <li>Click the "I'm not a robot" checkbox</li>
 *   <li>Wait for the image challenge to appear</li>
 *   <li>Extract the challenge description</li>
 *   <li>Detect grid size (3x3 or 4x4)</li>
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
 * @see GridSize
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
    private static final String TILE_SELECTOR_PATTERN = "td#%s";

    /** Selector for counting tiles to detect grid size */
    private static final String TILE_COUNT_SELECTOR = "#rc-imageselect-target > table > tbody td";

    // Add this constant near the other selectors
    /** Selector for the challenge image element (any tile's image works - they all share the same src) */
    private static final String CHALLENGE_IMAGE_ELEMENT_SELECTOR = "#rc-imageselect-target img";

    /** Selector pattern for individual tile image (use String.format with tile ID) */
    private static final String TILE_IMAGE_SELECTOR_PATTERN = "td#%s > div > div > img";

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

    /** Maximum iterations for fade-away tile processing */
    private static final int MAX_FADE_AWAY_ITERATIONS = 20;

    /** Buffer time after transition completes before fetching replacement image */
    private static final int FADE_AWAY_BUFFER_MS = 1000;

    /** Delay after clicking a replacement tile before checking for new fade */
    private static final int POST_REPLACEMENT_CLICK_DELAY_MS = 200;

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
            return page.hasClassInFrame(checkboxIframe, CHECKBOX_SELECTOR, SOLVED_CLASS);
        } catch (TimeoutException e) {
            return false;
        }
    }

    // ==================== Grid Detection ====================

    /**
     * Detects the grid size of the current reCAPTCHA challenge.
     *
     * <p>This method counts the number of td elements in the challenge grid table
     * to determine whether it's a 3x3 (9 tiles) or 4x4 (16 tiles) challenge.</p>
     *
     * @param page           the Page containing the reCAPTCHA
     * @param challengeIframe the challenge iframe info
     * @return the detected GridSize
     * @throws TimeoutException if the detection script fails
     * @throws IllegalArgumentException if the tile count is not 9 or 16
     */
    private static GridSize detectGridSize(Page page, IframeInfo challengeIframe) throws TimeoutException {
        String script = String.format(
                "document.querySelectorAll(\"%s\").length",
                TILE_COUNT_SELECTOR.replace("\"", "\\\"")
        );

        String result = page.evaluateInFrame(challengeIframe, script);

        if (result == null || result.isBlank()) {
            throw new TimeoutException("Failed to detect grid size: no result from tile count query");
        }

        int tileCount;
        try {
            tileCount = Integer.parseInt(result.trim());
        } catch (NumberFormatException e) {
            throw new TimeoutException("Failed to detect grid size: invalid tile count '" + result + "'");
        }

        GridSize gridSize = GridSize.fromTileCount(tileCount);
        System.out.println("[ReCaptchaSolver] Detected grid size: " + gridSize);

        return gridSize;
    }

    // ==================== Internal Flow Methods ====================

    /**
     * Clicks the reCAPTCHA checkbox and determines the result.
     */
    private static ClickCheckboxResult clickCheckbox(Page page, SolveOptions options) throws TimeoutException {
        // Check if already solved
        IframeInfo checkboxIframe = page.getIframeInfo(CHECKBOX_IFRAME_SELECTOR, 0);

        // Force the iframe into view and wait
        page.scrollIntoView(CHECKBOX_IFRAME_SELECTOR);
        page.sleep(500);

// Try to "touch" the iframe via CDP to force attachment
        try {
            JsonObject params = new JsonObject();
            params.addProperty("frameId", checkboxIframe.frameId());
            page.cdpClient().send("Page.getFrameTree", null); // Force frame enumeration
        } catch (Exception e) {
            // Ignore
        }
        page.sleep(200);

        if (page.hasClassInFrame(checkboxIframe, CHECKBOX_SELECTOR, SOLVED_CLASS)) {
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
        if (page.hasClassInFrame(checkboxIframe, CHECKBOX_SELECTOR, SOLVED_CLASS)) {
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

            // Detect grid size before proceeding
            GridSize gridSize = detectGridSize(page, challengeIframe);

            // Extract description
            String description = page.getTextInFrame(challengeIframe, DESCRIPTION_SELECTOR);
            if (description == null || description.isBlank()) {
                return RoundResult.error("Could not extract challenge description");
            }
            System.out.println("[ReCaptchaSolver] Challenge description: " + description);

            // Fetch the challenge image at native resolution
            Page.ImageData imageData;
            try {
                imageData = page.fetchImageInFrame(challengeIframe, CHALLENGE_IMAGE_ELEMENT_SELECTOR);
            } catch (TimeoutException e) {
                return RoundResult.error("Failed to fetch challenge image: " + e.getMessage());
            }

            // Validate image dimensions match expected grid size
            int expectedSize = gridSize.expectedImageSize();
            if (!imageData.hasExpectedSize(expectedSize)) {
                return RoundResult.error(String.format(
                        "Image dimensions mismatch: expected %dx%d for %s grid, got %s",
                        expectedSize, expectedSize, gridSize, imageData.dimensionsString()));
            }

            System.out.println("[ReCaptchaSolver] Fetched challenge image: " + imageData.dimensionsString() +
                    " (" + imageData.mimeType() + ", " + imageData.base64().length() + " chars base64)");

            // Send to AutoSolve AI
            AutoSolveAIResponse aiResponse;
            try {
                aiResponse = aiService.solve(description, imageData.base64());
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
            List<Integer> clickedTileIds = clickTiles(page, challengeIframe, aiResponse, gridSize, options);
            System.out.println("[ReCaptchaSolver] Clicked " + clickedTileIds.size() + " tiles");

            // Check for fade-away captcha (only applies to 3x3 grids)
            if (gridSize == GridSize.THREE_BY_THREE && !clickedTileIds.isEmpty()) {
                // Small delay to let styles apply
                page.sleep(200);

                // Re-fetch iframe info in case position changed
                challengeIframe = page.getIframeInfo(CHALLENGE_IFRAME_SELECTOR);

                if (isFadeAwayCaptcha(page, challengeIframe, clickedTileIds)) {
                    handleFadeAwayTiles(page, challengeIframe, clickedTileIds, description, aiService);
                }
            }

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
     *
     * @return list of tile IDs that were clicked
     */
    private static List<Integer> clickTiles(Page page, IframeInfo challengeIframe, AutoSolveAIResponse response,
                                            GridSize gridSize, SolveOptions options) throws TimeoutException {
        List<Integer> clickedTileIds = new ArrayList<>();
        int totalTiles = gridSize.tileCount();

        for (int tileId = 0; tileId < totalTiles; tileId++) {
            if (response.shouldSelectTileById(tileId, gridSize)) {
                String tileSelector = String.format(TILE_SELECTOR_PATTERN, escapeForCss(tileId));

                // Verify tile exists before clicking
                if (page.existsInFrame(challengeIframe, tileSelector)) {
                    page.clickInFrame(challengeIframe, tileSelector);
                    clickedTileIds.add(tileId);

                    // Small delay between tile clicks
                    page.sleep(POST_TILE_CLICK_DELAY_MS);
                } else {
                    System.err.println("[ReCaptchaSolver] Warning: Tile " + tileId + " not found");
                }
            }
        }

        return clickedTileIds;
    }

    /**
     * Checks if any of the clicked tiles have a fade-away transition style.
     *
     * @param page            the Page
     * @param challengeIframe the challenge iframe
     * @param clickedTileIds  list of tile IDs that were clicked
     * @return true if at least one tile has a transition style
     */
    private static boolean isFadeAwayCaptcha(Page page, IframeInfo challengeIframe,
                                             List<Integer> clickedTileIds) throws TimeoutException {
        for (int tileId : clickedTileIds) {
            String style = getTileStyle(page, challengeIframe, tileId);
            if (style != null && style.contains("transition")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the style attribute of a tile element.
     *
     * @param page            the Page
     * @param challengeIframe the challenge iframe
     * @param tileId          the tile ID
     * @return the style attribute value, or null if not present
     */
    private static String getTileStyle(Page page, IframeInfo challengeIframe, int tileId) throws TimeoutException {
        String tileSelector = String.format(TILE_SELECTOR_PATTERN, escapeForCss(tileId));
        String escapedSelector = tileSelector.replace("\\", "\\\\").replace("\"", "\\\"");
        String script = String.format(
                "(function() {" +
                        "  var el = document.querySelector(\"%s\");" +
                        "  return el ? el.getAttribute('style') : null;" +
                        "})()",
                escapedSelector
        );
        return page.evaluateInFrame(challengeIframe, script);
    }

    /**
     * Parses the transition duration from a style attribute.
     *
     * <p>Looks for patterns like:</p>
     * <ul>
     *   <li>"transition: opacity 4s;" → 4000ms</li>
     *   <li>"transition: opacity 500ms;" → 500ms</li>
     *   <li>"opacity 3.5s ease" → 3500ms</li>
     * </ul>
     *
     * @param style the style attribute value
     * @return duration in milliseconds, or -1 if not found
     */
    private static long parseTransitionDuration(String style) {
        if (style == null || style.isBlank()) {
            return -1;
        }

        // Pattern matches "opacity Xs" or "opacity Xms" where X is a number (possibly decimal)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "opacity\\s+([\\d.]+)(s|ms)",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(style);

        if (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2).toLowerCase();
                return (long) (unit.equals("s") ? value * 1000 : value);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        return -1;
    }

    /**
     * Fetches the replacement image for a single tile.
     *
     * @param page            the Page
     * @param challengeIframe the challenge iframe
     * @param tileId          the tile ID
     * @return ImageData for the 100x100 replacement tile
     * @throws TimeoutException if the image cannot be fetched
     */
    private static Page.ImageData fetchReplacementTileImage(Page page, IframeInfo challengeIframe,
                                                            int tileId) throws TimeoutException {
        String imgSelector = String.format(TILE_IMAGE_SELECTOR_PATTERN, escapeForCss(tileId));
        return page.fetchImageInFrame(challengeIframe, imgSelector);
    }

    /**
     * Handles fade-away tile processing for reCAPTCHA challenges.
     *
     * <p>This method processes tiles that fade away and are replaced with new images.
     * Each replacement tile is sent individually to the AI service for classification.</p>
     *
     * @param page              the Page
     * @param challengeIframe   the challenge iframe
     * @param initialClickedIds list of initially clicked tile IDs
     * @param description       the challenge description (e.g., "Select all images with traffic lights")
     * @param aiService         the AutoSolve AI service
     * @return true if processing completed successfully (regardless of clicks made)
     */
    private static boolean handleFadeAwayTiles(Page page, IframeInfo challengeIframe,
                                               List<Integer> initialClickedIds, String description,
                                               AutoSolveAIService aiService) {
        System.out.println("[ReCaptchaSolver] Detected fade-away captcha, processing replacement tiles...");

        java.util.PriorityQueue<PendingTile> pendingQueue = new java.util.PriorityQueue<>();
        int totalIterations = 0;

        try {
            // Initialize queue with initially clicked tiles that have transition styles
            long now = System.currentTimeMillis();
            for (int tileId : initialClickedIds) {
                String style = getTileStyle(page, challengeIframe, tileId);
                long duration = parseTransitionDuration(style);

                if (duration > 0) {
                    long readyAt = now + duration + FADE_AWAY_BUFFER_MS;
                    pendingQueue.add(new PendingTile(tileId, readyAt));
                    System.out.println("[ReCaptchaSolver] Tile " + tileId + " fading, ready in " +
                            (duration + FADE_AWAY_BUFFER_MS) + "ms");
                }
            }

            // Process tiles as they become ready
            while (!pendingQueue.isEmpty() && totalIterations < MAX_FADE_AWAY_ITERATIONS) {
                totalIterations++;
                PendingTile pending = pendingQueue.poll();

                // Wait until tile is ready
                long waitTime = pending.readyAtSystemMs() - System.currentTimeMillis();
                if (waitTime > 0) {
                    System.out.println("[ReCaptchaSolver] Waiting " + waitTime + "ms for tile " + pending.tileId());
                    page.sleep(waitTime);
                }

                System.out.println("[ReCaptchaSolver] Processing replacement tile " + pending.tileId() +
                        " (iteration " + totalIterations + "/" + MAX_FADE_AWAY_ITERATIONS + ")");

                // Fetch the replacement image
                Page.ImageData imageData;
                try {
                    imageData = fetchReplacementTileImage(page, challengeIframe, pending.tileId());
                } catch (TimeoutException e) {
                    System.err.println("[ReCaptchaSolver] Failed to fetch replacement image for tile " +
                            pending.tileId() + ": " + e.getMessage());
                    continue;
                }

                // Validate it's a 100x100 replacement tile
                if (!imageData.hasExpectedSize(GridSize.ONE_BY_ONE.expectedImageSize())) {
                    System.err.println("[ReCaptchaSolver] Unexpected replacement image size: " +
                            imageData.dimensionsString() + " (expected 100x100)");
                    continue;
                }

                // Send to AI service
                AutoSolveAIResponse aiResponse;
                try {
                    aiResponse = aiService.solve(description, imageData.base64());
                    System.out.println("[ReCaptchaSolver] AI response for tile " + pending.tileId() +
                            ": " + aiResponse);
                } catch (AutoSolveAIException e) {
                    System.err.println("[ReCaptchaSolver] AI error for tile " + pending.tileId() +
                            ": " + e.getMessage());
                    continue;
                }

                // Check if we should click this tile
                // For 1x1, the grid is boolean[1][1], so check squares[0][0]
                boolean shouldClick = aiResponse.success() &&
                        aiResponse.hasValidGrid() &&
                        aiResponse.shouldSelectTile(0, 0);

                if (shouldClick) {
                    System.out.println("[ReCaptchaSolver] Clicking replacement tile " + pending.tileId());
                    String tileSelector = String.format(TILE_SELECTOR_PATTERN, escapeForCss(pending.tileId()));

                    try {
                        page.clickInFrame(challengeIframe, tileSelector);
                    } catch (TimeoutException e) {
                        System.err.println("[ReCaptchaSolver] Failed to click tile " + pending.tileId() +
                                ": " + e.getMessage());
                        continue;
                    }

                    // Wait briefly then check if this click triggered another fade
                    page.sleep(POST_REPLACEMENT_CLICK_DELAY_MS);

                    String newStyle = getTileStyle(page, challengeIframe, pending.tileId());
                    long newDuration = parseTransitionDuration(newStyle);

                    if (newDuration > 0) {
                        long newReadyAt = System.currentTimeMillis() + newDuration + FADE_AWAY_BUFFER_MS;
                        pendingQueue.add(new PendingTile(pending.tileId(), newReadyAt));
                        System.out.println("[ReCaptchaSolver] Tile " + pending.tileId() +
                                " fading again, ready in " + (newDuration + FADE_AWAY_BUFFER_MS) + "ms");
                    }
                } else {
                    System.out.println("[ReCaptchaSolver] Skipping tile " + pending.tileId() + " (AI says no match)");
                }
            }

            if (totalIterations >= MAX_FADE_AWAY_ITERATIONS) {
                System.err.println("[ReCaptchaSolver] Warning: Hit max fade-away iterations (" +
                        MAX_FADE_AWAY_ITERATIONS + ")");
            }

            System.out.println("[ReCaptchaSolver] Fade-away processing complete after " + totalIterations + " iterations");
            return true;

        } catch (TimeoutException e) {
            System.err.println("[ReCaptchaSolver] Timeout during fade-away processing: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks the captcha status after clicking verify.
     */
    private static RoundResult checkSolveStatus(Page page) throws TimeoutException {
        IframeInfo checkboxIframe = page.getIframeInfo(CHECKBOX_IFRAME_SELECTOR, 0);

        // Check if solved
        if (page.hasClassInFrame(checkboxIframe, CHECKBOX_SELECTOR, SOLVED_CLASS)) {
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

    private static String escapeForCss(int id) {
        return String.valueOf(id)
                .chars()
                .mapToObj(c -> String.format("\\%x", c))
                .collect(Collectors.joining());
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

    /**
     * Represents a tile that is fading away and will have a replacement image.
     *
     * @param tileId          the tile ID (0-15 for 4x4, 0-8 for 3x3)
     * @param readyAtSystemMs system time when the replacement image should be ready
     */
    private record PendingTile(int tileId, long readyAtSystemMs) implements Comparable<PendingTile> {
        @Override
        public int compareTo(PendingTile other) {
            return Long.compare(this.readyAtSystemMs, other.readyAtSystemMs);
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