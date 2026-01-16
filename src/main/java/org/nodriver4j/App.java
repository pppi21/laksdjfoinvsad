package org.nodriver4j;

import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserManager;

import java.io.IOException;
import java.util.List;
import java.util.Scanner;

public class App {

    public static void main(String[] args) {
        String executablePath = System.getenv("chromepath");

        if (executablePath == null || executablePath.isBlank()) {
            System.err.println("ERROR: 'chromepath' environment variable is not set.");
            System.err.println("Please set it to the path of your Chrome executable.");
            return;
        }

        System.out.println("==========================================");
        System.out.println("  NoDriver4j - Browser Automation Demo");
        System.out.println("==========================================");
        System.out.println("Chrome path: " + executablePath);
        System.out.println();

        BrowserManager manager = BrowserManager.builder()
                .executablePath(executablePath)
                .fingerprintEnabled(true)
                .webrtcPolicy("default")
                .proxyEnabled(false)
                .warmProfile(true)  // Enable auto-warming
                .build();

        List<Browser> browsers = null;

        try {
            System.out.println("==========================================");
            System.out.println("  Creating 3 browsers with auto-warming...");
            System.out.println("==========================================");
            System.out.println();

            // Single call creates all browsers AND warms them in parallel
            browsers = manager.createSessions(2);

            System.out.println();
            System.out.println("==========================================");
            System.out.println("  Browser Details");
            System.out.println("==========================================");

            for (int i = 0; i < browsers.size(); i++) {
                Browser browser = browsers.get(i);

                System.out.println();
                System.out.println("--- Browser " + (i + 1) + " ---");
                System.out.println("  Port:        " + browser.getPort());
                System.out.println("  Open:        " + browser.isOpen());
                System.out.println("  Running:     " + browser.isRunning());
                System.out.println("  Warmed:      " + browser.isWarmed());
                System.out.println("  Pages:       " + browser.getPageCount());

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
            }

            System.out.println();
            System.out.println("==========================================");
            System.out.println("  Summary");
            System.out.println("==========================================");
            System.out.println("  Browsers created:  " + browsers.size());
            System.out.println("  Active browsers:   " + manager.getActiveBrowserCount());
            System.out.println("  Available ports:   " + manager.getAvailablePortCount());
            System.out.println("  Warming enabled:   " + manager.isWarmProfileEnabled());
            System.out.println("==========================================");
            System.out.println();

            // Wait for user input before closing
            System.out.println("Browsers are ready for manual interaction.");
            System.out.println("Press ENTER to close all browsers and exit...");

            try (Scanner scanner = new Scanner(System.in)) {
                scanner.nextLine();
            }

        } catch (InterruptedException e) {
            System.err.println("Interrupted while creating browsers: " + e.getMessage());
            Thread.currentThread().interrupt();
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