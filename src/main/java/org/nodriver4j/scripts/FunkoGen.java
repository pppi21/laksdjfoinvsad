package org.nodriver4j.scripts;

import org.nodriver4j.captcha.ReCaptchaSolver;
import org.nodriver4j.core.Page;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.services.*;

import java.security.SecureRandom;
import java.util.concurrent.TimeoutException;

/**
 * Automation script for Uber Eats account generation.
 *
 * <p>Navigates to Uber Eats via Google search, signs up with the profile's
 * email address, enters the email OTP, fills in the name, accepts terms,
 * and verifies successful account creation.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Instances are created by {@link ScriptRegistry} via the no-arg constructor.
 * {@link #run(Page, ProfileEntity, TaskLogger)} is called once per instance on
 * a background thread managed by
 * {@link org.nodriver4j.services.TaskExecutionService}.</p>
 *
 * <h2>Success / Failure</h2>
 * <ul>
 *   <li>Normal return → success. The caller appends a completion note to the
 *       profile and sets the task status to COMPLETED.</li>
 *   <li>Exception → failure. The caller sets the task status to FAILED and
 *       logs the error message.</li>
 * </ul>
 *
 * @see AutomationScript
 * @see ScriptRegistry
 */
public class FunkoGen implements AutomationScript {

    private static final int ATTEMPTS = 3;

    // ==================== Form Selectors ====================

    private static final String FIRST_NAME_TEXT = "#registration-form-fname";
    private static final String LAST_NAME_TEXT = "#registration-form-lname";
    private static final String EMAIL_TEXT = "#registration-form-email";
    private static final String PASSWORD_TEXT = "#registration-form-password";
    private static final String CONFIRM_PASSWORD_TEXT = "#registration-form-password-confirm";
    private static final String ACCEPT_TERMS_CHECKBOX = "div.terms-agree-field.form-group.custom-control.custom-checkbox > label";
    private static final String CREATE_ACCOUNT_BUTTON = "#register > form > button";
    private static final String OTP_SENT_ID = "#ajaxModal > div > div > div > div > div.modal-header > div";
    private static final String SKIP_PHONE_BUTTON = "#alt-action-skip";
    private static final String CONTINUE_NAME_BUTTON = "#forward-button";
    private static final String CONTINUE_TERMS_BUTTON = "#forward-button";
    private static final String SKIP_SECURITY_BUTTON = "button[data-testid='skip']";
    private static final String CONTINUE_SECURITY_BUTTON = "#guided-security-upgrade-ui > div[data-baseweb='block'] > button";
    private static final String HOMEPAGE_SUCCESS_ID = "div.entry-confirmation-main > h2";

    // ==================== Password Generation ====================

    private static final String UPPERCASE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWERCASE_CHARS = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGIT_CHARS = "0123456789";
    private static final String SPECIAL_CHARS = "!@#$%^&*";
    private static final String ALL_CHARS = UPPERCASE_CHARS + LOWERCASE_CHARS + DIGIT_CHARS + SPECIAL_CHARS;
    private static final int PASSWORD_LENGTH = 16;


    // ==================== Instance Fields ====================

    /**
     * Set at the start of {@link #run} — each instance is used exactly once.
     */
    private Page page;
    private ProfileEntity profile;
    private TaskLogger logger;

    // ==================== Constructor ====================

    /**
     * No-arg constructor for {@link ScriptRegistry} factory.
     */
    public FunkoGen() {
    }

    // ==================== AutomationScript Implementation ====================

    /**
     * Executes the Funko account generation workflow.
     *
     * <p>A normal return indicates the account was created successfully.
     * Any exception indicates failure.</p>
     *
     * @param page    the browser page to automate
     * @param profile the profile containing user data
     * @param logger  the logger for live UI messages
     * @throws Exception if the signup fails for any reason
     */
    @Override
    public void run(Page page, ProfileEntity profile, TaskLogger logger) throws Exception {
        this.page = page;
        this.profile = profile;
        this.logger = logger;

        try {
            logger.log("Navigating to Funko...");
            navigateToFunko();

            logger.log("Signing up...");
            signUp();

            logger.log("Waiting for email verifcation...");
            fetchVerificationLink();

            logger.success("Check browser for successful verification");
            page.sleep(1000000);

            if (page.exists(FIRST_NAME_TEXT)) {
                logger.log("Entering name...");
                enterName();
            } else if (page.exists(SKIP_PHONE_BUTTON)) {
                logger.log("Skipping phone number...");
                skipPhoneNumber();
                logger.log("Entering name...");
                enterName();
            }

            logger.log("Accepting terms...");
            acceptTerms();

            page.sleep(1000);
            if (page.exists(CONTINUE_SECURITY_BUTTON)) {
                logger.log("Skipping security prompt...");
                skipSecurity();
            }

            page.waitForLoadEvent(15000);
            page.sleep(3000);

            if (page.exists(HOMEPAGE_SUCCESS_ID)) {
                logger.success("Signup successful for: " + profile.emailAddress());
                return;
            }

        } catch (UnexpectedNavigationException | GmailClient.GmailClientException | TimeoutException e) {
            logger.error("Attempt failed: " + e.getMessage());
        }

        page.screenshot();
        throw new RuntimeException("Signup failed unexpectedly for: " + profile.emailAddress());
    }

    // ==================== Navigation ====================

    private void navigateToFunko() throws RuntimeException {

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                page.navigate("https://funko.com/login/?action=register&pageSearchParams=pid%3D90729&rurl=5");
                page.waitForLoadEvent(30000);
                return;

            } catch (TimeoutException e) {
                logger.log("Navigate attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("navigateToFunko failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    // ==================== Sign Up ====================

    private void signUp() throws RuntimeException {

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                fillFormField(FIRST_NAME_TEXT, profile.firstName(), true);
                fillFormField(LAST_NAME_TEXT, profile.lastName(), true);
                fillFormField(EMAIL_TEXT, profile.emailAddress(), true);
                String password = generatePassword();
                fillFormField(PASSWORD_TEXT, password, true);
                fillFormField(CONFIRM_PASSWORD_TEXT, password, true);
                page.jsClick(ACCEPT_TERMS_CHECKBOX);
                page.click(CREATE_ACCOUNT_BUTTON);
                page.waitForSelector(OTP_SENT_ID, 10000);
                System.out.println("FOUND............................");
                page.sleep(10000);
                return;

            } catch (TimeoutException | InterruptedException e) {
                logger.log("Sign up attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("signUpWitfhEmail failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    // ==================== Email OTP ====================

    private void fetchVerificationLink() throws GmailClient.GmailClientException {
        GmailClient gmail = new GmailClient(profile.emailAddress(), profile.catchallEmail(), profile.imapPassword());
        gmail.connect();
        FunkoVerificationExtractor extractor = new FunkoVerificationExtractor(gmail);

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                String link = extractor.extractVerificationLink(); // Polls for up to 60 seconds
                logger.log("Retrieved email verification...");
                page.navigate(link);
                return;

            } catch (EmailPollingBase.EmailExtractionException e) {
                logger.log("OTP attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Email OTP failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    // ==================== Phone ====================

    private void skipPhoneNumber() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                page.click(SKIP_PHONE_BUTTON);
                return;
            } catch (TimeoutException e) {
                logger.log("Phone skip attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
                page.sleep(1000);
            }
        }
    }

    // ==================== Name ====================

    private void enterName() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                fillFormField(FIRST_NAME_TEXT, profile.firstName(), true);
                fillFormField(LAST_NAME_TEXT, profile.lastName(), true);
                page.click(CONTINUE_NAME_BUTTON);
                return;
            } catch (InterruptedException | TimeoutException e) {
                logger.log("Name attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            }
        }
    }

    // ==================== Terms ====================

    private void acceptTerms() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                page.click(ACCEPT_TERMS_CHECKBOX);
                page.click(CONTINUE_TERMS_BUTTON);
                return;
            } catch (TimeoutException e) {
                logger.log("Terms attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            }
        }
    }

    // ==================== Security ====================

    private void skipSecurity() throws RuntimeException {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                page.waitForSelector(CONTINUE_SECURITY_BUTTON, 1000);
                page.click(SKIP_SECURITY_BUTTON);
                return;
            } catch (TimeoutException e) {
                logger.log("Security attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("Skip security failed: session likely flagged");
    }

    // ==================== Form Helpers ====================

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


    // ==================== Utilities ====================

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
        char[] passwordChars = new char[PASSWORD_LENGTH];

        // Ensure at least one of each required character type
        passwordChars[0] = UPPERCASE_CHARS.charAt(random.nextInt(UPPERCASE_CHARS.length()));
        passwordChars[1] = LOWERCASE_CHARS.charAt(random.nextInt(LOWERCASE_CHARS.length()));
        passwordChars[2] = DIGIT_CHARS.charAt(random.nextInt(DIGIT_CHARS.length()));
        passwordChars[3] = SPECIAL_CHARS.charAt(random.nextInt(SPECIAL_CHARS.length()));

        // Fill remaining positions with random characters from all sets
        for (int i = 4; i < PASSWORD_LENGTH; i++) {
            passwordChars[i] = ALL_CHARS.charAt(random.nextInt(ALL_CHARS.length()));
        }

        // Shuffle to randomize positions of required characters
        for (int i = PASSWORD_LENGTH - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char temp = passwordChars[i];
            passwordChars[i] = passwordChars[j];
            passwordChars[j] = temp;
        }

        return new String(passwordChars);
    }

    // ==================== Inner Exception Classes ====================

    /**
     * Thrown when the page unexpectedly navigates away from the target page,
     * typically due to popup interference causing a click on a link.
     */
    public static class UnexpectedNavigationException extends RuntimeException {

        private final String url;

        public UnexpectedNavigationException(String url) {
            super("Unexpected navigation to: " + url);
            this.url = url;
        }

        public String url() {
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