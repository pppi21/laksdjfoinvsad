package org.nodriver4j.scripts;

import org.nodriver4j.core.Page;
import org.nodriver4j.profiles.Profile;
import org.nodriver4j.profiles.ProfilePool;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.TimeoutException;

/**
 * Automation script for entering the Mattel Super Treasure Hunt draw.
 */
public class UberGen {

    private static final int RETRIES = 2;

    private static final String[] DISALLOWED_URLS = {
            "corporate.mattel.com/privacy-statement",
            "creations.mattel.com/pages/welcome-offer-terms",
            "mattelsupport.com/cookies-and-technologies"
    };

    // ==================== Form XPaths ====================

    private static final String GOOGLE_SEARCH_TEXT = "/html/body/div[2]/div[4]/form/div[1]/div[1]/div[1]/div[1]/div[3]/textarea";
    private static final String UE_RESULT_BUTTON = "[data-pcu^='https://www.ubereats.com/']";
    private static final String REJECT_COOKIES_BUTTON = "button[id='truste-consent-required']";
    private static final String FIRST_NAME_TEXT = "/html/body/div[8]/main/div[4]/section/div/div/div/form/div/div[3]/div[1]/div/input";
    private static final String LAST_NAME_TEXT = "/html/body/div[8]/main/div[4]/section/div/div/div/form/div/div[3]/div[2]/div/input";
    private static final String EMAIL_TEXT = "/html/body/div[8]/main/div[4]/section/div/div/div/form/div/div[4]/div/div/input";
    private static final String SUBMIT_BUTTON = "/html/body/div[8]/main/div[4]/section/div/div/div/form/div/div[5]/div/button";
    private static final String SUCCESS_MESSAGE = "span.ql-font-poppins";

    // ==================== Password Generation ====================

    private static final String UPPERCASE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWERCASE_CHARS = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGIT_CHARS = "0123456789";
    private static final String SPECIAL_CHARS = "!@#$%^&*";
    private static final String ALL_CHARS = UPPERCASE_CHARS + LOWERCASE_CHARS + DIGIT_CHARS + SPECIAL_CHARS;
    private static final int PASSWORD_LENGTH = 16;

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
    public UberGen(Page page, Profile profile, ProfilePool profilePool) {
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
    public void generate() {


            try {
                navigateToUber();
            } catch (UnexpectedNavigationException e) {
                System.out.println("[UberGen] ⚠ Attempt failed: " + e.getMessage());
            }
    }



    private void navigateToUber() throws RuntimeException {
        int totalAttempts = RETRIES + 1;

        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            try {
                page.navigate("https://www.google.com/");
                fillFormField(GOOGLE_SEARCH_TEXT, "uber eats");
                page.pressKey("Enter", false,false,false);
                page.sleep(2000);
                page.waitForSelector(UE_RESULT_BUTTON,100000);
                page.click(UE_RESULT_BUTTON);
                page.sleep(2000);
                page.waitForLoadEvent(100000);
                return;

            } catch (TimeoutException | InterruptedException e) {
                System.out.println("[UberGen] navigateToUber attempt " + attempt + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("navigateToUber failed: Maximum " + totalAttempts + " attempts reached");
    }

    private void fillFormField(String selector, String value) throws InterruptedException, TimeoutException {
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            page.waitForSelector(selector, 100000);
            page.fillFormField(selector, value, 150, 300);
            if (page.validateValue(selector, value)) {
                return;
            }
            page.sleep(200);
            page.clear(selector);
        }
        throw new TimeoutException("Failed to fill field after " + (RETRIES+1) + " attempts: " + selector);

    }


    /**
     * Generates a secure random password meeting complexity requirements.
     *
     * <p>The generated password contains:</p>
     * <ul>
     *   <li>At least one uppercase letter</li>
     *   <li>At least one lowercase letter</li>
     *   <li>At least one digit</li>
     *   <li>At least one special character (!@#$%^&*)</li>
     * </ul>
     *
     * @return a 16-character random password
     */
    private String generatePassword() {
        SecureRandom random = new SecureRandom();
        char[] password = new char[PASSWORD_LENGTH];

        // Ensure at least one of each required character type
        password[0] = UPPERCASE_CHARS.charAt(random.nextInt(UPPERCASE_CHARS.length()));
        password[1] = LOWERCASE_CHARS.charAt(random.nextInt(LOWERCASE_CHARS.length()));
        password[2] = DIGIT_CHARS.charAt(random.nextInt(DIGIT_CHARS.length()));
        password[3] = SPECIAL_CHARS.charAt(random.nextInt(SPECIAL_CHARS.length()));

        // Fill remaining positions with random characters from all sets
        for (int i = 4; i < PASSWORD_LENGTH; i++) {
            password[i] = ALL_CHARS.charAt(random.nextInt(ALL_CHARS.length()));
        }

        // Shuffle to randomize positions of required characters
        for (int i = PASSWORD_LENGTH - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char temp = password[i];
            password[i] = password[j];
            password[j] = temp;
        }

        return new String(password);
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