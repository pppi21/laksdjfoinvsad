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
    private static final String LCI_TEXT = "#form_78e122f3-147b-4483-a197-666803027dce";
    private static final String LCI_CONTENT = "Recently I wrapped up my internship with Pop Health LC and decided to refocus my attention on a personal project. For years I've used paid browser automation tools to run my reselling business. While making my own bot was enticing, there's no quality YouTube tutorials on how to make fast, efficient, and undetectable browser automation. Despite the struggles and lack of learning resources, I decided to give it a shot. This is the 3rd time I've attempted to make an automation library and this time, after hundreds of hours, I think I've finally gotten it right. In the past I've tried simply using other people's open-source automation libraries, but they consistently fall short in either performance, versatility, or low-level fingerprinting support. In a couple months, I believe I've created a library that, coupled with a patched chromium build I've put together, covers all three and more. While I would LOVE to include all of the technical details in this letter, I understand that you don't have much time. Instead, I'll explain the basic functionality of the app in this letter, then I'll link the Github repo in case you'd like to see some of the specific parts of the app I'm proud of plus some demonstration videos.\n" +
            "\n" +
            "# NoDriver4j - browser automation framework and script runner with a UI\n" +
            "This app gives the user the ability to run hundreds of chrome-like browser sessions at once (given sufficient compute), all of which are following pre-written scripts that complete a certain task.\n" +
            "1. The app can automatically solve recaptcha v2, recaptcha v3 and PerimiterX press-and-hold captchas (with support for much more on the way). It can also connect through IMAP to an email inbox in order to fetch login passcodes. Similar functionality with text messages coming soon.\n" +
            "2. The app masks all common bot-detection properties in order to prevent blocks and frequent captchas. \n" +
            "3. The app mimics real-world hardware profiles and related parameters using direct C++ patches to the chromium source code. This helps multiple browsers on the same machine appear different to whichever site you visit.\n" +
            "4. The app's underlying browser automation framework (which I plan to open-source) provides tools that simplify the script-writing process, such as page.click() and page.fillFormField(), which move an emulated cursor along a human-like path in order to interact with a webpage.\n" +
            "\n" +
            "It does much, much, more under the hood, these are just some user-facing features.\n" +
            "My ultimate vision for the future of this project has become clear recently. AI-controlled applications like OpenClaw struggle with passing bot protection. Wiring up OpenClaw to my browser framework would unlock OpenClaw to be able to complete much more complicated tasks without requiring user intervention.\n" +
            "\n" +
            "Above all else, I want this project to showcase the passion I have for the subject. I'd be honored if you took the time to watch the demo and look over the README:\n" +
            "https://github.com/pppi21/laksdjfoinvsad/blob/main/README.md (4 features I'm proud of)";

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

        logger.log("Navigating to Purdue Login...");
        navigateToPurdue();

        logger.log("Logging in with email...");
        loginWithEmail();

        logger.log("Opening application...");
        openApplication();

        logger.log("Opening form...");
        navToForm();

        logger.log("Writing letter of continued interest...");
        writeLetter();

        logger.success("Successfully wrote letter of continued interest!");

        page.sleep(10000);
    }

    // ==================== Navigation ====================

    private void navigateToPurdue() throws RuntimeException {

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                if(attempt == 1) page.navigate("https://apply.purdue.edu/account/login");
                if (page.exists(LOGIN_BUTTON)) {
                    return;
                }
                page.sleep(2000);

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
                page.sleep(3000);
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
                page.waitForClickable(FORM_BUTTON,10000);
                page.sleep(2000);
                if(page.exists(FORM_BUTTON)){
                    return;
                }
                page.navigate("https://apply.purdue.edu/apply/");
                page.sleep(2000);

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

                // Click opens a new tab — wait for it and attach
                Page formPage = page.browser().waitForNewPageReady(10000);
                logger.log("Switched to form tab: " + page.browser().targetUrl(formPage.targetId()));

                // Replace the page reference so subsequent methods use the new tab
                this.page = formPage;
                page.sleep(2000);
                return;

            } catch (TimeoutException e) {
                logger.log("Form navigation attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
                page.sleep(1000);
            }
        }
        throw new RuntimeException("navToForm failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    // ==================== Name ====================

    private void writeLetter() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                fillFormField(LCI_TEXT, LCI_CONTENT, 4.8, true);
                return;
            } catch (InterruptedException | TimeoutException e) {
                logger.log("Name attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            }
        }
    }

    // ==================== Form Helpers ====================

    private void fillFormField(String selector, String value, boolean validate) throws InterruptedException, TimeoutException{
        fillFormField(selector, value, 1.0, validate);
    }

    private void fillFormField(String selector, String value, double multiplier, boolean validate) throws InterruptedException, TimeoutException {
        for (int attempt = 0; attempt <= ATTEMPTS; attempt++) {
            page.fillFormField(selector, value, 0, 0, multiplier);
            if (page.validateValue(selector, value) || !validate) {
                return;
            }
            page.sleep(200);
            page.clear(selector);
        }
        throw new TimeoutException("Failed to fill field after " + ATTEMPTS + " attempts: " + selector);
    }

}