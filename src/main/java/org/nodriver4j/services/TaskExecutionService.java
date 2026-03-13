package org.nodriver4j.services;

import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserConfig;
import org.nodriver4j.core.Fingerprint;
import org.nodriver4j.core.monitoring.FingerprintMonitor;
import org.nodriver4j.core.monitoring.FingerprintReport;
import org.nodriver4j.persistence.Settings;
import org.nodriver4j.persistence.entity.*;
import org.nodriver4j.persistence.importer.FingerprintExtractor;
import org.nodriver4j.persistence.repository.*;
import org.nodriver4j.scripts.AutomationScript;
import org.nodriver4j.scripts.ScriptRegistry;
import org.nodriver4j.services.aycd.AutoSolveAIService;
import org.nodriver4j.services.proxy.ProxyDiagnosticService;
import org.nodriver4j.services.response.proxy.ProxyDiagnosticResult;

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
    private final ConcurrentHashMap<Long, TaskCallbacks> taskCallbacks;
    private final ConcurrentHashMap<Long, TaskContext> taskContexts;
    private final ConcurrentHashMap<Long, FingerprintMonitor> fpMonitors;
    private final BlockingQueue<Integer> availablePorts;
    private final AtomicBoolean isShutdown;
    private final Thread shutdownHook;

    // Repositories
    private final TaskRepository taskRepository;
    private final TaskGroupRepository taskGroupRepository;
    private final ProfileRepository profileRepository;
    private final ProxyRepository proxyRepository;
    private final FingerprintRepository fingerprintRepository;

    // Services
    private final FingerprintExtractor fingerprintExtractor;

    // ==================== Constructor ====================

    private TaskExecutionService() {
        this.runningBrowsers = new ConcurrentHashMap<>();
        this.scriptThreads = new ConcurrentHashMap<>();
        this.taskCallbacks = new ConcurrentHashMap<>();
        this.taskContexts = new ConcurrentHashMap<>();
        this.fpMonitors = new ConcurrentHashMap<>();
        this.isShutdown = new AtomicBoolean(false);
        this.taskRepository = new TaskRepository();
        this.taskGroupRepository = new TaskGroupRepository();
        this.profileRepository = new ProfileRepository();
        this.proxyRepository = new ProxyRepository();
        this.fingerprintRepository = new FingerprintRepository();
        this.fingerprintExtractor = new FingerprintExtractor();


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
     * <p>The provided callbacks are stored in a map and accessed via proxy
     * lambdas. This allows {@link #replaceCallbacks(long, BiConsumer, Consumer)}
     * to swap in new callbacks when task rows are rebuilt (e.g., due to
     * pagination or page re-entry), without interrupting the running script.</p>
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
     * @param taskId         the ID of the task to start
     * @param onLogUpdate    callback for live log messages from the script;
     *                       receives {@code (message, colorClass)} — the caller
     *                       is responsible for marshalling to the JavaFX thread;
     *                       may be null to disable UI log updates
     * @param onStatusChange callback for task status transitions; receives the
     *                       new status string (e.g., {@link TaskEntity#STATUS_RUNNING});
     *                       the caller is responsible for marshalling to the JavaFX
     *                       thread; may be null to disable UI status updates
     * @throws IllegalStateException if the service is shutdown, the task is already
     *                               active, or Settings is misconfigured
     */
    public void startTask(long taskId,
                          BiConsumer<String, String> onLogUpdate,
                          Consumer<String> onStatusChange) {
        ensureNotShutdown();
        ensureNotActive(taskId);
        validateSettings();

        // Store real callbacks in the map (replaceable via replaceCallbacks)
        taskCallbacks.put(taskId, new TaskCallbacks(onLogUpdate, onStatusChange));

        // Create proxy lambdas that always delegate to the current map entry.
        // This allows the controller to swap callbacks without touching the thread.
        BiConsumer<String, String> logProxy = (msg, color) -> {
            TaskCallbacks current = taskCallbacks.get(taskId);
            if (current != null && current.onLogUpdate() != null) {
                current.onLogUpdate().accept(msg, color);
            }
        };

        Consumer<String> statusProxy = status -> {
            TaskCallbacks current = taskCallbacks.get(taskId);
            if (current != null && current.onStatusChange() != null) {
                current.onStatusChange().accept(status);
            }
        };

        Thread scriptThread = new Thread(
                () -> executeTask(taskId, logProxy, statusProxy),
                "Task-" + taskId
        );
        scriptThread.setDaemon(true);

        // Reserve the slot before starting to prevent race conditions
        scriptThreads.put(taskId, scriptThread);
        scriptThread.start();

        System.out.println("[TaskExecutionService] Spawned script thread for task " + taskId);
    }

    /**
     * Replaces the UI callbacks for an active task.
     *
     * <p>Used when the UI rebuilds task rows — for example, when the user
     * navigates between pagination pages or re-enters the TaskGroupDetail
     * page. The new callbacks point to the freshly created {@code TaskRow},
     * so live log and status updates resume on the correct UI component.</p>
     *
     * <p>If the task is not active (no entry in the callbacks map), this
     * method does nothing. The caller does not need to check whether the
     * task is running before calling this.</p>
     *
     * @param taskId         the task ID
     * @param onLogUpdate    the new log callback, or null
     * @param onStatusChange the new status callback, or null
     */
    public void replaceCallbacks(long taskId,
                                 BiConsumer<String, String> onLogUpdate,
                                 Consumer<String> onStatusChange) {
        // Only replace if the task is actually active — otherwise the entry
        // doesn't exist and there's nothing to replace.
        if (taskCallbacks.containsKey(taskId)) {
            taskCallbacks.put(taskId, new TaskCallbacks(onLogUpdate, onStatusChange));
        }
    }

    /**
     * Executes the full task lifecycle on a background thread.
     *
     * <p>This method is the main body of the script thread spawned by
     * {@link #startTask}. It handles entity loading, browser launch,
     * optional session warming, script execution, success/failure
     * handling, and cleanup.</p>
     *
     * <p>A {@link TaskContext} is created at the start and stored in
     * {@link #taskContexts}. Infrastructure resources (e.g.,
     * {@link AutoSolveAIService}) are registered on the context after
     * browser launch. When the user clicks Stop, {@link #stopBrowser}
     * calls {@link TaskContext#cancel()} to forcefully tear down all
     * registered resources — unblocking threads stuck on non-interruptible
     * I/O (IMAP, HTTP). Script-created resources are registered on the
     * context by scripts themselves (once they receive it in Stage 3).</p>
     *
     * <p>The {@code finally} block uses conditional removal
     * ({@link java.util.concurrent.ConcurrentHashMap#remove(Object, Object)})
     * to ensure cleanup never interferes with a subsequent session (manual
     * browser or new scripted run) that may have been started for the same
     * task ID after {@link #stopBrowser} cleared the maps.</p>
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

        Browser myBrowser = null;
        Thread myThread = Thread.currentThread();
        TaskContext context = new TaskContext();
        taskContexts.put(taskId, context);

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
            myBrowser = launchBrowser(taskId, true, TaskEntity.STATUS_RUNNING);
            notifyStatusChange(onStatusChange, TaskEntity.STATUS_RUNNING);

            // Register infrastructure resources on context for cancellation teardown
            AutoSolveAIService aiService = myBrowser.autoSolveAIService();
            if (aiService != null) {
                context.register(aiService);
            }

            // Optional fingerprint monitoring (debug diagnostic)
            if (Settings.get().fingerprintMonitoringEnabled()) {
                try {
                    FingerprintMonitor monitor = new FingerprintMonitor(myBrowser.page(), false);
                    fpMonitors.put(taskId, monitor);
                    monitor.start();
                    logger.log("Fingerprint monitoring enabled");
                } catch (Exception e) {
                    System.err.println("[TaskExecutionService] Failed to start fingerprint monitor: " +
                            e.getMessage());
                }
            }

            // Warm session if enabled
            if (task.warmSession()) {
                myBrowser.warm(logger);
            }

            // Check for cancellation after browser launch + warming (user may
            // have clicked Stop while the browser was starting or warming)
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Cancelled before script execution");
            }
            context.checkCancelled();

            // Create and execute the automation script
            AutomationScript script = ScriptRegistry.create(group.scriptName());
            logger.log("Running " + group.scriptName() + "...");
            script.run(myBrowser.page(), profile, logger, context);

            // Script completed successfully — update profile notes
            appendCompletionNote(profile, group.scriptName());
            profileRepository.save(profile);

            logger.success(group.scriptName() + " completed");
            updateTaskStatus(taskId, TaskEntity.STATUS_COMPLETED);
            notifyStatusChange(onStatusChange, TaskEntity.STATUS_COMPLETED);

        } catch (InterruptedException e) {
            // User cancelled via stopBrowser (thread interruption path)
            Thread.currentThread().interrupt();
            updateTaskStatus(taskId, TaskEntity.STATUS_STOPPED);
            notifyStatusChange(onStatusChange, TaskEntity.STATUS_STOPPED);

        } catch (Exception e) {
            if (context.isCancelled() || Thread.currentThread().isInterrupted()) {
                updateTaskStatus(taskId, TaskEntity.STATUS_STOPPED);
                notifyStatusChange(onStatusChange, TaskEntity.STATUS_STOPPED);
            } else {
                // Genuine script failure
                System.err.println("[TaskExecutionService] Task " + taskId +
                        " failed: " + e.getMessage());
                logger.error("Failed: " + e.getMessage());
                updateTaskStatus(taskId, TaskEntity.STATUS_FAILED);
                notifyStatusChange(onStatusChange, TaskEntity.STATUS_FAILED);
            }

        } finally {
            // Export fingerprint report while browser is still open
            exportFingerprintReport(taskId);

            // Close context — tears down any remaining registered resources
            context.close();
            taskContexts.remove(taskId, context);

            // Conditional removal — only remove if the map still holds OUR instance.
            // If stopBrowser already cleared the slot and a new session was started,
            // these are no-ops on the map. The browser is still closed (idempotent)
            // to ensure our resources are cleaned up regardless.
            if (myBrowser != null) {
                runningBrowsers.remove(taskId, myBrowser);
                try {
                    myBrowser.close();
                } catch (Exception e) {
                    System.err.println("[TaskExecutionService] Error closing browser " +
                            "in cleanup for task " + taskId + ": " + e.getMessage());
                }
            }
            scriptThreads.remove(taskId, myThread);
            taskCallbacks.remove(taskId);
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
        Browser browser = launchBrowser(taskId, false, TaskEntity.STATUS_MANUAL);

        // Start fingerprint monitoring for manual sessions
        if (Settings.get().fingerprintMonitoringEnabled()) {
            try {
                FingerprintMonitor monitor = new FingerprintMonitor(browser.page(), false);
                fpMonitors.put(taskId, monitor);
                monitor.start();
                System.out.println("[TaskExecutionService] Fingerprint monitoring enabled for manual session " +
                        taskId);
            } catch (Exception e) {
                System.err.println("[TaskExecutionService] Failed to start fingerprint monitor: " +
                        e.getMessage());
            }
        }

        return browser;
    }

    // ==================== Stop ====================

    /**
     * Stops the browser for a task and preserves userdata.
     *
     * <p>The stop sequence is ordered for maximum effectiveness:</p>
     * <ol>
     *   <li><b>Cancel context</b> — closes all registered resources (IMAP
     *       connections, HTTP clients), immediately unblocking any thread
     *       stuck on non-interruptible network I/O</li>
     *   <li><b>Interrupt thread</b> — handles interruptible operations
     *       ({@code Thread.sleep}, {@code Object.wait}, NIO channels)</li>
     *   <li><b>Close browser</b> — terminates the Chrome process and
     *       releases the CDP connection</li>
     * </ol>
     *
     * <p>This method is safe to call even if the browser has already been
     * closed or the task is not running — it will simply update the status.</p>
     *
     * @param taskId the ID of the task to stop
     * @throws IllegalStateException if the service has been shutdown
     */
    public void stopBrowser(long taskId) {
        ensureNotShutdown();

        // 1. Export fingerprint report while browser is still running
        exportFingerprintReport(taskId);

        // 2. Cancel context — closes all registered resources, unblocking
        //    threads stuck on non-interruptible I/O (IMAP, HTTP)
        TaskContext context = taskContexts.remove(taskId);
        if (context != null) {
            context.cancel();
        }

        // 3. Interrupt thread — handles interruptible operations
        Thread thread = scriptThreads.remove(taskId);
        if (thread != null) {
            thread.interrupt();
            System.out.println("[TaskExecutionService] Interrupted script thread for task " + taskId);
        }

        // 4. Close browser
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

        taskCallbacks.remove(taskId);

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
     * browser launching, status updates, and tracking.</p>
     *
     * <p>Session warming is NOT performed here — it is the caller's
     * responsibility to warm after launch if needed. This ensures
     * the task status is set to RUNNING and the UI is notified before
     * the potentially long warming phase begins.</p>
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

        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("Task not found: " + taskId));

        ensureUserdataPath(task);
        ensureFingerprint(task);

        // Run proxy diagnostic before building config.
        // Detects the real exit IP through the proxy, then resolves timezone + geolocation.
        // Failure is non-blocking — the browser still launches, just without those two switches.
        ProxyDiagnosticResult diagnostic = null;
        if (task.hasProxy()) {
            ProxyEntity proxyEntity = proxyRepository.findById(task.proxyId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Proxy not found for task " + task.id() + ": proxyId=" + task.proxyId()));
            diagnostic = ProxyDiagnosticService.diagnose(proxyEntity.toProxyConfig());
            if (!diagnostic.success()) {
                System.err.println("[TaskExecutionService] Proxy diagnostic failed for task " +
                        taskId + ": " + diagnostic.failureReason() +
                        " — launching without timezone/geolocation overrides");
            }
        }

        BrowserConfig config = buildConfig(task, headless, diagnostic);

        Integer port = availablePorts.poll(PORT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (port == null) {
            throw new IOException("No ports available after " + PORT_TIMEOUT_SECONDS +
                    " seconds. All " + (PORT_RANGE_END - PORT_RANGE_START + 1) + " ports are in use.");
        }

        try {
            System.out.println("[TaskExecutionService] Launching browser for task " + taskId +
                    " (port " + port + ", headless=" + headless + ")");

            Browser browser = Browser.launch(config, port, this::releasePort);
            runningBrowsers.put(taskId, browser);

            task.clearLog();
            task.status(status);
            task.touchUpdatedAt();
            taskRepository.save(task);

            System.out.println("[TaskExecutionService] Task " + taskId + " is now " + status +
                    " (port " + port + ")");

            return browser;

        } catch (IOException | RuntimeException e) {
            releasePort(port);
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

    // ==================== Fingerprint Monitoring ====================

    /**
     * Stops the fingerprint monitor for a task and exports the report.
     *
     * <p>Uses atomic {@link ConcurrentHashMap#remove} so concurrent calls
     * from {@code stopBrowser()} and {@code executeTask()} are safe —
     * only the first caller exports.</p>
     *
     * @param taskId the task ID
     */
    private void exportFingerprintReport(long taskId) {
        FingerprintMonitor monitor = fpMonitors.remove(taskId);
        if (monitor == null) {
            return;
        }

        try {
            monitor.stop();
            FingerprintReport report = monitor.report();
            Path reportDir = Path.of("nodriver4j-data", "fp-reports");
            Files.createDirectories(reportDir);
            report.exportToFile(reportDir.resolve(
                    "task-" + taskId + "-" + System.currentTimeMillis() + ".json"));
            System.out.println("[TaskExecutionService] Fingerprint report exported for task " + taskId +
                    ": " + report.uniqueApis() + " unique APIs, " + report.totalAccesses() + " accesses");
        } catch (Exception e) {
            System.err.println("[TaskExecutionService] Failed to export fingerprint report for task " +
                    taskId + ": " + e.getMessage());
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
    private BrowserConfig buildConfig(TaskEntity task, boolean headless,
                                      ProxyDiagnosticResult diagnostic) {
        Settings settings = Settings.get();

        BrowserConfig.Builder builder = BrowserConfig.builder()
                .executablePath(settings.chromePath())
                .userDataDir(Path.of(task.userdataPath()));

        FingerprintEntity fp = null;
        if (settings.defaultFingerprintEnabled() && task.hasFingerprint()) {
            fp = fingerprintRepository.findById(task.fingerprintId()).orElse(null);
            if (fp != null) {
                builder.fingerprint(fp);
            }
        }

        if (headless) {
            builder.headless(true)
                    .headlessGpuAcceleration(true);
        }

        if (task.hasProxy()) {
            ProxyEntity proxyEntity = proxyRepository.findById(task.proxyId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Proxy not found for task " + task.id() + ": proxyId=" + task.proxyId()));
            builder.proxy(proxyEntity.toProxyConfig());
        }

        if (settings.hasAutoSolveApiKey()) {
            builder.autoSolveAIKey(settings.autoSolveApiKey());
        }

        // Apply proxy-derived timezone and geolocation overrides
        if (diagnostic != null && diagnostic.success()) {
            builder.argument("--fingerprint-timezone=" + diagnostic.timezone());
            builder.argument("--fingerprint-geolocation=" + diagnostic.toGeolocationArg());
            builder.argument("--webrtc-ip4=" + diagnostic.exitIp());
        }

        // Per-session connection fingerprint (conditions naturally vary between sessions)
        if (fp != null) {
            // Connection: "effectiveType,downlink,rtt,saveData,type"
            Random sessionRandom = new Random();
            String connType = sessionRandom.nextInt(100) < 65 ? "wifi" : "ethernet";
            double downlink = connType.equals("wifi")
                    ? 15.0 + sessionRandom.nextDouble() * 35.0
                    : 50.0 + sessionRandom.nextDouble() * 50.0;
            int rtt = connType.equals("wifi")
                    ? 50 + sessionRandom.nextInt(100)
                    : 20 + sessionRandom.nextInt(60);
            builder.argument("--fingerprint-connection=4g,"
                    + String.format("%.1f", downlink) + "," + rtt + ",false," + connType);

            // Media features: "colorScheme,reducedMotion,contrast,forcedColors,colorGamut"
            String colorScheme = fp.prefersColorScheme() != null ? fp.prefersColorScheme() : "light";
            String colorGamut = fp.colorGamut() != null ? fp.colorGamut() : "srgb";
            builder.argument("--fingerprint-media-features=" + colorScheme
                    + ",no-preference,no-preference,none," + colorGamut);
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

    /**
     * Ensures the task has a fingerprint entity assigned.
     *
     * <p>If the task does not yet have a fingerprint (first launch) and
     * fingerprinting is enabled in settings, a random JSONL line is selected,
     * extracted into a {@link FingerprintEntity} via {@link FingerprintExtractor},
     * persisted to the database, and linked to the task.</p>
     *
     * <p>Mobile fingerprints (phones/tablets) are automatically rejected
     * by the extractor. If a rejected line is selected, a different line
     * is tried, up to a maximum number of attempts.</p>
     *
     * <p>If fingerprinting is disabled in settings, this method returns
     * immediately without assigning a fingerprint. If it is later enabled,
     * the next launch will trigger extraction.</p>
     *
     * @param task the task entity (modified in place and saved if fingerprint is assigned)
     */
    private void ensureFingerprint(TaskEntity task) {
        if (task.hasFingerprint() || !Settings.get().defaultFingerprintEnabled()) {
            return;
        }

        final int maxAttempts = 10;

        try {
            int lineCount = Fingerprint.totalCount();

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                int index = new Random().nextInt(lineCount);

                try {
                    String jsonLine = Fingerprint.readJsonLine(index);

                    FingerprintEntity fingerprint = fingerprintExtractor.extract(jsonLine, index);
                    fingerprintRepository.save(fingerprint);

                    task.fingerprintId(fingerprint.id());
                    task.touchUpdatedAt();
                    taskRepository.save(task);

                    System.out.println("[TaskExecutionService] Assigned fingerprint for task " +
                            task.id() + ": id=" + fingerprint.id() +
                            " (JSONL line " + index + ", seed=" + fingerprint.seed() + ")");
                    return;

                } catch (IllegalArgumentException e) {
                    // Mobile GPU or unparseable line — try another
                    System.err.println("[TaskExecutionService] Fingerprint rejected for task " +
                            task.id() + " (attempt " + attempt + "/" + maxAttempts + "): " +
                            e.getMessage());
                }
            }

            System.err.println("[TaskExecutionService] Failed to assign fingerprint for task " +
                    task.id() + " after " + maxAttempts + " attempts — all selected lines were rejected");

        } catch (IOException e) {
            System.err.println("[TaskExecutionService] Failed to assign fingerprint for task " +
                    task.id() + ": " + e.getMessage());
        }
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
     * <p>Cancels all contexts, interrupts all script threads, and closes
     * all browsers without status updates to minimize work during JVM
     * teardown.</p>
     */
    private void emergencyShutdown() {
        System.out.println("[TaskExecutionService] Emergency shutdown triggered...");
        isShutdown.set(true);

        // Cancel all contexts — unblocks stuck threads
        for (TaskContext context : taskContexts.values()) {
            try {
                context.cancel();
            } catch (Exception e) {
                // Ignore during emergency shutdown
            }
        }

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

        // Export any remaining fingerprint reports
        for (Long taskId : fpMonitors.keySet()) {
            exportFingerprintReport(taskId);
        }

        runningBrowsers.clear();
        scriptThreads.clear();
        taskCallbacks.clear();
        taskContexts.clear();
        fpMonitors.clear();
    }

    /**
     * Stops all browsers and script threads with status updates.
     *
     * <p>Used during normal shutdown (not emergency). Cancels all contexts,
     * interrupts all script threads, closes all browsers, updates statuses
     * to STOPPED, and waits briefly for threads to terminate.</p>
     */
    private void stopAllInternal() {
        // Collect all active task IDs from both maps
        Set<Long> taskIds = new HashSet<>(runningBrowsers.keySet());
        taskIds.addAll(scriptThreads.keySet());

        // Export fingerprint reports while browsers are still open
        for (Long taskId : fpMonitors.keySet()) {
            exportFingerprintReport(taskId);
        }

        // Cancel all contexts first — unblocks stuck threads immediately
        for (TaskContext context : taskContexts.values()) {
            try {
                context.cancel();
            } catch (Exception e) {
                // Continue with other contexts
            }
        }
        taskContexts.clear();

        // Interrupt all script threads so they can begin cleanup
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
        taskCallbacks.clear();
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

    /**
     * Holds replaceable UI callbacks for an active task.
     *
     * <p>Stored in {@link #taskCallbacks} and looked up by proxy lambdas
     * on every invocation, allowing the controller to swap in new callbacks
     * when task rows are rebuilt (e.g., page navigation, returning to the page).</p>
     *
     * @param onLogUpdate    callback for log messages, or null
     * @param onStatusChange callback for status transitions, or null
     */
    public record TaskCallbacks(
            BiConsumer<String, String> onLogUpdate,
            Consumer<String> onStatusChange
    ) {}
}