package org.nodriver4j.services;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-execution context that tracks cancellation state and closeable resources
 * for a single scripted task run.
 *
 * <p>Created by {@link TaskExecutionService} at the start of each
 * {@code executeTask} call and passed to the automation script via
 * {@link org.nodriver4j.scripts.AutomationScript#run}. When the user
 * clicks Stop, {@link #cancel()} is called — which immediately closes
 * all registered resources (IMAP connections, HTTP clients, etc.),
 * unblocking any threads stuck on network I/O.</p>
 *
 * <h2>Resource Registration</h2>
 * <p>Scripts register {@link AutoCloseable} resources they create during
 * execution. Infrastructure resources (e.g., {@link AutoSolveAIService})
 * are registered by {@link TaskExecutionService} after browser launch.
 * On cancel or close, all registered resources are closed in reverse
 * registration order.</p>
 *
 * <h2>Cancellation Contract</h2>
 * <p>After {@link #cancel()} is called:</p>
 * <ul>
 *   <li>{@link #isCancelled()} returns {@code true}</li>
 *   <li>{@link #checkCancelled()} throws {@link InterruptedException}</li>
 *   <li>{@link #register} closes the resource immediately and throws
 *       {@link IllegalStateException}</li>
 *   <li>All previously registered resources have been closed</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>{@link #register} and {@link #cancel()} are mutually exclusive via
 * synchronized access on the resource list. This prevents a resource from
 * being added after the cancel sweep has completed. The cancelled flag
 * uses {@link AtomicBoolean} for lock-free reads in the fast path
 * ({@link #isCancelled()}, {@link #checkCancelled()}).</p>
 *
 * <h2>Usage in Scripts</h2>
 * <pre>{@code
 * public void run(Page page, ProfileEntity profile, TaskLogger logger,
 *                 TaskContext context) throws Exception {
 *
 *     // Register a resource — automatically closed on cancel
 *     UberOtpExtractor extractor = context.register(
 *             new UberOtpExtractor(email, catchall, password));
 *
 *     // Periodically check cancellation in long loops
 *     context.checkCancelled();
 *
 *     // Fast boolean check (no exception)
 *     if (context.isCancelled()) return;
 * }
 * }</pre>
 *
 * <h2>Usage in TaskExecutionService</h2>
 * <pre>{@code
 * TaskContext context = new TaskContext();
 * taskContexts.put(taskId, context);
 *
 * // Register infrastructure resources
 * AutoSolveAIService aiService = browser.autoSolveAIService();
 * if (aiService != null) {
 *     context.register(aiService);
 * }
 *
 * // Pass to script
 * script.run(page, profile, logger, context);
 *
 * // On stop:
 * TaskContext ctx = taskContexts.remove(taskId);
 * if (ctx != null) ctx.cancel();
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Track {@link AutoCloseable} resources for a single task execution</li>
 *   <li>Provide a cooperative cancellation flag</li>
 *   <li>Close all registered resources on cancel or close</li>
 *   <li>Guarantee thread-safe registration vs cancellation</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Browser lifecycle (managed by {@link TaskExecutionService})</li>
 *   <li>Thread interruption (managed by {@link TaskExecutionService})</li>
 *   <li>Creating services (scripts and infrastructure create their own)</li>
 *   <li>Task status or logging (managed by {@link TaskExecutionService}
 *       and {@link TaskLogger})</li>
 * </ul>
 *
 * @see TaskExecutionService
 * @see org.nodriver4j.scripts.AutomationScript
 */
public class TaskContext implements AutoCloseable {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * Registered resources in insertion order. Guarded by {@code synchronized(this)}
     * for atomic register-vs-cancel. Closed in reverse order on cancel/close.
     */
    private final List<AutoCloseable> resources = new ArrayList<>();

    // ==================== Registration ====================

    /**
     * Registers a closeable resource for automatic cleanup on cancel or close.
     *
     * <p>Returns the resource itself for fluent inline usage:</p>
     * <pre>{@code
     * UberOtpExtractor ext = context.register(new UberOtpExtractor(...));
     * }</pre>
     *
     * <p>If this context has already been cancelled, the resource is closed
     * immediately and an {@link IllegalStateException} is thrown. This
     * prevents orphaned resources from being created after teardown.</p>
     *
     * @param resource the resource to track (must not be null)
     * @param <T>      the resource type
     * @return the same resource, for inline use
     * @throws IllegalArgumentException if resource is null
     * @throws IllegalStateException    if this context has been cancelled
     */
    public <T extends AutoCloseable> T register(T resource) {
        if (resource == null) {
            throw new IllegalArgumentException("Resource cannot be null");
        }

        synchronized (this) {
            if (cancelled.get()) {
                closeQuietly(resource);
                throw new IllegalStateException("TaskContext has been cancelled — resource closed immediately");
            }
            resources.add(resource);
        }

        return resource;
    }

    // ==================== Cancellation ====================

    /**
     * Cancels this context: closes all registered resources immediately.
     *
     * <p>After this call, {@link #isCancelled()} returns {@code true} and
     * any future {@link #register} calls will close the resource on the
     * spot. Resources are closed in reverse registration order so that
     * dependencies are unwound correctly (e.g., an extractor is closed
     * before the underlying GmailClient it depends on).</p>
     *
     * <p>This method is idempotent — calling it multiple times is safe.
     * Only the first call performs the resource sweep.</p>
     *
     * <p>Exceptions thrown by individual {@link AutoCloseable#close()}
     * calls are caught and logged, never propagated. This ensures all
     * resources are attempted even if one fails.</p>
     */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        closeAllResources();
    }

    /**
     * Returns {@code true} if this context has been cancelled.
     *
     * <p>Lock-free volatile read — safe to call frequently in loops
     * without performance concern.</p>
     *
     * @return true if {@link #cancel()} has been called
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Throws {@link InterruptedException} if this context has been cancelled.
     *
     * <p>Designed to be called at safe checkpoints inside scripts — e.g.,
     * between major steps or at the top of retry loops. Uses
     * {@code InterruptedException} to align with the existing cancellation
     * contract in {@link org.nodriver4j.scripts.AutomationScript}.</p>
     *
     * @throws InterruptedException if this context has been cancelled
     */
    public void checkCancelled() throws InterruptedException {
        if (cancelled.get()) {
            throw new InterruptedException("Task has been cancelled");
        }
    }

    // ==================== AutoCloseable ====================

    /**
     * Closes this context and all remaining registered resources.
     *
     * <p>Called in the {@code finally} block of {@code executeTask} for
     * normal cleanup after task completion. Also safe to call after
     * {@link #cancel()} — the resource list will already be empty.</p>
     *
     * <p>Sets the cancelled flag to prevent post-close registration.</p>
     */
    @Override
    public void close() {
        cancelled.set(true);
        closeAllResources();
    }

    // ==================== Internal ====================

    /**
     * Closes all registered resources in reverse order and clears the list.
     *
     * <p>Synchronized to prevent concurrent modification with {@link #register}.
     * Resources are snapshotted and cleared under the lock, then closed outside
     * the lock to avoid holding it during potentially slow I/O teardown.</p>
     */
    private void closeAllResources() {
        List<AutoCloseable> snapshot;

        synchronized (this) {
            if (resources.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(resources);
            resources.clear();
        }

        // Close in reverse order (LIFO) — dependencies unwound correctly
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            closeQuietly(snapshot.get(i));
        }
    }

    /**
     * Closes a resource, catching and logging any exception.
     *
     * @param resource the resource to close
     */
    private void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            System.err.println("[TaskContext] Error closing " +
                    resource.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ==================== Diagnostics ====================

    /**
     * Returns the number of currently registered resources.
     *
     * <p>Primarily useful for logging and testing.</p>
     *
     * @return the resource count
     */
    public int resourceCount() {
        synchronized (this) {
            return resources.size();
        }
    }

    @Override
    public String toString() {
        return String.format("TaskContext{cancelled=%s, resources=%d}",
                cancelled.get(), resourceCount());
    }
}