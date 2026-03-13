package org.nodriver4j.scripts;

import org.nodriver4j.captcha.ArkoseSolver;
import org.nodriver4j.captcha.ReCaptchaSolver;
import org.nodriver4j.core.Page;
import org.nodriver4j.persistence.Settings;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.services.*;
import org.nodriver4j.services.sms.SmsProviderException;
import org.nodriver4j.services.imap.EmailPollingBase;
import org.nodriver4j.services.imap.GmailClient;
import org.nodriver4j.services.imap.impl.UberOtpExtractor;
import org.nodriver4j.services.response.sms.SmsActivation;
import org.nodriver4j.services.sms.DaisySmsService;
import org.nodriver4j.services.sms.SmsService;

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
public class UberGen implements AutomationScript {

    private static final int ATTEMPTS = 3;

    // ==================== Form Selectors ====================

    private static final String GOOGLE_SEARCH_TEXT = "textarea[aria-label='Search']";
    private static final String UE_RESULT_BUTTON = "a[href^='https://www.ubereats.com/'] > :nth-child(1)";
    private static final String SIGN_IN_BUTTON = "[tabindex='0'][href^='https://auth.uber.com/v2/']";
    private static final String EMAIL_TEXT = "input#PHONE_NUMBER_or_EMAIL_ADDRESS[type='email']";
    private static final String SUMBIT_EMAIL_BUTTON = "#forward-button";
    private static final String EMAIL_OTP_TEXT = "#EMAIL_OTP_CODE-0";
    private static final String EMAIL_OTP_RESEND_BUTTON = "#alt-action-resend";
    private static final String EMAIL_OTP_RESEND_CONFIRM_BUTTON = "#alt-action-resend[aria-label='Resend']";
    private static final String SKIP_PHONE_BUTTON = "#alt-action-skip";
    private static final String FIRST_NAME_TEXT = "#FIRST_NAME";
    private static final String LAST_NAME_TEXT = "#LAST_NAME";
    private static final String CONTINUE_NAME_BUTTON = "#forward-button";
    private static final String ACCEPT_TERMS_CHECKBOX = "#LEGAL_ACCEPT_TERMS > span";
    private static final String CONTINUE_TERMS_BUTTON = "#forward-button";
    private static final String SKIP_SECURITY_BUTTON = "button[data-testid='skip']";
    private static final String CONTINUE_SECURITY_BUTTON = "button[data-testid='gsu-next']";
    private static final String PHONE_NUMBER_TEXT = "input[type='text']";
    private static final String UPDATE_PHONE_BUTTON = "button[aria-label='Update']";
    private static final String PHONE_INPUT_ERROR = "p[data-testid='phone-input-error']";
    private static final String HOMEPAGE_SUCCESS_ID = "#main-content";

    // ==================== Instance Fields ====================

    /**
     * Set at the start of {@link #run} — each instance is used exactly once.
     */
    private Page page;
    private ProfileEntity profile;
    private TaskLogger logger;
    private TaskContext context;
    private String phoneNumber;

    // ==================== Constructor ====================

    /**
     * No-arg constructor for {@link ScriptRegistry} factory.
     */
    public UberGen() {
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
            logger.log("Navigating to Uber Eats...");
            navigateToUber();

            context.checkCancelled();

            logger.log("Signing up with email...");
            signUpWithEmail();

            context.checkCancelled();

            logger.log("Waiting for email OTP...");
            enterEmailOTP();

            context.checkCancelled();

            for (int i = 0; i < 3; i++) {
                if (page.exists(FIRST_NAME_TEXT)) {
                    logger.log("Entering name...");
                    enterName();
                    break;
                } else if (page.exists(SKIP_PHONE_BUTTON)) {
                    logger.log("Skipping phone number...");
                    skipPhoneNumber();
                    logger.log("Entering name...");
                    enterName();
                    break;
                } else {
                    page.sleep(5000);
                }
            }

            context.checkCancelled();

            logger.log("Accepting terms...");
            acceptTerms();

            page.sleep(5000);
            if (page.exists(SKIP_SECURITY_BUTTON)) {
                logger.log("Skipping security prompt...");
                skipSecurity();
            } else {
                logger.log("Verifying phone number...");
                verifyPhone();
            }

            logger.success("Signup successful for: " + profile.emailAddress());
            return;

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

    private void navigateToUber() throws RuntimeException {

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                page.navigate("https://www.google.com/");
                page.sleep(3000);
                fillFormField(GOOGLE_SEARCH_TEXT, "uber eats", true);
                page.pressKey("Enter", false, false, false);
                page.sleep(1000);
                waitForLoadEvent(10000);
                if (!page.exists(UE_RESULT_BUTTON)) {
                    ReCaptchaSolver.solve(page);
                    page.waitForSelector(UE_RESULT_BUTTON, 15000);
                }
                page.sleep(1500);
                page.click(UE_RESULT_BUTTON);
                page.waitForSelector(SIGN_IN_BUTTON, 60000);
                return;

            } catch (TimeoutException | InterruptedException e) {
                logger.log("Navigate attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("navigateToUber failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    // ==================== Sign Up ====================

    private void signUpWithEmail() throws RuntimeException {

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                int signInSeed = (int) (Math.random() * 100);
                if (attempt > 1) page.reload(true, 30000);
                // ArkoseSolver.installEnforcementHook(page);
                page.click(SIGN_IN_BUTTON);
                page.waitForLoadEvent(20000);
                page.sleep(2000);
                fillFormField(EMAIL_TEXT, profile.emailAddress(), !Character.isDigit(profile.emailAddress().charAt(0)));
                if (signInSeed % 3 == 0) {
                    page.pressKey("Enter", false, false, false);
                } else {
                    page.click(SUMBIT_EMAIL_BUTTON);
                }
                page.waitForSelector(EMAIL_OTP_TEXT, 15000);
                return;

            } catch (TimeoutException | InterruptedException e) {
                logger.log("Sign up attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("signUpWitfhEmail failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    // ==================== Email OTP ====================

    private void enterEmailOTP() throws GmailClient.GmailClientException {
        String catchall = profile.catchallEmail();
        String imap = profile.imapPassword();

        logger.log("IMAP credentials — catchall: " + catchall
                + ", password: " + (imap == null ? "NULL" : imap.length() + " chars"));

        try (UberOtpExtractor extractor = context.register(new UberOtpExtractor(
                profile.emailAddress(), catchall, imap))) {

            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                try {
                    if (attempt > 1 || !page.exists(EMAIL_OTP_TEXT)) {
                        page.reload(true, 30000);
                        page.click(EMAIL_OTP_RESEND_BUTTON);
                        page.sleep(2500);
                        page.click(EMAIL_OTP_RESEND_CONFIRM_BUTTON);
                    }
                    String otp = extractor.extractOtp();
                    logger.log("Retrieved email OTP: " + otp);
                    fillFormField(EMAIL_OTP_TEXT, otp, false);
                    page.sleep(1500);
                    return;

                } catch (EmailPollingBase.EmailExtractionException | InterruptedException | TimeoutException e) {
                    logger.log("OTP attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
                } catch (GmailClient.GmailClientException e) {
                    logger.log("OTP attempt " + attempt + "/" + ATTEMPTS
                            + " — IMAP error: " + e.getMessage()
                            + (e.getCause() != null ? " [cause: " + e.getCause().getClass().getSimpleName() + "]" : ""));

                    // Auth failures won't resolve by retrying with the same credentials
                    if (e.getMessage() != null && e.getMessage().contains("Authentication failed")) {
                        throw e;
                    }
                }
            }
            throw new RuntimeException("Email OTP failed: Maximum " + ATTEMPTS + " attempts reached");
        }
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

    private void verifyPhone() throws RuntimeException {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                page.waitForSelector(CONTINUE_SECURITY_BUTTON, 4000);
                page.click(CONTINUE_SECURITY_BUTTON);
                page.sleep(1000);
                if(!page.exists(PHONE_NUMBER_TEXT)) {
                    page.click(CONTINUE_SECURITY_BUTTON);
                }

                String daisyKey = Settings.get().daisySmsApiKey();
                try (DaisySmsService sms = context.register(new DaisySmsService(daisyKey, context))) {
                    SmsActivation activation = sms.requestNumber(SmsService.UBER);
                    logger.log("Phone number: " + activation.phoneNumber());

                    fillFormField(PHONE_NUMBER_TEXT, activation.phoneNumber(), true);
                    page.click(UPDATE_PHONE_BUTTON);
                    page.sleep(3000);
                    if(page.exists(UPDATE_PHONE_BUTTON)) {
                        page.jsClick(UPDATE_PHONE_BUTTON);
                    }
                    phoneNumber = activation.phoneNumber();

                    String code = sms.pollForCode(activation);
                    logger.log("Phone OTP: " + code);
                    fillFormField("input", code, false);

                    sms.completeActivation(activation.activationId());
                }

                page.sleep(20000);
                if (!page.exists(PHONE_NUMBER_TEXT)) {
                    profile.shippingPhone(phoneNumber);
                    return;
                }
            } catch (TimeoutException | InterruptedException e) {
                logger.log("Security attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            } catch (SmsProviderException e) {
                logger.log("SMS attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("Verify phone failed: session likely flagged");
    }

    // ==================== Helpers ====================

    private void fillFormField(String selector, String value, boolean validate) throws InterruptedException, TimeoutException {
        for (int attempt = 0; attempt <= ATTEMPTS; attempt++) {
            page.fillFormField(selector, value, randomDelay(), randomDelay(), randomSpeed());
            if (page.validateValue(selector, value) || !validate) {
                return;
            }
            page.sleep(200);
            page.clear(selector);
        }
        throw new TimeoutException("Failed to fill field after " + ATTEMPTS + " attempts: " + selector);
    }

    private int randomDelay(){
        return 300 + (int)(Math.random() * 600);
    }

    private double randomSpeed(){
        return 0.8 + (Math.random() * 0.5);
    }

    private void waitForLoadEvent(int timeout) {
        try{page.waitForLoadEvent(timeout);}catch(TimeoutException _){}
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