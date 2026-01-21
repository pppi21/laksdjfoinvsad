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

    // ==================== Form XPaths ====================

    private static final String CLOSE_POPUP_BUTTON = "button[aria-label='Close dialog']";
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
     *
     * @return true if entry was successful
     * @throws RuntimeException if entry fails after all retries
     */
    public void enterDraw() {

        System.out.println("[MattelDraw] Entering draw for: " + profile.emailAddress());

        try {
            // Navigate to registration page
            page.navigate(PRODUCT_URL);
            rejectCookies();
            fillFormField(FIRST_NAME_TEXT, profile.firstName());
            fillFormField(LAST_NAME_TEXT, profile.lastName());
            fillFormField(EMAIL_TEXT, profile.emailAddress());

            boolean success = submit();

            if (success) {
                System.out.println("[MattelDraw] ✓ Entry successful for: " + profile.emailAddress());
                writeCompletedProfile();
            } else {
                System.err.println("[MattelDraw] ✗ Entry failed for: " + profile.emailAddress());
            }

        } catch (TimeoutException e) {
            throw new RuntimeException("Timeout during draw entry: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write completed profile: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void fillFormField(String selector, String value) throws InterruptedException, TimeoutException {
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            checkForPopup();
            page.fillFormField(selector, value, 150, 300);
            if (page.validateValue(selector, value)) {
                return;
            }
            checkForPopup();
            page.sleep(200);
            page.clear(selector);
        }
        throw new TimeoutException("Failed to fill field after " + RETRIES + " attempts: " + selector);

    }

    private boolean checkForPopup() throws TimeoutException {
        if(page.exists(CLOSE_POPUP_BUTTON)){
            page.click(CLOSE_POPUP_BUTTON);
            return true;
        }
        return false;
    }

    private void rejectCookies() throws TimeoutException {
        if(page.isVisible(REJECT_COOKIES_BUTTON)){
            return;
        }
        page.waitForSelector(REJECT_COOKIES_BUTTON);
        page.click(REJECT_COOKIES_BUTTON);
        try {
            page.waitForSelectorHidden(REJECT_COOKIES_BUTTON, 10000);
        } catch (TimeoutException _) {}

    }

    private boolean submit() throws TimeoutException {
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            System.out.println("[MattelDraw] Submitting form (attempt " + attempt + "/" + RETRIES + ")...");
            checkForPopup();
            page.click(SUBMIT_BUTTON);
            if (waitForSuccessMessage()) {
                return true;
            }
            if (attempt < RETRIES) {
                System.out.println("[MattelDraw] Success message not found, retrying...");
                page.sleep(1000);
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
}