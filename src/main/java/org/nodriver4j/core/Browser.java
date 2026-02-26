package org.nodriver4j.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.nodriver4j.cdp.CDPClient;
import org.nodriver4j.scripts.SessionWarmer;
import org.nodriver4j.services.AutoSolveAIService;
import org.nodriver4j.services.TaskLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Manages a Chrome browser instance with automatic resource cleanup.
 *
 * <p>This class handles the complete browser lifecycle:</p>
 * <ul>
 *   <li>Launching Chrome with appropriate command-line arguments</li>
 *   <li>Establishing CDP connections (browser-level and page-level)</li>
 *   <li>Setting up proxy authentication via CDP Fetch domain</li>
 *   <li>Resource blocking for performance optimization</li>
 *   <li>Applying fingerprint settings via CDP Emulation domain</li>
 *   <li>Tracking and managing Page instances for all browser tabs</li>
 *   <li>Profile warming to collect cookies and appear more natural</li>
 *   <li>Port tracking and release back to pool on close</li>
 *   <li>Cleanup on close (terminate process, delete user data directory)</li>
 * </ul>
 *
 * <h2>Usage with BrowserManager (Recommended)</h2>
 * <pre>{@code
 * BrowserManager manager = BrowserManager.builder()
 *     .defaultConfig(BrowserConfig.builder()
 *         .executablePath("/path/to/chrome")
 *         .fingerprintEnabled(true)
 *         .resourceBlocking(true)
 *         .build())
 *     .build();
 *
 * try (Browser browser = manager.createSession()) {
 *     Page page = browser.page();
 *     page.navigate("https://example.com");
 * }
 * }</pre>
 *
 * <h2>Standalone Usage</h2>
 * <pre>{@code
 * BrowserConfig config = BrowserConfig.builder()
 *     .executablePath("/path/to/chrome")
 *     .fingerprintEnabled(true)
 *     .resourceBlocking(true)
 *     .proxy("host:port:user:pass")
 *     .build();
 *
 * try (Browser browser = Browser.launch(config, 9222, port -> {})) {
 *     Page page = browser.page();
 *     page.navigate("https://example.com");
 * }
 * }</pre>
 *
 * @see BrowserManager
 * @see BrowserConfig
 * @see Page
 */
public class Browser implements AutoCloseable {

    private static final int CDP_CONNECTION_RETRY_DELAY_MS = 500;
    private static final int CDP_CONNECTION_MAX_RETRIES = 20;
    private static final int[][] HEADLESS_RESOLUTIONS = {
            {1920, 1080},  // 1080p
            {2560, 1440}   // 1440p
    };

    // ==================== Resource Blocking Constants ====================

    /**
     * Resource types to block when resource blocking is enabled.
     * These types are unnecessary for most automation tasks.
     */
    private static final Set<String> BLOCKED_RESOURCE_TYPES = Set.of(
            "Media",
            "Font",
            "Prefetch",
            "Ping",
            "Manifest",
            "CSPViolationReport",
            "SignedExchange",
            "TextTrack"
    );

    /**
     * URL patterns for analytics/tracking services to block.
     * Uses simple substring matching for performance.
     */
    private static final List<String> BLOCKED_URL_PATTERNS = List.of(
            "google-analytics.com",
            "googletagmanager.com",
            "facebook.net",
            "doubleclick.net",
            "googlesyndication.com",
            "hotjar.com",
            "segment.io",
            "segment.com",
            "mixpanel.com",
            "newrelic.com",
            "sentry.io",
            "clarity.ms",
            "optimizely.com",
            "cdn.amplitude.com"
    );

    // ==================== Instance Fields ====================

    private final BrowserConfig config;
    private final Process process;
    private final CDPClient cdpClient;           // Page-level connection (Emulation, Page, DOM, etc.)
    private final CDPClient browserCdpClient;    // Browser-level connection (Target, Fetch for proxy auth)
    private final Fingerprint fingerprint;
    private final Path userDataDir;

    // Resource management
    private final int port;
    private final IntConsumer portReleaser;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean warmed = new AtomicBoolean(false);
    private final boolean ownsUserDataDir;

    // Resource blocking counter
    private final AtomicInteger blockedResourceCount = new AtomicInteger(0);

    // Page tracking
    private final Map<String, Page> pages = new ConcurrentHashMap<>();
    private volatile String mainPageTargetId;

    private Browser(BrowserConfig config, Process process, CDPClient cdpClient,
                    CDPClient browserCdpClient, Fingerprint fingerprint, Path userDataDir,
                    int port, IntConsumer portReleaser, boolean ownsUserDataDir) {
        this.config = config;
        this.process = process;
        this.cdpClient = cdpClient;
        this.browserCdpClient = browserCdpClient;
        this.fingerprint = fingerprint;
        this.userDataDir = userDataDir;
        this.port = port;
        this.portReleaser = portReleaser;
        this.ownsUserDataDir = ownsUserDataDir;
    }

    // ==================== Launch Methods ====================

    /**
     * Launches a new browser instance with the given configuration.
     *
     * <p>This method blocks until the browser is initialized and ready for use,
     * including CDP connections, proxy authentication setup, resource blocking,
     * and fingerprint application.</p>
     *
     * <p>For standalone usage without a BrowserManager:</p>
     * <pre>{@code
     * BrowserConfig config = BrowserConfig.builder()
     *     .executablePath("/path/to/chrome")
     *     .build();
     *
     * Browser browser = Browser.launch(config, 9222, port -> {});
     * }</pre>
     *
     * @param config       the browser configuration
     * @param port         the CDP debugging port to use
     * @param portReleaser callback to release the port back to pool on close (can be no-op)
     * @return a running Browser instance
     * @throws IOException if the browser process fails to start or CDP connection fails
     */
    public static Browser launch(BrowserConfig config, int port, IntConsumer portReleaser) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("BrowserConfig cannot be null");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
        }
        if (portReleaser == null) {
            portReleaser = p -> {}; // No-op if not provided
        }

        // Use provided userdata dir (persistent) or generate a temp one
        boolean ownsUserDataDir;
        Path userDataDir;

        if (config.hasUserDataDir()) {
            userDataDir = config.userDataDir();
            ownsUserDataDir = false;
            try {
                if (!Files.exists(userDataDir)) {
                    Files.createDirectories(userDataDir);
                }
            } catch (IOException e) {
                throw new IOException("Failed to create user data directory: " + userDataDir, e);
            }
            System.out.println("[Browser] Using persistent userdata: " + userDataDir);
        } else {
            userDataDir = generateUserDataDir();
            ownsUserDataDir = true;
        }

        // Load fingerprint if enabled
        Fingerprint fingerprint = null;
        if (config.fingerprintEnabled()) {
            if (config.fingerprintIndex() != null) {
                System.out.println("[Browser] Loading persisted fingerprint (index " +
                        config.fingerprintIndex() + ")...");
                fingerprint = new Fingerprint(config.fingerprintIndex());
            } else {
                System.out.println("[Browser] Loading random fingerprint...");
                fingerprint = new Fingerprint();
            }
            System.out.println("[Browser] Loaded fingerprint: " + fingerprint);
        }

        // Build Chrome arguments
        List<String> arguments = buildArguments(config, port, userDataDir, fingerprint);

        System.out.println("[Browser] Launching on port " + port + "...");

        // Start Chrome process
        ProcessBuilder processBuilder = new ProcessBuilder(arguments);
        Process process = processBuilder.start();

        // Connect to browser target for target discovery and proxy auth
        System.out.println("[Browser] Connecting to browser target...");
        CDPClient browserCdpClient = connectToBrowserWithRetry(port);

        // Connect to page target for page operations
        System.out.println("[Browser] Connecting to page target...");
        CDPClient cdpClient = connectWithRetry(port);

        Browser browser = new Browser(config, process, cdpClient, browserCdpClient,
                fingerprint, userDataDir, port, portReleaser, ownsUserDataDir);

        // Setup Fetch interception (proxy auth and/or resource blocking)
        if (browser.needsFetchInterception()) {
            browser.setupFetchInterception();
        }

        // Enable auto-attach to child frames (required for cross-origin iframe access)
        try {
            System.out.println("[Browser] Enabling frame auto-attach...");
            JsonObject autoAttachParams = new JsonObject();
            autoAttachParams.addProperty("autoAttach", true);
            autoAttachParams.addProperty("waitForDebuggerOnStart", false);
            autoAttachParams.addProperty("flatten", true);
            browserCdpClient.send("Target.setAutoAttach", autoAttachParams);
        } catch (TimeoutException e) {
            System.err.println("[Browser] Warning: Failed to enable frame auto-attach: " + e.getMessage());
        }

        // Setup target discovery to track all pages
        browser.setupTargetDiscovery();

        System.out.println("[Browser] Ready on port " + port);

        return browser;
    }

    /**
     * Generates a unique temporary user data directory.
     *
     * @return path to the new directory
     */
    private static Path generateUserDataDir() {
        String tempDir = System.getProperty("java.io.tmpdir");
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        return Path.of(tempDir, "nodriver4j-" + uniqueId);
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
    public SessionWarmer.WarmingResult warm() {
        return warm(null);
    }

    /**
     * Warms the browser profile with optional live UI logging.
     *
     * <p>Behaves identically to {@link #warm()}, but when a {@link TaskLogger}
     * is provided, warming progress (site visits, cookie counts) is reported
     * to the UI in real time via the logger's callbacks.</p>
     *
     * @param logger the TaskLogger for live UI updates, or null for stdout only
     * @return the warming result containing collected cookies and any warnings
     * @throws IllegalStateException if the browser has been closed
     * @see SessionWarmer#SessionWarmer(Page, TaskLogger)
     */
    public SessionWarmer.WarmingResult warm(TaskLogger logger) {
        ensureOpen();

        // Only warm once
        if (!warmed.compareAndSet(false, true)) {
            System.out.println("[Browser] Already warmed, skipping (port " + port + ")");
            return new SessionWarmer.WarmingResult(Collections.emptyMap(), Collections.emptyList());
        }

        System.out.println("[Browser] Starting profile warming (port " + port + ")...");

        SessionWarmer warmer = new SessionWarmer(page(), logger);
        SessionWarmer.WarmingResult result = warmer.warm();

        if (result.hasWarnings()) {
            System.err.println("[Browser] Warming completed with " +
                    result.getWarnings().size() + " warnings (port " + port + ")");
        } else {
            System.out.println("[Browser] Warming completed successfully (port " + port + ")");
        }

        return result;
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
            browserCdpClient.addEventListener("Target.attachedToTarget", params -> {
                System.out.println("[DEBUG] Target attached: " + params);
            });

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
            Page page;
            if (mainPageTargetId == null) {
                // First page - use the existing page-level CDP client
                mainPageTargetId = targetId;
                page = new Page(cdpClient, targetId, this, config.interactionOptions());
                pages.put(targetId, page);

                String url = targetInfo.has("url") ? targetInfo.get("url").getAsString() : "about:blank";
                System.out.println("[Browser] Main page registered: " + targetId + " (" + url + ")");
            } else {
                // Additional pages - track with limited functionality note
                String url = targetInfo.has("url") ? targetInfo.get("url").getAsString() : "about:blank";
                System.out.println("[Browser] Additional page detected: " + targetId + " (" + url + ")");
                System.out.println("[Browser] Note: Additional pages have limited automation support in current version");

                page = new Page(cdpClient, targetId, this, config.interactionOptions());
                pages.put(targetId, page);
            }

        } catch (Exception e) {
            System.err.println("[Browser] Failed to create page for target " + targetId + ": " + e.getMessage());
        }
    }

    // ==================== Fetch Interception (Proxy Auth + Resource Blocking) ====================

    /**
     * Checks if Fetch interception is needed for this browser configuration.
     *
     * @return true if proxy auth or resource blocking is enabled
     */
    private boolean needsFetchInterception() {
        return (config.hasProxy() && config.proxyConfig().requiresAuth()) || config.resourceBlocking();
    }

    /**
     * Sets up CDP Fetch interception for proxy authentication and/or resource blocking.
     *
     * <p>This method consolidates both proxy auth and resource blocking into a single
     * Fetch.enable call to avoid conflicts from multiple handlers.</p>
     */
    private void setupFetchInterception() {
        boolean hasProxy = config.hasProxy() && config.proxyConfig().requiresAuth();
        boolean hasBlocking = config.resourceBlocking();

        if (hasProxy) {
            System.out.println("[Browser] Setting up proxy authentication for " +
                    config.proxyConfig().host() + ":" + config.proxyConfig().port());
        }
        if (hasBlocking) {
            System.out.println("[Browser] Setting up resource blocking");
        }

        try {
            // Enable Fetch domain - only enable auth handling if proxy is configured
            JsonObject enableParams = new JsonObject();
            enableParams.addProperty("handleAuthRequests", hasProxy);
            browserCdpClient.send("Fetch.enable", enableParams);

            // Handle paused requests (resource blocking + continue)
            browserCdpClient.addEventListener("Fetch.requestPaused", this::handleRequestPaused);

            // Handle auth challenges (only relevant if proxy is configured)
            if (hasProxy) {
                browserCdpClient.addEventListener("Fetch.authRequired", this::handleProxyAuthRequired);
            }

            System.out.println("[Browser] Fetch interception configured (proxy=" + hasProxy +
                    ", blocking=" + hasBlocking + ")");

        } catch (TimeoutException e) {
            throw new RuntimeException("Failed to setup Fetch interception: " + e.getMessage(), e);
        }
    }

    /**
     * Handles paused requests from CDP Fetch domain.
     *
     * <p>This method decides whether to block or continue each request based on:</p>
     * <ol>
     *   <li>Resource type (if resource blocking is enabled)</li>
     *   <li>URL patterns matching analytics/tracking services</li>
     * </ol>
     */
    private void handleRequestPaused(JsonObject event) {
        String requestId = event.get("requestId").getAsString();

        // Check if we should block this request
        if (config.resourceBlocking() && shouldBlockRequest(event)) {
            // Block the request
            JsonObject failParams = new JsonObject();
            failParams.addProperty("requestId", requestId);
            failParams.addProperty("errorReason", "Aborted");
            browserCdpClient.sendAsync("Fetch.failRequest", failParams);

            blockedResourceCount.incrementAndGet();
            return;
        }

        // Continue the request
        JsonObject continueParams = new JsonObject();
        continueParams.addProperty("requestId", requestId);
        browserCdpClient.sendAsync("Fetch.continueRequest", continueParams);
    }

    /**
     * Determines if a request should be blocked based on resource type and URL.
     *
     * @param event the Fetch.requestPaused event
     * @return true if the request should be blocked
     */
    private boolean shouldBlockRequest(JsonObject event) {
        // Check resource type
        if (event.has("resourceType")) {
            String resourceType = event.get("resourceType").getAsString();
            if (BLOCKED_RESOURCE_TYPES.contains(resourceType)) {
                return true;
            }
        }

        // Check URL patterns
        if (event.has("request")) {
            JsonObject request = event.getAsJsonObject("request");
            if (request.has("url")) {
                String url = request.get("url").getAsString().toLowerCase();
                for (String pattern : BLOCKED_URL_PATTERNS) {
                    if (url.contains(pattern)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Handles proxy authentication challenges from CDP.
     */
    private void handleProxyAuthRequired(JsonObject event) {
        Proxy proxy = config.proxyConfig();
        String requestId = event.get("requestId").getAsString();

        if (event.has("authChallenge")) {
            JsonObject challenge = event.getAsJsonObject("authChallenge");
            String source = challenge.has("source") ? challenge.get("source").getAsString() : "unknown";
            String origin = challenge.has("origin") ? challenge.get("origin").getAsString() : "unknown";
            System.out.println("[Browser] Proxy auth required - source: " + source + ", origin: " + origin);
        }

        JsonObject authResponse = new JsonObject();
        authResponse.addProperty("requestId", requestId);

        JsonObject authChallengeResponse = new JsonObject();
        authChallengeResponse.addProperty("response", "ProvideCredentials");
        authChallengeResponse.addProperty("username", proxy.username());
        authChallengeResponse.addProperty("password", proxy.password());
        authResponse.add("authChallengeResponse", authChallengeResponse);

        browserCdpClient.sendAsync("Fetch.continueWithAuth", authResponse);

        System.out.println("[Browser] Proxy credentials provided for request: " + requestId);
    }

    /**
     * Gets the count of blocked resources since browser launch.
     *
     * @return the number of blocked resources
     */
    public int blockedResourceCount() {
        return blockedResourceCount.get();
    }

    /**
     * Logs a summary of blocked resources and resets the counter.
     *
     * <p>Call this after a page load or navigation to see how many resources were blocked.</p>
     */
    public void logAndResetBlockedCount() {
        int count = blockedResourceCount.getAndSet(0);
        if (count > 0) {
            System.out.println("[Browser] Blocked " + count + " resources");
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
    public Page page() {
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
    public List<Page> pages() {
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
    public Page pageByTargetId(String targetId) {
        ensureOpen();
        return pages.get(targetId);
    }

    /**
     * Gets the number of tracked pages.
     *
     * @return the page count
     */
    public int pageCount() {
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
        params.addProperty("targetId", page.targetId());

        browserCdpClient.send("Target.closeTarget", params);
        pages.remove(page.targetId());
    }

    // ==================== State and Resource Access ====================

    /**
     * Gets the configuration used by this browser.
     *
     * @return the BrowserConfig
     */
    public BrowserConfig config() {
        return config;
    }

    /**
     * Gets the debugging port allocated to this browser.
     *
     * @return the CDP debugging port number
     */
    public int port() {
        return port;
    }

    /**
     * Gets the user data directory for this browser session.
     *
     * @return the path to the user data directory
     */
    public Path userDataDir() {
        return userDataDir;
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
     * @return the page-level CDPClient instance
     * @throws IllegalStateException if browser is closed
     */
    public CDPClient cdpClient() {
        ensureOpen();
        return cdpClient;
    }

    /**
     * Gets the browser-level CDP client.
     *
     * @return the browser-level CDPClient instance
     * @throws IllegalStateException if browser is closed
     */
    public CDPClient browserCdpClient() {
        ensureOpen();
        return browserCdpClient;
    }

    /**
     * Gets the fingerprint used by this browser instance, if any.
     *
     * @return the Fingerprint, or null if fingerprinting is disabled
     */
    public Fingerprint fingerprint() {
        return fingerprint;
    }

    /**
     * Gets the proxy configuration for this browser, if any.
     *
     * @return the Proxy, or null if no proxy is configured
     */
    public Proxy proxyConfig() {
        return config.proxyConfig();
    }

    /**
     * Gets the interaction options for this browser.
     *
     * @return the InteractionOptions
     */
    public InteractionOptions interactionOptions() {
        return config.interactionOptions();
    }

    /**
     * Gets the AutoSolve AI service for this browser, if configured.
     *
     * @return the AutoSolveAIService, or null if not configured
     */
    public AutoSolveAIService autoSolveAIService() {
        return config.autoSolveAIService();
    }

    // ==================== Deprecated Methods (for backward compatibility) ====================

    /**
     * @deprecated Use {@link #page()} instead
     */
    @Deprecated
    public Page getPage() {
        return page();
    }

    /**
     * @deprecated Use {@link #pages()} instead
     */
    @Deprecated
    public List<Page> getPages() {
        return pages();
    }

    /**
     * @deprecated Use {@link #pageByTargetId(String)} instead
     */
    @Deprecated
    public Page getPageByTargetId(String targetId) {
        return pageByTargetId(targetId);
    }

    /**
     * @deprecated Use {@link #pageCount()} instead
     */
    @Deprecated
    public int getPageCount() {
        return pageCount();
    }

    /**
     * @deprecated Use {@link #port()} instead
     */
    @Deprecated
    public int getPort() {
        return port();
    }

    /**
     * @deprecated Use {@link #cdpClient()} instead
     */
    @Deprecated
    public CDPClient getCdpClient() {
        return cdpClient();
    }

    /**
     * @deprecated Use {@link #browserCdpClient()} instead
     */
    @Deprecated
    public CDPClient getBrowserCdpClient() {
        return browserCdpClient();
    }

    /**
     * @deprecated Use {@link #fingerprint()} instead
     */
    @Deprecated
    public Fingerprint getFingerprint() {
        return fingerprint();
    }

    /**
     * @deprecated Use {@link #proxyConfig()} instead
     */
    @Deprecated
    public Proxy getProxyConfig() {
        return proxyConfig();
    }

    /**
     * @deprecated Use {@link #interactionOptions()} instead
     */
    @Deprecated
    public InteractionOptions getInteractionOptions() {
        return interactionOptions();
    }

    // ==================== Lifecycle Management ====================

    /**
     * Ensures this browser is still open.
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
     * <p>This method is idempotent. It will:</p>
     * <ul>
     *   <li>Log resource blocking summary (if blocking was enabled)</li>
     *   <li>Close CDP WebSocket connections</li>
     *   <li>Terminate the Chrome process</li>
     *   <li>Delete the temporary user data directory</li>
     *   <li>Release the allocated port back to the pool</li>
     * </ul>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // Already closed
        }

        // Log blocked resources summary
        if (config.resourceBlocking()) {
            int count = blockedResourceCount.get();
            if (count > 0) {
                System.out.println("[Browser] Total blocked resources: " + count);
            }
        }

        try {
            // Remove event listeners and close CDP
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

            // Only delete userdata if we generated it (not externally provided)
            if (ownsUserDataDir) {
                deleteUserDataDir();
            } else {
                System.out.println("[Browser] Preserving persistent userdata: " + userDataDir);
            }

        } catch (Exception e) {
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
     */
    private void deleteUserDataDir() {
        if (userDataDir == null || !Files.exists(userDataDir)) {
            return;
        }

        final int maxRetries = 5;
        final int retryDelayMs = 500;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (tryDeleteDirectory(userDataDir)) {
                return;
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

        System.err.println("[Browser] Failed to delete user data directory after " + maxRetries +
                " attempts: " + userDataDir);
    }

    /**
     * Attempts to delete a directory and all its contents.
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

    /**
     * Builds Chrome command-line arguments from configuration.
     *
     * @param config      the browser configuration
     * @param port        the CDP debugging port
     * @param userDataDir the user data directory path
     * @param fingerprint the fingerprint to apply (can be null)
     * @return list of command-line arguments
     */
    private static List<String> buildArguments(BrowserConfig config, int port,
                                               Path userDataDir, Fingerprint fingerprint) {
        List<String> args = new ArrayList<>();
        args.add(config.executablePath());

        // Core browser arguments
        args.add("--remote-debugging-port=" + port);
        args.add("--user-data-dir=" + userDataDir.toAbsolutePath());
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
        args.add("--disable-features=BlockThirdPartyCookies,UseEcoQoSForBackgroundProcess");
        args.add("--disable-hang-monitor");
        args.add("--disable-domain-reliability");
        args.add("--metrics-recording-only");
        args.add("--mute-audio");
        args.add("--disable-sync");

        // Proxy configuration
        if (config.hasProxy()) {
            Proxy proxy = config.proxyConfig();
            args.add("--proxy-server=" + proxy.toProxyServerArg());
        }

        // Headless mode
        if (config.headless()) {
            args.add("--headless=new");

            // Random screen dimensions
            int[] resolution = HEADLESS_RESOLUTIONS[new Random().nextInt(HEADLESS_RESOLUTIONS.length)];
            args.add("--window-size=" + resolution[0] + "," + resolution[1]);

            // GPU acceleration (opt-in)
            if (config.headlessGpuAcceleration()) {
                args.add("--enable-gpu");
                args.add("--enable-webgl");
                args.add("--use-gl=desktop");
                args.add("--disable-software-rasterizer");
            }
        }

        // WebRTC policy
        String webrtcPolicy = config.webrtcPolicy();
        if (webrtcPolicy != null && !webrtcPolicy.isBlank()) {
            args.add("--webrtc-ip-handling-policy=" + webrtcPolicy);
        }

        // Custom arguments from config
        List<String> customArgs = config.arguments();
        if (customArgs != null && !customArgs.isEmpty()) {
            args.addAll(customArgs);
        }

        /*
        TODO Need to add --fingerprint-device-memory, --fingerprint-brand-version,
         --fingerprint-brand-version-long, --fingerprint-brand,
         --fingerprint-platform, --fingerprint-platform-version
         and more...
         */


        // Fingerprint arguments
        if (fingerprint != null) {
            args.add("--canvas-fingerprint=" + fingerprint.seed());
            args.add("--audio-fingerprint=" + fingerprint.seed());
            args.add("--fingerprint-hardware-concurrency=" + fingerprint.hardwareConcurrency());

            if (fingerprint.gpuVendor() != null) {
                args.add("--fingerprint-gpu-vendor=" + fingerprint.gpuVendor());
            }
            if (fingerprint.gpuRenderer() != null) {
                args.add("\"--fingerprint-gpu-renderer=" + fingerprint.gpuRenderer() + "\"");
            }
        }

        return args;
    }

    @Override
    public String toString() {
        return String.format("Browser{port=%d, open=%s, warmed=%s, pages=%d, fingerprint=%s, proxy=%s, resourceBlocking=%s}",
                port,
                !closed.get(),
                warmed.get(),
                pages.size(),
                fingerprint != null ? "enabled" : "disabled",
                config.proxyConfig() != null ? config.proxyConfig().host() : "none",
                config.resourceBlocking() ? "enabled" : "disabled");
    }
}