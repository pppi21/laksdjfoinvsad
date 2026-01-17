package org.nodriver4j.scripts;

import org.nodriver4j.core.*;
import org.nodriver4j.profiles.Profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

public class SandwichGen {

    private static final int PAGE_LOAD_TIMEOUT_MS = 30000;
    private static final String FIRST_NAME_TEXT = "//*[@id=\"firstName\"]";
    private static final String LAST_NAME_TEXT = "/html/body/div/div[2]/div[1]/div[2]/div/div/div/div[2]/form/div/div[4]/div/input";

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
            byte[] pngBytes = page.screenshot();
            Files.write(Path.of("C:\\Users\\leofo\\Documents\\NoDriver4j\\data\\screenshot.png"), pngBytes);
        } catch (TimeoutException | InterruptedException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        CreatedIkesAccount account = new CreatedIkesAccount(profile, month, day, year, referrerUrl);
        return account;
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
