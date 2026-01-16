package org.nodriver4j.core;

import org.nodriver4j.cdp.CDPClient;
import org.nodriver4j.cdp.ProfileWarmer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Represents a managed browser session with automatic resource cleanup.
 *
 * <p>BrowserSession wraps a {@link Browser} instance along with its allocated resources
 * (port, proxy, fingerprint). When closed, it ensures all resources are properly released.</p>
 *
 * <p>Typical usage with try-with-resources:</p>
 * <pre>{@code
 * try (BrowserSession session = browserManager.createSession()) {
 *     Page page = session.getPage();
 *     page.navigate("https://example.com");
 *     page.click("//button[@id='submit']");
 * } // automatically closes browser and releases port
 * }</pre>
 *
 * <p>For profile warming, call {@link #warm()} explicitly or use
 * {@link BrowserManager#warmSessions(java.util.List)} for parallel warming:</p>
 * <pre>{@code
 * BrowserSession session = manager.createSession();
 * session.warm(); // blocks until warming completes
 * }</pre>
 *
 * <p>BrowserSession instances should only be created by {@link BrowserManager}.</p>
 */
public class BrowserSession implements AutoCloseable {

    private final Browser browser;
    private final int allocatedPort;
    private final IntConsumer portReleaser;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean warmed = new AtomicBoolean(false);

    /**
     * Creates a new BrowserSession by launching a browser with the given configuration.
     *
     * <p>Package-private constructor - only {@link BrowserManager} should create instances.</p>
     *
     * @param config       the browser configuration (must include allocated port)
     * @param portReleaser callback to release the port back to the pool on close
     * @throws IOException if the browser fails to launch
     */
    BrowserSession(BrowserConfig config, IntConsumer portReleaser) throws IOException {
        this(config, portReleaser, InteractionOptions.defaults());
    }

    /**
     * Creates a new BrowserSession with custom interaction options.
     *
     * <p>Package-private constructor - only {@link BrowserManager} should create instances.</p>
     *
     * @param config             the browser configuration (must include allocated port)
     * @param portReleaser       callback to release the port back to the pool on close
     * @param interactionOptions options for human-like interactions
     * @throws IOException if the browser fails to launch
     */
    BrowserSession(BrowserConfig config, IntConsumer portReleaser, InteractionOptions interactionOptions)
            throws IOException {
        this.allocatedPort = config.getPort();
        this.portReleaser = portReleaser;

        try {
            this.browser = Browser.launch(config, interactionOptions);
        } catch (IOException e) {
            // Release port if launch fails - don't leave it allocated
            releasePort();
            throw e;
        } catch (RuntimeException e) {
            // Handle unchecked exceptions from Browser.launch as well
            releasePort();
            throw e;
        }
    }

    /**
     * Warms the browser profile by visiting common websites to collect cookies.
     *
     * <p>This makes the browser profile appear more natural to anti-bot systems.
     * Warming visits sites like Google, YouTube, Facebook, Amazon, and Twitter
     * to accumulate realistic cookies and browsing history.</p>
     *
     * <p>This method blocks until warming is complete. For parallel warming of
     * multiple sessions, use {@link BrowserManager#warmSessions(java.util.List)}.</p>
     *
     * <p>Warming is idempotent - calling it multiple times has no additional effect.</p>
     *
     * @return the warming result containing collected cookies and any warnings
     * @throws IllegalStateException if the session has been closed
     */
    public ProfileWarmer.WarmingResult warm() {
        ensureOpen();

        // Only warm once
        if (!warmed.compareAndSet(false, true)) {
            System.out.println("[BrowserSession] Already warmed, skipping (port " + allocatedPort + ")");
            return new ProfileWarmer.WarmingResult(java.util.Collections.emptyMap(), java.util.Collections.emptyList());
        }

        System.out.println("[BrowserSession] Starting profile warming (port " + allocatedPort + ")...");

        ProfileWarmer warmer = new ProfileWarmer(browser.getCdpClient());
        ProfileWarmer.WarmingResult result = warmer.warm();

        if (result.hasWarnings()) {
            System.err.println("[BrowserSession] Warming completed with " +
                    result.getWarnings().size() + " warnings (port " + allocatedPort + ")");
        } else {
            System.out.println("[BrowserSession] Warming completed successfully (port " + allocatedPort + ")");
        }

        return result;
    }

    /**
     * Checks if profile warming is enabled for this session.
     *
     * <p>This reflects the configuration setting, not whether warming has been performed.
     * Use {@link #isWarmed()} to check if warming has already been done.</p>
     *
     * @return true if warming is enabled in the configuration
     */
    public boolean isWarmProfileEnabled() {
        return browser.isWarmProfileEnabled();
    }

    /**
     * Checks if this session has already been warmed.
     *
     * @return true if {@link #warm()} has been called
     */
    public boolean isWarmed() {
        return warmed.get();
    }

    // ==================== Page Access Methods ====================

    /**
     * Gets the main page (first/default tab) for this session.
     *
     * <p>This is the primary way to interact with the browser. Example:</p>
     * <pre>{@code
     * Page page = session.getPage();
     * page.navigate("https://example.com");
     * page.click("//button[@id='login']");
     * page.type("//input[@name='username']", "myuser");
     * }</pre>
     *
     * @return the main Page instance
     * @throws IllegalStateException if the session has been closed or no pages exist
     */
    public Page getPage() {
        ensureOpen();
        return browser.getPage();
    }

    /**
     * Gets all tracked pages in this browser session.
     *
     * <p>This includes pages opened by automation and pages opened manually by the user.</p>
     *
     * @return unmodifiable list of all Page instances
     * @throws IllegalStateException if the session has been closed
     */
    public List<Page> getPages() {
        ensureOpen();
        return browser.getPages();
    }

    /**
     * Gets a page by its CDP target ID.
     *
     * @param targetId the CDP target ID
     * @return the Page instance, or null if not found
     * @throws IllegalStateException if the session has been closed
     */
    public Page getPageByTargetId(String targetId) {
        ensureOpen();
        return browser.getPageByTargetId(targetId);
    }

    /**
     * Gets the number of open pages/tabs in this session.
     *
     * @return the page count
     * @throws IllegalStateException if the session has been closed
     */
    public int getPageCount() {
        ensureOpen();
        return browser.getPageCount();
    }

    /**
     * Creates a new page/tab in the browser.
     *
     * @return the new Page instance
     * @throws TimeoutException if the operation times out
     * @throws IllegalStateException if the session has been closed
     */
    public Page newPage() throws TimeoutException {
        ensureOpen();
        return browser.newPage();
    }

    /**
     * Creates a new page/tab in the browser and navigates to a URL.
     *
     * @param url the URL to navigate to
     * @return the new Page instance
     * @throws TimeoutException if the operation times out
     * @throws IllegalStateException if the session has been closed
     */
    public Page newPage(String url) throws TimeoutException {
        ensureOpen();
        return browser.newPage(url);
    }

    /**
     * Closes a specific page/tab.
     *
     * @param page the page to close
     * @throws TimeoutException if the operation times out
     * @throws IllegalStateException if the session has been closed
     */
    public void closePage(Page page) throws TimeoutException {
        ensureOpen();
        browser.closePage(page);
    }

    // ==================== Browser Access Methods ====================

    /**
     * Gets the underlying browser instance.
     *
     * @return the browser
     * @throws IllegalStateException if the session has been closed
     */
    public Browser getBrowser() {
        ensureOpen();
        return browser;
    }

    /**
     * Gets the page-level CDP client for direct protocol access.
     *
     * <p>Convenience method equivalent to {@code getBrowser().getCdpClient()}.</p>
     *
     * @return the page-level CDPClient instance
     * @throws IllegalStateException if the session has been closed
     */
    public CDPClient getCdpClient() {
        ensureOpen();
        return browser.getCdpClient();
    }

    /**
     * Gets the browser-level CDP client, if available.
     *
     * <p>This is always available and used for target management and proxy auth.</p>
     *
     * @return the browser-level CDPClient instance
     * @throws IllegalStateException if the session has been closed
     */
    public CDPClient getBrowserCdpClient() {
        ensureOpen();
        return browser.getBrowserCdpClient();
    }

    /**
     * Gets the fingerprint used by this browser session, if any.
     *
     * @return the Fingerprint, or null if fingerprinting is disabled
     * @throws IllegalStateException if the session has been closed
     */
    public Fingerprint getFingerprint() {
        ensureOpen();
        return browser.getFingerprint();
    }

    /**
     * Gets the proxy configuration for this session, if any.
     *
     * @return the ProxyConfig, or null if no proxy is configured
     * @throws IllegalStateException if the session has been closed
     */
    public ProxyConfig getProxyConfig() {
        ensureOpen();
        return browser.getProxyConfig();
    }

    /**
     * Gets the interaction options for this session.
     *
     * @return the InteractionOptions
     * @throws IllegalStateException if the session has been closed
     */
    public InteractionOptions getInteractionOptions() {
        ensureOpen();
        return browser.getInteractionOptions();
    }

    /**
     * Gets the debugging port allocated to this session.
     *
     * @return the CDP debugging port number
     */
    public int getPort() {
        return allocatedPort;
    }

    /**
     * Checks if this session is still open.
     *
     * @return true if the session has not been closed
     */
    public boolean isOpen() {
        return !closed.get();
    }

    /**
     * Checks if the browser process is still running.
     *
     * @return true if the browser process is alive
     */
    public boolean isBrowserRunning() {
        return !closed.get() && browser.isRunning();
    }

    /**
     * Closes this session, releasing all resources.
     *
     * <p>This method is idempotent - calling it multiple times has no additional effect.
     * It will:</p>
     * <ul>
     *   <li>Close the browser (terminates process, deletes user data directory)</li>
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
            browser.close();
        } catch (Exception e) {
            // Log but don't rethrow - we must release the port
            System.err.println("[BrowserSession] Error closing browser: " + e.getMessage());
        } finally {
            releasePort();
        }
    }

    /**
     * Releases the allocated port back to the pool.
     */
    private void releasePort() {
        try {
            portReleaser.accept(allocatedPort);
        } catch (Exception e) {
            System.err.println("[BrowserSession] Error releasing port " + allocatedPort + ": " + e.getMessage());
        }
    }

    /**
     * Ensures this session is still open, throwing if it has been closed.
     *
     * @throws IllegalStateException if the session has been closed
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("BrowserSession has been closed");
        }
    }

    @Override
    public String toString() {
        return String.format("BrowserSession{port=%d, open=%s, warmed=%s, pages=%d, fingerprint=%s, proxy=%s}",
                allocatedPort,
                !closed.get(),
                warmed.get(),
                browser.getPageCount(),
                browser.getFingerprint() != null ? "enabled" : "disabled",
                browser.getProxyConfig() != null ? browser.getProxyConfig().getHost() : "none");
    }
}