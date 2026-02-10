package org.nodriver4j.services;

import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserConfig;
import org.nodriver4j.core.ProxyConfig;
import org.nodriver4j.persistence.Settings;
import org.nodriver4j.persistence.entity.ProxyEntity;
import org.nodriver4j.persistence.entity.TaskEntity;
import org.nodriver4j.persistence.repository.ProxyRepository;
import org.nodriver4j.persistence.repository.TaskRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton service that manages browser lifecycle for tasks.
 *
 * <p>This is the bridge between the UI layer and {@link Browser#launch}.
 * It handles:</p>
 * <ul>
 *   <li>Building {@link BrowserConfig} from {@link TaskEntity}, {@link ProxyEntity},
 *       and {@link Settings}</li>
 *   <li>Port pool allocation for CDP connections</li>
 *   <li>Tracking running {@link Browser} instances by task ID</li>
 *   <li>Starting manual browser sessions (headed, no script)</li>
 *   <li>Stopping browsers gracefully while preserving userdata</li>
 *   <li>Assigning persistent userdata directories on first launch</li>
 *   <li>Session warming when configured on the task</li>
 *   <li>Updating task status in the database on lifecycle transitions</li>
 *   <li>Emergency cleanup via JVM shutdown hook</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TaskExecutionService service = TaskExecutionService.instance();
 *
 * // Launch a manual (headed) browser for a task
 * Browser browser = service.startManualBrowser(taskId);
 *
 * // Check if a task has an active browser
 * boolean running = service.isRunning(taskId);
 *
 * // Stop the browser, preserving userdata
 * service.stopBrowser(taskId);
 *
 * // Shutdown all browsers on app exit
 * service.shutdown();
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Browser lifecycle management (launch, stop, track)</li>
 *   <li>Port pool management</li>
 *   <li>BrowserConfig construction from entities + settings</li>
 *   <li>Userdata path assignment and persistence</li>
 *   <li>Task status updates on lifecycle transitions</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Script execution (handled by script layer in Stage 3B)</li>
 *   <li>Screencast / view browser (Stage 3C)</li>
 *   <li>UI concerns (called by UI controllers but has no JavaFX dependency)</li>
 *   <li>Managing proxy groups, profile groups, or task groups</li>
 *   <li>Database schema or connection lifecycle</li>
 * </ul>
 *
 * @see Browser
 * @see BrowserConfig
 * @see TaskEntity
 * @see Settings
 */
public class TaskExecutionService {

    private static final int PORT_RANGE_START = 9222;
    private static final int PORT_RANGE_END = 9621;
    private static final int PORT_TIMEOUT_SECONDS = 30;

    // ==================== Singleton ====================

    private static volatile TaskExecutionService instance;

    /**
     * Gets the singleton instance, creating it on first access.
     *
     * @return the TaskExecutionService instance
     */
    public static TaskExecutionService instance() {
        if (instance == null) {
            synchronized (TaskExecutionService.class) {
                if (instance == null) {
                    instance = new TaskExecutionService();
                }
            }
        }
        return instance;
    }

    // ==================== Instance Fields ====================

    private final ConcurrentHashMap<Long, Browser> runningBrowsers;
    private final BlockingQueue<Integer> availablePorts;
    private final AtomicBoolean isShutdown;
    private final Thread shutdownHook;

    // Repositories
    private final TaskRepository taskRepository;
    private final ProxyRepository proxyRepository;

    // ==================== Constructor ====================

    private TaskExecutionService() {
        this.runningBrowsers = new ConcurrentHashMap<>();
        this.isShutdown = new AtomicBoolean(false);
        this.taskRepository = new TaskRepository();
        this.proxyRepository = new ProxyRepository();

        // Initialize port pool
        this.availablePorts = new LinkedBlockingQueue<>();
        for (int port = PORT_RANGE_START; port <= PORT_RANGE_END; port++) {
            availablePorts.add(port);
        }

        // Register shutdown hook for emergency cleanup
        this.shutdownHook = new Thread(this::emergencyShutdown, "TaskExecution-ShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        System.out.println("[TaskExecutionService] Initialized with " +
                availablePorts.size() + " ports available");
    }

    // ==================== Browser Lifecycle ====================

    /**
     * Launches a manual (headed) browser session for a task.
     *
     * <p>The browser opens in headed mode with no automation script running,
     * allowing the user to manually interact with sites using the task's
     * identity (same proxy, fingerprint, and userdata). The task status
     * is set to {@link TaskEntity#STATUS_MANUAL}.</p>
     *
     * <p>If this is the task's first launch, a persistent userdata directory
     * is assigned and saved to the database. Session warming is performed
     * if {@link TaskEntity#warmSession()} is enabled.</p>
     *
     * <p>This method blocks until the browser is fully initialized and
     * ready for interaction. Callers should invoke this on a background
     * thread to avoid blocking the UI.</p>
     *
     * @param taskId the ID of the task to launch
     * @return the launched Browser instance
     * @throws IOException           if the browser fails to launch
     * @throws InterruptedException  if interrupted while waiting for a port
     * @throws IllegalStateException if the service is shutdown, the task is
     *                               already running, or Settings is misconfigured
     */
    public Browser startManualBrowser(long taskId) throws IOException, InterruptedException {
        return launchBrowser(taskId, false, TaskEntity.STATUS_MANUAL);
    }

    /**
     * Stops the browser for a task and preserves userdata.
     *
     * <p>The browser is closed gracefully, the userdata directory is preserved
     * for future runs, and the task status is updated to
     * {@link TaskEntity#STATUS_STOPPED}. The allocated port is returned
     * to the pool.</p>
     *
     * <p>This method is safe to call even if the browser has already been
     * closed or the task is not running — it will simply update the status.</p>
     *
     * @param taskId the ID of the task to stop
     * @throws IllegalStateException if the service has been shutdown
     */
    public void stopBrowser(long taskId) {
        ensureNotShutdown();

        Browser browser = runningBrowsers.remove(taskId);

        if (browser != null) {
            System.out.println("[TaskExecutionService] Stopping browser for task " + taskId +
                    " (port " + browser.port() + ")");

            try {
                browser.close();
            } catch (Exception e) {
                System.err.println("[TaskExecutionService] Error closing browser for task " +
                        taskId + ": " + e.getMessage());
            }
        }

        // Always update status, even if browser wasn't in the map
        // (handles edge case where browser crashed but status wasn't updated)
        updateTaskStatus(taskId, TaskEntity.STATUS_STOPPED);
    }

    /**
     * Stops all currently running browsers.
     *
     * <p>Each browser is stopped gracefully and its userdata is preserved.
     * Task statuses are updated to {@link TaskEntity#STATUS_STOPPED}.</p>
     */
    public void stopAll() {
        List<Long> taskIds = new ArrayList<>(runningBrowsers.keySet());

        System.out.println("[TaskExecutionService] Stopping " + taskIds.size() + " browser(s)...");

        for (long taskId : taskIds) {
            try {
                stopBrowser(taskId);
            } catch (Exception e) {
                System.err.println("[TaskExecutionService] Error stopping task " +
                        taskId + ": " + e.getMessage());
            }
        }
    }

    // ==================== Core Launch Logic ====================

    /**
     * Core browser launch method used by all entry points.
     *
     * <p>Handles port allocation, config building, userdata assignment,
     * browser launching, optional warming, status updates, and tracking.</p>
     *
     * @param taskId   the task ID
     * @param headless true for headless mode, false for headed
     * @param status   the status to set on successful launch
     * @return the launched Browser instance
     * @throws IOException           if the browser fails to launch
     * @throws InterruptedException  if interrupted while waiting for a port
     * @throws IllegalStateException if preconditions are not met
     */
    private Browser launchBrowser(long taskId, boolean headless, String status)
            throws IOException, InterruptedException {

        ensureNotShutdown();
        ensureNotRunning(taskId);
        validateSettings();

        // Load task from database
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("Task not found: " + taskId));

        // Assign userdata path if this is the first launch
        ensureUserdataPath(task);

        // Build config from task + settings
        BrowserConfig config = buildConfig(task, headless);

        // Allocate a port
        Integer port = availablePorts.poll(PORT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (port == null) {
            throw new IOException("No ports available after " + PORT_TIMEOUT_SECONDS +
                    " seconds. All " + (PORT_RANGE_END - PORT_RANGE_START + 1) + " ports are in use.");
        }

        try {
            System.out.println("[TaskExecutionService] Launching browser for task " + taskId +
                    " (port " + port + ", headless=" + headless + ")");

            // Launch browser
            Browser browser = Browser.launch(config, port, this::releasePort);

            // Track the browser
            runningBrowsers.put(taskId, browser);

            // Warm if requested
            if (task.warmSession()) {
                System.out.println("[TaskExecutionService] Warming session for task " + taskId + "...");
                browser.warm();
            }

            // Clear previous log and update status
            task.clearLog();
            task.status(status);
            task.touchUpdatedAt();
            taskRepository.save(task);

            System.out.println("[TaskExecutionService] Task " + taskId + " is now " + status +
                    " (port " + port + ")");

            return browser;

        } catch (IOException | RuntimeException e) {
            // Launch failed — release port, don't track
            releasePort(port);

            // Update task status to FAILED
            updateTaskStatus(taskId, TaskEntity.STATUS_FAILED);

            throw e;
        }
    }

    // ==================== Config Building ====================

    /**
     * Builds a BrowserConfig from a TaskEntity and application settings.
     *
     * <p>Configuration sources:</p>
     * <ul>
     *   <li>Chrome path, fingerprint, resource blocking, WebRTC policy → {@link Settings}</li>
     *   <li>Proxy → {@link ProxyEntity} referenced by the task (if any)</li>
     *   <li>Userdata directory → {@link TaskEntity#userdataPath()}</li>
     *   <li>AutoSolve AI → API key from {@link Settings} (if configured)</li>
     *   <li>Headless mode → determined by caller (manual=false, scripted=from settings)</li>
     * </ul>
     *
     * @param task     the task entity
     * @param headless whether to run in headless mode
     * @return the built BrowserConfig
     * @throws IllegalStateException if a referenced proxy is not found
     */
    private BrowserConfig buildConfig(TaskEntity task, boolean headless) {
        Settings settings = Settings.get();

        BrowserConfig.Builder builder = BrowserConfig.builder()
                .executablePath(settings.chromePath())
                .headless(headless)
                .fingerprintEnabled(settings.defaultFingerprintEnabled())
                .resourceBlocking(settings.defaultResourceBlocking())
                .webrtcPolicy(settings.defaultWebrtcPolicy())
                .userDataDir(Path.of(task.userdataPath()));

        // Proxy from task's referenced ProxyEntity
        if (task.hasProxy()) {
            ProxyEntity proxyEntity = proxyRepository.findById(task.proxyId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Proxy not found for task " + task.id() + ": proxyId=" + task.proxyId()));
            builder.proxy(proxyEntity.toProxyConfig());
        }

        // AutoSolve AI from settings
        if (settings.hasAutoSolveApiKey()) {
            builder.autoSolveAIKey(settings.autoSolveApiKey());
        }

        return builder.build();
    }

    // ==================== Userdata Management ====================

    /**
     * Ensures the task has a persistent userdata path assigned.
     *
     * <p>If the task does not yet have a userdata path (first launch),
     * one is generated via {@link Settings#userdataPathForTask(long)}
     * and persisted to the database.</p>
     *
     * @param task the task entity (modified in place and saved if path is assigned)
     */
    private void ensureUserdataPath(TaskEntity task) {
        if (task.hasUserdataPath()) {
            return;
        }

        Path userdataPath = Settings.get().userdataPathForTask(task.id());
        task.userdataPath(userdataPath.toString());
        task.touchUpdatedAt();
        taskRepository.save(task);

        System.out.println("[TaskExecutionService] Assigned userdata path for task " +
                task.id() + ": " + userdataPath);
    }

    // ==================== Port Management ====================

    /**
     * Releases a port back to the available pool.
     *
     * <p>Called by {@link Browser#close()} via the portReleaser callback.</p>
     *
     * @param port the port to release
     */
    private void releasePort(int port) {
        availablePorts.offer(port);
        System.out.println("[TaskExecutionService] Released port " + port +
                " (available: " + availablePorts.size() + ")");
    }

    // ==================== Query Methods ====================

    /**
     * Checks if a task has an active browser session.
     *
     * <p>Verifies both that the browser is tracked AND that the
     * underlying Chrome process is still alive.</p>
     *
     * @param taskId the task ID to check
     * @return true if the task has a running browser
     */
    public boolean isRunning(long taskId) {
        Browser browser = runningBrowsers.get(taskId);
        return browser != null && browser.isRunning();
    }

    /**
     * Gets the active browser for a task.
     *
     * @param taskId the task ID
     * @return the Browser instance, or null if the task has no active browser
     */
    public Browser browser(long taskId) {
        return runningBrowsers.get(taskId);
    }

    /**
     * Gets an unmodifiable view of all currently running task-to-browser mappings.
     *
     * @return unmodifiable map of task ID to Browser
     */
    public Map<Long, Browser> runningBrowsers() {
        return Collections.unmodifiableMap(runningBrowsers);
    }

    /**
     * Gets the number of currently active browser sessions.
     *
     * @return the count of running browsers
     */
    public int activeBrowserCount() {
        return runningBrowsers.size();
    }

    /**
     * Gets the number of available ports in the pool.
     *
     * @return the count of ports not currently in use
     */
    public int availablePortCount() {
        return availablePorts.size();
    }

    /**
     * Checks if the service has been shutdown.
     *
     * @return true if shutdown has been initiated
     */
    public boolean isShutdown() {
        return isShutdown.get();
    }

    // ==================== Shutdown ====================

    /**
     * Shuts down the service, stopping all active browsers.
     *
     * <p>All browsers are stopped gracefully with userdata preserved.
     * Task statuses are updated to {@link TaskEntity#STATUS_STOPPED}.
     * After shutdown, no new browsers can be launched.</p>
     *
     * <p>This method is idempotent — calling it multiple times is safe.</p>
     */
    public void shutdown() {
        if (!isShutdown.compareAndSet(false, true)) {
            return;
        }

        System.out.println("[TaskExecutionService] Shutting down...");

        // Stop all running browsers
        stopAllInternal();

        // Remove shutdown hook (not needed if we're shutting down normally)
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM is already shutting down
        }

        System.out.println("[TaskExecutionService] Shutdown complete");
    }

    /**
     * Emergency shutdown called by JVM shutdown hook.
     *
     * <p>Closes all browsers without status updates to minimize
     * work during JVM teardown.</p>
     */
    private void emergencyShutdown() {
        System.out.println("[TaskExecutionService] Emergency shutdown triggered...");
        isShutdown.set(true);

        for (Map.Entry<Long, Browser> entry : runningBrowsers.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                System.err.println("[TaskExecutionService] Error closing browser for task " +
                        entry.getKey() + ": " + e.getMessage());
            }
        }

        runningBrowsers.clear();
    }

    /**
     * Stops all browsers with status updates.
     *
     * <p>Used during normal shutdown (not emergency).</p>
     */
    private void stopAllInternal() {
        List<Long> taskIds = new ArrayList<>(runningBrowsers.keySet());

        for (long taskId : taskIds) {
            Browser browser = runningBrowsers.remove(taskId);
            if (browser != null) {
                try {
                    browser.close();
                } catch (Exception e) {
                    System.err.println("[TaskExecutionService] Error closing browser for task " +
                            taskId + ": " + e.getMessage());
                }
            }
            updateTaskStatus(taskId, TaskEntity.STATUS_STOPPED);
        }
    }

    // ==================== Validation ====================

    /**
     * Ensures the service has not been shutdown.
     *
     * @throws IllegalStateException if the service is shutdown
     */
    private void ensureNotShutdown() {
        if (isShutdown.get()) {
            throw new IllegalStateException("TaskExecutionService has been shutdown");
        }
    }

    /**
     * Ensures a task does not already have an active browser.
     *
     * @param taskId the task ID to check
     * @throws IllegalStateException if the task is already running
     */
    private void ensureNotRunning(long taskId) {
        if (isRunning(taskId)) {
            throw new IllegalStateException("Task " + taskId + " already has an active browser session");
        }
    }

    /**
     * Validates that required application settings are configured.
     *
     * @throws IllegalStateException if Chrome path is not set
     */
    private void validateSettings() {
        Settings settings = Settings.get();

        if (!settings.hasChromePath()) {
            throw new IllegalStateException(
                    "Chrome path is not configured. Set it in Settings before launching browsers.");
        }
    }

    // ==================== Database Helpers ====================

    /**
     * Updates a task's status in the database.
     *
     * <p>Uses the repository's convenience method for lightweight status
     * updates without loading the full entity.</p>
     *
     * @param taskId the task ID
     * @param status the new status
     */
    private void updateTaskStatus(long taskId, String status) {
        try {
            taskRepository.updateStatus(taskId, status);
        } catch (Exception e) {
            System.err.println("[TaskExecutionService] Failed to update status for task " +
                    taskId + " to " + status + ": " + e.getMessage());
        }
    }
}