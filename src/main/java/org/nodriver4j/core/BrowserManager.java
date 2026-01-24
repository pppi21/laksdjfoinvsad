package org.nodriver4j.core;

import org.nodriver4j.profiles.ProfilePool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.nodriver4j.services.AutoSolveAIService;


/**
 * Manages browser instances with automatic resource allocation and thread pool execution.
 *
 * <p>BrowserManager handles:</p>
 * <ul>
 *   <li>Thread pool management (auto-sized to available processors by default)</li>
 *   <li>Port allocation for CDP connections</li>
 *   <li>Proxy consumption from file (if enabled)</li>
 *   <li>Profile consumption from CSV (if configured)</li>
 *   <li>Automatic profile warming (if enabled)</li>
 *   <li>Browser lifecycle and cleanup</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Managed Execution (Recommended)</h3>
 * <pre>{@code
 * BrowserManager manager = BrowserManager.builder()
 *     .executablePath("/path/to/chrome")
 *     .build();
 *
 * // Submit tasks - returns Future for result handling
 * Future<String> future = manager.submit(browser -> {
 *     Page page = browser.getPage();
 *     page.navigate("https://example.com");
 *     return page.getTitle();
 * });
 *
 * String result = future.get(); // blocks until complete
 * manager.shutdown();
 * }</pre>
 *
 * <h3>Manual Browser Control</h3>
 * <pre>{@code
 * BrowserManager manager = BrowserManager.builder()
 *     .executablePath("/path/to/chrome")
 *     .warmProfile(true)  // Enable auto-warming
 *     .build();
 *
 * // Single browser - auto-warms if enabled
 * try (Browser browser = manager.createSession()) {
 *     Page page = browser.getPage();
 *     page.navigate("https://example.com");
 *     page.click("//button[@id='login']");
 * }
 *
 * // Multiple browsers - creates all, then warms all in parallel
 * List<Browser> browsers = manager.createSessions(6);
 * // All 6 browsers are now launched and warmed (if enabled)
 * }</pre>
 *
 * <h3>With Profile Management</h3>
 * <pre>{@code
 * BrowserManager manager = BrowserManager.builder()
 *     .executablePath("/path/to/chrome")
 *     .profileInputPath("input_profiles.csv")
 *     .profileOutputPath("completed_profiles.csv")
 *     .build();
 *
 * // Get the shared profile pool
 * ProfilePool pool = manager.profilePool();
 * Profile profile = pool.consumeFirst();
 *
 * // After script completes
 * Profile completed = profile.toBuilder()
 *     .accountLoginInfo(email + ":" + password)
 *     .build();
 * pool.writeCompleted(completed);
 * }</pre>
 *
 * <h3>Custom Interaction Options</h3>
 * <pre>{@code
 * InteractionOptions fastOptions = InteractionOptions.builder()
 *     .moveSpeed(80)
 *     .keystrokeDelayMin(30)
 *     .keystrokeDelayMax(80)
 *     .build();
 *
 * BrowserManager manager = BrowserManager.builder()
 *     .executablePath("/path/to/chrome")
 *     .interactionOptions(fastOptions)
 *     .build();
 * }</pre>
 */
public class BrowserManager implements AutoCloseable {

    private static final int DEFAULT_PORT_RANGE_START = 9222;
    private static final int DEFAULT_PORT_RANGE_END = 9621;

    private final ExecutorService executor;
    private final BlockingQueue<Integer> availablePorts;
    private final Set<Browser> activeBrowsers;
    private final AtomicBoolean isShutdown;
    private final Thread shutdownHook;

    // Configuration for browser creation
    private final String executablePath;
    private final boolean fingerprintEnabled;
    private final boolean warmProfile;
    private final boolean headless;
    private final String webrtcPolicy;
    private final boolean proxyEnabled;
    private final InteractionOptions interactionOptions;
    private final ArrayList<String> arguements;
    private final boolean headlessGpuAcceleration;

    // Profile management
    private final String profileInputPath;
    private final String profileOutputPath;
    private volatile ProfilePool profilePool;
    private final Object profilePoolLock = new Object();

    // AutoSolve AI integration
    private final String autoSolveAIKey;
    private volatile AutoSolveAIService autoSolveAIService;
    private final Object autoSolveAIServiceLock = new Object();

    private BrowserManager(Builder builder) {
        this.executablePath = builder.executablePath;
        this.fingerprintEnabled = builder.fingerprintEnabled;
        this.warmProfile = builder.warmProfile;
        this.headless = builder.headless;
        this.webrtcPolicy = builder.webrtcPolicy;
        this.proxyEnabled = builder.proxyEnabled;
        this.interactionOptions = builder.interactionOptions;
        this.profileInputPath = builder.profileInputPath;
        this.profileOutputPath = builder.profileOutputPath;
        this.arguements = builder.arguements;
        this.headlessGpuAcceleration = builder.headlessGpuAcceleration;
        this.autoSolveAIKey = builder.autoSolveAIKey;

        this.isShutdown = new AtomicBoolean(false);
        this.activeBrowsers = ConcurrentHashMap.newKeySet();

        // Initialize port pool
        this.availablePorts = new LinkedBlockingQueue<>();
        for (int port = builder.portRangeStart; port <= builder.portRangeEnd; port++) {
            availablePorts.add(port);
        }

        // Initialize thread pool
        int threadCount = Math.min(Runtime.getRuntime().availableProcessors(), builder.threadCount);

        this.executor = new ThreadPoolExecutor(
                threadCount,                      // core pool size
                threadCount,                      // max pool size (same as core for fixed pool)
                60L, TimeUnit.SECONDS,            // keep-alive time for idle threads
                new LinkedBlockingQueue<>(),      // unbounded task queue
                new BrowserThreadFactory(),       // custom thread factory for naming
                new ThreadPoolExecutor.AbortPolicy()  // reject if shutdown
        );

        // Register shutdown hook for emergency cleanup
        this.shutdownHook = new Thread(this::emergencyShutdown, "BrowserManager-ShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        System.out.println("[BrowserManager] Initialized with " + threadCount + " threads, "
                + availablePorts.size() + " ports available, warmProfile=" + warmProfile);

        if (hasProfilePaths()) {
            System.out.println("[BrowserManager] Profile management enabled: input=" + profileInputPath
                    + ", output=" + profileOutputPath);
        }
    }

    /**
     * Creates a new builder for BrowserManager configuration.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== Profile Pool Access ====================

    /**
     * Gets the shared ProfilePool for this manager.
     *
     * <p>The ProfilePool is created lazily on first access and shared across
     * all browsers managed by this instance. This allows thread-safe profile
     * consumption across multiple concurrent browser tasks.</p>
     *
     * <p>If profile paths were not configured, this method attempts to create
     * a ProfilePool using environment variables ({@code profiles_input} and
     * {@code profiles_output}).</p>
     *
     * @return the shared ProfilePool instance
     * @throws IllegalStateException if profile paths are not configured and
     *                               environment variables are not set
     */
    public ProfilePool profilePool() {
        if (profilePool == null) {
            synchronized (profilePoolLock) {
                if (profilePool == null) {
                    if (hasProfilePaths()) {
                        profilePool = new ProfilePool(profileInputPath, profileOutputPath);
                    } else {
                        // Fall back to environment variables
                        profilePool = new ProfilePool();
                    }
                }
            }
        }
        return profilePool;
    }

    /**
     * Checks if profile paths were explicitly configured.
     *
     * @return true if both input and output paths are set
     */
    public boolean hasProfilePaths() {
        return profileInputPath != null && !profileInputPath.isBlank()
                && profileOutputPath != null && !profileOutputPath.isBlank();
    }

    /**
     * Gets the configured profile input path.
     *
     * @return the input path, or null if not configured
     */
    public String profileInputPath() {
        return profileInputPath;
    }

    /**
     * Gets the configured profile output path.
     *
     * @return the output path, or null if not configured
     */
    public String profileOutputPath() {
        return profileOutputPath;
    }

    // ==================== AutoSolve AI Service ====================

    /**
     * Gets the shared AutoSolveAIService for this manager.
     *
     * <p>The service is created lazily on first access. If no API key was
     * configured via the builder, this method returns null.</p>
     *
     * @return the shared AutoSolveAIService instance, or null if not configured
     */
    public AutoSolveAIService autoSolveAIService() {
        if (autoSolveAIKey == null) {
            return null;
        }

        if (autoSolveAIService == null) {
            synchronized (autoSolveAIServiceLock) {
                if (autoSolveAIService == null) {
                    autoSolveAIService = new AutoSolveAIService(autoSolveAIKey);
                }
            }
        }
        return autoSolveAIService;
    }

    /**
     * Checks if AutoSolve AI is configured.
     *
     * @return true if an API key was provided
     */
    public boolean hasAutoSolveAI() {
        return autoSolveAIKey != null && !autoSolveAIKey.isBlank();
    }

    // ==================== Task Submission Methods ====================

    /**
     * Submits an automation task for execution.
     *
     * <p>The task will be queued and executed when a thread becomes available.
     * A new browser is created for the task and automatically closed
     * after the task completes (whether successfully or with an exception).</p>
     *
     * <p>If warming is enabled, the browser is warmed before the task runs.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * manager.submit(browser -> {
     *     Page page = browser.getPage();
     *     page.navigate("https://example.com");
     *     page.click("//button[@id='submit']");
     *     return page.getText("//div[@class='result']");
     * });
     * }</pre>
     *
     * @param task the automation task to execute, receives a Browser and returns a result
     * @param <T>  the result type
     * @return a Future representing the pending result
     * @throws RejectedExecutionException if the manager has been shutdown
     */
    public <T> Future<T> submit(Function<Browser, T> task) {
        ensureNotShutdown();

        return executor.submit(() -> {
            try (Browser browser = createSession()) {
                return task.apply(browser);
            }
        });
    }

    /**
     * Submits an automation task that doesn't return a result.
     *
     * @param task the automation task to execute
     * @return a Future that completes when the task is done
     * @throws RejectedExecutionException if the manager has been shutdown
     */
    public Future<Void> submit(ThrowingConsumer<Browser> task) {
        ensureNotShutdown();

        return executor.submit(() -> {
            try (Browser browser = createSession()) {
                task.accept(browser);
                return null;
            }
        });
    }

    /**
     * Submits an automation task that works directly with a Page.
     *
     * <p>Convenience method that automatically extracts the main page:</p>
     * <pre>{@code
     * manager.submitPage(page -> {
     *     page.navigate("https://example.com");
     *     page.click("//button[@id='login']");
     *     return page.getTitle();
     * });
     * }</pre>
     *
     * @param task the automation task to execute, receives a Page and returns a result
     * @param <T>  the result type
     * @return a Future representing the pending result
     * @throws RejectedExecutionException if the manager has been shutdown
     */
    public <T> Future<T> submitPage(Function<Page, T> task) {
        ensureNotShutdown();

        return executor.submit(() -> {
            try (Browser browser = createSession()) {
                return task.apply(browser.getPage());
            }
        });
    }

    /**
     * Submits an automation task that works directly with a Page and doesn't return a result.
     *
     * @param task the automation task to execute
     * @return a Future that completes when the task is done
     * @throws RejectedExecutionException if the manager has been shutdown
     */
    public Future<Void> submitPage(ThrowingConsumer<Page> task) {
        ensureNotShutdown();

        return executor.submit(() -> {
            try (Browser browser = createSession()) {
                task.accept(browser.getPage());
                return null;
            }
        });
    }

    // ==================== Browser Creation Methods ====================

    /**
     * Creates a new browser instance.
     *
     * <p>If warming is enabled in the manager configuration, the browser will
     * be automatically warmed before this method returns.</p>
     *
     * <p>The caller is responsible for closing the browser when done.
     * Use try-with-resources for automatic cleanup:</p>
     *
     * <pre>{@code
     * try (Browser browser = manager.createSession()) {
     *     Page page = browser.getPage();
     *     page.navigate("https://example.com");
     *     page.click("//button[@id='submit']");
     * }
     * }</pre>
     *
     * @return a new Browser instance (warmed if warming is enabled)
     * @throws IOException              if the browser fails to launch
     * @throws IllegalStateException    if the manager has been shutdown
     * @throws InterruptedException     if interrupted while waiting for a port
     * @throws NoAvailablePortException if no ports are available (all in use)
     */
    public Browser createSession() throws IOException, InterruptedException {
        Browser browser = createBrowserInternal();

        // Auto-warm if enabled (blocks until complete)
        if (warmProfile) {
            browser.warm();
        }

        return browser;
    }

    /**
     * Creates multiple browser instances with parallel warming.
     *
     * <p>This method is optimized for creating multiple browsers:</p>
     * <ol>
     *   <li>Phase 1: Creates all browsers as fast as possible (sequential but fast)</li>
     *   <li>Phase 2: Warms all browsers in parallel using the thread pool (if warming enabled)</li>
     * </ol>
     *
     * <p>If any browser fails to launch, the method continues with the remaining browsers
     * and logs warnings for failures. Successfully created browsers are returned.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * // Create 6 browsers - all warmed in parallel
     * List<Browser> browsers = manager.createSessions(6);
     *
     * // Use the browsers
     * for (Browser browser : browsers) {
     *     // Each browser is ready to use
     *     browser.getPage().navigate("https://example.com");
     * }
     *
     * // Don't forget to close them when done
     * browsers.forEach(Browser::close);
     * }</pre>
     *
     * @param count the number of browsers to create
     * @return list of successfully created browsers (may be less than count if some failed)
     * @throws IllegalStateException    if the manager has been shutdown
     * @throws IllegalArgumentException if count is less than 1
     */
    public List<Browser> createSessions(int count) throws InterruptedException {
        ensureNotShutdown();

        if (count < 1) {
            throw new IllegalArgumentException("Count must be at least 1, got: " + count);
        }

        List<Browser> browsers = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        System.out.println("[BrowserManager] Creating " + count + " browser(s)...");

        // Phase 1: Create all browsers (fast, no warming)
        for (int i = 0; i < count; i++) {
            try {
                Browser browser = createBrowserInternal();
                browsers.add(browser);
                System.out.println("[BrowserManager] Browser " + (i + 1) + "/" + count +
                        " launched (port " + browser.getPort() + ")");
            } catch (IOException e) {
                String error = "Browser " + (i + 1) + ": " + e.getMessage();
                errors.add(error);
                System.err.println("[BrowserManager] Failed to launch browser " + (i + 1) + ": " + e.getMessage());
            }
        }

        // Log warnings for failures
        if (!errors.isEmpty()) {
            System.err.println("[BrowserManager] Warning: " + errors.size() + " of " + count +
                    " browser(s) failed to launch");
        }

        // Phase 2: Warm all browsers in parallel (if enabled)
        if (warmProfile && !browsers.isEmpty()) {
            warmAllInternal(browsers);
        }

        System.out.println("[BrowserManager] " + browsers.size() + " browser(s) ready");

        return browsers;
    }

    /**
     * Creates a browser without warming (internal use only).
     *
     * @return a new Browser instance (not warmed)
     * @throws IOException          if the browser fails to launch
     * @throws InterruptedException if interrupted while waiting for a port
     */
    private Browser createBrowserInternal() throws IOException, InterruptedException {
        ensureNotShutdown();

        // Allocate port (blocks if none available, but with timeout)
        Integer port = availablePorts.poll(30, TimeUnit.SECONDS);
        if (port == null) {
            throw new NoAvailablePortException("No ports available after 30 seconds. " +
                    "All " + (DEFAULT_PORT_RANGE_END - DEFAULT_PORT_RANGE_START + 1) + " ports are in use.");
        }

        try {
            BrowserConfig config = buildConfig(port);
            Browser browser = Browser.launch(config, this::releasePort, interactionOptions);

            // Track browser for shutdown cleanup
            activeBrowsers.add(browser);

            return browser;

        } catch (IOException | RuntimeException e) {
            // Release port if browser creation fails
            releasePort(port);
            throw e;
        }
    }

    /**
     * Warms multiple browsers in parallel using the thread pool.
     *
     * @param browsers the browsers to warm
     */
    private void warmAllInternal(List<Browser> browsers) {
        if (browsers.isEmpty()) {
            return;
        }

        System.out.println("[BrowserManager] Warming " + browsers.size() + " browser(s) in parallel...");

        // Submit warming tasks to thread pool
        List<Future<?>> futures = new ArrayList<>();
        for (Browser browser : browsers) {
            futures.add(executor.submit(browser::warm));
        }

        // Wait for all to complete, log any failures
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < futures.size(); i++) {
            try {
                futures.get(i).get();
                successCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[BrowserManager] Warming interrupted for browser " + (i + 1));
                failureCount++;
            } catch (ExecutionException e) {
                System.err.println("[BrowserManager] Warming failed for browser " + (i + 1) +
                        ": " + e.getCause().getMessage());
                failureCount++;
            }
        }

        System.out.println("[BrowserManager] Warming complete: " + successCount + " succeeded, " +
                failureCount + " failed");
    }

    // ==================== Configuration Helpers ====================

    /**
     * Builds a BrowserConfig with the allocated port and current settings.
     */
    private BrowserConfig buildConfig(int port) throws IOException {
        BrowserConfig.Builder builder = BrowserConfig.builder()
                .executablePath(executablePath)
                .port(port)
                .fingerprintEnabled(fingerprintEnabled)
                .warmProfile(warmProfile)
                .headless(headless)
                .webrtcPolicy(webrtcPolicy)
                .headlessGpuAcceleration(headlessGpuAcceleration)
                .chromeArguements(arguements);

        // Consume proxy from file if enabled
        if (proxyEnabled) {
            try {
                ProxyConfig proxy = new ProxyConfig(); // Consumes from env file
                builder.proxy(proxy);
            } catch (IOException e) {
                System.err.println("[BrowserManager] Warning: Failed to load proxy: " + e.getMessage());
                System.err.println("[BrowserManager] Continuing without proxy.");
            } catch (IllegalStateException e) {
                // Environment variable not set - this is expected if proxyEnabled but no file
                System.err.println("[BrowserManager] Warning: " + e.getMessage());
                System.err.println("[BrowserManager] Continuing without proxy.");
            }
        }

        return builder.build();
    }

    /**
     * Releases a port back to the available pool.
     */
    private void releasePort(int port) {
        availablePorts.offer(port);
        System.out.println("[BrowserManager] Released port " + port + " (available: " + availablePorts.size() + ")");
    }

    // ==================== Status Methods ====================

    /**
     * Gets the interaction options configured for this manager.
     *
     * @return the InteractionOptions
     */
    public InteractionOptions interactionOptions() {
        return interactionOptions;
    }

    /**
     * Gets the number of currently active browsers.
     *
     * @return the count of active browsers
     */
    public int activeBrowserCount() {
        return activeBrowsers.size();
    }

    /**
     * Gets the number of available ports.
     *
     * @return the count of ports not currently in use
     */
    public int availablePortCount() {
        return availablePorts.size();
    }

    /**
     * Checks if the manager has been shutdown.
     *
     * @return true if shutdown has been initiated
     */
    public boolean isShutdown() {
        return isShutdown.get();
    }

    /**
     * Checks if all tasks have completed after shutdown.
     *
     * @return true if shutdown and all tasks are done
     */
    public boolean isTerminated() {
        return executor.isTerminated();
    }

    /**
     * Checks if profile warming is enabled.
     *
     * @return true if warming is enabled
     */
    public boolean isWarmProfileEnabled() {
        return warmProfile;
    }

    // ==================== Shutdown Methods ====================

    /**
     * Initiates an orderly shutdown.
     *
     * <p>Stops accepting new tasks and waits for currently executing tasks to complete.
     * Does not forcibly close running browsers.</p>
     */
    public void shutdown() {
        if (!isShutdown.compareAndSet(false, true)) {
            return; // Already shutdown
        }

        System.out.println("[BrowserManager] Initiating shutdown...");

        // Stop accepting new tasks
        executor.shutdown();

        // Remove shutdown hook since we're shutting down normally
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM is already shutting down
        }
    }

    /**
     * Initiates an orderly shutdown and waits for completion.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit
     * @return true if all tasks completed, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean shutdown(long timeout, TimeUnit unit) throws InterruptedException {
        shutdown();
        return executor.awaitTermination(timeout, unit);
    }

    /**
     * Attempts to stop all actively executing tasks and closes all browsers.
     *
     * <p>Use this for immediate cleanup when you don't want to wait for tasks to complete.</p>
     */
    public void shutdownNow() {
        if (!isShutdown.compareAndSet(false, true)) {
            // Already shutdown, but still close browsers
            closeAllBrowsers();
            return;
        }

        System.out.println("[BrowserManager] Initiating immediate shutdown...");

        // Stop accepting and interrupt running tasks
        executor.shutdownNow();

        // Close all active browsers
        closeAllBrowsers();

        // Remove shutdown hook
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM is already shutting down
        }
    }

    /**
     * Waits for all submitted tasks to complete after a shutdown.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit
     * @return true if all tasks completed, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    /**
     * Closes all active browsers.
     */
    private void closeAllBrowsers() {
        System.out.println("[BrowserManager] Closing " + activeBrowsers.size() + " active browser(s)...");

        for (Browser browser : activeBrowsers) {
            try {
                browser.close();
            } catch (Exception e) {
                System.err.println("[BrowserManager] Error closing browser: " + e.getMessage());
            }
        }

        activeBrowsers.clear();
    }

    /**
     * Emergency shutdown called by JVM shutdown hook.
     * Closes all browsers without waiting for tasks.
     */
    private void emergencyShutdown() {
        System.out.println("[BrowserManager] Emergency shutdown triggered...");
        isShutdown.set(true);
        executor.shutdownNow();
        closeAllBrowsers();
    }

    /**
     * Ensures the manager hasn't been shutdown.
     *
     * @throws IllegalStateException if shutdown has been called
     */
    private void ensureNotShutdown() {
        if (isShutdown.get()) {
            throw new IllegalStateException("BrowserManager has been shutdown");
        }
    }

    /**
     * Implements AutoCloseable for try-with-resources support.
     * Equivalent to calling {@link #shutdown()}.
     */
    @Override
    public void close() {
        shutdown();
    }

    // ==================== Inner Classes ====================

    /**
     * Custom exception thrown when no ports are available.
     */
    public static class NoAvailablePortException extends RuntimeException {
        public NoAvailablePortException(String message) {
            super(message);
        }
    }

    /**
     * Functional interface for tasks that don't return a value but may throw exceptions.
     */
    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }

    /**
     * Custom thread factory for naming browser worker threads.
     */
    private static class BrowserThreadFactory implements ThreadFactory {
        private int threadNumber = 0;

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "BrowserWorker-" + threadNumber++);
            thread.setDaemon(false); // Non-daemon so tasks complete before JVM exit
            return thread;
        }
    }

    // ==================== Builder ====================

    /**
     * Builder for configuring BrowserManager instances.
     */
    public static class Builder {

        private String executablePath;
        private int threadCount = Runtime.getRuntime().availableProcessors(); // 0 means auto-detect
        private int portRangeStart = DEFAULT_PORT_RANGE_START;
        private int portRangeEnd = DEFAULT_PORT_RANGE_END;
        private boolean fingerprintEnabled = true;
        private boolean warmProfile = false;
        private boolean headless = false;
        private String webrtcPolicy = "disable_non_proxied_udp";
        private boolean proxyEnabled = true;
        private InteractionOptions interactionOptions = InteractionOptions.defaults();
        private String profileInputPath;
        private String profileOutputPath;
        private final ArrayList<String> arguements = new ArrayList<>();
        private final boolean headlessGpuAcceleration = false;
        private String autoSolveAIKey;

        private Builder() {}

        /**
         * Sets the path to the Chrome executable. Required.
         *
         * @param executablePath path to chrome or chrome-headless-shell
         * @return this builder
         */
        public Builder executablePath(String executablePath) {
            this.executablePath = executablePath;
            return this;
        }

        /**
         * Sets the number of worker threads.
         *
         * <p>Default: auto-detected using {@code Runtime.getRuntime().availableProcessors()}</p>
         *
         * @param threadCount the number of concurrent browser operations to support
         * @return this builder
         */
        public Builder threadCount(int threadCount) {
            if (threadCount < 1) {
                throw new IllegalArgumentException("Thread count must be at least 1");
            }
            this.threadCount = threadCount;
            return this;
        }

        /**
         * Sets the port range for CDP connections.
         *
         * <p>Default: 9222-9621 (400 ports)</p>
         *
         * @param start the first port in the range (inclusive)
         * @param end   the last port in the range (inclusive)
         * @return this builder
         */
        public Builder portRange(int start, int end) {
            if (start < 1 || start > 65535) {
                throw new IllegalArgumentException("Port range start must be between 1 and 65535");
            }
            if (end < start || end > 65535) {
                throw new IllegalArgumentException("Port range end must be >= start and <= 65535");
            }
            this.portRangeStart = start;
            this.portRangeEnd = end;
            return this;
        }

        /**
         * Enables or disables browser fingerprint spoofing.
         *
         * <p>Default: true</p>
         *
         * @param enabled true to enable fingerprinting
         * @return this builder
         */
        public Builder fingerprintEnabled(boolean enabled) {
            this.fingerprintEnabled = enabled;
            return this;
        }

        /**
         * Enables or disables automatic profile warming.
         *
         * <p>When enabled, browsers created via {@link #createSession()} or
         * {@link #createSessions(int)} will automatically be warmed by visiting
         * common websites to collect cookies and appear more natural.</p>
         *
         * <p>For {@link #createSessions(int)}, warming is performed in parallel
         * for maximum efficiency.</p>
         *
         * <p>Default: false</p>
         *
         * @param enabled true to enable automatic profile warming
         * @return this builder
         */
        public Builder warmProfile(boolean enabled) {
            this.warmProfile = enabled;
            return this;
        }

        /**
         * Enables or disables headless mode.
         *
         * <p>Default: false (visible browser window)</p>
         *
         * @param enabled true to run headless
         * @return this builder
         */
        public Builder headless(boolean enabled) {
            this.headless = enabled;
            return this;
        }

        /**
         * Sets the WebRTC IP handling policy.
         *
         * <p>Default: "disable_non_proxied_udp" (prevents WebRTC IP leaks)</p>
         *
         * @param policy one of: "default", "default_public_interface_only",
         *               "default_public_and_private_interfaces", "disable_non_proxied_udp"
         * @return this builder
         */
        public Builder webrtcPolicy(String policy) {
            this.webrtcPolicy = policy;
            return this;
        }

        /**
         * Enables or disables proxy usage.
         *
         * <p>When enabled, proxies are consumed from the file specified by the
         * "proxies" environment variable. Each browser gets a unique proxy.</p>
         *
         * <p>Default: true</p>
         *
         * @param enabled true to enable proxy usage
         * @return this builder
         */
        public Builder proxyEnabled(boolean enabled) {
            this.proxyEnabled = enabled;
            return this;
        }

        /**
         * Sets the AutoSolve AI API key for captcha solving.
         *
         * <p>When set, the manager provides access to a shared {@link AutoSolveAIService}
         * via {@link BrowserManager#autoSolveAIService()}.</p>
         *
         * <p>If not set, captcha solving features will not be available.</p>
         *
         * @param apiKey the AutoSolve AI API key
         * @return this builder
         */
        public Builder autoSolveAIKey(String apiKey) {
            this.autoSolveAIKey = apiKey;
            return this;
        }

        /**
         * Sets the interaction options for human-like behavior.
         *
         * <p>These options control timing and movement patterns for mouse clicks,
         * typing, scrolling, and other interactions to appear more human-like.</p>
         *
         * <p>Default: {@link InteractionOptions#defaults()}</p>
         *
         * <p>Example:</p>
         * <pre>{@code
         * InteractionOptions options = InteractionOptions.builder()
         *     .moveSpeed(60)
         *     .keystrokeDelayMin(50)
         *     .keystrokeDelayMax(150)
         *     .overshootEnabled(true)
         *     .build();
         *
         * BrowserManager manager = BrowserManager.builder()
         *     .executablePath("/path/to/chrome")
         *     .interactionOptions(options)
         *     .build();
         * }</pre>
         *
         * @param options the interaction options
         * @return this builder
         */
        public Builder interactionOptions(InteractionOptions options) {
            if (options == null) {
                throw new IllegalArgumentException("InteractionOptions cannot be null");
            }
            this.interactionOptions = options;
            return this;
        }

        /**
         * Sets the path to the input CSV file containing profiles.
         *
         * <p>When set along with {@link #profileOutputPath(String)}, enables
         * profile management via {@link BrowserManager#profilePool()}.</p>
         *
         * <p>If not set, profile pool will fall back to environment variable
         * {@code profiles_input}.</p>
         *
         * @param path the path to the input CSV file
         * @return this builder
         */
        public Builder profileInputPath(String path) {
            this.profileInputPath = path;
            return this;
        }

        /**
         * Sets the path to the output CSV file for completed profiles.
         *
         * <p>When set along with {@link #profileInputPath(String)}, enables
         * profile management via {@link BrowserManager#profilePool()}.</p>
         *
         * <p>If not set, profile pool will fall back to environment variable
         * {@code profiles_output}.</p>
         *
         * @param path the path to the output CSV file
         * @return this builder
         */
        public Builder profileOutputPath(String path) {
            this.profileOutputPath = path;
            return this;
        }

        public Builder chromeArguement(String arguement){
            this.arguements.add(arguement);
            return this;
        }



        /**
         * Builds the BrowserManager with the configured settings.
         *
         * @return a new BrowserManager instance
         * @throws IllegalStateException if executablePath is not set
         */
        public BrowserManager build() {
            if (executablePath == null || executablePath.isBlank()) {
                throw new IllegalStateException("executablePath is required");
            }
            return new BrowserManager(this);
        }
    }
}