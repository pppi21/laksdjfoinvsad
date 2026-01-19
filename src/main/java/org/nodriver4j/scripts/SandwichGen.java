package org.nodriver4j.scripts;

import org.nodriver4j.core.Page;
import org.nodriver4j.profiles.Profile;
import org.nodriver4j.profiles.ProfilePool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    /**
     * Creates an Ike's rewards account using the configured profile.
     *
     * <p>This method performs the full account creation flow:</p>
     * <ol>
     *   <li>Navigates to the registration page</li>
     *   <li>Fills in all form fields with profile data</li>
     *   <li>Generates a random birthday and password</li>
     *   <li>Submits the form</li>
     *   <li>Writes the completed profile to the output file</li>
     * </ol>
     *
     * <p>On success, the completed profile is written to the ProfilePool
     * with additional fields including the generated password and birthday.</p>
     *
     * @throws RuntimeException if navigation or form interaction fails
     */
    public void createAccount() {
        // Generate transient data
        Birthday birthday = generateBirthday();
        String password = generatePassword();

        System.out.println("[SandwichGen] Creating account for: " + profile.emailAddress());
        System.out.println("[SandwichGen] Birthday: " + birthday);

        try {
            // Navigate to registration page
            if (referrerUrl != null && !referrerUrl.isBlank()) {
                page.navigate(referrerUrl);
            } else {
                throw new IllegalStateException("Referrer URL is required");
            }

            int captchaWait = 10000;
            int retries = 0;
            while(retries <= 2) {
                page.solvePressHoldCaptcha(4000);

                if (page.exists(FIRST_NAME_TEXT))
                    break;
                page.sleep(captchaWait);
                captchaWait += 2000;
                retries++;
            }

            // Fill form fields
            fillFormField(FIRST_NAME_TEXT, profile.firstName(), 900, 2000);
            fillFormField(LAST_NAME_TEXT, profile.lastName(), 900, 2000);
            fillFormField(PHONE_NUMBER_TEXT, profile.shippingPhone(), 900, 2000);

            // Birthday fields
            fillFormField(MONTH_TEXT, birthday.month(), 900, 1300);
            fillFormField(DAY_TEXT, birthday.day(), 900, 1300);
            fillFormField(YEAR_TEXT, birthday.year(), 900, 2000);

            // Email and password
            fillFormField(EMAIL_TEXT, profile.emailAddress(), 900, 2000);
            fillFormField(PASSWORD_TEXT, password, 900, 2000);
            fillFormField(CONFIRM_PASSWORD_TEXT, password, 900, 2000);

            // Email opt-in
            page.click(EMAIL_OPT_IN_CHECKBOX);
            page.sleep(500);

            // Scroll to bottom for dropdowns
            page.scrollToBottom();
            page.sleep(1000);

            // State selection (California = 258)
            page.click(STATE_DROPDOWN);
            page.sleep(2500);
            page.select(STATE_DROPDOWN, "258");
            page.sleep(3000);

            int captchaWait2 = 4000;
            int retries2 = 0;
            while(retries2 <= 2) {
                page.solvePressHoldCaptcha(2000);

                if (page.exists(FIRST_NAME_TEXT))
                    break;

                if (retries2 == 0)
                    System.out.println("[SandwichGen] 2nd captcha detected for: " + profile.emailAddress());
                page.sleep(captchaWait2);
                captchaWait2 += 2000;
                retries2++;
            }

            // Store selection (Del Mar = 401)
            page.click(STORE_DROPDOWN);
            page.sleep(2500);
            page.select(STORE_DROPDOWN, "401");
            page.sleep(1500);

            // Submit form
            page.click(SUBMIT_BUTTON);
            page.sleep(3000);

            page.waitForSelector(SUCCESS_MESSAGE, 15000);
            writeCompletedProfile(password, birthday);

            System.out.println("[SandwichGen] Account created successfully for: " + profile.emailAddress());

        } catch (TimeoutException e) {
            throw new RuntimeException("Timeout during account creation: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during account creation", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write completed profile: " + e.getMessage(), e);
        }
    }

    /**
     * Fills a form field with click, delay, type, delay pattern.
     */
    private void fillFormField(String xpath, String value, int preTypeDelay, int postTypeDelay)
            throws TimeoutException, InterruptedException {
        page.click(xpath);
        page.sleep(preTypeDelay);
        page.type(value);
        page.sleep(postTypeDelay);
    }

    /**
     * Writes the completed profile to the output file with extra fields.
     */
    private void writeCompletedProfile(String password, Birthday birthday) throws IOException {
        Profile completed = profile.toBuilder()
                .accountLoginInfo(profile.emailAddress() + ":" + password)
                .extraField("Birthday Month", birthday.month())
                .extraField("Birthday Day", birthday.day())
                .extraField("Birthday Year", birthday.year())
                .extraField("Referrer URL", referrerUrl != null ? referrerUrl : "")
                .build();

        profilePool.writeCompleted(completed);
    }

    // ==================== Birthday Generation ====================

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

    // ==================== Password Generation ====================

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