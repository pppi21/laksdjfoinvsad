package org.nodriver4j.scripts;

import org.nodriver4j.core.Page;
import org.nodriver4j.profiles.Profile;
import org.nodriver4j.profiles.ProfilePool;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Automation script for entering the Mattel Super Treasure Hunt draw.
 */
public class MattelDraw {

    private static final String PRODUCT_URL = "https://creations.mattel.com/pages/2025-super-treasure-hunt-draw";
    private static final int RETRIES = 2;

    private static final String[] DISALLOWED_URLS = {
            "corporate.mattel.com/privacy-statement",
            "creations.mattel.com/pages/welcome-offer-terms",
            "mattelsupport.com/cookies-and-technologies"
    };

    // ==================== Form XPaths ====================

    private static final String CLOSE_POPUP_BUTTON = "button[aria-label='Close dialog']";
    private static final String COOKIES_DISPLAY = "[class='ta-show ta-display-block']";
    private static final String REJECT_COOKIES_BUTTON = "button[id='truste-consent-required']";
    private static final String FIRST_NAME_TEXT = "/html/body/div[8]/main/div[4]/section/div/div/div/form/div/div[3]/div[1]/div/input";
    private static final String LAST_NAME_TEXT = "/html/body/div[8]/main/div[4]/section/div/div/div/form/div/div[3]/div[2]/div/input";
    private static final String EMAIL_TEXT = "/html/body/div[8]/main/div[4]/section/div/div/div/form/div/div[4]/div/div/input";
    private static final String SUBMIT_BUTTON = "/html/body/div[8]/main/div[4]/section/div/div/div/form/div/div[5]/div/button";
    private static final String SUCCESS_MESSAGE = "span.ql-font-poppins";

    // ==================== Fields ====================

    private final Page page;
    private final Profile profile;
    private final ProfilePool profilePool;
    private boolean popupClosed = false;

    /**
     * Creates a MattelDraw script with a referrer URL.
     *
     * @param page        the Page to automate
     * @param profile     the profile containing user data
     * @param profilePool the pool for writing completed profiles
     */
    public MattelDraw(Page page, Profile profile, ProfilePool profilePool) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        if (profile == null) {
            throw new IllegalArgumentException("Profile cannot be null");
        }
        if (profilePool == null) {
            throw new IllegalArgumentException("ProfilePool cannot be null");
        }

        this.page = page;
        this.profile = profile;
        this.profilePool = profilePool;
    }

    /**
     * Enters the Mattel draw using the configured profile.
     * Automatically retries if popup causes accidental navigation.
     *
     * @throws RuntimeException if entry fails after all retries
     */
    public void enterDraw() {
        System.out.println("[MattelDraw] Entering draw for: " + profile.emailAddress());

        int totalAttempts = RETRIES + 1;

        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            try {
                attemptEntry();
                return; // Success - exit

            } catch (UnexpectedNavigationException | SubmissionFailedException | IOException | TimeoutException e) {
                System.out.println("[MattelDraw] ⚠ Attempt " + attempt + " failed: " + e.getMessage());

                if (attempt < totalAttempts) {
                    System.out.println("[MattelDraw] Restarting flow (attempt " + (attempt + 1) + "/" + totalAttempts + ")...");
                    resetFlowState();

                    try {
                        page.navigate(PRODUCT_URL);
                    } catch (TimeoutException te) {
                        throw new RuntimeException("Failed to navigate back to draw page: " + te.getMessage(), te);
                    }
                } else {
                    throw new RuntimeException("Entry failed after " + totalAttempts + " attempts: " + e.getMessage(), e);
                }
            }
        }

        // This line should never be reached, but just in case:
        throw new RuntimeException("Entry failed: loop exited unexpectedly");
    }

    /**
     * Resets state variables for a fresh flow attempt.
     */
    private void resetFlowState() {
        popupClosed = false;
    }

    /**
     * Enters the Mattel draw using the configured profile.
     *
     * @return true if entry was successful
     * @throws RuntimeException if entry fails after all retries
     */
    private void attemptEntry() throws IOException, TimeoutException {
        try {
            page.navigate(PRODUCT_URL);
            page.waitForLoadEvent(60000);
            rejectCookies();
            fillFormField(FIRST_NAME_TEXT, profile.firstName());
            verifyOnDrawPage();
            fillFormField(LAST_NAME_TEXT, profile.lastName());
            verifyOnDrawPage();
            fillFormField(EMAIL_TEXT, profile.emailAddress());
            verifyOnDrawPage();

            boolean success = submit();

            if (success) {
                System.out.println("[MattelDraw] ✓ Entry successful for: " + profile.emailAddress());
                writeCompletedProfile();
            } else {
                throw new SubmissionFailedException("Form submission did not succeed");
            }

        } catch (UnexpectedNavigationException | SubmissionFailedException e) {
            throw e; // Re-throw for flow retry handling
        } catch (TimeoutException e) {
            page.screenshot();
            throw new RuntimeException("Timeout during draw entry: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write completed profile: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during draw entry", e);
        }
    }

    /**
     * Verifies we're still on the draw page.
     * Call after any action where popup interference could cause navigation.
     *
     * @throws UnexpectedNavigationException if navigated away from draw page
     * @throws TimeoutException if URL check times out
     */
    private void verifyOnDrawPage() throws TimeoutException {
        String currentUrl = page.currentUrl();

        for (String disallowed : DISALLOWED_URLS) {
            if (currentUrl.contains(disallowed)) {
                throw new UnexpectedNavigationException(currentUrl);
            }
        }
    }

    private void fillFormField(String selector, String value) throws InterruptedException, TimeoutException, IOException {
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            checkForPopup();
            page.waitForSelector(selector, 100000);
            page.fillFormField(selector, value, 150, 300);
            if (page.validateValue(selector, value)) {
                return;
            }
            checkForPopup();
            page.sleep(200);
            page.clear(selector);
        }
        page.screenshot();
        throw new TimeoutException("Failed to fill field after " + (RETRIES+1) + " attempts: " + selector);

    }

    /**
     * Checks for and dismisses the popup if present.
     * After the popup has been closed once, this method returns immediately
     * since the popup won't reappear during this flow attempt.
     *
     * @return true if popup was dismissed (this call or previously)
     * @throws TimeoutException if click operation times out
     */
    private boolean checkForPopup() throws TimeoutException {
        // Popup won't reappear after being closed once
        if (popupClosed) {
            return true;
        }

        if (page.exists(CLOSE_POPUP_BUTTON)) {
            page.click(CLOSE_POPUP_BUTTON);
            page.sleep(1000);
            if (!page.exists(CLOSE_POPUP_BUTTON)) {
                popupClosed = true;
                return true;
            }
        }

        return false;
    }


    private void rejectCookies() throws IOException, TimeoutException {
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            page.waitForClickable(COOKIES_DISPLAY, 100000);
            checkForPopup();
            page.click(REJECT_COOKIES_BUTTON);
            page.sleep(300);
            if (!page.exists(COOKIES_DISPLAY)) {
                return;
            }

            if (attempt < RETRIES) {
                System.out.println("[MattelDraw] Reject cookies not loaded, retrying...");
                page.sleep(10000);
            }
        }
    }

    private boolean submit() {
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            try {
                System.out.println("[MattelDraw] Submitting form (attempt " + (attempt + 1) + "/" + (RETRIES + 1) + ")...");
                checkForPopup();
                page.click(SUBMIT_BUTTON);
                if (waitForSuccessMessage()) {
                    return true;
                }
                verifyOnDrawPage();
                if (attempt < RETRIES) {
                    System.out.println("[MattelDraw] Success message not found, retrying...");
                    page.sleep(10000);
                }
            } catch (TimeoutException e) {
                System.out.println("[MattelDraw] Submission attempt " + attempt + " failed: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Waits for the success message to appear with the expected text.
     *
     * @return true if success message appears within timeout
     */
    private boolean waitForSuccessMessage() {
        long deadline = System.currentTimeMillis() + 4000;

        while (System.currentTimeMillis() < deadline) {
            try {
                if (page.exists(SUCCESS_MESSAGE) &&
                        page.containsTextTrimmed(SUCCESS_MESSAGE, "YOUR ENTRY IS IN!")) {
                    return true;
                }
            } catch (TimeoutException _) {
            }
            page.sleep(200);
        }

        return false;
    }



    /**
     * Writes the completed profile to the output file.
     */
    private void writeCompletedProfile() throws IOException {
        Profile completed = profile.toBuilder().build();
        profilePool.writeCompleted(completed);
    }

    // ==================== Inner Exception Class ====================

    /**
     * Thrown when the page unexpectedly navigates away from the draw page,
     * typically due to popup interference causing a click on a link.
     */
    public static class UnexpectedNavigationException extends RuntimeException {

        private final String url;

        public UnexpectedNavigationException(String url) {
            super("Unexpected navigation to: " + url);
            this.url = url;
        }

        public String getUrl() {
            return url;
        }
    }

    /**
     * Thrown when form submission completes but does not succeed.
     */
    public static class SubmissionFailedException extends RuntimeException {
        public SubmissionFailedException(String message) {
            super(message);
        }
    }
}