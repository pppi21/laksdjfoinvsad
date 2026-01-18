package org.nodriver4j.scripts;

import org.nodriver4j.core.*;
import org.nodriver4j.profiles.Profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeoutException;

public class SandwichGen {
    private static final String FIRST_NAME_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[3]/div/input";
    private static final String LAST_NAME_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[4]/div/input";
    private static final String PHONE_NUMBER_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[5]/div/input";
    private static final String  MONTH_TEXT = "/html/body/div[1]/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[6]/div/div/div[1]/input";
    private static final String DAY_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[6]/div/div/div[3]/input";
    private static final String YEAR_TEXT = "/html/body/div[1]/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[6]/div/div/div[5]/input";
    private static final String EMAIL_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[7]/div/input";
    private static final String PASSWORD_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[8]/div/input";
    private static final String CONFIRM_PASSWORD_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[9]/div/input[1]";
    private static final String EMAIL_OPT_IN_CHECKBOX = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[10]/div/input[1]";
    private static final String STATE_DROPDOWN = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[12]/div/div[1]/div/select";
    private static final String CA_BUTTON = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[12]/div/div[1]/div/select/option[4]";
    private static final String STORE_DROPDOWN = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[12]/div/div[2]/div/select";
    private static final String DEL_MAR_BUTTON = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[12]/div/div[2]/div/select/option[18]";
    private static final String SUBMIT_BUTTON = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[13]/div/button";

    private final Page page;
    private final Profile profile;
    private String referrerUrl = null;

    public SandwichGen(Page page, Profile profile) {
        this.page = page;
        this.profile = profile;
    }

    public SandwichGen(Page page, Profile profile, String referrerUrl) {
        this.page = page;
        this.profile = profile;
        this.referrerUrl = referrerUrl;
    }

    public CreatedIkesAccount createAccount(){
        String month = "02";
        String day = "01";
        String year = "2001";

        try {
            page.sleep(3000);
            page.navigate(referrerUrl, 4000);
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        try {
            page.click(FIRST_NAME_TEXT);
            page.sleep(900);
            page.type(profile.firstName());
            page.sleep(2000);
            page.click(LAST_NAME_TEXT);
            page.sleep(900);
            page.type(profile.lastName());
            page.sleep(2000);
            page.click(PHONE_NUMBER_TEXT);
            page.sleep(900);
            page.type(profile.billingPhone());
            page.sleep(2000);
            page.click(MONTH_TEXT);
            page.sleep(900);
            page.type(month);
            page.sleep(1300);
            page.click(DAY_TEXT);
            page.sleep(900);
            page.type(day);
            page.sleep(1300);
            page.click(YEAR_TEXT);
            page.sleep(900);
            page.type(year);
            page.sleep(2000);
            page.click(EMAIL_TEXT);
            page.sleep(900);
            page.type(profile.emailAddress());
            page.sleep(2000);
            page.click(PASSWORD_TEXT);
            page.sleep(900);
            page.type(generatePassword());
            page.sleep(2000);
            page.click(CONFIRM_PASSWORD_TEXT);
            page.sleep(900);
            page.type(profile.password());
            page.sleep(2000);
            page.click(EMAIL_OPT_IN_CHECKBOX);
            page.sleep(500);
            page.scrollToBottom();
            page.sleep(1000);
            page.click(STATE_DROPDOWN);
            page.sleep(2500);
            page.select(STATE_DROPDOWN, "258");
            page.sleep(3000);
            page.click(STORE_DROPDOWN);
            page.sleep(2500);
            page.select(STORE_DROPDOWN, "401");
            page.sleep(1500);
            //page.click(SUBMIT_BUTTON);

        } catch (TimeoutException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        CreatedIkesAccount account = new CreatedIkesAccount(profile, month, day, year, referrerUrl);
        return account;
    }

    private void screenshot() throws TimeoutException, IOException {
        byte[] pngBytes = page.screenshot();

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String filename = "screenshot_" + timestamp + ".png";

        Path outputPath = Path.of("C:\\Users\\leofo\\Documents\\NoDriver4j\\screenshots", filename);

        Files.createDirectories(outputPath.getParent());

        Files.write(outputPath, pngBytes);

        System.out.println("Screenshot saved to: " + outputPath);
    }

    public class CreatedIkesAccount {
        private final Profile profile;
        private String referrerUrl = null;
        private final String birthMonth;
        private final String birthYear;
        private final String birthDay;

        public CreatedIkesAccount(Profile profile, String month, String day, String year) {
            this.profile = profile;
            this.birthMonth = month;
            this.birthDay = day;
            this.birthYear = year;
        }

        public CreatedIkesAccount(Profile profile, String month, String day, String year, String referrerUrl) {
            this.profile = profile;
            this.birthMonth = month;
            this.birthDay = day;
            this.birthYear = year;
            this.referrerUrl = referrerUrl;
        }

        public String getBirthDate() {
            return this.birthYear + "-" + this.birthMonth + "-" + this.birthDay;
        }

        public String getReferrerUrl() {
            return this.referrerUrl;
        }

        public Profile getProfile() {
            return this.profile;
        }
    }


}
