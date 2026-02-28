package org.nodriver4j.scripts;

import org.nodriver4j.core.Page;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.services.GmailClient;
import org.nodriver4j.services.TaskLogger;

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
public class PurdueApp implements AutomationScript {

    private static final int ATTEMPTS = 3;

    // ==================== Form Selectors ====================

    private static final String EMAIL_TEXT = "#email";
    private static final String PASSWORD_TEXT = "#password";
    private static final String LOGIN_BUTTON = "button[type='submit']";
    private static final String APPLICATION_BUTTON = "a[href='//']";
    private static final String APPLICATION_BUTTON_CONFIRM = "button.default";
    private static final String FORM_BUTTON = "a[class='btn btn-bar btn-black']";
    private static final String SKIP_PHONE_BUTTON = "#alt-action-skip";
    private static final String FIRST_NAME_TEXT = "#FIRST_NAME";
    private static final String LAST_NAME_TEXT = "#LAST_NAME";
    private static final String CONTINUE_NAME_BUTTON = "#forward-button";
    private static final String ACCEPT_TERMS_CHECKBOX = "#LEGAL_ACCEPT_TERMS > span";
    private static final String CONTINUE_TERMS_BUTTON = "#forward-button";
    private static final String SKIP_SECURITY_BUTTON = "button[data-testid='skip']";
    private static final String CONTINUE_SECURITY_BUTTON = "#guided-security-upgrade-ui > div[data-baseweb='block'] > button";
    private static final String LCI_CONTENT = "Recently I wrapped up my internship with Pop Health LC and decided to refocus my attention on a personal project. For years I’ve used paid browser automation tools to run my reselling business. While making my own bot was enticing, there’s no quality YouTube tutorials on how to make fast, efficient, and undetectable browser automation. Despite the struggles and lack of learning resources, I decided to give it a shot. This is the 3rd time I’ve attempted to make an automation library and this time, after hundreds of hours, I think I’ve finally gotten it right. In the past I’ve tried simply using other people’s open-source automation libraries, but they consistently fall short in either performance, versatility, or low-level fingerprinting support. In a couple months, I believe I’ve created a library that, coupled with a patched chromium build I’ve put together, covers all three and more. While I would LOVE to include all of the technical details in this letter, I understand that you don’t have much time. Instead, I’ll explain the basic functionality of the app in this letter, then I’ll link the Github repo in case you’d like to see some of the specific parts of the app I’m proud of plus some demonstration videos.\n" +
            "\n" +
            "# NoDriver4j - browser automation framework and script runner with a UI\n" +
            "This app gives the user the ability to run hundreds of chrome-like browser sessions at once (given sufficient compute), all of which are following pre-written scripts that complete a certain task.\n" +
            "1. The app can automatically solve recaptcha v2, recaptcha v3 and PerimiterX press-and-hold captchas (with support for much more on the way). It can also connect through IMAP to an email inbox in order to fetch login passcodes. Similar functionality with text messages coming soon.\n" +
            "2. The app masks all common bot-detection properties in order to prevent blocks and frequent captchas. \n" +
            "3. The app mimics real-world hardware profiles and related parameters using direct C++ patches to the chromium source code. This helps multiple browsers on the same machine appear different to whichever site you visit.\n" +
            "4. The app’s underlying browser automation framework (which I plan to open-source) provides tools that simplify the script-writing process, such as page.click() and page.fillFormField(), which move an emulated cursor along a human-like path in order to interact with a webpage.\n" +
            "\n" +
            "It does much, much, more under the hood, these are just some user-facing features.\n" +
            "My ultimate vision for the future of this project has become clear recently. AI-controlled applications like OpenClaw struggle with passing bot protection. Wiring up OpenClaw to my browser framework would unlock OpenClaw to be able to complete much more complicated tasks without requiring user intervention.\n" +
            "\n" +
            "Above all else, I want this project to showcase the passion I have for the subject. I’d be honored if you took the time to watch the demo and look over the README:\n" +
            "https://github.com/pppi21/laksdjfoinvsad/blob/main/README.md (4 features I’m proud of)";

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
    public PurdueApp() {
    }

    // ==================== AutomationScript Implementation ====================

    /**
     * Executes the Uber Eats account generation workflow.
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
            logger.log("Navigating to Purdue Login...");
            navigateToPurdue();

            logger.log("Logging in with email...");
            loginWithEmail();

            logger.log("Waiting for email OTP...");
            openApplication();

            if (page.exists(FIRST_NAME_TEXT)) {
                logger.log("Entering name...");
                enterName();
            } else if (page.exists(SKIP_PHONE_BUTTON)) {
                logger.log("Skipping phone number...");
                navToForm();
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

        } catch (GmailClient.GmailClientException e) {
            logger.error("IMAP connection failed: " + e.getMessage());
            System.err.println("[UberGen] IMAP failure for " + profile.emailAddress()
                    + " (catchall: " + profile.catchallEmail() + "): " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("[UberGen] Cause: " + e.getCause());
            }
        } catch (UnexpectedNavigationException e) {
            logger.error("Unexpected navigation: " + e.url());
        } catch (TimeoutException e) {
            logger.error("Timeout: " + e.getMessage());
        }

        throw new RuntimeException("Signup failed unexpectedly for: " + profile.emailAddress());
    }

    // ==================== Navigation ====================

    private void navigateToPurdue() throws RuntimeException {

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                page.navigate("https://apply.purdue.edu/account/login");
                if (!page.exists(LOGIN_BUTTON)) {
                    return;
                }

            } catch (TimeoutException e) {
                logger.log("Navigate attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("navigateToPurdue failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    // ==================== Sign Up ====================

    private void loginWithEmail() throws RuntimeException {

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                fillFormField(EMAIL_TEXT, profile.emailAddress(), true);
                fillFormField(PASSWORD_TEXT, "P21PURrodarte$", true);
                page.click(LOGIN_BUTTON);
                page.waitForLoadEvent(15000);
                if(page.exists(APPLICATION_BUTTON)) return;

            } catch (TimeoutException | InterruptedException e) {
                logger.log("Sign up attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("loginWithEmail failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    // ==================== Email OTP ====================

    private void openApplication() throws RuntimeException {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                page.click(APPLICATION_BUTTON);
                page.click(APPLICATION_BUTTON_CONFIRM);
                if(page.exists(FORM_BUTTON)){
                    return;
                }
                page.navigate("https://apply.purdue.edu/apply/");

            } catch (TimeoutException e) {
                logger.log("Open app attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("openApplication failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    // ==================== Phone ====================

    private void navToForm() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                page.click(FORM_BUTTON);
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