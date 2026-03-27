package org.nodriver4j.scripts;

import org.nodriver4j.captcha.PerimeterXSolver;
import org.nodriver4j.core.Page;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.repository.ProfileRepository;
import org.nodriver4j.services.TaskContext;
import org.nodriver4j.services.TaskLogger;

import org.nodriver4j.core.exceptions.AutomationException;
import org.nodriver4j.core.exceptions.ElementNotInteractableException;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Automation script for creating Ike's sandwich rewards accounts.
 *
 * <p>This script automates the account registration process on the Ike's
 * rewards platform using profile data from the database.</p>
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
public class SandwichGen implements AutomationScript {

    // ==================== Retry Configuration ====================

    private static final int ATTEMPTS = 3;
    private static final int CAPTCHA_MAX_RETRIES = 3;

    // ==================== Form Selectors ====================

    private static final String FIRST_NAME_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[3]/div/input";
    private static final String LAST_NAME_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[4]/div/input";
    private static final String PHONE_NUMBER_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[5]/div/input";
    private static final String MONTH_TEXT = "/html/body/div[1]/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[6]/div/div/div[1]/input";
    private static final String DAY_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[6]/div/div/div[3]/input";
    private static final String YEAR_TEXT = "/html/body/div[1]/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[6]/div/div/div[5]/input";
    private static final String EMAIL_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[7]/div/input";
    private static final String PASSWORD_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[8]/div/input";
    private static final String CONFIRM_PASSWORD_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[9]/div/input[1]";
    private static final String EMAIL_OPT_IN_CHECKBOX = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[10]/div/input[1]";
    private static final String STATE_DROPDOWN = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[12]/div/div[1]/div/select";
    private static final String STORE_DROPDOWN = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[12]/div/div[2]/div/select";
    private static final String SUBMIT_BUTTON = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[13]/div/button";
    private static final String SUCCESS_MESSAGE = "/html/body/div[1]/div[2]/div[1]/div[2]/div/div/div/div[2]/div/p[1]";

    // ==================== Dropdown Values ====================

    private static final String STATE_CALIFORNIA = "258";
    private static final String STORE_DEL_MAR = "401";

    // ==================== Password Generation ====================

    private static final String UPPERCASE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWERCASE_CHARS = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGIT_CHARS = "0123456789";
    private static final String SPECIAL_CHARS = "!@#$%^&*";
    private static final String ALL_CHARS = UPPERCASE_CHARS + LOWERCASE_CHARS + DIGIT_CHARS + SPECIAL_CHARS;
    private static final int PASSWORD_LENGTH = 16;

    // ==================== Birthday Generation ====================

    private static final int MIN_BIRTH_YEAR = 1945;
    private static final int MIN_AGE_YEARS = 20;

    // ==================== Referrer URL ====================

    /**
     * The referral link URL used for registration. Set this field before
     * registering the script, or update it at runtime.
     */
    private static final String REFERRER_URL = "https://ikes.myguestaccount.com/guest/enroll?card-template=JTIldXJsLXBhcmFtLWFlcy1rZXklbC9Mdlh0Y29zR1V6ay9ibSVWMnliSm96NW1nVE5Qb3NtUVN0N1dqaz0%3D&template=2&referral_code=HHnRBikmNBanPhmRBQQFDCJAACBMCHqJa";

    // ==================== Instance Fields ====================

    /**
     * Set at the start of {@link #run} — each instance is used exactly once.
     */
    private Page page;
    private ProfileEntity profile;
    private TaskLogger logger;
    private TaskContext context;

    // Generated data (populated during run)
    private Birthday birthday;
    private String password;

    private final ProfileRepository profileRepository = new ProfileRepository();

    // ==================== Constructor ====================

    /**
     * No-arg constructor for {@link ScriptRegistry} factory.
     */
    public SandwichGen() {
    }

    // ==================== AutomationScript Implementation ====================

    /**
     * Creates an Ike's rewards account using the configured profile.
     *
     * <p>This method performs the full account creation flow:</p>
     * <ol>
     *   <li>Navigates to the registration page</li>
     *   <li>Solves the initial captcha</li>
     *   <li>Fills in personal information</li>
     *   <li>Fills in account credentials</li>
     *   <li>Selects location preferences</li>
     *   <li>Submits the form and verifies success</li>
     * </ol>
     *
     * <p>On success, the completed profile is persisted with additional
     * notes including the generated password and birthday.</p>
     *
     * @param page    the browser page to automate
     * @param profile the profile containing user data
     * @param logger  the logger for live UI messages
     * @param context the task context for resource registration and cancellation
     * @throws Exception if account creation fails
     */
    @Override
    public void run(Page page, ProfileEntity profile, TaskLogger logger,
                    TaskContext context) throws Exception {
        this.page = page;
        this.profile = profile;
        this.logger = logger;
        this.context = context;

        // Generate transient data
        this.birthday = generateBirthday();
        this.password = generatePassword();

        logger.log("Creating account for: " + profile.emailAddress());
        logger.log("Birthday: " + birthday);

        navigateToRegistration();

        context.checkCancelled();

        solveInitialCaptcha();

        context.checkCancelled();

        fillPersonalInfo();

        context.checkCancelled();

        fillAccountCredentials();

        context.checkCancelled();

        selectLocationPreferences();

        context.checkCancelled();

        submitForm();

        persistCompletedProfile();

        logger.success("Account created successfully for: " + profile.emailAddress());
    }

    // ==================== Step Functions ====================

    /**
     * Step 1: Navigate to the registration page.
     */
    private void navigateToRegistration() {
        if (REFERRER_URL == null || REFERRER_URL.isBlank()) {
            throw new IllegalStateException("Referrer URL is required");
        }

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                logger.log("Navigating to registration page (attempt " + attempt + ")...");
                page.navigate(REFERRER_URL);
                return;

            } catch (AutomationException e) {
                logger.log("Navigation attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
                if (attempt == ATTEMPTS) {
                    throw new RuntimeException("Failed to navigate after " + ATTEMPTS + " attempts", e);
                }
                page.sleep(2000);
            }
        }
    }

    /**
     * Step 2: Solve the initial PerimeterX captcha.
     */
    private void solveInitialCaptcha() {
        logger.log("Solving initial captcha...");

        int captchaWait = 10000;

        for (int retry = 0; retry <= CAPTCHA_MAX_RETRIES; retry++) {
            PerimeterXSolver.solve(page, PerimeterXSolver.SolveOptions.builder()
                    .waitTimeoutMs(4000)
                    .build());

            if (page.exists(FIRST_NAME_TEXT)) {
                logger.log("Initial captcha passed");
                return;
            }

            if (retry < CAPTCHA_MAX_RETRIES) {
                page.sleep(captchaWait);
                captchaWait += 2000;
            }
        }

        throw new RuntimeException("Failed to pass initial captcha after " + (CAPTCHA_MAX_RETRIES + 1) + " attempts");
    }

    /**
     * Step 3: Fill in personal information (name, phone, birthday).
     */
    private void fillPersonalInfo() {
        logger.log("Filling personal information...");

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                fillFormField(FIRST_NAME_TEXT, profile.firstName(), true);
                fillFormField(LAST_NAME_TEXT, profile.lastName(), true);
                fillFormField(PHONE_NUMBER_TEXT, profile.shippingPhone(), false);

                // Birthday fields
                fillFormField(MONTH_TEXT, birthday.month(), true);
                fillFormField(DAY_TEXT, birthday.day(), true);
                fillFormField(YEAR_TEXT, birthday.year(), true);

                logger.log("Personal information filled");
                return;

            } catch (AutomationException e) {
                logger.log("Fill personal info attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
                if (attempt == ATTEMPTS) {
                    throw new RuntimeException("Failed to fill personal info after " + ATTEMPTS + " attempts", e);
                }
                page.sleep(1000);
            }
        }
    }

    /**
     * Step 4: Fill in account credentials (email, password, opt-in).
     */
    private void fillAccountCredentials() {
        logger.log("Filling account credentials...");

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                fillFormField(EMAIL_TEXT, profile.emailAddress(), true);
                fillFormField(PASSWORD_TEXT, password, true);
                fillFormField(CONFIRM_PASSWORD_TEXT, password, true);

                // Email opt-in checkbox
                page.click(EMAIL_OPT_IN_CHECKBOX);
                page.sleep(500);

                logger.log("Account credentials filled");
                return;

            } catch (AutomationException e) {
                logger.log("Fill credentials attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
                if (attempt == ATTEMPTS) {
                    throw new RuntimeException("Failed to fill credentials after " + ATTEMPTS + " attempts", e);
                }
                page.sleep(1000);
            }
        }
    }

    /**
     * Step 5: Select location preferences (state, store) and handle secondary captcha.
     */
    private void selectLocationPreferences() {
        logger.log("Selecting location preferences...");

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            // Scroll to bottom for dropdowns
            page.scrollToBottom();
            page.sleep(1000);

            // State selection
            page.click(STATE_DROPDOWN);
            page.sleep(2500);
            page.select(STATE_DROPDOWN, STATE_CALIFORNIA);
            page.sleep(3000);

            // Handle potential second captcha
            solveSecondaryCaptcha();

            // Store selection
            page.click(STORE_DROPDOWN);
            page.sleep(2500);
            page.select(STORE_DROPDOWN, STORE_DEL_MAR);
            page.sleep(1500);

            logger.log("Location preferences selected");
            return;

        }
    }

    /**
     * Handles the secondary captcha that sometimes appears after state selection.
     */
    private void solveSecondaryCaptcha() {
        int captchaWait = 4000;
        boolean logged = false;

        for (int retry = 0; retry <= CAPTCHA_MAX_RETRIES; retry++) {
            PerimeterXSolver.SolveResult result = PerimeterXSolver.solve(page, PerimeterXSolver.SolveOptions.builder()
                    .waitTimeoutMs(2000)
                    .build());

            // If no captcha found, we're done
            if (result.wasNotFound()) {
                return;
            }

            // Log only once that we detected a second captcha
            if (!logged && result.wasAttempted()) {
                logger.log("Secondary captcha detected for: " + profile.emailAddress());
                logged = true;
            }

            if (page.exists(FIRST_NAME_TEXT)) {
                return;
            }

            if (retry < CAPTCHA_MAX_RETRIES) {
                page.sleep(captchaWait);
                captchaWait += 2000;
            }
        }
    }

    /**
     * Step 6: Submit the form and verify success.
     */
    private void submitForm() {
        logger.log("Submitting form...");

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            page.click(SUBMIT_BUTTON);
            page.sleep(3000);

            page.waitForSelector(SUCCESS_MESSAGE, 15000);
            logger.log("Form submitted successfully");
            return;

        }
    }

    // ==================== Helper Methods ====================

    /**
     * Fills a form field with retry and validation logic.
     *
     * @param selector the XPath or CSS selector
     * @param value    the value to type
     * @param validate whether to validate the value was entered correctly
     */
    private void fillFormField(String selector, String value, boolean validate) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            page.fillFormField(selector, value, 900, 2000);

            if (!validate || page.validateValue(selector, value)) {
                return;
            }

            page.sleep(200);
            page.clear(selector);
        }

        throw new ElementNotInteractableException(
                "Failed to fill field after " + ATTEMPTS + " attempts: " + selector, selector);
    }

    /**
     * Persists the generated account data to the profile notes.
     */
    private void persistCompletedProfile() {
        String note = String.format("Password: %s:%s | Birthday: %s | Referrer: %s",
                profile.emailAddress(), password, birthday,
                REFERRER_URL != null ? REFERRER_URL : "");

        AutomationScript.persistNote(profile, note, profileRepository);
    }

    // ==================== Data Generation ====================

    /**
     * Generates a birthday for account registration.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>Month: Next month from current date (December wraps to January)</li>
     *   <li>Day: Always the 1st</li>
     *   <li>Year: Random between 1945 and (current_year - 20)</li>
     * </ul>
     *
     * @return a Birthday record with month, day, and year
     */
    private Birthday generateBirthday() {
        LocalDate today = LocalDate.now();

        // Next month (wrap December -> January)
        int nextMonth = today.getMonthValue() + 1;
        if (nextMonth > 12) {
            nextMonth = 1;
        }

        String month = String.format("%02d", nextMonth);
        String day = "01";

        int maxBirthYear = today.getYear() - MIN_AGE_YEARS;
        int randomYear = ThreadLocalRandom.current().nextInt(MIN_BIRTH_YEAR, maxBirthYear + 1);
        String year = String.valueOf(randomYear);

        return new Birthday(month, day, year);
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

    // ==================== Inner Classes ====================

    /**
     * Record holding generated birthday data.
     */
    private record Birthday(String month, String day, String year) {
        @Override
        public String toString() {
            return month + "/" + day + "/" + year;
        }
    }
}