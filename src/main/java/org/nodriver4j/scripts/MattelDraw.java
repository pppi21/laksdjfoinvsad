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
public class MattelDraw {

    private static final String PRODUCT_URL = "https://creations.mattel.com/pages/2025-super-treasure-hunt-draw";
    private static final int RETRIES = 2;
    // ==================== Form XPaths ====================

    private static final String CLOSE_POPUP_BUTTON = "button[aria-label='Close dialog']";
    private static final String REJECT_COOKIES_BUTTON = "button[id='truste-consent-required']";
    private static final String FIRST_NAME_TEXT = "/html/body/div[8]/main/div[4]/section/div/div/div/form/div/div[3]/div[1]/div/input";
    private static final String LAST_NAME_TEXT = "/html/body/div[8]/main/div[4]/section/div/div/div/form/div/div[3]/div[2]/div/input";
    private static final String EMAIL_TEXT = "/html/body/div[8]/main/div[4]/section/div/div/div/form/div/div[4]/div/div/input";
    private static final String SUBMIT_BUTTON = "/html/body/div[8]/main/div[4]/section/div/div/div/form/div/div[5]/div/button";
    private static final String SUCCESS_MESSAGE = "span[class='ql-font-poppins']";

    // ==================== Fields ====================

    private final Page page;
    private final Profile profile;
    private final ProfilePool profilePool;


    /**
     * Creates a MattelDraw script with a referrer URL.
     *
     * @param page        the Page to automate
     * @param profile     the profile containing user data
     * @param profilePool the pool for writing completed profiles
     */
    public MattelDraw(Page page, Profile profile, ProfilePool profilePool) {
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
    public void enterDraw() {

        System.out.println("[MattelDraw] Entering draw for: " + profile.emailAddress());

        try {
            // Navigate to registration page
            page.navigate(PRODUCT_URL);
            rejectCookies();
            fillFormField(FIRST_NAME_TEXT, profile.firstName());
            fillFormField(LAST_NAME_TEXT, profile.lastName());
            fillFormField(EMAIL_TEXT, profile.emailAddress());
            submit(RETRIES);

        } catch (TimeoutException e) {
            throw new RuntimeException("Timeout during account creation: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void fillFormField(String selector, String value) throws InterruptedException, TimeoutException {
        checkForPopup();
        page.fillFormField(selector, value, 900,1100);
        if(!page.validateValue(selector, value)) {
            checkForPopup();
            page.sleep(500);
            page.clear(selector);
            fillFormField(selector,value);
        }
    }

    private boolean checkForPopup() throws TimeoutException {
        if(page.exists(CLOSE_POPUP_BUTTON)){
            page.click(CLOSE_POPUP_BUTTON);
            return true;
        }
        return false;
    }

    private void rejectCookies() throws TimeoutException {
        while(!page.exists(REJECT_COOKIES_BUTTON)){
            page.sleep(1000);
        }
        page.click(REJECT_COOKIES_BUTTON);
        page.sleep(1500);
    }

    private boolean submit(int retries) throws TimeoutException {
        page.sleep(2000);
        checkForPopup();
        page.click(SUBMIT_BUTTON);
        if(!page.exists(SUCCESS_MESSAGE) && retries > 0){
            submit(retries-1);
        }
        return true;
    }



    /**
     * Writes the completed profile to the output file with extra fields.
     */
    private void writeCompletedProfile() throws IOException {
        Profile completed = profile.toBuilder()
                .build();

        profilePool.writeCompleted(completed);
    }
}