package org.nodriver4j.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.nodriver4j.cdp.CDPClient;
import org.nodriver4j.cdp.CDPSession;
import org.nodriver4j.scripts.SessionWarmer;
import org.nodriver4j.services.AutoSolveAIService;
import org.nodriver4j.services.TaskLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Manages a Chrome browser instance with automatic resource cleanup.
 *
 * <p>This class handles the complete browser lifecycle:</p>
 * <ul>
 *   <li>Launching Chrome with appropriate command-line arguments</li>
 *   <li>Establishing a single browser-level CDP connection</li>
 *   <li>Session-based multi-tab control via {@link CDPSession}</li>
 *   <li>Setting up proxy authentication via CDP Fetch domain</li>
 *   <li>Resource blocking for performance optimization</li>
 *   <li>Applying fingerprint settings via command-line arguments</li>
 *   <li>Tracking and managing Page instances for all browser tabs</li>
 *   <li>Attaching to tabs on demand for automation</li>
 *   <li>Profile warming to collect cookies and appear more natural</li>
 *   <li>Port tracking and release back to pool on close</li>
 *   <li>Cleanup on close (terminate process, delete user data directory)</li>
 * </ul>
 *
 * <h2>Multi-Tab Architecture</h2>
 * <p>All CDP communication flows through a single browser-level WebSocket.
 * Each tab is controlled via a {@link CDPSession} obtained by attaching to
 * the tab's target. Pages are discovered automatically via
 * {@code Target.setDiscoverTargets}, but only become functional after
 * attachment via {@link #attachToPage(String)}.</p>
 *
 * <h2>Tab Lifecycle for Scripts</h2>
 * <pre>{@code
 * // Main page is auto-attached during launch
 * Page mainPage = browser.page();
 * mainPage.navigate("https://example.com");
 *
 * // Click opens a new tab
 * mainPage.click("#open-tab-button");
 *
 * // Wait for new tab and attach
 * Page newTab = browser.waitForNewPageReady(5000);
 * newTab.navigate("https://other.com");
 *
 * // Or use separate steps
 * Page unattached = browser.waitForNewPage(5000);
 * Page attached = browser.attachToPage(unattached.targetId());
 *
 * // Find tab by URL
 * Page found = browser.pageByUrl("other.com");
 * }</pre>
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
 * @see CDPSession
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
    private final CDPClient browserCdpClient;    // Single browser-level connection for all CDP traffic
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
    private volatile String activePageTargetId;
    private volatile Consumer<Page> onPageAttached;

    /**
     * Last known URLs for tracked page targets, updated from CDP target events.
     * Enables {@link #pageByUrl(String)} without requiring CDP calls or attachment.
     */
    private final ConcurrentHashMap<String, String> targetUrls = new ConcurrentHashMap<>();

    /**
     * Queue of target IDs for newly created page targets.
     * Populated by {@link #onTargetCreated}, consumed by {@link #waitForNewPage}.
     */
    private final BlockingQueue<String> newPageQueue = new LinkedBlockingQueue<>();

    private Browser(BrowserConfig config, Process process, CDPClient browserCdpClient,
                    Fingerprint fingerprint, Path userDataDir,
                    int port, IntConsumer portReleaser, boolean ownsUserDataDir) {
        this.config = config;
        this.process = process;
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
     * including the CDP connection, proxy authentication setup, resource blocking,
     * target discovery, and initial page attachment.</p>
     *
     * <p>A single browser-level WebSocket is established. The initial page tab
     * is automatically attached and ready for use via {@link #page()}. Additional
     * tabs opened during automation can be accessed via {@link #waitForNewPage},
     * {@link #attachToPage}, or {@link #waitForNewPageReady}.</p>
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

        // Connect to browser target — the single WebSocket for all CDP traffic
        System.out.println("[Browser] Connecting to browser target...");
        CDPClient browserCdpClient = connectToBrowserWithRetry(port);

        Browser browser = new Browser(config, process, browserCdpClient,
                fingerprint, userDataDir, port, portReleaser, ownsUserDataDir);

        // Setup Fetch interception (proxy auth and/or resource blocking)
        if (browser.needsFetchInterception()) {
            browser.setupFetchInterception();
        }

        // Setup target discovery and attach initial page(s)
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
     *
     * <p>Registers event listeners for target lifecycle events, enables
     * target discovery, and auto-attaches any existing page targets so
     * they are immediately usable via {@link #page()}.</p>
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

            // Discover and auto-attach existing page targets
            JsonObject result = browserCdpClient.send("Target.getTargets", null);
            JsonArray targetInfos = result.getAsJsonArray("targetInfos");

            for (JsonElement element : targetInfos) {
                JsonObject targetInfo = element.getAsJsonObject();
                String type = targetInfo.get("type").getAsString();

                if ("page".equals(type)) {
                    String targetId = targetInfo.get("targetId").getAsString();
                    registerTarget(targetId, targetInfo);

                    // In setupTargetDiscovery(), after registerTarget:
                    try {
                        attachToPage(targetId);
                    } catch (Exception e) {
                        if (activePageTargetId != null && activePageTargetId.equals(targetId)) {
                            throw new RuntimeException("Failed to attach to initial page: " + e.getMessage(), e);
                        }
                        System.err.println("[Browser] Warning: Failed to attach to page "
                                + targetId + ": " + e.getMessage());
                    }
                }
            }

            System.out.println("[Browser] Target discovery enabled, tracking " + pages.size() + " page(s)");

            // Drain any target IDs queued during setup — these are pre-existing
            // pages, not new tabs opened by scripts
            newPageQueue.clear();
        } catch (TimeoutException e) {
            System.err.println("[Browser] Warning: Failed to setup target discovery: " + e.getMessage());
        }
    }

    /**
     * Handles target created events from CDP.
     *
     * <p>For page targets, registers the target (unattached) and queues the
     * target ID for consumption by {@link #waitForNewPage}.</p>
     */
    private void onTargetCreated(JsonObject params) {
        JsonObject targetInfo = params.getAsJsonObject("targetInfo");
        String type = targetInfo.get("type").getAsString();

        if ("page".equals(type)) {
            String targetId = targetInfo.get("targetId").getAsString();

            // Only queue if this is a genuinely new target, not a discovery
            // echo of an already-tracked page from setupTargetDiscovery()
            boolean isNew = !pages.containsKey(targetId);

            System.out.println("[Browser] New page target created: " + targetId);
            registerTarget(targetId, targetInfo);

            if (isNew) {
                newPageQueue.offer(targetId);
            }
        }
    }

    /**
     * Handles target destroyed events from CDP.
     */
    private void onTargetDestroyed(JsonObject params) {
        String targetId = params.get("targetId").getAsString();
        Page removed = pages.remove(targetId);
        targetUrls.remove(targetId);

        if (removed != null) {
            System.out.println("[Browser] Page target destroyed: " + targetId);

            if (targetId.equals(activePageTargetId)) {
                activePageTargetId = null;
            }

            if (removed.isAttached()) {
                removed.cdpSession().close();
            }
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
            targetUrls.put(targetId, url);

            Page page = pages.get(targetId);
            if (page != null) {
                System.out.println("[Browser] Page navigated: " + targetId + " -> " + url);
            }
        }
    }

    /**
     * Registers a page target for tracking without attaching a CDP session.
     *
     * <p>The resulting Page is unattached — calling automation methods on it
     * will fail until {@link #attachToPage} is called. The initial page(s)
     * discovered during launch are auto-attached in {@link #setupTargetDiscovery}.</p>
     *
     * @param targetId   the CDP target ID
     * @param targetInfo the target info from CDP
     */
    private void registerTarget(String targetId, JsonObject targetInfo) {
        if (pages.containsKey(targetId)) {
            return; // Already tracking this target
        }

        Page page = new Page(null, targetId, this, config.interactionOptions());
        pages.put(targetId, page);

        if (activePageTargetId == null) {
            activePageTargetId = targetId;
        }

        // Track URL from target info
        String url = targetInfo.has("url") ? targetInfo.get("url").getAsString() : "about:blank";
        targetUrls.put(targetId, url);

        System.out.println("[Browser] Tracking page: " + targetId + " (" + url + ")");
    }

    // ==================== Page Attachment ====================

    /**
     * Attaches to a page target, creating a {@link CDPSession} and making
     * the Page fully functional for automation.
     *
     * <p>This method calls {@code Target.attachToTarget} to obtain a session ID,
     * creates a {@link CDPSession} on the browser WebSocket, and wires it into
     * the Page object.</p>
     *
     * <p>This method is idempotent — calling it on an already-attached page
     * returns the existing Page as-is.</p>
     *
     * @param targetId the CDP target ID of the page to attach to
     * @return the attached, functional Page
     * @throws TimeoutException         if the CDP attachment fails
     * @throws IllegalArgumentException if no page is tracked with the given target ID
     * @throws IllegalStateException    if the browser has been closed
     */
    public Page attachToPage(String targetId) throws TimeoutException {
        ensureOpen();

        Page page = pages.get(targetId);
        if (page == null) {
            throw new IllegalArgumentException("No tracked page with targetId: " + targetId);
        }

        // Idempotent — already attached
        if (page.isAttached()) {
            return page;
        }

        // Attach to the target to get a session ID
        JsonObject params = new JsonObject();
        params.addProperty("targetId", targetId);
        params.addProperty("flatten", true);

        JsonObject result = browserCdpClient.send("Target.attachToTarget", params);
        String sessionId = result.get("sessionId").getAsString();

        // Create a CDPSession and wire it into the Page
        CDPSession session = browserCdpClient.createSession(sessionId);
        page.attachSession(session);

        activePageTargetId = targetId;

        System.out.println("[Browser] Attached to page: " + targetId + " (sessionId=" + sessionId + ")");

        Consumer<Page> callback = onPageAttached;
        if (callback != null) {
            callback.accept(page);
        }

        return page;
    }

    /**
     * Waits for a new page target to appear.
     *
     * <p>Blocks until a {@code Target.targetCreated} event fires for a page
     * target, then returns the corresponding Page. The returned Page is
     * <b>unattached</b> — calling automation methods on it will fail until
     * {@link #attachToPage} is called.</p>
     *
     * <p>Use this when you need to detect a new tab before deciding whether
     * to interact with it. For the common case of "wait + attach", use
     * {@link #waitForNewPageReady} instead.</p>
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the unattached Page for the new tab
     * @throws TimeoutException      if no new page appears within the timeout
     * @throws IllegalStateException if the browser has been closed
     */
    public Page waitForNewPage(long timeoutMs) throws TimeoutException {
        ensureOpen();

        try {
            String targetId = newPageQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (targetId == null) {
                throw new TimeoutException("No new page appeared within " + timeoutMs + "ms");
            }

            Page page = pages.get(targetId);
            if (page == null) {
                throw new TimeoutException("New page was detected but not tracked: " + targetId);
            }

            return page;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for new page", e);
        }
    }

    /**
     * Waits for a new page target to appear and attaches to it.
     *
     * <p>Combines {@link #waitForNewPage} and {@link #attachToPage} into a
     * single call. The returned Page is fully functional for automation.</p>
     *
     * <p>This is the recommended method for scripts that click a button
     * expected to open a new tab:</p>
     * <pre>{@code
     * page.click("#open-new-tab");
     * Page newTab = browser.waitForNewPageReady(5000);
     * newTab.navigate("https://example.com");
     * }</pre>
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the attached, functional Page for the new tab
     * @throws TimeoutException      if no new page appears or attachment fails
     * @throws IllegalStateException if the browser has been closed
     */
    public Page waitForNewPageReady(long timeoutMs) throws TimeoutException {
        Page page = waitForNewPage(timeoutMs);
        return attachToPage(page.targetId());
    }

    /**
     * Brings a page's tab to the foreground in the browser window.
     *
     * <p>This calls {@code Target.activateTarget} which makes the tab visible
     * in the Chrome UI. It does not affect which Page can receive automation
     * commands — all attached Pages are independently controllable regardless
     * of which tab is visually active.</p>
     *
     * @param page the page to activate
     * @throws TimeoutException      if the CDP call fails
     * @throws IllegalStateException if the browser has been closed
     */
    public void activatePage(Page page) throws TimeoutException {
        ensureOpen();

        JsonObject params = new JsonObject();
        params.addProperty("targetId", page.targetId());
        browserCdpClient.send("Target.activateTarget", params);
    }

    public void onPageAttached(Consumer<Page> listener) {
        this.onPageAttached = listener;
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
     * <p>The main page is the first page target discovered during browser launch.
     * It is automatically attached and ready for automation.</p>
     *
     * @return the main Page instance
     * @throws IllegalStateException if no pages are available or browser is closed
     */
    public Page page() {
        ensureOpen();

        if (activePageTargetId != null) {
            Page page = pages.get(activePageTargetId);
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
     * Gets all tracked pages (both attached and unattached).
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
     * Finds a tracked page by URL substring match.
     *
     * <p>Searches the last known URLs from CDP target events — does not make
     * CDP calls, so works for both attached and unattached pages.</p>
     *
     * @param urlSubstring the substring to search for in page URLs
     * @return the first matching Page, or null if no match
     * @throws IllegalStateException if browser is closed
     */
    public Page pageByUrl(String urlSubstring) {
        ensureOpen();

        for (Map.Entry<String, String> entry : targetUrls.entrySet()) {
            String url = entry.getValue();
            if (url != null && url.contains(urlSubstring)) {
                Page page = pages.get(entry.getKey());
                if (page != null) {
                    return page;
                }
            }
        }

        return null;
    }

    /**
     * Gets the last known URL for a page target.
     *
     * <p>Returns the URL from the most recent CDP target event, without making
     * a CDP call. May be stale if the page has navigated since the last event.</p>
     *
     * @param targetId the CDP target ID
     * @return the last known URL, or null if unknown
     */
    public String targetUrl(String targetId) {
        return targetUrls.get(targetId);
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
     * <p>The new page is automatically attached and ready for automation.</p>
     *
     * @return the new Page instance
     * @throws TimeoutException      if the operation times out
     * @throws IllegalStateException if browser is closed
     */
    public Page newPage() throws TimeoutException {
        return newPage("about:blank");
    }

    /**
     * Creates a new page/tab in the browser and navigates to a URL.
     *
     * <p>The new page is automatically attached and ready for automation.</p>
     *
     * @param url the URL to navigate to
     * @return the new Page instance
     * @throws TimeoutException      if the operation times out
     * @throws IllegalStateException if browser is closed
     */
    public Page newPage(String url) throws TimeoutException {
        ensureOpen();

        JsonObject params = new JsonObject();
        params.addProperty("url", url);

        JsonObject result = browserCdpClient.send("Target.createTarget", params);
        String targetId = result.get("targetId").getAsString();

        // Wait for the page to be registered via target discovery
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (pages.containsKey(targetId)) {
                return attachToPage(targetId);
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
     * <p>Closes the page's CDP session (if attached), then closes the target
     * in Chrome. The page is removed from tracking.</p>
     *
     * @param page the page to close
     * @throws TimeoutException      if the operation times out
     * @throws IllegalStateException if browser is closed
     */
    public void closePage(Page page) throws TimeoutException {
        ensureOpen();

        // Close the session if attached
        if (page.isAttached()) {
            page.cdpSession().close();
        }

        JsonObject params = new JsonObject();
        params.addProperty("targetId", page.targetId());
        browserCdpClient.send("Target.closeTarget", params);

        pages.remove(page.targetId());
        targetUrls.remove(page.targetId());
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
     * Gets the browser-level CDP client.
     *
     * <p>Use this for browser-wide commands (Target, Fetch). For page-level
     * commands, use {@link Page#cdpSession()} instead.</p>
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
     *   <li>Close all CDP sessions and the browser WebSocket</li>
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
            // Close CDP — this closes all sessions and the WebSocket
            if (browserCdpClient != null) {
                browserCdpClient.removeAllEventListeners();
                browserCdpClient.close();
            }

            // Clear page tracking
            pages.clear();
            activePageTargetId = null;
            newPageQueue.clear();
            targetUrls.clear();

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
        args.add("--disable-features=BlockThirdPartyCookies,UseEcoQoSForBackgroundProcess,ReduceAcceptLanguage,ReduceAcceptLanguageHTTP");
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