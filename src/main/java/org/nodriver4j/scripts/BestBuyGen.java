package org.nodriver4j.scripts;

import org.nodriver4j.core.Page;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.repository.ProfileRepository;
import org.nodriver4j.services.*;

import org.nodriver4j.core.exceptions.AutomationException;
import org.nodriver4j.core.exceptions.ElementNotInteractableException;

import java.security.SecureRandom;

/**
 * Automation script for Best Buy account generation.
 *
 * <p>Navigates to Best Buy via Google search, signs up with the profile's
 * email address, enters the email OTP, fills in the name, accepts terms,
 * and verifies successful account creation.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Instances are created by {@link ScriptRegistry} via the no-arg constructor.
 * {@link #run(Page, ProfileEntity, TaskLogger, TaskContext)} is called once per
 * instance on a background thread managed by
 * {@link org.nodriver4j.services.TaskExecutionService}.</p>
 *
 * <h2>Success / Failure</h2>
 * <ul>
 *   <li>Normal return -> success. The caller appends a completion note to the
 *       profile and sets the task status to COMPLETED.</li>
 *   <li>Exception -> failure. The caller sets the task status to FAILED and
 *       logs the error message.</li>
 * </ul>
 *
 * @see AutomationScript
 * @see ScriptRegistry
 */
public class BestBuyGen implements AutomationScript {

    private static final int ATTEMPTS = 3;

    // ==================== Form Selectors ====================

    private static final String SIGN_IN_BUTTON = "#account-menu-account-button > span";
    private static final String CREATE_ACCOUNT_BUTTON = "button[data-testid='createAccountButton']";
    private static final String FIRST_NAME_TEXT = "#firstName";
    private static final String LAST_NAME_TEXT = "#lastName";
    private static final String EMAIL_TEXT = "#email";
    private static final String PASSWORD_TEXT_1 = "#fld-p1";
    private static final String PASSWORD_TEXT_2 = "#reenterPassword";
    private static final String PHONE_NUMBER_TEXT = "#phone";
    private static final String SUBMIT_ACCOUNT_BUTTON = "button[type='submit']";
    private static final String ACCOUNT_URL_PREFIX = "https://www.bestbuy.com/customer/myaccount";

    // ==================== Password Generation ====================

    private static final String UPPERCASE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWERCASE_CHARS = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGIT_CHARS = "0123456789";
    private static final String SPECIAL_CHARS = "!@#$%^&*";
    private static final String ALL_CHARS = UPPERCASE_CHARS + LOWERCASE_CHARS + DIGIT_CHARS + SPECIAL_CHARS;
    private static final int PASSWORD_LENGTH = 16;

    // ==================== Instance Fields ====================

    /**
     * Set at the start of {@link #run} -- each instance is used exactly once.
     */
    private final ProfileRepository profileRepository = new ProfileRepository();
    private Page page;
    private ProfileEntity profile;
    private TaskLogger logger;
    private TaskContext context;
    private String phoneNumber;
    private String password;

    // ==================== Constructor ====================

    /**
     * No-arg constructor for {@link ScriptRegistry} factory.
     */
    public BestBuyGen() {
    }

    // ==================== AutomationScript Implementation ====================

    /**
     * Executes the Best Buy account generation workflow.
     *
     * <p>A normal return indicates the account was created successfully.
     * Any exception indicates failure.</p>
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
            logger.log("Navigating to Best Buy...");
            page.navigate("https://www.bestbuy.com/");
            context.checkCancelled();
            logger.log("Navigating to Sign Up...");
            navigateToSignUp();
            // need a function that navigates to sign up by clicking SIGN_IN_BUTTON, then clicks
            // CREATE_ACCOUNT_BUTTON. CREATE_ACCOUNT_BUTTON only appears after SIGN_IN_BUTTON is clicked.
            // It activates a popup containing the sign in options. We need to leverage current Page
            // methods to ensure CREATE_ACCOUNT_BUTTON is visible before attempting a click, and if it's not,
            // we should attempt to click SIGN_IN_BUTTON again.

            context.checkCancelled();

            logger.log("Signing up...");
            fillSignUpForm();
            // Need a function that handles filling the signup form. It should fill the following fields
            // in order with input validation: FIRST_NAME_TEXT, LAST_NAME_TEXT, EMAIL_TEXT, PASSWORD_TEXT_1,
            // PASSWORD_TEXT_2, PHONE_NUMBER_TEXT. Password will be generated and saved for later.
            // Upon successful account creation the password should be added to the profile notes (same format
            // as SandwichGen). We'll stop here for now and get everything up to this point working before
            // setting up phone number verification, which would require an sms service.

            logger.success("Signup successful for: " + profile.emailAddress());
            return;

        } catch (UnexpectedNavigationException e) {
            logger.error("Unexpected navigation: " + e.url());
        } catch (AutomationException e) {
            logger.error("Automation error: " + e.getMessage());
        }

        throw new RuntimeException("Signup failed unexpectedly for: " + profile.emailAddress());
    }

    // ==================== Navigation ====================

    /**
     * Opens the account menu and clicks the "Create Account" link.
     *
     * <p>The Create Account button only appears after clicking the Sign In
     * button, which toggles a popup menu. If the popup fails to appear
     * (animation delay, click intercepted, etc.), the entire sequence is
     * retried up to {@link #ATTEMPTS} times.</p>
     *
     * @throws RuntimeException if the Create Account button cannot be reached
     */
    private void navigateToSignUp() throws InterruptedException {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                page.click(SIGN_IN_BUTTON);
                page.sleep(500);

                if (!page.isVisible(CREATE_ACCOUNT_BUTTON)) {
                    page.sleep(1000);
                    if (!page.isVisible(CREATE_ACCOUNT_BUTTON)) {
                        throw new RuntimeException("Create Account button not visible");
                    }
                }
                context.checkCancelled();
                page.click(CREATE_ACCOUNT_BUTTON);
                page.sleep(1000);
                return;

            } catch (RuntimeException e) {
                logger.log("Navigate to sign up attempt " + attempt + "/" + ATTEMPTS
                        + " failed: " + e.getMessage());
                if (attempt == ATTEMPTS) {
                    throw new RuntimeException(
                            "Failed to navigate to sign up after " + ATTEMPTS + " attempts", e);
                }
                page.sleep(1000);
            }
        }
    }

    // ==================== Sign Up ====================

    /**
     * Fills the Best Buy account creation form, submits it, and verifies
     * the redirect to the account page.
     *
     * <p>Fields are filled in DOM order with input validation. The password
     * is generated and stored in {@link #password}. If the first submit does
     * not redirect to the account page, the submit button is retried. If that
     * also fails, the entire form is reloaded and re-filled. Profile data is
     * only persisted after a confirmed redirect.</p>
     *
     * @throws AutomationException if any field cannot be filled
     * @throws RuntimeException    if the redirect is not detected after all retries
     */
    private void fillSignUpForm() throws InterruptedException {
        this.password = generatePassword();
        this.phoneNumber = resolvePhoneNumber();

        for (int attempt = 1; attempt <= 2; attempt++) {
            fillFields();
            context.checkCancelled();
            logger.log("Submitting Account...");
            page.click(SUBMIT_ACCOUNT_BUTTON);
            page.sleep(3000);

            if (isOnAccountPage()) {
                persistCompletedProfile();
                return;
            }

            // Retry submit click once before re-filling
            logger.log("Success not detected, retrying submit...");
            page.click(SUBMIT_ACCOUNT_BUTTON);
            page.sleep(3000);

            if (isOnAccountPage()) {
                persistCompletedProfile();
                return;
            }

            if (attempt < 2) {
                logger.log("Submit failed, retrying full form fill...");
                page.reload(true, 30000);
                page.sleep(2000);
            }
        }

        throw new RuntimeException("Signup form submission failed: not redirected to account page");
    }

    private void fillFields() throws InterruptedException{
        fillFormField(FIRST_NAME_TEXT, profile.firstName(), true);
        context.checkCancelled();
        fillFormField(LAST_NAME_TEXT, profile.lastName(), true);
        context.checkCancelled();
        logger.log("Filling Email...");
        fillFormField(EMAIL_TEXT, profile.emailAddress(), true);
        context.checkCancelled();
        logger.log("Filling Password...");
        fillFormField(PASSWORD_TEXT_1, password, true);
        context.checkCancelled();
        fillFormField(PASSWORD_TEXT_2, password, true);
        context.checkCancelled();
        logger.log("Filling Phone Number...");
        fillFormField(PHONE_NUMBER_TEXT, phoneNumber, false);
    }

    private boolean isOnAccountPage() {
        String url = page.currentUrl();
        return url != null && url.startsWith(ACCOUNT_URL_PREFIX);
    }

    // ==================== Helpers ====================

    /**
     * Returns the phone number to use for signup. Checks billing phone first,
     * then shipping phone. If neither is set, generates a random US number
     * and updates both profile fields.
     */
    private String resolvePhoneNumber() {
        String billing = profile.billingPhone();
        if (billing != null && !billing.isBlank()) return billing;

        String shipping = profile.shippingPhone();
        if (shipping != null && !shipping.isBlank()) return shipping;

        phoneNumber = generatePhoneNumber();
        return phoneNumber;
    }

    /**
     * Generates a realistic 10-digit US phone number without country code.
     */
    private String generatePhoneNumber() {
        SecureRandom random = new SecureRandom();
        int area = 201 + random.nextInt(799 - 201);
        int exchange = 200 + random.nextInt(800 - 200);
        int subscriber = random.nextInt(10000);
        return String.format("%03d%03d%04d", area, exchange, subscriber);
    }

    /**
     * Persists the generated password to the profile notes.
     */
    private void persistCompletedProfile() {
        String note = String.format("Password: %s:%s",
                profile.emailAddress(), password);
        AutomationScript.persistNote(profile, note, profileRepository);
        profile.billingPhone(phoneNumber);
        profile.shippingPhone(phoneNumber);
    }

    private void fillFormField(String selector, String value, boolean validate) {
        for (int attempt = 0; attempt <= ATTEMPTS; attempt++) {
            page.fillFormField(selector, value, randomDelay(), randomDelay(), randomSpeed());
            if (page.validateValue(selector, value) || !validate) {
                return;
            }
            page.sleep(200);
            page.clear(selector);
        }
        throw new ElementNotInteractableException(
                "Failed to fill field after " + ATTEMPTS + " attempts: " + selector, selector);
    }

    private int randomDelay(){
        return 300 + (int)(Math.random() * 600);
    }

    private double randomSpeed(){
        return 0.8 + (Math.random() * 0.5);
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