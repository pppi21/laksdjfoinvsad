package org.nodriver4j;

import org.nodriver4j.captcha.PerimeterXSolver;
import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserConfig;
import org.nodriver4j.core.BrowserManager;
import org.nodriver4j.core.Page;

import java.util.Scanner;

public class HUMANCaptchaTest {

    private static final String URL = "https://www.wayfair.com/";
    private static final String CUSTOM_CHROME = "C:\\chromium\\src\\out\\Default\\chrome.exe";
    private static final String BOT_UA = "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/131.0.0.0 Safari/537.36";

    public static void main(String[] args) {
        // Prefer custom Chromium build, fall back to env var
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
        System.out.println("  NoDriver4j - PerimeterX Shadow DOM Test");
        System.out.println("==========================================");
        System.out.println("Chrome: " + executablePath);
        System.out.println();

        BrowserConfig config = BrowserConfig.builder()
                .executablePath(executablePath)
                .fingerprintEnabled(true)
                .argument(BOT_UA)
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

            System.out.println("Navigating to " + URL);
            page.navigate(URL);

            // Wait for page to settle
            page.sleep(3000);

            System.out.println("Attempting PerimeterX captcha solve...");
            PerimeterXSolver.SolveResult result = PerimeterXSolver.solve(page);

            System.out.println();
            System.out.println("==========================================");
            System.out.println("  Result: " + result.result());
            System.out.println("==========================================");

            if (result.wasAttempted()) {
                System.out.println("  Held for: " + result.detectedDurationMs() + "ms");
            } else if (result.wasNotFound()) {
                System.out.println("  No captcha detected — try a more obvious bot UA");
            } else if (result.hadError()) {
                System.out.println("  Error: " + result.errorMessage());
            }

            // Keep browser open for inspection
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