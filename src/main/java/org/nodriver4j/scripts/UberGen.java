package org.nodriver4j.scripts;

import org.nodriver4j.core.Page;
import org.nodriver4j.profiles.Profile;
import org.nodriver4j.profiles.ProfilePool;
import org.nodriver4j.services.GmailClient;
import org.nodriver4j.services.UberOtpExtractor;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.TimeoutException;

/**
 * Automation script for entering the Mattel Super Treasure Hunt draw.
 */
public class UberGen {

    private static final int ATTEMPTS = 3;

    private static final String[] DISALLOWED_URLS = {
            "corporate.mattel.com/privacy-statement",
            "creations.mattel.com/pages/welcome-offer-terms",
            "mattelsupport.com/cookies-and-technologies"
    };

    // ==================== Form XPaths ====================

    private static final String GOOGLE_SEARCH_TEXT = "/html/body/div[2]/div[4]/form/div[1]/div[1]/div[1]/div[1]/div[3]/textarea";
    private static final String UE_RESULT_BUTTON = "a[href^='https://www.ubereats.com/'] > :nth-child(1)";
    private static final String SIGN_IN_BUTTON = "[tabindex='0'][href^='https://auth.uber.com/v2/']";
    private static final String EMAIL_TEXT = "input#PHONE_NUMBER_or_EMAIL_ADDRESS[type='email']";
    private static final String SUMBIT_EMAIL_BUTTON = "#forward-button";
    private static final String EMAIL_OTP_TEXT = "#EMAIL_OTP_CODE-0";
    private static final String EMAIL_OTP_RESEND_BUTTON = "#alt-action-resend";
    private static final String EMAIL_OTP_RESEND_CONFIRM_BUTTON = "#alt-action-resend[aria-label='Resend']";
    private static final String FIRST_NAME_TEXT = "#FIRST_NAME";
    private static final String LAST_NAME_TEXT = "#LAST_NAME";
    private static final String CONTINUE_NAME_BUTTON = "#forward-button";

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

                signUpWithEmail();

                enterEmailOTP();

                enterName();
            } catch (UnexpectedNavigationException | GmailClient.GmailClientException e) {
                System.out.println("[UberGen] ⚠ Attempt failed: " + e.getMessage());
            }
    }



    private void navigateToUber() throws RuntimeException {

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                page.navigate("https://www.google.com/");
                fillFormField(GOOGLE_SEARCH_TEXT, "uber eats", true);
                page.pressKey("Enter", false,false,false);
                if(!page.exists(UE_RESULT_BUTTON)) {
                    page.waitForSelector(UE_RESULT_BUTTON,100000);
                }
                page.sleep(1500);
                page.click(UE_RESULT_BUTTON);
                return;

            } catch (TimeoutException | InterruptedException e) {
                System.out.println("[UberGen] navigateToUber attempt " + attempt + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("navigateToUber failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    private void signUpWithEmail() throws RuntimeException {

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                int signInSeed = (int)(Math.random() * 100);
                if(attempt > 1) page.navigate("https://www.ubereats.com/");
                page.waitForLoadEvent(60000);
                page.click(SIGN_IN_BUTTON);
                page.waitForLoadEvent(100000);
                page.sleep(2000);
                fillFormField(EMAIL_TEXT, profile.emailAddress(), true);
                if(signInSeed % 3 == 0) { page.pressKey("Enter", false,false,false); }
                else { page.click(SUMBIT_EMAIL_BUTTON); }
                return;

            } catch (TimeoutException | InterruptedException e) {
                System.out.println("[UberGen] navigateToUber attempt " + attempt + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("navigateToUber failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    private void enterEmailOTP() throws GmailClient.GmailClientException {
        GmailClient gmail = new GmailClient(profile.emailAddress(), profile.catchallEmail(), profile.imapPassword());
        gmail.connect();
        UberOtpExtractor extractor = new UberOtpExtractor(gmail);

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                String attemptStr = "Attempt " + attempt + "/" + ATTEMPTS + " - ";
                if(attempt > 1) {
                    page.click(EMAIL_OTP_RESEND_BUTTON);
                    page.sleep(1500);
                    page.click(EMAIL_OTP_RESEND_CONFIRM_BUTTON);
                }
                String otp = extractor.extractOtp(); // Polls for up to 60 seconds
                System.out.println("[UberGen] " + attemptStr+ "Retrieved email OTP for " + profile.emailAddress() + ": " + otp);
                fillFormField(EMAIL_OTP_TEXT, otp, false);
                page.waitForSelector(FIRST_NAME_TEXT, 15000);
                return;

            } catch (UberOtpExtractor.OtpExtractionException | InterruptedException | TimeoutException e) {
                System.out.println("[UberGen] OTP extraction failed for " + profile.emailAddress() + ": " + e.getMessage());
            }
        }
        throw new RuntimeException("Mail OTP failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    private void enterName() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String attemptStr = "Attempt " + attempt + "/" + ATTEMPTS + " - ";
            try{
                fillFormField(FIRST_NAME_TEXT, profile.firstName(), true);
                fillFormField(LAST_NAME_TEXT, profile.lastName(), true);
                page.click(CONTINUE_NAME_BUTTON);
                return;
            } catch (InterruptedException | TimeoutException e) {
                System.out.println("[UberGen] Name stage failed for " + profile.emailAddress() + ": " + e.getMessage());
            }
        }
    }

    private void fillFormField(String selector, String value, boolean validate) throws InterruptedException, TimeoutException {
        for (int attempt = 0; attempt <= ATTEMPTS; attempt++) {
            page.fillFormField(selector, value, 300, 600);
            if (page.validateValue(selector, value) || !validate) {
                return;
            }
            page.sleep(200);
            page.clear(selector);
        }
        throw new TimeoutException("Failed to fill field after " + ATTEMPTS + " attempts: " + selector);

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