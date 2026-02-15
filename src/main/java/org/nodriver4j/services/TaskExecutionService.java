package org.nodriver4j.services;

import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserConfig;
import org.nodriver4j.core.ProxyConfig;
import org.nodriver4j.persistence.Settings;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.entity.ProxyEntity;
import org.nodriver4j.persistence.entity.TaskEntity;
import org.nodriver4j.persistence.entity.TaskGroupEntity;
import org.nodriver4j.persistence.repository.ProfileRepository;
import org.nodriver4j.persistence.repository.ProxyRepository;
import org.nodriver4j.persistence.repository.TaskGroupRepository;
import org.nodriver4j.persistence.repository.TaskRepository;
import org.nodriver4j.scripts.AutomationScript;
import org.nodriver4j.scripts.ScriptRegistry;
import org.nodriver4j.ui.windows.ViewBrowserWindow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Singleton service that manages browser lifecycle and script execution for tasks.
 *
 * <p>This is the bridge between the UI layer and {@link Browser#launch}.
 * It handles:</p>
 * <ul>
 *   <li>Building {@link BrowserConfig} from {@link TaskEntity}, {@link ProxyEntity},
 *       and {@link Settings}</li>
 *   <li>Port pool allocation for CDP connections</li>
 *   <li>Tracking running {@link Browser} instances by task ID</li>
 *   <li>Starting manual browser sessions (headed, no script)</li>
 *   <li>Starting scripted task execution on background threads</li>
 *   <li>Stopping browsers gracefully while preserving userdata</li>
 *   <li>Interrupting script threads on user-initiated stop</li>
 *   <li>Assigning persistent userdata directories on first launch</li>
 *   <li>Session warming when configured on the task</li>
 *   <li>Updating task status in the database on lifecycle transitions</li>
 *   <li>Appending completion notes to profiles on script success</li>
 *   <li>Emergency cleanup via JVM shutdown hook</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TaskExecutionService service = TaskExecutionService.instance();
 *
 * // Launch a scripted task (headless browser + automation script)
 * service.startTask(taskId,
 *     (msg, color) -> Platform.runLater(() -> row.setLogText(msg, color)),
 *     status -> Platform.runLater(() -> row.setStatus(status)));
 *
 * // Launch a manual (headed) browser for a task
 * Browser browser = service.startManualBrowser(taskId);
 *
 * // Check if a task has an active browser or script thread
 * boolean active = service.isActive(taskId);
 *
 * // Stop the browser (and interrupt script thread if running)
 * service.stopBrowser(taskId);
 *
 * // Shutdown all browsers on app exit
 * service.shutdown();
 * }</pre>
 *
 * <h2>Script Execution Flow</h2>
 * <ol>
 *   <li>{@link #startTask} validates preconditions and spawns a background thread</li>
 *   <li>The thread loads the task, group, and profile from the database</li>
 *   <li>A headless browser is launched with the correct config</li>
 *   <li>The script is created via {@link ScriptRegistry} and executed</li>
 *   <li>On success: profile notes are updated, status → COMPLETED</li>
 *   <li>On failure: error is logged, status → FAILED</li>
 *   <li>On cancellation (thread interrupted): status → STOPPED</li>
 *   <li>Browser is always closed in the finally block</li>
 * </ol>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Browser lifecycle management (launch, stop, track)</li>
 *   <li>Script thread management (spawn, interrupt, track)</li>
 *   <li>Port pool management</li>
 *   <li>BrowserConfig construction from entities + settings</li>
 *   <li>Userdata path assignment and persistence</li>
 *   <li>Task status updates on lifecycle transitions</li>
 *   <li>Profile note updates on script success</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Script logic (implemented by {@link AutomationScript} classes)</li>
 *   <li>Script name resolution (delegated to {@link ScriptRegistry})</li>
 *   <li>Log persistence and UI notification (delegated to {@link TaskLogger})</li>
 *   <li>Screencast / view browser (Stage 3C)</li>
 *   <li>UI concerns (called by UI controllers but has no JavaFX dependency)</li>
 *   <li>Managing proxy groups, profile groups, or task groups</li>
 *   <li>Database schema or connection lifecycle</li>
 * </ul>
 *
 * @see Browser
 * @see BrowserConfig
 * @see AutomationScript
 * @see TaskLogger
 * @see ScriptRegistry
 * @see TaskEntity
 * @see Settings
 */
public class TaskExecutionService {

    private static final int PORT_RANGE_START = 9222;
    private static final int PORT_RANGE_END = 9621;
    private static final int PORT_TIMEOUT_SECONDS = 30;
    private static final int THREAD_JOIN_TIMEOUT_MS = 3000;
    private static final DateTimeFormatter NOTE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
    private final ConcurrentHashMap<Long, Thread> scriptThreads;
    private final BlockingQueue<Integer> availablePorts;
    private final AtomicBoolean isShutdown;
    private final Thread shutdownHook;

    // Repositories
    private final TaskRepository taskRepository;
    private final TaskGroupRepository taskGroupRepository;
    private final ProfileRepository profileRepository;
    private final ProxyRepository proxyRepository;

    // ==================== Constructor ====================

    private TaskExecutionService() {
        this.runningBrowsers = new ConcurrentHashMap<>();
        this.scriptThreads = new ConcurrentHashMap<>();
        this.isShutdown = new AtomicBoolean(false);
        this.taskRepository = new TaskRepository();
        this.taskGroupRepository = new TaskGroupRepository();
        this.profileRepository = new ProfileRepository();
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

    // ==================== Scripted Task Execution ====================

    /**
     * Starts a scripted task: launches a headless browser and runs the
     * automation script on a background thread.
     *
     * <p>This method returns immediately after spawning the background thread.
     * All heavy work (browser launch, script execution, cleanup) happens
     * asynchronously. The caller is notified of progress via callbacks.</p>
     *
     * <p>The background thread handles the full lifecycle:</p>
     * <ol>
     *   <li>Load task, group, and profile entities from the database</li>
     *   <li>Launch a headless browser with the correct config</li>
     *   <li>Resolve and execute the automation script</li>
     *   <li>On success: append a completion note to the profile, set COMPLETED</li>
     *   <li>On failure: log the error, set FAILED</li>
     *   <li>On cancellation: set STOPPED (via thread interruption from
     *       {@link #stopBrowser(long)})</li>
     *   <li>Always: close the browser and release resources</li>
     * </ol>
     *
     * @param onLogUpdate    callback for live log messages from the script;
     *                       receives {@code (message, colorClass)} — the caller
     *                       is responsible for marshalling to the JavaFX thread;
     *                       may be null to disable UI log updates
     * @param onStatusChange callback for task status transitions; receives the
     *                       new status string (e.g., {@link TaskEntity#STATUS_RUNNING});
     *                       the caller is responsible for marshalling to the JavaFX
     *                       thread; may be null to disable UI status updates
     * @param taskId         the ID of the task to start
     * @throws IllegalStateException if the service is shutdown, the task is already
     *                               active, or Settings is misconfigured
     */
    public void startTask(long taskId,
                          BiConsumer<String, String> onLogUpdate,
                          Consumer<String> onStatusChange) {
        ensureNotShutdown();
        ensureNotActive(taskId);
        validateSettings();

        Thread scriptThread = new Thread(
                () -> executeTask(taskId, onLogUpdate, onStatusChange),
                "Task-" + taskId
        );
        scriptThread.setDaemon(true);

        // Reserve the slot before starting to prevent race conditions
        scriptThreads.put(taskId, scriptThread);
        scriptThread.start();

        System.out.println("[TaskExecutionService] Spawned script thread for task " + taskId);
    }

    /**
     * Executes the full task lifecycle on a background thread.
     *
     * <p>This method is the main body of the script thread spawned by
     * {@link #startTask}. It handles entity loading, browser launch,
     * script execution, success/failure handling, and cleanup.</p>
     *
     * @param taskId         the task ID
     * @param onLogUpdate    UI callback for log messages, or null
     * @param onStatusChange UI callback for status transitions, or null
     */
    private void executeTask(long taskId,
                             BiConsumer<String, String> onLogUpdate,
                             Consumer<String> onStatusChange) {

        TaskLogger logger = new TaskLogger(taskId, taskRepository);
        logger.setOnLogUpdate(onLogUpdate);

        try {
            // Load entities from database
            TaskEntity task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("Task not found: " + taskId));

            TaskGroupEntity group = taskGroupRepository.findById(task.groupId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Task group not found: " + task.groupId()));

            ProfileEntity profile = profileRepository.findById(task.profileId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Profile not found: " + task.profileId()));

            // Launch headless browser
            logger.log("Launching browser...");
            Browser browser = launchBrowser(taskId, true, TaskEntity.STATUS_RUNNING);
            notifyStatusChange(onStatusChange, TaskEntity.STATUS_RUNNING);

            // Check for interruption after browser launch (user may have
            // clicked stop while the browser was starting)
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Cancelled before script execution");
            }

            // Create and execute the automation script
            AutomationScript script = ScriptRegistry.create(group.scriptName());
            logger.log("Running " + group.scriptName() + "...");
            script.run(browser.page(), profile, logger);

            // Script completed successfully — update profile notes
            appendCompletionNote(profile, group.scriptName());
            profileRepository.save(profile);

            logger.success(group.scriptName() + " completed");
            updateTaskStatus(taskId, TaskEntity.STATUS_COMPLETED);
            notifyStatusChange(onStatusChange, TaskEntity.STATUS_COMPLETED);

        } catch (InterruptedException e) {
            // User cancelled via stopBrowser — re-set the interrupt flag
            // and let the finally block handle cleanup. stopBrowser already
            // sets STOPPED status, but we set it here too for robustness
            // in case the interruption came from elsewhere.
            Thread.currentThread().interrupt();
            updateTaskStatus(taskId, TaskEntity.STATUS_STOPPED);
            notifyStatusChange(onStatusChange, TaskEntity.STATUS_STOPPED);

        } catch (Exception e) {
            System.err.println("[TaskExecutionService] Task " + taskId +
                    " failed: " + e.getMessage());
            logger.error("Failed: " + e.getMessage());
            updateTaskStatus(taskId, TaskEntity.STATUS_FAILED);
            notifyStatusChange(onStatusChange, TaskEntity.STATUS_FAILED);

        } finally {
            // Close the browser if stopBrowser hasn't already done so.
            // ConcurrentHashMap.remove() is atomic, so only one of
            // executeTask or stopBrowser will get the reference.
            Browser browser = runningBrowsers.remove(taskId);
            if (browser != null) {
                try {
                    browser.close();
                } catch (Exception e) {
                    System.err.println("[TaskExecutionService] Error closing browser " +
                            "in cleanup for task " + taskId + ": " + e.getMessage());
                }
            }
            scriptThreads.remove(taskId);
        }
    }

    // ==================== Manual Browser ====================

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
     *                               already active, or Settings is misconfigured
     */
    public Browser startManualBrowser(long taskId) throws IOException, InterruptedException {
        ensureNotActive(taskId);
        validateSettings();
        return launchBrowser(taskId, false, TaskEntity.STATUS_MANUAL);
    }

    // ==================== Stop ====================

    /**
     * Stops the browser for a task and preserves userdata.
     *
     * <p>If a script thread is running for this task, it is interrupted
     * first. The browser is then closed gracefully, the userdata directory
     * is preserved for future runs, and the task status is updated to
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

        // Interrupt script thread if present. The thread's finally block
        // will handle its own cleanup, but we also close the browser here
        // to ensure the Chrome process is terminated promptly.
        Thread thread = scriptThreads.remove(taskId);
        if (thread != null) {
            thread.interrupt();
            System.out.println("[TaskExecutionService] Interrupted script thread for task " + taskId);
        }

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
     * Stops all currently active tasks and browsers.
     *
     * <p>Each browser is stopped gracefully, script threads are interrupted,
     * and userdata is preserved. Task statuses are updated to
     * {@link TaskEntity#STATUS_STOPPED}.</p>
     */
    public void stopAll() {
        // Collect from both maps to cover tasks where the browser hasn't
        // launched yet (script thread exists but browser doesn't)
        Set<Long> taskIds = new HashSet<>(runningBrowsers.keySet());
        taskIds.addAll(scriptThreads.keySet());

        System.out.println("[TaskExecutionService] Stopping " + taskIds.size() + " task(s)...");

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

    // ==================== Profile Notes ====================

    /**
     * Appends a completion note to a profile entity.
     *
     * <p>Format: {@code "<ScriptName> Account created on 2025-07-15 14:30"}</p>
     * <p>If existing notes are present, the new note is appended with a
     * {@code " | "} separator.</p>
     *
     * @param profile    the profile to update (modified in place)
     * @param scriptName the name of the script that completed
     */
    private void appendCompletionNote(ProfileEntity profile, String scriptName) {
        String note = scriptName + " Account created on " +
                LocalDateTime.now().format(NOTE_FORMATTER);

        String existing = profile.notes();
        if (existing != null && !existing.isBlank()) {
            profile.notes(existing + " | " + note);
        } else {
            profile.notes(note);
        }
    }

    // ==================== UI Callbacks ====================

    /**
     * Safely invokes a status change callback.
     *
     * <p>Catches and logs any exceptions thrown by the callback to prevent
     * UI errors from crashing the script thread.</p>
     *
     * @param callback the callback to invoke, or null
     * @param status   the new status string
     */
    private void notifyStatusChange(Consumer<String> callback, String status) {
        if (callback == null) {
            return;
        }
        try {
            callback.accept(status);
        } catch (Exception e) {
            System.err.println("[TaskExecutionService] Status change callback failed: " +
                    e.getMessage());
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
     *   <li>Headless mode → determined by caller (manual=false, scripted=true)</li>
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
                .fingerprintEnabled(settings.defaultFingerprintEnabled())
                .resourceBlocking(settings.defaultResourceBlocking())
                .webrtcPolicy(settings.defaultWebrtcPolicy())
                .userDataDir(Path.of(task.userdataPath()));


        if (headless){
            builder.headless(true)
                    .headlessGpuAcceleration(true);

        }

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
     * Checks if a task has any active session — either a running browser
     * or a script thread that hasn't finished yet.
     *
     * <p>Use this to determine if a task can be started. A task may have
     * a script thread but no browser yet (during the launch phase), so
     * checking only {@link #isRunning(long)} is insufficient.</p>
     *
     * @param taskId the task ID to check
     * @return true if the task has an active browser or script thread
     */
    public boolean isActive(long taskId) {
        return isRunning(taskId) || scriptThreads.containsKey(taskId);
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
     * Gets the number of currently running script threads.
     *
     * @return the count of active script threads
     */
    public int activeScriptCount() {
        return scriptThreads.size();
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
     * Shuts down the service, stopping all active browsers and script threads.
     *
     * <p>All browsers are stopped gracefully with userdata preserved.
     * Script threads are interrupted and given a brief period to terminate.
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

        // Stop all running browsers and script threads
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
     * <p>Interrupts all script threads and closes all browsers without
     * status updates to minimize work during JVM teardown.</p>
     */
    private void emergencyShutdown() {
        System.out.println("[TaskExecutionService] Emergency shutdown triggered...");
        isShutdown.set(true);

        // Interrupt all script threads
        for (Thread thread : scriptThreads.values()) {
            try {
                thread.interrupt();
            } catch (Exception e) {
                // Ignore during emergency shutdown
            }
        }

        // Close all browsers
        for (Map.Entry<Long, Browser> entry : runningBrowsers.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                System.err.println("[TaskExecutionService] Error closing browser for task " +
                        entry.getKey() + ": " + e.getMessage());
            }
        }

        runningBrowsers.clear();
        scriptThreads.clear();
    }

    /**
     * Stops all browsers and script threads with status updates.
     *
     * <p>Used during normal shutdown (not emergency). Interrupts all
     * script threads, closes all browsers, updates statuses to STOPPED,
     * and waits briefly for threads to terminate.</p>
     */
    private void stopAllInternal() {
        // Collect all active task IDs from both maps
        Set<Long> taskIds = new HashSet<>(runningBrowsers.keySet());
        taskIds.addAll(scriptThreads.keySet());

        // Interrupt all script threads first so they can begin cleanup
        for (Thread thread : scriptThreads.values()) {
            try {
                thread.interrupt();
            } catch (Exception e) {
                // Continue with other threads
            }
        }

        // Close all browsers and update statuses
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

        // Wait briefly for script threads to finish their cleanup
        for (Map.Entry<Long, Thread> entry : scriptThreads.entrySet()) {
            try {
                entry.getValue().join(THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        scriptThreads.clear();
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
     * Ensures a task does not already have an active browser or script thread.
     *
     * <p>Checks both {@link #runningBrowsers} and {@link #scriptThreads}
     * to cover the window between thread spawn and browser launch.</p>
     *
     * @param taskId the task ID to check
     * @throws IllegalStateException if the task is already active
     */
    private void ensureNotActive(long taskId) {
        if (isRunning(taskId) || scriptThreads.containsKey(taskId)) {
            throw new IllegalStateException(
                    "Task " + taskId + " already has an active session");
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

    // ==================== Directory Utilities ====================

    /**
     * Attempts to delete a directory and all its contents.
     *
     * <p>Walks the file tree in reverse order (deepest files first) and
     * deletes each entry. Returns false if any file could not be deleted
     * (e.g., locked by another process).</p>
     *
     * @param directory the directory to delete
     * @return true if the directory was fully deleted, false otherwise
     */
    public static boolean tryDeleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) {
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

    /**
     * Deletes a directory with retry logic to handle lingering file locks.
     *
     * <p>After stopping a browser, Chrome may not release file locks on the
     * userdata directory immediately. This method retries up to 5 times with
     * a 500ms delay between attempts.</p>
     *
     * <p>If the directory does not exist, this method returns immediately.
     * If deletion fails after all retries, a warning is logged but no
     * exception is thrown — callers should proceed with cleanup regardless.</p>
     *
     * @param directory the directory to delete
     */
    public static void deleteDirectoryWithRetry(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }

        final int maxRetries = 5;
        final int retryDelayMs = 500;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (tryDeleteDirectory(directory)) {
                System.out.println("[TaskExecutionService] Deleted directory: " + directory);
                return;
            }

            if (attempt < maxRetries) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("[TaskExecutionService] Interrupted while retrying directory deletion: " + directory);
                    return;
                }
            }
        }

        System.err.println("[TaskExecutionService] Failed to delete directory after " +
                maxRetries + " attempts: " + directory);
    }
}