package org.nodriver4j;

import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserManager;
import org.nodriver4j.core.Page;
import org.nodriver4j.captcha.ReCaptchaSolver;
import org.nodriver4j.services.AutoSolveAIService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class HUMANCaptchaTest {

    private static final String URL = "https://recaptcha-demo.appspot.com/";
    private static final String HEADLESS_UA = "--user-agent=Mozilla/5.0 (Windows NT 6.0; Win64; x64; Xbox; Xbox One) AppleWebKit/606 (KHTML, like Gecko) HeadlessChrome/81.0.4015.0 Safari/606";
    private static final String AUTOSOLVE_AI_KEY = System.getenv("autosolve_ai_key");

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
        System.out.println("  NoDriver4j - HUMAN Captcha Press & Hold Demo");
        System.out.println("==========================================");
        System.out.println();

        BrowserManager manager = BrowserManager.builder()
                .executablePath(executablePath)
                .fingerprintEnabled(true)
                //.webrtcPolicy("default")
                .proxyEnabled(true)
                .warmProfile(false)
                .chromeArguement(HEADLESS_UA)
                .autoSolveAIKey(AUTOSOLVE_AI_KEY)
                //.headless(true)
                .build();

        List<Browser> browsers = null;
        ExecutorService scriptExecutor = null;

        try {
            System.out.println();

            // Create browser sessions (launches and warms all in parallel)
            browsers = manager.createSessions(SESSION_COUNT);

            System.out.println();
            System.out.println("==========================================");
            System.out.println("  Running HUMAN Captcha Press & Hold Demo  ");
            System.out.println("==========================================");
            System.out.println();

            // Run scripts in parallel - each browser gets its own profile
            scriptExecutor = Executors.newFixedThreadPool(Math.min(browsers.size(), Runtime.getRuntime().availableProcessors()));
            List<CompletableFuture<ScriptResult>> futures = new ArrayList<>();

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            for (int i = 0; i < browsers.size(); i++) {
                final Browser browser = browsers.get(i);
                final int browserIndex = i + 1;

                CompletableFuture<ScriptResult> future = CompletableFuture.supplyAsync(() -> {
                    try {

                        Page page = browser.getPage();
                        AutoSolveAIService aiService = manager.autoSolveAIService();

                        page.navigate(URL);

                        Scanner scanner = new Scanner(System.in);

                        scanner.nextLine();

                        ReCaptchaSolver.solve(page, aiService);

                        Scanner scanner2 = new Scanner(System.in);

                        scanner2.nextLine();


                        successCount.incrementAndGet();
                        return new ScriptResult(browserIndex, true, null);

                    } catch (RuntimeException e) {
                        failureCount.incrementAndGet();
                        System.err.println("[Browser " + browserIndex + "] Script failed: " + e.getMessage());
                        return new ScriptResult(browserIndex,  false, e.getMessage());
                    } catch (TimeoutException e) {
                        throw new RuntimeException(e);
                    }
                }, scriptExecutor);

                futures.add(future);
            }

            // Wait for all scripts to complete
            System.out.println("Waiting for all scripts to complete...");
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Collect and display results
            System.out.println();
            System.out.println("==========================================");
            System.out.println("  Results Summary");
            System.out.println("==========================================");

            for (CompletableFuture<ScriptResult> future : futures) {
                ScriptResult result = future.join();
                if (result.success()) {
                    System.out.println("  [Browser " + result.browserIndex() + "] SUCCESS ");
                } else {
                    System.out.println("  [Browser " + result.browserIndex() + "] FAILED - " + result.errorMessage());
                }
            }

            System.out.println();
            System.out.println("  Total: " + successCount.get() + " succeeded, " + failureCount.get() + " failed");

            // Wait for user input before closing
            System.out.println();
            System.out.println("Browsers are ready for manual inspection.");
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
            // Shutdown script executor
            if (scriptExecutor != null) {
                scriptExecutor.shutdown();
                try {
                    if (!scriptExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        scriptExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scriptExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

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

    /**
     * Record to hold the result of each script execution.
     */
    private record ScriptResult(int browserIndex, boolean success, String errorMessage) {}
}