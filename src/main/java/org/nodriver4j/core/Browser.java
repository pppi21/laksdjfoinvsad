package org.nodriver4j.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages a Chrome browser process.
 */
public class Browser {

    private final BrowserConfig config;
    private final Process process;

    private Browser(BrowserConfig config, Process process) {
        this.config = config;
        this.process = process;
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
        return new Browser(config, process);
    }

    /**
     * Closes the browser and terminates the process.
     */
    public void close() {
        if (process.isAlive()) {
            process.destroy();
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
        //args.add("--disable-blink-features=AutomationControlled");
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