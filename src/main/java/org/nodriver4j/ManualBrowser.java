package org.nodriver4j;

import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserManager;
import org.nodriver4j.core.Page;
import org.nodriver4j.profiles.Profile;
import org.nodriver4j.profiles.ProfilePool;
import org.nodriver4j.scripts.SandwichGen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ManualBrowser {


    private static String URL = "https://recaptcha-demo.appspot.com/";


    // Number of concurrent browser sessions
    private static final int SESSION_COUNT = 1;

    public static void main(String[] args) {
        String executablePath = System.getenv("chromepath");

        if (executablePath == null || executablePath.isBlank()) {
            System.err.println("ERROR: 'chromepath' environment variable is not set.");
            System.err.println("Please set it to the path of your Chrome executable.");
            return;
        }

        System.out.println("==========================================");
        System.out.println("  NoDriver4j - Manual Browser Session");
        System.out.println("==========================================");
        System.out.println();

        // Build manager with optional explicit profile paths
        BrowserManager.Builder managerBuilder = BrowserManager.builder()
                .executablePath(executablePath)
                .fingerprintEnabled(true)
                .webrtcPolicy("default")
                .proxyEnabled(false)
                .headless(false)
                .warmProfile(false);


        BrowserManager manager = managerBuilder.build();
        List<Browser> browsers = null;

        try {

            System.out.println();

            // Create browser sessions (launches and warms all in parallel)
            browsers = manager.createSessions(SESSION_COUNT);

            System.out.println();
            System.out.println("==========================================");
            System.out.println("  Browser Details");
            System.out.println("==========================================");

            for (int i = 0; i < browsers.size(); i++) {
                Browser browser = browsers.get(i);

                System.out.println();
                System.out.println("--- Browser " + (i + 1) + " ---");

                if (browser.getFingerprint() != null) {
                    System.out.println("  Fingerprint: " + browser.getFingerprint());
                } else {
                    System.out.println("  Fingerprint: disabled");
                }

                if (browser.getProxyConfig() != null) {
                    System.out.println("  Proxy:       " + browser.getProxyConfig());
                } else {
                    System.out.println("  Proxy:       disabled");
                }

                Page page  = browser.getPage();

//                page.sleep(2000);
//                page.navigate(URL);
//                page.waitForSelector("p._1fr98s7[class*='1fr98s7']");
//                page.scrollIntoView("p._1fr98s7[class*='1fr98s7']");
//                page.screenshot();

            }

            // Wait for user input before closing
            System.out.println();
            System.out.println("Browsers are ready for manual interaction.");
            System.out.println("Press ENTER to close all browsers and exit...");

            try (Scanner scanner = new Scanner(System.in)) {
                scanner.nextLine();
            }

        } catch (InterruptedException e) {
            System.err.println("Interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Close all browsers
            System.out.println();
            System.out.println("==========================================");
            System.out.println("  Closing browsers...");
            System.out.println("==========================================");

            if (browsers != null) {
                for (int i = 0; i < browsers.size(); i++) {
                    try {
                        browsers.get(i).close();
                        System.out.println("  Browser " + (i + 1) + " closed.");
                    } catch (Exception e) {
                        System.err.println("  Error closing browser " + (i + 1) + ": " + e.getMessage());
                    }
                }
            }

            // Shutdown manager
            manager.shutdown();
            System.out.println();
            System.out.println("Done. Goodbye!");
        }
    }
}