package org.nodriver4j.scripts;

import org.nodriver4j.captcha.PerimeterXSolver;
import org.nodriver4j.core.Page;
import org.nodriver4j.profiles.Profile;
import org.nodriver4j.profiles.ProfilePool;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/**
 * Automation script for creating Ike's sandwich rewards accounts.
 *
 * <p>This script automates the account registration process on the Ike's
 * rewards platform using profile data from CSV files.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * ProfilePool pool = manager.profilePool();
 * Profile profile = pool.consumeFirst();
 *
 * SandwichGen script = new SandwichGen(page, profile, pool, referrerUrl);
 * script.createAccount();
 * // Profile is automatically written to output on success
 * }</pre>
 *
 * @see Profile
 * @see ProfilePool
 */
public class SandwichGen {

    // ==================== Retry Configuration ====================

    private static final int MAX_ATTEMPTS = 3;
    private static final int CAPTCHA_MAX_RETRIES = 3;

    // ==================== Form XPaths ====================

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

    // ==================== Fields ====================

    private final Page page;
    private final Profile profile;
    private final ProfilePool profilePool;
    private final String referrerUrl;

    // Generated data (populated during createAccount)
    private Birthday birthday;
    private String password;

    /**
     * Creates a SandwichGen script without a referrer URL.
     *
     * @param page        the Page to automate
     * @param profile     the profile containing user data
     * @param profilePool the pool for writing completed profiles
     */
    public SandwichGen(Page page, Profile profile, ProfilePool profilePool) {
        this(page, profile, profilePool, null);
    }

    /**
     * Creates a SandwichGen script with a referrer URL.
     *
     * @param page        the Page to automate
     * @param profile     the profile containing user data
     * @param profilePool the pool for writing completed profiles
     * @param referrerUrl the referral link URL (or null for default)
     */
    public SandwichGen(Page page, Profile profile, ProfilePool profilePool, String referrerUrl) {
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
        this.referrerUrl = referrerUrl;
    }

    // ==================== Main Entry Point ====================

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
     * <p>On success, the completed profile is written to the ProfilePool
     * with additional fields including the generated password and birthday.</p>
     *
     * @throws RuntimeException if account creation fails after all retries
     */
    public void createAccount() {
        // Generate transient data
        this.birthday = generateBirthday();
        this.password = generatePassword();

        System.out.println("[SandwichGen] Creating account for: " + profile.emailAddress());
        System.out.println("[SandwichGen] Birthday: " + birthday);

        try {
            navigateToRegistration();
            solveInitialCaptcha();
            fillPersonalInfo();
            fillAccountCredentials();
            selectLocationPreferences();
            submitForm();

            writeCompletedProfile();
            System.out.println("[SandwichGen] ✓ Account created successfully for: " + profile.emailAddress());

        } catch (Exception e) {
            System.err.println("[SandwichGen] ✗ Account creation failed for: " + profile.emailAddress());
            throw new RuntimeException("Account creation failed: " + e.getMessage(), e);
        }
    }

    // ==================== Step Functions ====================

    /**
     * Step 1: Navigate to the registration page.
     */
    private void navigateToRegistration() {
        if (referrerUrl == null || referrerUrl.isBlank()) {
            throw new IllegalStateException("Referrer URL is required");
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                System.out.println("[SandwichGen] Navigating to registration page (attempt " + attempt + ")...");
                page.navigate(referrerUrl);
                return;

            } catch (TimeoutException e) {
                System.err.println("[SandwichGen] Navigation attempt " + attempt + " failed: " + e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    throw new RuntimeException("Failed to navigate after " + MAX_ATTEMPTS + " attempts", e);
                }
                page.sleep(2000);
            }
        }
    }

    /**
     * Step 2: Solve the initial PerimeterX captcha.
     */
    private void solveInitialCaptcha() {
        System.out.println("[SandwichGen] Solving initial captcha...");

        int captchaWait = 10000;

        for (int retry = 0; retry <= CAPTCHA_MAX_RETRIES; retry++) {
            PerimeterXSolver.solve(page, PerimeterXSolver.SolveOptions.builder()
                    .waitTimeoutMs(4000)
                    .build());

            try {
                if (page.exists(FIRST_NAME_TEXT)) {
                    System.out.println("[SandwichGen] Initial captcha passed");
                    return;
                }
            } catch (TimeoutException e) {
                // Continue to retry
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
        System.out.println("[SandwichGen] Filling personal information...");

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                fillFormField(FIRST_NAME_TEXT, profile.firstName(), true);
                fillFormField(LAST_NAME_TEXT, profile.lastName(), true);
                fillFormField(PHONE_NUMBER_TEXT, profile.shippingPhone(), false);

                // Birthday fields
                fillFormField(MONTH_TEXT, birthday.month(), true);
                fillFormField(DAY_TEXT, birthday.day(), true);
                fillFormField(YEAR_TEXT, birthday.year(), true);

                System.out.println("[SandwichGen] Personal information filled");
                return;

            } catch (TimeoutException e) {
                System.err.println("[SandwichGen] Fill personal info attempt " + attempt + " failed: " + e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    throw new RuntimeException("Failed to fill personal info after " + MAX_ATTEMPTS + " attempts", e);
                }
                page.sleep(1000);
            }
        }
    }

    /**
     * Step 4: Fill in account credentials (email, password, opt-in).
     */
    private void fillAccountCredentials() {
        System.out.println("[SandwichGen] Filling account credentials...");

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                fillFormField(EMAIL_TEXT, profile.emailAddress(), true);
                fillFormField(PASSWORD_TEXT, password, true);
                fillFormField(CONFIRM_PASSWORD_TEXT, password, true);

                // Email opt-in checkbox
                page.click(EMAIL_OPT_IN_CHECKBOX);
                page.sleep(500);

                System.out.println("[SandwichGen] Account credentials filled");
                return;

            } catch (TimeoutException e) {
                System.err.println("[SandwichGen] Fill credentials attempt " + attempt + " failed: " + e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    throw new RuntimeException("Failed to fill credentials after " + MAX_ATTEMPTS + " attempts", e);
                }
                page.sleep(1000);
            }
        }
    }

    /**
     * Step 5: Select location preferences (state, store) and handle secondary captcha.
     */
    private void selectLocationPreferences() {
        System.out.println("[SandwichGen] Selecting location preferences...");

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
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

                System.out.println("[SandwichGen] Location preferences selected");
                return;

            } catch (TimeoutException e) {
                System.err.println("[SandwichGen] Select location attempt " + attempt + " failed: " + e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    throw new RuntimeException("Failed to select location after " + MAX_ATTEMPTS + " attempts", e);
                }
                page.sleep(1000);
            }
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
                System.out.println("[SandwichGen] Secondary captcha detected for: " + profile.emailAddress());
                logged = true;
            }

            try {
                if (page.exists(FIRST_NAME_TEXT)) {
                    return;
                }
            } catch (TimeoutException e) {
                // Continue to retry
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
        System.out.println("[SandwichGen] Submitting form...");

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                page.click(SUBMIT_BUTTON);
                page.sleep(3000);

                page.waitForSelector(SUCCESS_MESSAGE, 15000);
                System.out.println("[SandwichGen] Form submitted successfully");
                return;

            } catch (TimeoutException e) {
                System.err.println("[SandwichGen] Submit attempt " + attempt + " failed: " + e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    throw new RuntimeException("Failed to submit form after " + MAX_ATTEMPTS + " attempts", e);
                }
                page.sleep(2000);
            }
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Fills a form field with retry and validation logic.
     *
     * @param selector the XPath or CSS selector
     * @param value    the value to type
     * @param validate whether to validate the value was entered correctly
     * @throws TimeoutException if the field cannot be filled after retries
     */
    private void fillFormField(String selector, String value, boolean validate) throws TimeoutException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                page.fillFormField(selector, value, 900, 2000);

                if (!validate || page.validateValue(selector, value)) {
                    return;
                }

                // Value mismatch - clear and retry
                page.sleep(200);
                page.clear(selector);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TimeoutException("Interrupted while filling field: " + selector);
            } catch (TimeoutException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                page.sleep(500);
            }
        }

        throw new TimeoutException("Failed to fill field after " + MAX_ATTEMPTS + " attempts: " + selector);
    }

    /**
     * Writes the completed profile to the output file with extra fields.
     */
    private void writeCompletedProfile() throws IOException {
        Profile completed = profile.toBuilder()
                .accountLoginInfo(profile.emailAddress() + ":" + password)
                .extraField("Birthday Month", birthday.month())
                .extraField("Birthday Day", birthday.day())
                .extraField("Birthday Year", birthday.year())
                .extraField("Referrer URL", referrerUrl != null ? referrerUrl : "")
                .build();

        profilePool.writeCompleted(completed);
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

        // Format month as 2-digit string
        String month = String.format("%02d", nextMonth);

        // Day is always 1st
        String day = "01";

        // Year: 1945 to (current_year - 20)
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