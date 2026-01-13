package org.nodriver4j.core;

import org.nodriver4j.cdp.CDPClient;
import org.nodriver4j.cdp.ProfileWarmer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Manages a Chrome browser process.
 */
public class Browser {

    private static final int CDP_CONNECTION_RETRY_DELAY_MS = 500;
    private static final int CDP_CONNECTION_MAX_RETRIES = 20;

    private final BrowserConfig config;
    private final Process process;
    private final CDPClient cdpClient;

    private Browser(BrowserConfig config, Process process, CDPClient cdpClient) {
        this.config = config;
        this.process = process;
        this.cdpClient = cdpClient;
    }

    /**
     * Launches a new browser instance with the given configuration.
     *
     * @param config the browser configuration
     * @return a running Browser instance
     * @throws IOException if the browser process fails to start
     */
    public static Browser launch(BrowserConfig config) throws IOException {
        List<String> arguments = buildArguments(config);
        ProcessBuilder processBuilder = new ProcessBuilder(arguments);
        Process process = processBuilder.start();

        // Connect to CDP with retries (Chrome needs time to start)
        CDPClient cdpClient = connectWithRetry(config.getPort());

        Browser browser = new Browser(config, process, cdpClient);

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

    private static List<String> buildArguments(BrowserConfig config) {
        List<String> args = new ArrayList<>();
        args.add(config.getExecutablePath());
        args.add("--remote-debugging-port=" + config.getPort());
        args.add("--user-data-dir=" + config.getUserDataDir().toAbsolutePath());
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        args.add("--homepage=google.com");
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

        if (config.isHeadless()) {
            args.add("--headless=new");
        }

        return args;
    }

}