package org.nodriver4j;

import org.nodriver4j.captcha.ReCaptchaSolver;
import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserConfig;
import org.nodriver4j.core.BrowserManager;
import org.nodriver4j.core.Page;

import java.util.Scanner;

public class ReCaptchaV2Test {

    private static final String URL = "https://recaptcha-demo.appspot.com/recaptcha-v2-checkbox.php";
    private static final String CUSTOM_CHROME = "C:\\Users\\leofo\\AppData\\Local\\Chromium\\Application\\chrome.exe";

    public static void main(String[] args) {
        String executablePath = CUSTOM_CHROME;
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(executablePath))) {
            executablePath = System.getenv("chromepath");
        }

        if (executablePath == null || executablePath.isBlank()) {
            System.err.println("ERROR: Chrome executable not found.");
            System.err.println("Expected at: " + CUSTOM_CHROME);
            System.err.println("Or set 'chromepath' environment variable.");
            return;
        }

        System.out.println("==========================================");
        System.out.println("  NoDriver4j - reCAPTCHA v2 Checkbox Test");
        System.out.println("==========================================");
        System.out.println("Chrome: " + executablePath);
        System.out.println();

        BrowserConfig config = BrowserConfig.builder()
                .executablePath(executablePath)
                .fingerprintEnabled(true)
                .headless(false)
                .autoSolveAIKey(System.getenv("autosolve_key"))
                .build();

        BrowserManager manager = BrowserManager.builder()
                .config(config)
                .proxyEnabled(false)
                .warmProfile(false)
                .build();

        Browser browser = null;

        try {
            browser = manager.createSession();
            Page page = browser.page();

            System.out.println("Navigating to " + URL);
            page.navigate(URL);

            page.sleep(3000);

            System.out.println("Checking for reCAPTCHA presence...");
            if (!ReCaptchaSolver.isPresent(page)) {
                System.err.println("No reCAPTCHA found on page!");
                return;
            }

            System.out.println("reCAPTCHA detected. Attempting solve...");
            ReCaptchaSolver.SolveResult result = ReCaptchaSolver.solve(page);

            System.out.println();
            System.out.println("==========================================");
            System.out.println("  Success: " + result.success());
            System.out.println("  Rounds:  " + result.roundsCompleted());
            System.out.println("==========================================");

            if (!result.success()) {
                System.out.println("  Failure reason: " + result.failureReason());
            }

            if (result.hasWarnings()) {
                System.out.println("  Warnings:");
                result.warnings().forEach(w -> System.out.println("    - " + w));
            }

            System.out.println();
            System.out.println("Press ENTER to close browser and exit...");
            try (Scanner scanner = new Scanner(System.in)) {
                scanner.nextLine();
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (browser != null) {
                browser.close();
            }
            manager.shutdown();
            System.out.println("Done.");
        }
    }
}