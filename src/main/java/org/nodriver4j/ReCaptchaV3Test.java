package org.nodriver4j;

import org.nodriver4j.captcha.ReCaptchaV3Solver;
import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserConfig;
import org.nodriver4j.core.BrowserManager;
import org.nodriver4j.core.Page;

import java.util.Scanner;

public class ReCaptchaV3Test {

    private static final String URL = "https://recaptcha-demo.appspot.com/recaptcha-v3-request-scores.php";
    private static final String CUSTOM_CHROME = "C:\\Users\\leofo\\AppData\\Local\\Chromium\\Application\\chrome.exe";

    /**
     * Known values for this test site (discovered via page source).
     * The solver can extract the sitekey automatically, but we provide
     * it here as a fallback and for verification.
     */
    private static final String KNOWN_SITE_KEY = "6LdKlZEpAAAAAAOQjzC2v_d36tWxCl6dWsozdSy9";
    private static final String KNOWN_ACTION = "examples/v3scores";

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
        System.out.println("  NoDriver4j - reCAPTCHA v3 Test");
        System.out.println("==========================================");
        System.out.println("Chrome: " + executablePath);
        System.out.println("Target: " + URL);
        System.out.println();

        BrowserConfig config = BrowserConfig.builder()
                .executablePath(executablePath)
                .headless(false)
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

            // Step 1: Navigate and let the page load its own v3 challenge
            System.out.println("[Test] Navigating to " + URL);
            page.navigate(URL);

            // Step 3: Check if v3 is detected on the page
            System.out.println("[Test] Checking for reCAPTCHA v3 presence...");
            boolean present = ReCaptchaV3Solver.isPresent(page);
            System.out.println("[Test] reCAPTCHA v3 detected: " + present);

            if (!present) {
                System.err.println("[Test] No reCAPTCHA v3 found on page!");
                System.out.println("[Test] Attempting solve with known sitekey anyway...");
            }

            // Step 4: Attempt auto-extraction solve
            System.out.println();
            System.out.println("------------------------------------------");
            System.out.println("  Test A: Solve with auto sitekey extraction");
            System.out.println("------------------------------------------");

            ReCaptchaV3Solver.SolveResult autoResult = ReCaptchaV3Solver.solve(page, KNOWN_ACTION);
            printResult("Auto-extract", autoResult);

            // Step 5: Attempt explicit sitekey solve
            System.out.println();
            System.out.println("------------------------------------------");
            System.out.println("  Test B: Solve with explicit sitekey");
            System.out.println("------------------------------------------");

            ReCaptchaV3Solver.SolveResult explicitResult = ReCaptchaV3Solver.solve(page, KNOWN_ACTION, KNOWN_SITE_KEY);
            printResult("Explicit key", explicitResult);

            // Step 6: If we got a token, log a truncated preview
            if (explicitResult.hasToken()) {
                String token = explicitResult.token();
                String preview = token.length() > 40
                        ? token.substring(0, 20) + "..." + token.substring(token.length() - 20)
                        : token;
                System.out.println();
                System.out.println("[Test] Token preview: " + preview);
                System.out.println("[Test] Token length:  " + token.length());
            }

            // Keep browser open for manual inspection
            System.out.println();
            System.out.println("==========================================");
            System.out.println("  Browser is open for manual inspection.");
            System.out.println("  Press ENTER to close and exit...");
            System.out.println("==========================================");
            try (Scanner scanner = new Scanner(System.in)) {
                scanner.nextLine();
            }

        } catch (Exception e) {
            System.err.println("[Test] Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (browser != null) {
                browser.close();
            }
            manager.shutdown();
            System.out.println("[Test] Done.");
        }
    }

    private static void printResult(String label, ReCaptchaV3Solver.SolveResult result) {
        System.out.println();
        System.out.println("  [" + label + "] Success:           " + result.success());
        System.out.println("  [" + label + "] Injected into page: " + result.injectedIntoPage());

        if (result.success()) {
            System.out.println("  [" + label + "] Token length:       " + result.token().length());
        } else {
            System.out.println("  [" + label + "] Failure reason:     " + result.failureReason());
        }
    }
}