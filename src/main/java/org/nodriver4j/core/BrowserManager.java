package org.nodriver4j.core;

import org.nodriver4j.profiles.ProfilePool;
import org.nodriver4j.services.aycd.AutoSolveAIService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * <p>(For dev testing convenience. Not used in production.)</p>
 *
 *
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
 * <h3>Basic Setup</h3>
 * <pre>{@code
 * BrowserConfig config = BrowserConfig.builder()
 *     .executablePath("/path/to/chrome")
 *     .fingerprintEnabled(true)
 *     .build();
 *
 * BrowserManager manager = BrowserManager.builder()
 *     .config(config)
 *     .warmProfile(true)
 *     .build();
 * }</pre>
 *
 * <h3>Manual Browser Control</h3>
 * <pre>{@code
 * try (Browser browser = manager.createSession()) {
 *     Page page = browser.page();
 *     page.navigate("https://example.com");
 *     page.click("//button[@id='login']");
 * }
 * }</pre>
 *
 * <h3>Multiple Browsers (Parallel Warming)</h3>
 * <pre>{@code
 * List<Browser> browsers = manager.createSessions(6);
 * // All 6 browsers are now launched and warmed (if enabled)
 *
 * // Use the browsers...
 *
 * // Close when done
 * browsers.forEach(Browser::close);
 * }</pre>
 *
 * <h3>Managed Execution</h3>
 * <pre>{@code
 * Future<String> future = manager.submit(browser -> {
 *     Page page = browser.page();
 *     page.navigate("https://example.com");
 *     return page.title();
 * });
 *
 * String result = future.get();
 * manager.shutdown();
 * }</pre>
 *
 * <h3>With Profile Management</h3>
 * <pre>{@code
 * BrowserManager manager = BrowserManager.builder()
 *     .config(config)
 *     .profileInputPath("input_profiles.csv")
 *     .profileOutputPath("completed_profiles.csv")
 *     .build();
 *
 * ProfilePool pool = manager.profilePool();
 * Profile profile = pool.consumeFirst();
 * }</pre>
 *
 * @see BrowserConfig
 * @see Browser
 * @see ProfilePool
 */
public class BrowserManager implements AutoCloseable {

    private static final int DEFAULT_PORT_RANGE_START = 9222;
    private static final int DEFAULT_PORT_RANGE_END = 9621;

    // Thread pool and port management
    private final ExecutorService executor;
    private final BlockingQueue<Integer> availablePorts;
    private final Set<Browser> activeBrowsers;
    private final AtomicBoolean isShutdown;
    private final Thread shutdownHook;

    // Template configuration for creating browsers
    private final BrowserConfig templateConfig;

    // Manager-level settings (not browser-specific)
    private final boolean warmProfile;
    private final boolean proxyEnabled;

    // Profile management
    private final String profileInputPath;
    private final String profileOutputPath;
    private volatile ProfilePool profilePool;
    private final Object profilePoolLock = new Object();

    // AutoSolve AI (can be from config or created from key)
    private volatile AutoSolveAIService autoSolveAIService;
    private final Object autoSolveAIServiceLock = new Object();

    private BrowserManager(Builder builder) {
        this.templateConfig = builder.config;
        this.warmProfile = builder.warmProfile;
        this.proxyEnabled = builder.proxyEnabled;
        this.profileInputPath = builder.profileInputPath;
        this.profileOutputPath = builder.profileOutputPath;

        // Initialize AutoSolve AI from config if available
        if (templateConfig.hasAutoSolveAI()) {
            this.autoSolveAIService = templateConfig.autoSolveAIService();
        }

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
                threadCount,
                threadCount,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new BrowserThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
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
     * all browsers managed by this instance.</p>
     *
     * @return the shared ProfilePool instance
     * @throws IllegalStateException if profile paths are not configured
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
     * @return the shared AutoSolveAIService instance, or null if not configured
     */
    public AutoSolveAIService autoSolveAIService() {
        return autoSolveAIService;
    }

    /**
     * Checks if AutoSolve AI is configured.
     *
     * @return true if an AutoSolve AI service is available
     */
    public boolean hasAutoSolveAI() {
        return autoSolveAIService != null;
    }

    // ==================== Task Submission Methods ====================

    /**
     * Submits an automation task for execution.
     *
     * <p>A new browser is created for the task and automatically closed
     * after the task completes.</p>
     *
     * @param task the automation task to execute
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
     * @param task the automation task to execute
     * @param <T>  the result type
     * @return a Future representing the pending result
     * @throws RejectedExecutionException if the manager has been shutdown
     */
    public <T> Future<T> submitPage(Function<Page, T> task) {
        ensureNotShutdown();

        return executor.submit(() -> {
            try (Browser browser = createSession()) {
                return task.apply(browser.page());
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
                task.accept(browser.page());
                return null;
            }
        });
    }

    // ==================== Browser Creation Methods ====================

    /**
     * Creates a new browser instance.
     *
     * <p>If warming is enabled, the browser will be automatically warmed
     * before this method returns.</p>
     *
     * @return a new Browser instance (warmed if warming is enabled)
     * @throws IOException          if the browser fails to launch
     * @throws IllegalStateException if the manager has been shutdown
     * @throws InterruptedException if interrupted while waiting for a port
     */
    public Browser createSession() throws IOException, InterruptedException {
        Browser browser = createBrowserInternal();

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
     *   <li>Phase 1: Creates all browsers sequentially (fast)</li>
     *   <li>Phase 2: Warms all browsers in parallel (if warming enabled)</li>
     * </ol>
     *
     * @param count the number of browsers to create
     * @return list of successfully created browsers
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
                        " launched (port " + browser.port() + ")");
            } catch (IOException e) {
                String error = "Browser " + (i + 1) + ": " + e.getMessage();
                errors.add(error);
                System.err.println("[BrowserManager] Failed to launch browser " + (i + 1) + ": " + e.getMessage());
            }
        }

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
     */
    private Browser createBrowserInternal() throws IOException, InterruptedException {
        ensureNotShutdown();

        // Allocate port
        Integer port = availablePorts.poll(30, TimeUnit.SECONDS);
        if (port == null) {
            throw new NoAvailablePortException("No ports available after 30 seconds. " +
                    "All " + (DEFAULT_PORT_RANGE_END - DEFAULT_PORT_RANGE_START + 1) + " ports are in use.");
        }

        try {
            BrowserConfig config = buildConfigForLaunch();
            Browser browser = Browser.launch(config, port, this::releasePort);

            // Track browser for shutdown cleanup
            activeBrowsers.add(browser);

            return browser;

        } catch (IOException | RuntimeException e) {
            releasePort(port);
            throw e;
        }
    }

    /**
     * Builds a BrowserConfig for launching, adding proxy if enabled.
     */
    private BrowserConfig buildConfigForLaunch() throws IOException {
        if (!proxyEnabled) {
            return templateConfig;
        }

        // Consume proxy from file and add to config
        try {
            Proxy proxy = new Proxy(); // Consumes from env file
            return templateConfig.toBuilder()
                    .proxy(proxy)
                    .build();
        } catch (IOException e) {
            System.err.println("[BrowserManager] Warning: Failed to load proxy: " + e.getMessage());
            System.err.println("[BrowserManager] Continuing without proxy.");
            return templateConfig;
        } catch (IllegalStateException e) {
            System.err.println("[BrowserManager] Warning: " + e.getMessage());
            System.err.println("[BrowserManager] Continuing without proxy.");
            return templateConfig;
        }
    }

    /**
     * Warms multiple browsers in parallel using the thread pool.
     */
    private void warmAllInternal(List<Browser> browsers) {
        if (browsers.isEmpty()) {
            return;
        }

        System.out.println("[BrowserManager] Warming " + browsers.size() + " browser(s) in parallel...");

        List<Future<?>> futures = new ArrayList<>();
        for (Browser browser : browsers) {
            futures.add(executor.submit((Runnable) browser::warm));
        }

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

    /**
     * Releases a port back to the available pool.
     */
    private void releasePort(int port) {
        availablePorts.offer(port);
        System.out.println("[BrowserManager] Released port " + port + " (available: " + availablePorts.size() + ")");
    }

    // ==================== Status Methods ====================

    /**
     * Gets the template configuration used for creating browsers.
     *
     * @return the BrowserConfig template
     */
    public BrowserConfig config() {
        return templateConfig;
    }

    /**
     * Gets the interaction options from the template configuration.
     *
     * @return the InteractionOptions
     */
    public InteractionOptions interactionOptions() {
        return templateConfig.interactionOptions();
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

    /**
     * Checks if proxy usage is enabled.
     *
     * @return true if proxy consumption is enabled
     */
    public boolean isProxyEnabled() {
        return proxyEnabled;
    }

    // ==================== Shutdown Methods ====================

    /**
     * Initiates an orderly shutdown.
     */
    public void shutdown() {
        if (!isShutdown.compareAndSet(false, true)) {
            return;
        }

        System.out.println("[BrowserManager] Initiating shutdown...");

        executor.shutdown();

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
     */
    public void shutdownNow() {
        if (!isShutdown.compareAndSet(false, true)) {
            closeAllBrowsers();
            return;
        }

        System.out.println("[BrowserManager] Initiating immediate shutdown...");

        executor.shutdownNow();
        closeAllBrowsers();

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
     */
    private void emergencyShutdown() {
        System.out.println("[BrowserManager] Emergency shutdown triggered...");
        isShutdown.set(true);
        executor.shutdownNow();
        closeAllBrowsers();
    }

    /**
     * Ensures the manager hasn't been shutdown.
     */
    private void ensureNotShutdown() {
        if (isShutdown.get()) {
            throw new IllegalStateException("BrowserManager has been shutdown");
        }
    }

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
            thread.setDaemon(false);
            return thread;
        }
    }

    // ==================== Builder ====================

    /**
     * Builder for configuring BrowserManager instances.
     *
     * <p>Required: {@link #config(BrowserConfig)}</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * BrowserConfig config = BrowserConfig.builder()
     *     .executablePath("/path/to/chrome")
     *     .fingerprintEnabled(true)
     *     .build();
     *
     * BrowserManager manager = BrowserManager.builder()
     *     .config(config)
     *     .warmProfile(true)
     *     .proxyEnabled(true)
     *     .threadCount(4)
     *     .build();
     * }</pre>
     */
    public static class Builder {

        private BrowserConfig config;
        private int threadCount = Runtime.getRuntime().availableProcessors();
        private int portRangeStart = DEFAULT_PORT_RANGE_START;
        private int portRangeEnd = DEFAULT_PORT_RANGE_END;
        private boolean warmProfile = false;
        private boolean proxyEnabled = true;
        private String profileInputPath;
        private String profileOutputPath;

        private Builder() {}

        /**
         * Sets the template BrowserConfig. Required.
         *
         * <p>This configuration is used as the base for all browsers created
         * by this manager. Proxy may be added dynamically if proxyEnabled is true.</p>
         *
         * @param config the browser configuration template
         * @return this builder
         */
        public Builder config(BrowserConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Sets the number of worker threads.
         *
         * <p>Default: {@code Runtime.getRuntime().availableProcessors()}</p>
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
         * Enables or disables automatic profile warming.
         *
         * <p>When enabled, browsers created via {@link BrowserManager#createSession()} or
         * {@link BrowserManager#createSessions(int)} will automatically be warmed.</p>
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
         * Sets the path to the input CSV file containing profiles.
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
         * @param path the path to the output CSV file
         * @return this builder
         */
        public Builder profileOutputPath(String path) {
            this.profileOutputPath = path;
            return this;
        }

        /**
         * Builds the BrowserManager with the configured settings.
         *
         * @return a new BrowserManager instance
         * @throws IllegalStateException if config is not set
         */
        public BrowserManager build() {
            if (config == null) {
                throw new IllegalStateException("BrowserConfig is required. Use .config(browserConfig) to set it.");
            }
            return new BrowserManager(this);
        }
    }
}