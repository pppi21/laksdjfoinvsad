package org.nodriver4j.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.nodriver4j.cdp.CDPClient;
import org.nodriver4j.scripts.ProfileWarmer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Manages a Chrome browser instance with automatic resource cleanup.
 *
 * <p>This class handles the complete browser lifecycle:</p>
 * <ul>
 *   <li>Launching Chrome with appropriate command-line arguments</li>
 *   <li>Establishing CDP connections (browser-level and page-level)</li>
 *   <li>Setting up proxy authentication via CDP Fetch domain</li>
 *   <li>Applying fingerprint settings via CDP Emulation domain</li>
 *   <li>Tracking and managing Page instances for all browser tabs</li>
 *   <li>Profile warming to collect cookies and appear more natural</li>
 *   <li>Port tracking and release back to pool on close</li>
 *   <li>Cleanup on close (terminate process, delete user data directory)</li>
 * </ul>
 *
 * <p>Typical usage with try-with-resources:</p>
 * <pre>{@code
 * try (Browser browser = manager.createSession()) {
 *     Page page = browser.getPage();
 *     page.navigate("https://example.com");
 *     page.click("//button[@id='submit']");
 * } // automatically closes browser and releases port
 * }</pre>
 *
 * <p>For most use cases, prefer using {@link BrowserManager} which handles
 * resource allocation (ports, proxies) and warming automatically.</p>
 *
 * @see BrowserManager
 * @see Page
 */
public class Browser implements AutoCloseable {

    private static final int CDP_CONNECTION_RETRY_DELAY_MS = 500;
    private static final int CDP_CONNECTION_MAX_RETRIES = 20;

    private final BrowserConfig config;
    private final Process process;
    private final CDPClient cdpClient;           // Page-level connection (Emulation, Page, DOM, etc.)
    private final CDPClient browserCdpClient;    // Browser-level connection (Target, Fetch for proxy auth)
    private final Fingerprint fingerprint;
    private final InteractionOptions interactionOptions;

    // Resource management (previously in BrowserSession)
    private final int port;
    private final IntConsumer portReleaser;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean warmed = new AtomicBoolean(false);

    // Page tracking
    private final Map<String, Page> pages = new ConcurrentHashMap<>();
    private volatile String mainPageTargetId;

    private Browser(BrowserConfig config, Process process, CDPClient cdpClient,
                    CDPClient browserCdpClient, Fingerprint fingerprint,
                    InteractionOptions interactionOptions, int port, IntConsumer portReleaser) {
        this.config = config;
        this.process = process;
        this.cdpClient = cdpClient;
        this.browserCdpClient = browserCdpClient;
        this.fingerprint = fingerprint;
        this.interactionOptions = interactionOptions;
        this.port = port;
        this.portReleaser = portReleaser;
    }

    /**
     * Launches a new browser instance with the given configuration.
     *
     * <p>This method blocks until the browser is initialized and ready for use,
     * including CDP connections, proxy authentication setup, and fingerprint application.</p>
     *
     * <p>Note: Profile warming is NOT performed during launch. Warming is handled
     * automatically by {@link BrowserManager#createSession()} if enabled, or can be
     * triggered manually via {@link #warm()} for advanced use cases.</p>
     *
     * @param config       the browser configuration
     * @param portReleaser callback to release the port back to the pool on close
     * @return a running Browser instance
     * @throws IOException if the browser process fails to start or CDP connection fails
     */
    public static Browser launch(BrowserConfig config, IntConsumer portReleaser) throws IOException {
        return launch(config, portReleaser, InteractionOptions.defaults());
    }

    /**
     * Launches a new browser instance with the given configuration and interaction options.
     *
     * @param config             the browser configuration
     * @param portReleaser       callback to release the port back to the pool on close
     * @param interactionOptions options for human-like interactions
     * @return a running Browser instance
     * @throws IOException if the browser process fails to start or CDP connection fails
     */
    public static Browser launch(BrowserConfig config, IntConsumer portReleaser,
                                 InteractionOptions interactionOptions) throws IOException {
        int port = config.getPort();

        // Load fingerprint if enabled
        Fingerprint fingerprint = null;
        if (config.isFingerprintEnabled()) {
            System.out.println("[Browser] Fingerprint mode enabled, loading profile...");
            fingerprint = new Fingerprint();
            System.out.println("[Browser] Loaded fingerprint: " + fingerprint);
        }

        List<String> arguments = buildArguments(config, fingerprint);

        System.out.println("[Browser] Launching on port " + port + "...");

        ProcessBuilder processBuilder = new ProcessBuilder(arguments);
        Process process = processBuilder.start();

        // Always connect to browser target for target discovery
        System.out.println("[Browser] Connecting to browser target...");
        CDPClient browserCdpClient = connectToBrowserWithRetry(port);

        // Connect to page target
        System.out.println("[Browser] Connecting to page target...");
        CDPClient cdpClient = connectWithRetry(port);

        Browser browser = new Browser(config, process, cdpClient, browserCdpClient,
                fingerprint, interactionOptions, port, portReleaser);

        // Setup proxy authentication handler FIRST (before any requests are made)
        if (config.hasProxy() && config.getProxyConfig().requiresAuth()) {
            browser.setupProxyAuthentication();
        }

        // Setup target discovery to track all pages
        browser.setupTargetDiscovery();

        // Apply CDP-based fingerprint settings (screen emulation, etc.)
        if (fingerprint != null) {
            browser.applyFingerprintViaCDP();
        }

        System.out.println("[Browser] Ready on port " + port);

        return browser;
    }

    // ==================== Warming ====================

    /**
     * Warms the browser profile by visiting common websites to collect cookies.
     *
     * <p>This makes the browser profile appear more natural to anti-bot systems.
     * Warming visits sites like Google, YouTube, Facebook, Amazon, and Twitter
     * to accumulate realistic cookies and browsing history.</p>
     *
     * <p>This method blocks until warming is complete.</p>
     *
     * <p>Warming is idempotent - calling it multiple times has no additional effect.</p>
     *
     * <p>Note: This method is package-private. Warming is typically handled automatically
     * by {@link BrowserManager#createSession()} or {@link BrowserManager#createSessions(int)}
     * when warming is enabled in the manager configuration.</p>
     *
     * @return the warming result containing collected cookies and any warnings
     * @throws IllegalStateException if the browser has been closed
     */
    ProfileWarmer.WarmingResult warm() {
        ensureOpen();

        // Only warm once
        if (!warmed.compareAndSet(false, true)) {
            System.out.println("[Browser] Already warmed, skipping (port " + port + ")");
            return new ProfileWarmer.WarmingResult(Collections.emptyMap(), Collections.emptyList());
        }

        System.out.println("[Browser] Starting profile warming (port " + port + ")...");

        ProfileWarmer warmer = new ProfileWarmer(getPage());
        ProfileWarmer.WarmingResult result = warmer.warm();

        if (result.hasWarnings()) {
            System.err.println("[Browser] Warming completed with " +
                    result.getWarnings().size() + " warnings (port " + port + ")");
        } else {
            System.out.println("[Browser] Warming completed successfully (port " + port + ")");
        }

        return result;
    }

    /**
     * Checks if profile warming is enabled for this browser.
     *
     * <p>This reflects the configuration setting, not whether warming has been performed.
     * Use {@link #isWarmed()} to check if warming has already been done.</p>
     *
     * @return true if warming is enabled in the configuration
     */
    public boolean isWarmProfileEnabled() {
        return config.isWarmProfile();
    }

    /**
     * Checks if this browser has already been warmed.
     *
     * @return true if warming has been performed
     */
    public boolean isWarmed() {
        return warmed.get();
    }

    // ==================== Target Discovery ====================

    /**
     * Sets up target discovery to track all browser tabs/pages.
     * This allows automatic Page instance creation for new tabs.
     */
    private void setupTargetDiscovery() {
        try {
            // Register event listeners BEFORE enabling discovery
            browserCdpClient.addEventListener("Target.targetCreated", this::onTargetCreated);
            browserCdpClient.addEventListener("Target.targetDestroyed", this::onTargetDestroyed);
            browserCdpClient.addEventListener("Target.targetInfoChanged", this::onTargetInfoChanged);

            // Enable target discovery
            JsonObject params = new JsonObject();
            params.addProperty("discover", true);
            browserCdpClient.send("Target.setDiscoverTargets", params);

            // Get existing targets
            JsonObject result = browserCdpClient.send("Target.getTargets", null);
            JsonArray targetInfos = result.getAsJsonArray("targetInfos");

            for (JsonElement element : targetInfos) {
                JsonObject targetInfo = element.getAsJsonObject();
                String type = targetInfo.get("type").getAsString();

                if ("page".equals(type)) {
                    String targetId = targetInfo.get("targetId").getAsString();
                    createPageForTarget(targetId, targetInfo);
                }
            }

            System.out.println("[Browser] Target discovery enabled, tracking " + pages.size() + " page(s)");

        } catch (TimeoutException e) {
            System.err.println("[Browser] Warning: Failed to setup target discovery: " + e.getMessage());
        }
    }

    /**
     * Handles target created events from CDP.
     */
    private void onTargetCreated(JsonObject params) {
        JsonObject targetInfo = params.getAsJsonObject("targetInfo");
        String type = targetInfo.get("type").getAsString();

        if ("page".equals(type)) {
            String targetId = targetInfo.get("targetId").getAsString();
            System.out.println("[Browser] New page target created: " + targetId);
            createPageForTarget(targetId, targetInfo);
        }
    }

    /**
     * Handles target destroyed events from CDP.
     */
    private void onTargetDestroyed(JsonObject params) {
        String targetId = params.get("targetId").getAsString();
        Page removed = pages.remove(targetId);

        if (removed != null) {
            System.out.println("[Browser] Page target destroyed: " + targetId);
        }
    }

    /**
     * Handles target info changed events from CDP.
     */
    private void onTargetInfoChanged(JsonObject params) {
        JsonObject targetInfo = params.getAsJsonObject("targetInfo");
        String targetId = targetInfo.get("targetId").getAsString();

        // We could update page metadata here if needed
        // For now, just log significant changes
        if (targetInfo.has("url")) {
            String url = targetInfo.get("url").getAsString();
            Page page = pages.get(targetId);
            if (page != null) {
                System.out.println("[Browser] Page navigated: " + targetId + " -> " + url);
            }
        }
    }

    /**
     * Creates a Page instance for a target.
     */
    private void createPageForTarget(String targetId, JsonObject targetInfo) {
        if (pages.containsKey(targetId)) {
            return; // Already tracking this target
        }

        try {
            // For the main page, we use the existing cdpClient
            // For additional pages, we need to create a new CDP connection
            Page page;
            if (mainPageTargetId == null) {
                // First page - use the existing page-level CDP client
                mainPageTargetId = targetId;
                page = new Page(cdpClient, targetId, interactionOptions);
                pages.put(targetId, page);

                String url = targetInfo.has("url") ? targetInfo.get("url").getAsString() : "about:blank";
                System.out.println("[Browser] Main page registered: " + targetId + " (" + url + ")");
            } else {
                // Additional pages - we track them but note they need separate CDP connection
                // For full support, CDPClient would need a connectToUrl(wsUrl) method
                // For now, log that this page exists but may have limited functionality
                String url = targetInfo.has("url") ? targetInfo.get("url").getAsString() : "about:blank";
                System.out.println("[Browser] Additional page detected: " + targetId + " (" + url + ")");
                System.out.println("[Browser] Note: Additional pages have limited automation support in current version");

                // Still create a Page with the main cdpClient - some operations may work
                // but page-specific operations may affect the wrong page
                // This is a known limitation until CDPClient supports direct URL connection
                page = new Page(cdpClient, targetId, interactionOptions);
                pages.put(targetId, page);
            }

        } catch (Exception e) {
            System.err.println("[Browser] Failed to create page for target " + targetId + ": " + e.getMessage());
        }
    }

    // ==================== Proxy Authentication ====================

    /**
     * Sets up CDP-based proxy authentication handler on the browser-level connection.
     * This handler remains active for the entire browser session and responds
     * to authentication challenges from the proxy server for ALL tabs/pages.
     */
    private void setupProxyAuthentication() {
        ProxyConfig proxy = config.getProxyConfig();
        System.out.println("[Browser] Setting up proxy authentication for " +
                proxy.getHost() + ":" + proxy.getPort());

        try {
            // Enable Fetch domain with auth interception on BROWSER-level connection
            JsonObject enableParams = new JsonObject();
            enableParams.addProperty("handleAuthRequests", true);
            browserCdpClient.send("Fetch.enable", enableParams);

            // Handle regular paused requests - continue them immediately
            browserCdpClient.addEventListener("Fetch.requestPaused", this::handleRequestPaused);

            // Handle auth challenges - provide credentials
            browserCdpClient.addEventListener("Fetch.authRequired", this::handleProxyAuthRequired);

            System.out.println("[Browser] Proxy authentication handler registered (browser-wide)");

        } catch (TimeoutException e) {
            throw new RuntimeException("Failed to setup proxy authentication: " + e.getMessage(), e);
        }
    }

    /**
     * Handles paused requests from CDP.
     * Continues the request immediately without modification.
     */
    private void handleRequestPaused(JsonObject event) {
        String requestId = event.get("requestId").getAsString();

        // Continue the request without modification
        JsonObject continueParams = new JsonObject();
        continueParams.addProperty("requestId", requestId);

        browserCdpClient.sendAsync("Fetch.continueRequest", continueParams);
    }

    /**
     * Handles proxy authentication challenges from CDP.
     * Called each time the proxy requires authentication (initial connection,
     * reconnection after timeout, new parallel connections, etc.)
     */
    private void handleProxyAuthRequired(JsonObject event) {
        ProxyConfig proxy = config.getProxyConfig();
        String requestId = event.get("requestId").getAsString();

        // Log auth challenge details for debugging
        if (event.has("authChallenge")) {
            JsonObject challenge = event.getAsJsonObject("authChallenge");
            String source = challenge.has("source") ? challenge.get("source").getAsString() : "unknown";
            String origin = challenge.has("origin") ? challenge.get("origin").getAsString() : "unknown";
            System.out.println("[Browser] Proxy auth required - source: " + source + ", origin: " + origin);
        }

        // Build auth response
        JsonObject authResponse = new JsonObject();
        authResponse.addProperty("requestId", requestId);

        JsonObject authChallengeResponse = new JsonObject();
        authChallengeResponse.addProperty("response", "ProvideCredentials");
        authChallengeResponse.addProperty("username", proxy.getUsername());
        authChallengeResponse.addProperty("password", proxy.getPassword());
        authResponse.add("authChallengeResponse", authChallengeResponse);

        // Send credentials asynchronously (no need to wait for response)
        browserCdpClient.sendAsync("Fetch.continueWithAuth", authResponse);

        System.out.println("[Browser] Proxy credentials provided for request: " + requestId);
    }

    // ==================== Fingerprint Application ====================

    /**
     * Applies fingerprint settings that require CDP calls (screen dimensions, etc.)
     * Uses the page-level CDP connection.
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

    // ==================== CDP Connection Helpers ====================

    private static CDPClient connectWithRetry(int port) throws IOException {
        for (int i = 0; i < CDP_CONNECTION_MAX_RETRIES; i++) {
            try {
                return CDPClient.connect(port);
            } catch (Exception e) {
                if (i == CDP_CONNECTION_MAX_RETRIES - 1) {
                    throw new IOException("Failed to connect to CDP page target after " +
                            CDP_CONNECTION_MAX_RETRIES + " retries", e);
                }
                try {
                    Thread.sleep(CDP_CONNECTION_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to retry CDP connection", ie);
                }
            }
        }
        throw new IOException("Failed to connect to CDP page target");
    }

    private static CDPClient connectToBrowserWithRetry(int port) throws IOException {
        for (int i = 0; i < CDP_CONNECTION_MAX_RETRIES; i++) {
            try {
                return CDPClient.connectToBrowser(port);
            } catch (Exception e) {
                if (i == CDP_CONNECTION_MAX_RETRIES - 1) {
                    throw new IOException("Failed to connect to CDP browser target after " +
                            CDP_CONNECTION_MAX_RETRIES + " retries", e);
                }
                try {
                    Thread.sleep(CDP_CONNECTION_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to retry CDP connection", ie);
                }
            }
        }
        throw new IOException("Failed to connect to CDP browser target");
    }

    // ==================== Page Access Methods ====================

    /**
     * Gets the main page (first/default tab).
     *
     * @return the main Page instance
     * @throws IllegalStateException if no pages are available or browser is closed
     */
    public Page getPage() {
        ensureOpen();

        if (mainPageTargetId != null) {
            Page page = pages.get(mainPageTargetId);
            if (page != null) {
                return page;
            }
        }

        // Fallback: return first available page
        if (!pages.isEmpty()) {
            return pages.values().iterator().next();
        }

        throw new IllegalStateException("No pages available");
    }

    /**
     * Gets all tracked pages.
     *
     * @return unmodifiable list of all Page instances
     * @throws IllegalStateException if browser is closed
     */
    public List<Page> getPages() {
        ensureOpen();
        return Collections.unmodifiableList(new ArrayList<>(pages.values()));
    }

    /**
     * Gets a page by its target ID.
     *
     * @param targetId the CDP target ID
     * @return the Page instance, or null if not found
     * @throws IllegalStateException if browser is closed
     */
    public Page getPageByTargetId(String targetId) {
        ensureOpen();
        return pages.get(targetId);
    }

    /**
     * Gets the number of tracked pages.
     *
     * @return the page count
     */
    public int getPageCount() {
        return pages.size();
    }

    /**
     * Creates a new page/tab in the browser.
     *
     * @return the new Page instance
     * @throws TimeoutException if the operation times out
     * @throws IllegalStateException if browser is closed
     */
    public Page newPage() throws TimeoutException {
        return newPage("about:blank");
    }

    /**
     * Creates a new page/tab in the browser and navigates to a URL.
     *
     * @param url the URL to navigate to
     * @return the new Page instance
     * @throws TimeoutException if the operation times out
     * @throws IllegalStateException if browser is closed
     */
    public Page newPage(String url) throws TimeoutException {
        ensureOpen();

        JsonObject params = new JsonObject();
        params.addProperty("url", url);

        JsonObject result = browserCdpClient.send("Target.createTarget", params);
        String targetId = result.get("targetId").getAsString();

        // Wait for the page to be registered
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Page page = pages.get(targetId);
            if (page != null) {
                return page;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for new page", e);
            }
        }

        throw new TimeoutException("New page was not registered within timeout");
    }

    /**
     * Closes a specific page/tab.
     *
     * @param page the page to close
     * @throws TimeoutException if the operation times out
     * @throws IllegalStateException if browser is closed
     */
    public void closePage(Page page) throws TimeoutException {
        ensureOpen();

        JsonObject params = new JsonObject();
        params.addProperty("targetId", page.getTargetId());

        browserCdpClient.send("Target.closeTarget", params);
        pages.remove(page.getTargetId());
    }

    // ==================== State and Resource Access ====================

    /**
     * Gets the debugging port allocated to this browser.
     *
     * @return the CDP debugging port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Checks if this browser is still open.
     *
     * @return true if the browser has not been closed
     */
    public boolean isOpen() {
        return !closed.get();
    }

    /**
     * Checks if the browser process is still running.
     *
     * @return true if the Chrome process is alive
     */
    public boolean isRunning() {
        return !closed.get() && process.isAlive();
    }

    /**
     * Gets the page-level CDP client for direct protocol access.
     *
     * <p>Use this for page-specific commands like Page, DOM, Emulation,
     * Network (monitoring), Runtime, etc.</p>
     *
     * @return the page-level CDPClient instance
     * @throws IllegalStateException if browser is closed
     */
    public CDPClient getCdpClient() {
        ensureOpen();
        return cdpClient;
    }

    /**
     * Gets the browser-level CDP client.
     *
     * <p>Use this for browser-wide commands that should apply to all tabs,
     * such as Target management and Fetch (proxy auth).</p>
     *
     * @return the browser-level CDPClient instance
     * @throws IllegalStateException if browser is closed
     */
    public CDPClient getBrowserCdpClient() {
        ensureOpen();
        return browserCdpClient;
    }

    /**
     * Gets the fingerprint used by this browser instance, if any.
     *
     * @return the Fingerprint, or null if fingerprinting is disabled
     */
    public Fingerprint getFingerprint() {
        return fingerprint;
    }

    /**
     * Gets the proxy configuration for this browser, if any.
     *
     * @return the ProxyConfig, or null if no proxy is configured
     */
    public ProxyConfig getProxyConfig() {
        return config.getProxyConfig();
    }

    /**
     * Gets the interaction options for this browser.
     *
     * @return the InteractionOptions
     */
    public InteractionOptions getInteractionOptions() {
        return interactionOptions;
    }

    // ==================== Lifecycle Management ====================

    /**
     * Ensures this browser is still open, throwing if it has been closed.
     *
     * @throws IllegalStateException if the browser has been closed
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Browser has been closed");
        }
    }

    /**
     * Closes the browser and releases all resources.
     *
     * <p>This method is idempotent - calling it multiple times has no additional effect.
     * It will:</p>
     * <ul>
     *   <li>Close CDP WebSocket connections</li>
     *   <li>Terminate the Chrome process</li>
     *   <li>Delete the temporary user data directory</li>
     *   <li>Release the allocated port back to the pool</li>
     * </ul>
     *
     * <p>This method does not throw exceptions. Any errors during cleanup are logged
     * but suppressed to ensure the port is always released.</p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            // Already closed
            return;
        }

        try {
            // Remove event listeners
            if (browserCdpClient != null) {
                browserCdpClient.removeAllEventListeners();
                browserCdpClient.close();
            }
            if (cdpClient != null) {
                cdpClient.close();
            }

            // Clear page tracking
            pages.clear();
            mainPageTargetId = null;

            // Terminate process
            if (process != null) {
                process.destroy();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Delete user data directory
            deleteUserDataDir();

        } catch (Exception e) {
            // Log but don't rethrow - we must release the port
            System.err.println("[Browser] Error during cleanup: " + e.getMessage());
        } finally {
            // Always release the port
            releasePort();
        }
    }

    /**
     * Releases the allocated port back to the pool.
     */
    private void releasePort() {
        if (portReleaser != null) {
            try {
                portReleaser.accept(port);
            } catch (Exception e) {
                System.err.println("[Browser] Error releasing port " + port + ": " + e.getMessage());
            }
        }
    }

    /**
     * Deletes the temporary user data directory with retry logic.
     * Retries deletion to handle files locked by Chrome processes that are still closing.
     * Called during close() to clean up disk space.
     */
    private void deleteUserDataDir() {
        Path userDataDir = config.getUserDataDir();
        if (userDataDir == null || !Files.exists(userDataDir)) {
            return;
        }

        final int maxRetries = 5;
        final int retryDelayMs = 500;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (tryDeleteDirectory(userDataDir)) {
                return; // Success
            }

            if (attempt < maxRetries) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        // Only log if all retries failed
        System.err.println("[Browser] Failed to delete user data directory after " + maxRetries +
                " attempts: " + userDataDir);
    }

    /**
     * Attempts to delete a directory and all its contents.
     *
     * @param directory the directory to delete
     * @return true if deletion was successful, false if any file could not be deleted
     */
    private boolean tryDeleteDirectory(Path directory) {
        if (!Files.exists(directory)) {
            return true;
        }

        final boolean[] success = {true};

        try {
            Files.walk(directory)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            success[0] = false;
                        }
                    });
        } catch (IOException e) {
            return false;
        }

        return success[0];
    }

    // ==================== Command Line Arguments ====================

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
        args.add("--disable-features=BlockThirdPartyCookies");

        // Proxy configuration
        if (config.hasProxy()) {
            ProxyConfig proxy = config.getProxyConfig();
            args.add("--proxy-server=" + proxy.toProxyServerArg());
            System.out.println("[Browser] Proxy configured: " + proxy);
        }

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

    @Override
    public String toString() {
        return String.format("Browser{port=%d, open=%s, warmed=%s, pages=%d, fingerprint=%s, proxy=%s}",
                port,
                !closed.get(),
                warmed.get(),
                pages.size(),
                fingerprint != null ? "enabled" : "disabled",
                config.getProxyConfig() != null ? config.getProxyConfig().getHost() : "none");
    }
}