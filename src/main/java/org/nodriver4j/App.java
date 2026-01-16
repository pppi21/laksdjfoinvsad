package org.nodriver4j;

import org.nodriver4j.core.BrowserManager;
import org.nodriver4j.core.BrowserSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class App {

    public static void main(String[] args) {
        String executablePath = System.getenv("chromepath");

        BrowserManager manager = BrowserManager.builder()
                .executablePath(executablePath)
                .fingerprintEnabled(true)
                .proxyEnabled(true)
                .webrtcPolicy("disable_non_proxied_udp")
                .warmProfile(false)
                .build();

        List<BrowserSession> sessions = new ArrayList<>();

        try {
            // Phase 1: Launch all 6 browsers (fast - no warming yet)
            System.out.println("\n==========================================");
            System.out.println("Phase 1: Launching 6 browsers...");
            System.out.println("==========================================\n");

            for (int i = 0; i < 1; i++) {
                System.out.println("--- Launching Browser " + (i + 1) + " ---");

                BrowserSession session = manager.createSession();
                sessions.add(session);

                System.out.println("Browser " + (i + 1) + " launched:");
                System.out.println("  Port: " + session.getPort());
                System.out.println("  Fingerprint: " + session.getFingerprint());
                System.out.println("  Proxy: " + session.getProxyConfig());
                System.out.println();
            }

            System.out.println("==========================================");
            System.out.println("All 6 browsers launched!");
            System.out.println("Active sessions: " + manager.getActiveSessionCount());
            System.out.println("==========================================\n");

            // Phase 2: Warm all browsers in parallel
            System.out.println("==========================================");
            System.out.println("Phase 2: Warming all browsers in parallel...");
            System.out.println("==========================================\n");

            manager.warmSessions(sessions);

            System.out.println("\n==========================================");
            System.out.println("All 6 browsers launched and warmed!");
            System.out.println("Active sessions: " + manager.getActiveSessionCount());
            System.out.println("Available ports: " + manager.getAvailablePortCount());
            System.out.println("Browsers are ready for use. Close manually when done.");
            System.out.println("==========================================\n");

        } catch (IOException e) {
            System.err.println("Failed to launch browser: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("Interrupted while launching browser: " + e.getMessage());
            Thread.currentThread().interrupt();
        }

        // Note: Sessions are intentionally NOT closed here to allow manual interaction.
        // The JVM shutdown hook in BrowserManager will clean up when the process exits.
    }
}