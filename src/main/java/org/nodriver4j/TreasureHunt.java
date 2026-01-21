package org.nodriver4j;

import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserManager;
import org.nodriver4j.profiles.Profile;
import org.nodriver4j.profiles.ProfilePool;
import org.nodriver4j.scripts.MattelDraw;
import org.nodriver4j.scripts.SandwichGen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TreasureHunt {

    // Number of concurrent browser sessions
    private static final int SESSION_COUNT = 10;

    public static void main(String[] args) {
        String executablePath = System.getenv("chromepath");
        String profileInputPath = System.getenv("mattel_profiles_input");
        String profileOutputPath = System.getenv("mattel_profiles_output");

        if (executablePath == null || executablePath.isBlank()) {
            System.err.println("ERROR: 'chromepath' environment variable is not set.");
            System.err.println("Please set it to the path of your Chrome executable.");
            return;
        }

        System.out.println("==========================================");
        System.out.println("  NoDriver4j - Browser Automation Demo");
        System.out.println("==========================================");
        System.out.println();

        // Build manager with optional explicit profile paths
        BrowserManager.Builder managerBuilder = BrowserManager.builder()
                .executablePath(executablePath)
                .fingerprintEnabled(true)
                //.webrtcPolicy("default")
                .proxyEnabled(true)
                .headless(true)
                .warmProfile(false);


        // Add profile paths if provided via env vars
        if (profileInputPath != null && profileOutputPath != null) {
            managerBuilder
                    .profileInputPath(profileInputPath)
                    .profileOutputPath(profileOutputPath);
        }

        BrowserManager manager = managerBuilder.build();
        List<Browser> browsers = null;
        ExecutorService scriptExecutor = null;

        try {
            // Get the profile pool and check availability
            ProfilePool pool = manager.profilePool();
            int remainingProfiles = pool.countRemaining();

            System.out.println("Remaining profiles: " + remainingProfiles);

            if (remainingProfiles == 0) {
                System.err.println("ERROR: No profiles remaining in input file.");
                return;
            }

            // Determine how many sessions we can actually run
            int sessionsToRun = Math.min(SESSION_COUNT, remainingProfiles);
            if (sessionsToRun < SESSION_COUNT) {
                System.out.println("WARNING: Only " + remainingProfiles + " profiles available, running " + sessionsToRun + " session(s).");
            }

            System.out.println();

            // Create browser sessions (launches and warms all in parallel)
            browsers = manager.createSessions(sessionsToRun);

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
            }

            System.out.println();
            System.out.println("==========================================");
            System.out.println("  Running SandwichGen Scripts  ");
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
                        // Each thread consumes its own profile (thread-safe)
                        Profile profile = pool.consumeFirst();
                        System.out.println("[Browser " + browserIndex + "] Consumed profile: " + profile.emailAddress());

                        // Run the script
                        MattelDraw script = new MattelDraw(
                                browser.getPage(),
                                profile,
                                pool
                        );
                        script.enterDraw();

                        successCount.incrementAndGet();
                        return new ScriptResult(browserIndex, profile.emailAddress(), true, null);

                    } catch (IOException e) {
                        failureCount.incrementAndGet();
                        System.err.println("[Browser " + browserIndex + "] Failed to consume profile: " + e.getMessage());
                        return new ScriptResult(browserIndex, null, false, e.getMessage());

                    } catch (RuntimeException e) {
                        failureCount.incrementAndGet();
                        System.err.println("[Browser " + browserIndex + "] Script failed: " + e.getMessage());
                        return new ScriptResult(browserIndex, null, false, e.getMessage());
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
                    System.out.println("  [Browser " + result.browserIndex() + "] SUCCESS - " + result.email());
                } else {
                    System.out.println("  [Browser " + result.browserIndex() + "] FAILED - " + result.errorMessage());
                }
            }

            System.out.println();
            System.out.println("  Total: " + successCount.get() + " succeeded, " + failureCount.get() + " failed");
            System.out.println("  Completed profiles written: " + pool.countCompleted());
            System.out.println("  Remaining profiles: " + pool.countRemaining());

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
        } catch (IOException e) {
            System.err.println("Error with profile management: " + e.getMessage());
            e.printStackTrace();
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
    private record ScriptResult(int browserIndex, String email, boolean success, String errorMessage) {}
}