package org.nodriver4j.core;

import com.google.gson.JsonObject;
import org.nodriver4j.cdp.CDPClient;
import org.nodriver4j.cdp.ProfileWarmer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Manages a Chrome browser process with optional fingerprint spoofing.
 */
public class Browser implements AutoCloseable {

    private static final int CDP_CONNECTION_RETRY_DELAY_MS = 500;
    private static final int CDP_CONNECTION_MAX_RETRIES = 20;

    private final BrowserConfig config;
    private final Process process;
    private final CDPClient cdpClient;
    private final Fingerprint fingerprint;

    private Browser(BrowserConfig config, Process process, CDPClient cdpClient, Fingerprint fingerprint) {
        this.config = config;
        this.process = process;
        this.cdpClient = cdpClient;
        this.fingerprint = fingerprint;
    }

    /**
     * Launches a new browser instance with the given configuration.
     *
     * @param config the browser configuration
     * @return a running Browser instance
     * @throws IOException if the browser process fails to start
     */
    public static Browser launch(BrowserConfig config) throws IOException {
        // Load fingerprint if enabled
        Fingerprint fingerprint = null;
        if (config.isFingerprintEnabled()) {
            System.out.println("[Browser] Fingerprint mode enabled, loading profile...");
            fingerprint = new Fingerprint();
            System.out.println("[Browser] Loaded fingerprint: " + fingerprint);
        }

        List<String> arguments = buildArguments(config, fingerprint);

        System.out.println("[Browser] Launching with arguments:");
        for (String arg : arguments) {
            // Mask potentially long renderer strings for cleaner logging
            if (arg.startsWith("--fingerprint-gpu-renderer=")) {
                System.out.println("  " + arg.substring(0, 60) + "...");
            } else {
                System.out.println("  " + arg);
            }
        }

        ProcessBuilder processBuilder = new ProcessBuilder(arguments);
        Process process = processBuilder.start();

        // Connect to CDP with retries (Chrome needs time to start)
        CDPClient cdpClient = connectWithRetry(config.getPort());

        Browser browser = new Browser(config, process, cdpClient, fingerprint);

        // Apply CDP-based fingerprint settings (screen emulation, etc.)
        if (fingerprint != null) {
            browser.applyFingerprintViaCDP();
        }

        // Warm profile if enabled
        if (config.isWarmProfile()) {
            System.out.println("[Browser] Profile warming enabled, starting...");
            ProfileWarmer warmer = new ProfileWarmer(cdpClient);
            ProfileWarmer.WarmingResult result = warmer.warm();
            if (result.hasWarnings()) {
                System.err.println("[Browser] Profile warming completed with " + result.getWarnings().size() + " warnings");
            } else {
                System.out.println("[Browser] Profile warming completed successfully");
            }
        }

        return browser;
    }

    /**
     * Applies fingerprint settings that require CDP calls (screen dimensions, etc.)
     */
    private void applyFingerprintViaCDP() {
        if (fingerprint == null) {
            return;
        }

        try {
            System.out.println("[Browser] Applying screen emulation via CDP...");

            // Enable necessary domains
            cdpClient.send("Emulation.clearDeviceMetricsOverride", null);

            // Set device metrics to match fingerprint
            JsonObject params = new JsonObject();
            params.addProperty("width", fingerprint.getScreenWidth());
            params.addProperty("height", fingerprint.getScreenHeight());
            params.addProperty("deviceScaleFactor", 1);
            params.addProperty("mobile", false);

            // Screen orientation
            JsonObject screenOrientation = new JsonObject();
            screenOrientation.addProperty("type", "landscapePrimary");
            screenOrientation.addProperty("angle", 0);
            params.add("screenOrientation", screenOrientation);

            cdpClient.send("Emulation.setDeviceMetricsOverride", params);

            System.out.println("[Browser] Screen emulation applied: " +
                    fingerprint.getScreenWidth() + "x" + fingerprint.getScreenHeight());

        } catch (TimeoutException e) {
            System.err.println("[Browser] WARNING: Failed to apply screen emulation: " + e.getMessage());
        }
    }

    private static CDPClient connectWithRetry(int port) throws IOException {
        for (int i = 0; i < CDP_CONNECTION_MAX_RETRIES; i++) {
            try {
                return CDPClient.connect(port);
            } catch (Exception e) {
                if (i == CDP_CONNECTION_MAX_RETRIES - 1) {
                    throw new IOException("Failed to connect to CDP after " + CDP_CONNECTION_MAX_RETRIES + " retries", e);
                }
                try {
                    Thread.sleep(CDP_CONNECTION_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to retry CDP connection", ie);
                }
            }
        }
        throw new IOException("Failed to connect to CDP");
    }

    /**
     * Closes the browser and terminates the process.
     * Also deletes the temporary user data directory.
     */
    @Override
    public void close() {
        // Close CDP connection first
        if (cdpClient != null) {
            cdpClient.close();
        }

        if (process.isAlive()) {
            process.destroy();
        }
        deleteUserDataDir();
    }

    /**
     * Gets the CDP client for direct protocol access.
     *
     * @return the CDPClient instance
     */
    public CDPClient getCdpClient() {
        return cdpClient;
    }

    /**
     * Gets the fingerprint used by this browser instance, if any.
     *
     * @return the Fingerprint, or null if fingerprinting is disabled
     */
    public Fingerprint getFingerprint() {
        return fingerprint;
    }

    private void deleteUserDataDir() {
        Path userDataDir = config.getUserDataDir();
        if (userDataDir == null || !Files.exists(userDataDir)) {
            return;
        }
        try {
            Files.walk(userDataDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            System.err.println("Failed to delete: " + path);
                        }
                    });
        } catch (IOException e) {
            System.err.println("Failed to delete user data directory: " + userDataDir);
        }
    }

    /**
     * Checks if the browser process is still running.
     *
     * @return true if the process is alive
     */
    public boolean isRunning() {
        return process.isAlive();
    }

    public BrowserConfig getConfig() {
        return config;
    }

    private static List<String> buildArguments(BrowserConfig config, Fingerprint fingerprint) {
        List<String> args = new ArrayList<>();
        args.add(config.getExecutablePath());

        // Core browser arguments
        args.add("--remote-debugging-port=" + config.getPort());
        args.add("--user-data-dir=" + config.getUserDataDir().toAbsolutePath());
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        args.add("--disable-breakpad");
        args.add("--disable-translate");
        args.add("--disable-password-generation");
        args.add("--disable-prompt-on-repost");
        args.add("--disable-backgrounding-occluded-windows");
        args.add("--disable-renderer-backgrounding");
        args.add("--remote-allow-origins=*");
        args.add("--no-service-autorun");
        args.add("--disable-ipc-flooding-protection");
        args.add("--disable-client-side-phishing-detection");
        args.add("--disable-background-networking");

        // Headless mode
        if (config.isHeadless()) {
            args.add("--headless=new");
        }

        // WebRTC policy
        String webrtcPolicy = config.getWebrtcPolicy();
        if (webrtcPolicy != null && !webrtcPolicy.isBlank()) {
            args.add("--webrtc-ip-handling-policy=" + webrtcPolicy);
        }

        // Fingerprint arguments
        if (fingerprint != null) {
            // Core fingerprint seed - enables all fingerprinting features
            args.add("--fingerprint=" + fingerprint.getSeed());

            // Platform spoofing
            args.add("--fingerprint-platform=" + fingerprint.getPlatform());
            if (fingerprint.getPlatformVersion() != null) {
                args.add("--fingerprint-platform-version=" + fingerprint.getPlatformVersion());
            }

            // Hardware concurrency
            args.add("--fingerprint-hardware-concurrency=" + fingerprint.getHardwareConcurrency());

            // GPU/WebGL spoofing
            if (fingerprint.getGpuVendor() != null) {
                args.add("--fingerprint-gpu-vendor=" + fingerprint.getGpuVendor());
            }
            if (fingerprint.getGpuRenderer() != null) {
                args.add("\"--fingerprint-gpu-renderer=" + fingerprint.getGpuRenderer() + "\"");
            }

            // Timezone
            // args.add("--timezone=" + fingerprint.getTimezone());
        }

        return args;
    }
}