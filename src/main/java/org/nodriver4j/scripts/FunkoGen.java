package org.nodriver4j.scripts;

import org.nodriver4j.core.Page;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.repository.ProfileRepository;
import org.nodriver4j.services.*;
import org.nodriver4j.services.imap.EmailPollingBase;
import org.nodriver4j.services.imap.GmailClient;
import org.nodriver4j.services.imap.impl.FunkoVerificationExtractor;

import org.nodriver4j.core.exceptions.AutomationException;
import org.nodriver4j.core.exceptions.ElementNotInteractableException;

import java.security.SecureRandom;

/**
 * Automation script for Funko account generation.
 *
 * <p>Navigates to Funko's registration page, signs up with the profile's
 * email address, and verifies the account via email link.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Instances are created by {@link ScriptRegistry} via the no-arg constructor.
 * {@link #run(Page, ProfileEntity, TaskLogger, TaskContext)} is called once per
 * instance on a background thread managed by
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
    private TaskContext context;
    private String generatedPassword;
    private final ProfileRepository profileRepository = new ProfileRepository();

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
     * <p>A normal return indicates the account was created and verified
     * successfully. Any exception indicates failure.</p>
     *
     * @param page    the browser page to automate
     * @param profile the profile containing user data
     * @param logger  the logger for live UI messages
     * @param context the task context for resource registration and cancellation
     * @throws Exception if the signup fails for any reason
     */
    @Override
    public void run(Page page, ProfileEntity profile, TaskLogger logger,
                    TaskContext context) throws Exception {
        this.page = page;
        this.profile = profile;
        this.logger = logger;
        this.context = context;

        try {
            logger.log("Navigating to Funko...");
            navigateToFunko();

            context.checkCancelled();

            logger.log("Signing up...");
            signUp();

            AutomationScript.persistNote(profile, "Funko Password: " + generatedPassword, profileRepository);

            context.checkCancelled();

            logger.log("Password saved, waiting for email verification...");
            fetchVerificationLink();

            logger.success("Account created and verified successfully!");
            return;

        } catch (GmailClient.GmailClientException e) {
            logger.error("Email error: " + e.getMessage());
        } catch (UnexpectedNavigationException e) {
            logger.error("Unexpected navigation: " + e.url());
        }

        page.screenshot();
        throw new RuntimeException("Signup failed unexpectedly for: " + profile.emailAddress());
    }

    // ==================== Navigation ====================

    private void navigateToFunko() throws RuntimeException {

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                page.navigate("https://funko.com/login/?action=register&pageSearchParams=pid%3D90729&rurl=5");
                page.waitForLoadEvent(40000);
                return;

            } catch (AutomationException e) {
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
                generatedPassword = generatePassword();
                fillFormField(PASSWORD_TEXT, generatedPassword, true);
                fillFormField(CONFIRM_PASSWORD_TEXT, generatedPassword, true);
                page.jsClick(ACCEPT_TERMS_CHECKBOX);
                logger.log("Submitting signup...");
                page.click(CREATE_ACCOUNT_BUTTON);
                for (int check = 0; check < 25; check++) {
                    if (page.exists(OTP_SENT_ID)) return;
                    page.sleep(2000);
                }
                page.reload(true, 40000);
                page.sleep(10000);
                throw new ElementNotInteractableException("Potentially bad proxy.", "");
            } catch (AutomationException e) {
                logger.log("Sign up attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("signUp failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    // ==================== Email Verification ====================

    private void fetchVerificationLink() throws GmailClient.GmailClientException {
        try (FunkoVerificationExtractor extractor = context.register(new FunkoVerificationExtractor(
                profile.emailAddress(), profile.catchallEmail(), profile.imapPassword()))) {

            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                try {
                    String link = extractor.extractVerificationLink();
                    logger.log("Retrieved email verification...");
                    page.navigate(link);
                    return;

                } catch (EmailPollingBase.EmailExtractionException e) {
                    logger.log("Verification attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
                } catch (AutomationException e) {
                    throw new RuntimeException(e);
                }
            }
            throw new RuntimeException("Email verification failed: Maximum " + ATTEMPTS + " attempts reached");
        }
    }

    // ==================== Form Helpers ====================

    private void fillFormField(String selector, String value, boolean validate) {
        for (int attempt = 0; attempt <= ATTEMPTS; attempt++) {
            page.fillFormField(selector, value, 300, 600);
            if (page.validateValue(selector, value) || !validate) {
                return;
            }
            page.sleep(200);
            page.clear(selector);
        }
        throw new ElementNotInteractableException(
                "Failed to fill field after " + ATTEMPTS + " attempts: " + selector, selector);
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

        passwordChars[0] = UPPERCASE_CHARS.charAt(random.nextInt(UPPERCASE_CHARS.length()));
        passwordChars[1] = LOWERCASE_CHARS.charAt(random.nextInt(LOWERCASE_CHARS.length()));
        passwordChars[2] = DIGIT_CHARS.charAt(random.nextInt(DIGIT_CHARS.length()));
        passwordChars[3] = SPECIAL_CHARS.charAt(random.nextInt(SPECIAL_CHARS.length()));

        for (int i = 4; i < PASSWORD_LENGTH; i++) {
            passwordChars[i] = ALL_CHARS.charAt(random.nextInt(ALL_CHARS.length()));
        }

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