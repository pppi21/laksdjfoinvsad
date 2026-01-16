package org.nodriver4j.core;

import org.nodriver4j.cdp.ProfileWarmer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Manages browser sessions with automatic resource allocation and thread pool execution.
 *
 * <p>BrowserManager handles:</p>
 * <ul>
 *   <li>Thread pool management (auto-sized to available processors by default)</li>
 *   <li>Port allocation for CDP connections</li>
 *   <li>Proxy consumption from file (if enabled)</li>
 *   <li>Session lifecycle and cleanup</li>
 *   <li>Parallel profile warming</li>
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
 *     // automation logic here
 *     return "result";
 * });
 *
 * String result = future.get(); // blocks until complete
 * manager.shutdown();
 * }</pre>
 *
 * <h3>Manual Session Control with Parallel Warming</h3>
 * <pre>{@code
 * BrowserManager manager = BrowserManager.builder()
 *     .executablePath("/path/to/chrome")
 *     .warmProfile(true)
 *     .build();
 *
 * // Launch multiple browsers
 * List<BrowserSession> sessions = new ArrayList<>();
 * for (int i = 0; i < 6; i++) {
 *     sessions.add(manager.createSession());
 * }
 *
 * // Warm all browsers in parallel
 * manager.warmSessions(sessions);
 *
 * // All browsers are now warmed and ready
 * manager.shutdown();
 * }</pre>
 */
public class BrowserManager implements AutoCloseable {

    private static final int DEFAULT_PORT_RANGE_START = 9222;
    private static final int DEFAULT_PORT_RANGE_END = 9621;

    private final ExecutorService executor;
    private final BlockingQueue<Integer> availablePorts;
    private final Set<BrowserSession> activeSessions;
    private final AtomicBoolean isShutdown;
    private final Thread shutdownHook;

    // Configuration for browser creation
    private final String executablePath;
    private final boolean fingerprintEnabled;
    private final boolean warmProfile;
    private final boolean headless;
    private final String webrtcPolicy;
    private final boolean proxyEnabled;

    private BrowserManager(Builder builder) {
        this.executablePath = builder.executablePath;
        this.fingerprintEnabled = builder.fingerprintEnabled;
        this.warmProfile = builder.warmProfile;
        this.headless = builder.headless;
        this.webrtcPolicy = builder.webrtcPolicy;
        this.proxyEnabled = builder.proxyEnabled;

        this.isShutdown = new AtomicBoolean(false);
        this.activeSessions = ConcurrentHashMap.newKeySet();

        // Initialize port pool
        this.availablePorts = new LinkedBlockingQueue<>();
        for (int port = builder.portRangeStart; port <= builder.portRangeEnd; port++) {
            availablePorts.add(port);
        }

        // Initialize thread pool
        int threadCount = builder.threadCount > 0
                ? builder.threadCount
                : Runtime.getRuntime().availableProcessors();

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
                + availablePorts.size() + " ports available");
    }

    /**
     * Creates a new builder for BrowserManager configuration.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Submits an automation task for execution.
     *
     * <p>The task will be queued and executed when a thread becomes available.
     * A new browser session is created for the task and automatically closed
     * after the task completes (whether successfully or with an exception).</p>
     *
     * @param task the automation task to execute, receives a Browser and returns a result
     * @param <T>  the result type
     * @return a Future representing the pending result
     * @throws RejectedExecutionException if the manager has been shutdown
     */
    public <T> Future<T> submit(Function<Browser, T> task) {
        ensureNotShutdown();

        return executor.submit(() -> {
            try (BrowserSession session = createSession()) {
                // Auto-warm if enabled
                if (session.isWarmProfileEnabled()) {
                    session.warm();
                }
                return task.apply(session.getBrowser());
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
            try (BrowserSession session = createSession()) {
                // Auto-warm if enabled
                if (session.isWarmProfileEnabled()) {
                    session.warm();
                }
                task.accept(session.getBrowser());
                return null;
            }
        });
    }

    /**
     * Creates a new browser session for manual control.
     *
     * <p>The caller is responsible for closing the session when done.
     * Use try-with-resources for automatic cleanup:</p>
     *
     * <pre>{@code
     * try (BrowserSession session = manager.createSession()) {
     *     Browser browser = session.getBrowser();
     *     // ...
     * }
     * }</pre>
     *
     * <p>Note: Profile warming is NOT performed automatically when using createSession().
     * Call {@link BrowserSession#warm()} or {@link #warmSessions(List)} explicitly.</p>
     *
     * @return a new BrowserSession
     * @throws IOException              if the browser fails to launch
     * @throws IllegalStateException    if the manager has been shutdown
     * @throws InterruptedException     if interrupted while waiting for a port
     * @throws NoAvailablePortException if no ports are available (all in use)
     */
    public BrowserSession createSession() throws IOException, InterruptedException {
        ensureNotShutdown();

        // Allocate port (blocks if none available, but with timeout)
        Integer port = availablePorts.poll(30, TimeUnit.SECONDS);
        if (port == null) {
            throw new NoAvailablePortException("No ports available after 30 seconds. " +
                    "All " + (DEFAULT_PORT_RANGE_END - DEFAULT_PORT_RANGE_START + 1) + " ports are in use.");
        }

        try {
            BrowserConfig config = buildConfig(port);
            BrowserSession session = new BrowserSession(config, this::releasePort);

            // Track session for shutdown cleanup
            activeSessions.add(session);

            // Remove from tracking when session closes
            session.getBrowser(); // Just to ensure it's valid before we return

            return session;

        } catch (IOException | RuntimeException e) {
            // Release port if session creation fails
            releasePort(port);
            throw e;
        }
    }

    /**
     * Warms multiple browser sessions in parallel.
     *
     * <p>This method uses the thread pool to warm all sessions concurrently,
     * which is much faster than warming them sequentially.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * List<BrowserSession> sessions = new ArrayList<>();
     * for (int i = 0; i < 6; i++) {
     *     sessions.add(manager.createSession());
     * }
     * manager.warmSessions(sessions); // Warms all 6 in parallel
     * }</pre>
     *
     * <p>This method blocks until all sessions have completed warming.</p>
     *
     * @param sessions the list of sessions to warm
     * @return a list of warming results, one per session (in same order as input)
     * @throws IllegalStateException if the manager has been shutdown
     */
    public List<ProfileWarmer.WarmingResult> warmSessions(List<BrowserSession> sessions) {
        ensureNotShutdown();

        if (sessions == null || sessions.isEmpty()) {
            return new ArrayList<>();
        }

        System.out.println("[BrowserManager] Starting parallel warming of " + sessions.size() + " sessions...");

        // Submit warming tasks for all sessions
        List<Future<ProfileWarmer.WarmingResult>> futures = new ArrayList<>();
        for (BrowserSession session : sessions) {
            Future<ProfileWarmer.WarmingResult> future = executor.submit(session::warm);
            futures.add(future);
        }

        // Collect results
        List<ProfileWarmer.WarmingResult> results = new ArrayList<>();
        int successCount = 0;
        int warningCount = 0;

        for (int i = 0; i < futures.size(); i++) {
            try {
                ProfileWarmer.WarmingResult result = futures.get(i).get();
                results.add(result);

                if (result.hasWarnings()) {
                    warningCount++;
                } else {
                    successCount++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[BrowserManager] Warming interrupted for session " + (i + 1));
                results.add(new ProfileWarmer.WarmingResult(
                        java.util.Collections.emptyMap(),
                        java.util.Collections.singletonList("Warming interrupted")
                ));
            } catch (ExecutionException e) {
                System.err.println("[BrowserManager] Warming failed for session " + (i + 1) + ": " + e.getCause().getMessage());
                results.add(new ProfileWarmer.WarmingResult(
                        java.util.Collections.emptyMap(),
                        java.util.Collections.singletonList("Warming failed: " + e.getCause().getMessage())
                ));
            }
        }

        System.out.println("[BrowserManager] Parallel warming complete: " + successCount + " succeeded, " +
                warningCount + " with warnings, " + (sessions.size() - successCount - warningCount) + " failed");

        return results;
    }

    /**
     * Warms sessions that have warming enabled in their configuration.
     *
     * <p>Convenience method that filters the list to only warm sessions
     * where {@link BrowserSession#isWarmProfileEnabled()} returns true.</p>
     *
     * @param sessions the list of sessions to potentially warm
     * @return a list of warming results for sessions that were warmed
     * @throws IllegalStateException if the manager has been shutdown
     */
    public List<ProfileWarmer.WarmingResult> warmEnabledSessions(List<BrowserSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return new ArrayList<>();
        }

        List<BrowserSession> toWarm = new ArrayList<>();
        for (BrowserSession session : sessions) {
            if (session.isWarmProfileEnabled() && !session.isWarmed()) {
                toWarm.add(session);
            }
        }

        if (toWarm.isEmpty()) {
            System.out.println("[BrowserManager] No sessions require warming");
            return new ArrayList<>();
        }

        return warmSessions(toWarm);
    }

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
                .webrtcPolicy(webrtcPolicy);

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

    /**
     * Initiates an orderly shutdown.
     *
     * <p>Stops accepting new tasks and waits for currently executing tasks to complete.
     * Does not forcibly close running browser sessions.</p>
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
     * Attempts to stop all actively executing tasks and closes all browser sessions.
     *
     * <p>Use this for immediate cleanup when you don't want to wait for tasks to complete.</p>
     */
    public void shutdownNow() {
        if (!isShutdown.compareAndSet(false, true)) {
            // Already shutdown, but still close sessions
            closeAllSessions();
            return;
        }

        System.out.println("[BrowserManager] Initiating immediate shutdown...");

        // Stop accepting and interrupt running tasks
        executor.shutdownNow();

        // Close all active sessions
        closeAllSessions();

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
     * Closes all active browser sessions.
     */
    private void closeAllSessions() {
        System.out.println("[BrowserManager] Closing " + activeSessions.size() + " active sessions...");

        for (BrowserSession session : activeSessions) {
            try {
                session.close();
            } catch (Exception e) {
                System.err.println("[BrowserManager] Error closing session: " + e.getMessage());
            }
        }

        activeSessions.clear();
    }

    /**
     * Emergency shutdown called by JVM shutdown hook.
     * Closes all sessions without waiting for tasks.
     */
    private void emergencyShutdown() {
        System.out.println("[BrowserManager] Emergency shutdown triggered...");
        isShutdown.set(true);
        executor.shutdownNow();
        closeAllSessions();
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
     * Gets the number of currently active browser sessions.
     *
     * @return the count of active sessions
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * Gets the number of available ports.
     *
     * @return the count of ports not currently in use
     */
    public int getAvailablePortCount() {
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
     * Implements AutoCloseable for try-with-resources support.
     * Equivalent to calling {@link #shutdown()}.
     */
    @Override
    public void close() {
        shutdown();
    }

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

    /**
     * Builder for configuring BrowserManager instances.
     */
    public static class Builder {

        private String executablePath;
        private int threadCount = 0; // 0 means auto-detect
        private int portRangeStart = DEFAULT_PORT_RANGE_START;
        private int portRangeEnd = DEFAULT_PORT_RANGE_END;
        private boolean fingerprintEnabled = true;
        private boolean warmProfile = false;
        private boolean headless = false;
        private String webrtcPolicy = "disable_non_proxied_udp";
        private boolean proxyEnabled = true;

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
         * @param threadCount the number of concurrent browser sessions to support
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
         * Enables or disables profile warming (visiting common sites to collect cookies).
         *
         * <p>When using {@link #submit(Function)}, warming is performed automatically
         * if this is enabled. When using {@link #createSession()}, you must call
         * {@link BrowserSession#warm()} or {@link #warmSessions(List)} explicitly.</p>
         *
         * <p>Default: false</p>
         *
         * @param enabled true to enable profile warming
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
         * "proxies" environment variable. Each browser session gets a unique proxy.</p>
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