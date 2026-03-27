package org.nodriver4j.scripts;

import org.nodriver4j.core.Browser;
import org.nodriver4j.core.Page;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.services.*;


import java.io.IOException;
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
public class BrowserScan implements AutomationScript {

    private static final int ATTEMPTS = 3;

    // ==================== Form Selectors ====================

    private static final String BOT_DETECTION_BUTTON = "#browserscan > main > div._1kipv3a > div > div > div:nth-child(1) > ul > li:nth-child(3) > a > div > div";
    private static final String HEADLESS_CHROME_ID = "#browserscan > main > div > div > div:nth-child(8) > div._1zc4yn._tivc4w > div:nth-child(16)";
    private static final String CDP_ID = "#browserscan > main > div > div > div:nth-child(10) > div._1zc4yn > div._1g6odkd > div:nth-child(1) > div._1pu5vjm > div > div";
    private static final String APPCODENAME_ID = "#browserscan > main > div > div > div:nth-child(11) > div._1zc4yn._tivc4w > div:nth-child(14)";
    private static final String CREDENTIALS_ID = "#browserscan > main > div._1v8q0td > div > div:nth-child(11) > div._1zc4yn._tivc4w > div:nth-child(38)";
    private static final String RUNADAUCTION_ID = "#browserscan > main > div._1v8q0td > div > div:nth-child(11) > div._1zc4yn._tivc4w > div:nth-child(63)";
    private static final String UNREGISTERPROTOCOLHANDER_ID = "#browserscan > main > div._1v8q0td > div > div:nth-child(11) > div._1zc4yn._tivc4w > div:nth-child(84)";


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
    public BrowserScan() {
    }

    // ==================== AutomationScript Implementation ====================

    @Override
    public void run(Page page, ProfileEntity profile, TaskLogger logger,
                    TaskContext context) throws Exception {
        this.page = page;
        this.profile = profile;
        this.logger = logger;
        this.context = context;

        try {
            logger.log("Navigating to Browser Scan...");
            navigateToBrowserScan();

            logger.success("Signup successful for: " + profile.emailAddress());
            return;
        } catch (RuntimeException e){
            logger.error(e.getMessage());
        }

        throw new RuntimeException("Signup failed unexpectedly for: " + profile.emailAddress());
    }

    // ==================== Navigation ====================

    private void navigateToBrowserScan() throws RuntimeException {

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                page.navigate("https://www.browserscan.net/");
                page.sleep(7000);
                page.screenshot();
                Browser browser = page.browser();
                page.click(BOT_DETECTION_BUTTON);
                page = browser.waitForNewPageReady(10000);
                browser.activatePage(page);
                waitForLoadEvent(5000);
                page.screenshot(HEADLESS_CHROME_ID);
                page.screenshot(CDP_ID);
                page.screenshot(APPCODENAME_ID);
                page.screenshot(CREDENTIALS_ID);
                page.screenshot(RUNADAUCTION_ID);
                page.screenshot(UNREGISTERPROTOCOLHANDER_ID);
                page.sleep(5000);
                return;

            } catch (TimeoutException e) {
                logger.log("Navigate attempt " + attempt + "/" + ATTEMPTS + " failed: " + e.getMessage());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("navigateToUber failed: Maximum " + ATTEMPTS + " attempts reached");
    }

    // ==================== Helpers ====================

    private void waitForLoadEvent(int timeout) {
        page.waitForLoadEvent(timeout);
    }
}