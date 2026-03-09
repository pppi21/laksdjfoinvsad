package org.nodriver4j.scripts;

import org.nodriver4j.core.Page;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.repository.ProfileRepository;
import org.nodriver4j.services.TaskContext;
import org.nodriver4j.services.TaskLogger;

/**
 * Contract for automation scripts executed by the task execution system.
 *
 * <p>An automation script performs a specific workflow (e.g., account
 * creation, form submission) using a browser {@link Page} and the data
 * from a {@link ProfileEntity}. Scripts communicate progress and results
 * to the UI via a {@link TaskLogger}.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link org.nodriver4j.services.TaskExecutionService} loads the task,
 *       profile, and proxy from the database</li>
 *   <li>A headless browser is launched with the correct config</li>
 *   <li>A {@link TaskContext} is created and infrastructure resources
 *       (e.g., AutoSolveAIService) are registered on it</li>
 *   <li>The {@link ScriptRegistry} creates an instance of the script</li>
 *   <li>{@link #run(Page, ProfileEntity, TaskLogger, TaskContext)} is invoked
 *       on a background thread</li>
 *   <li>On normal return → task status is set to COMPLETED, profile is saved</li>
 *   <li>On exception → task status is set to FAILED, error is logged</li>
 * </ol>
 *
 * <h2>Cancellation Contract</h2>
 * <p>Scripts run on a background thread that may be cancelled via two
 * complementary mechanisms when the user clicks Stop:</p>
 * <ul>
 *   <li><b>Context cancellation:</b> {@link TaskContext#cancel()} closes all
 *       registered resources, unblocking threads stuck on non-interruptible
 *       I/O (IMAP reads, HTTP requests). Scripts should register services
 *       they create via {@link TaskContext#register} and can check
 *       {@link TaskContext#isCancelled()} or call
 *       {@link TaskContext#checkCancelled()} at safe checkpoints.</li>
 *   <li><b>Thread interruption:</b> {@link Thread#interrupt()} handles
 *       interruptible operations ({@code Thread.sleep}, {@code Object.wait},
 *       NIO channels). Allow {@link InterruptedException} to propagate.</li>
 * </ul>
 *
 * <h2>Resource Registration</h2>
 * <p>Scripts should register any {@link AutoCloseable} services they create
 * on the {@link TaskContext} so that cancellation can tear them down
 * immediately. The fluent API works naturally with try-with-resources:</p>
 * <pre>{@code
 * try (var extractor = context.register(new UberOtpExtractor(email, catchall, pass))) {
 *     String otp = extractor.extractOtp();
 * }
 * }</pre>
 *
 * <h2>Profile Modification</h2>
 * <p>Scripts may modify the {@link ProfileEntity} passed to them (e.g.,
 * appending notes on success). The caller saves the entity after
 * {@code run()} returns, so changes are persisted automatically.
 * For mid-execution persistence (e.g., saving a generated password
 * before verification completes), use {@link #persistNote}:</p>
 * <pre>{@code
 * AutomationScript.persistNote(profile, "Password: " + password, profileRepo);
 * }</pre>
 *
 * <h2>Implementation Example</h2>
 * <pre>{@code
 * public class UberGen implements AutomationScript {
 *
 *     @Override
 *     public void run(Page page, ProfileEntity profile, TaskLogger logger,
 *                     TaskContext context) throws Exception {
 *         logger.log("Navigating to Uber Eats...");
 *
 *         page.navigate("https://www.ubereats.com/");
 *         page.waitForLoadEvent(30000);
 *
 *         logger.log("Signing up with email...");
 *         // ... automation steps ...
 *
 *         logger.success("Account created successfully");
 *     }
 * }
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Define the execution contract for automation scripts</li>
 *   <li>Standardize how scripts receive runtime dependencies</li>
 *   <li>Establish exception and cancellation semantics</li>
 *   <li>Provide shared utilities for mid-execution profile persistence</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Browser lifecycle (managed by {@link org.nodriver4j.services.TaskExecutionService})</li>
 *   <li>Script registration or lookup (managed by {@link ScriptRegistry})</li>
 *   <li>Thread management (managed by {@link org.nodriver4j.services.TaskExecutionService})</li>
 *   <li>Profile persistence after execution (managed by caller)</li>
 *   <li>Task status transitions (managed by {@link org.nodriver4j.services.TaskExecutionService})</li>
 * </ul>
 *
 * @see TaskContext
 * @see TaskLogger
 * @see ScriptRegistry
 * @see org.nodriver4j.services.TaskExecutionService
 */
@FunctionalInterface
public interface AutomationScript {

    /**
     * Executes the automation workflow.
     *
     * <p>This method is called on a background thread by the task execution
     * service. It should perform the complete automation workflow using the
     * provided page and profile data, logging progress via the task logger.</p>
     *
     * <p>A normal return indicates success. Any exception indicates failure.
     * {@link InterruptedException} indicates the user requested cancellation
     * and should be allowed to propagate.</p>
     *
     * <p>Scripts should register any {@link AutoCloseable} services they
     * create on the context via {@link TaskContext#register}, so that
     * cancellation can tear them down immediately.</p>
     *
     * @param page    the browser page to automate (never null)
     * @param profile the profile data for this task (never null, may be modified)
     * @param logger  the logger for pushing live messages to the UI (never null)
     * @param context the task context for resource registration and cancellation
     *                checking (never null)
     * @throws InterruptedException if the script is cancelled via thread interruption
     * @throws Exception            if the script fails for any reason
     */
    void run(Page page, ProfileEntity profile, TaskLogger logger, TaskContext context) throws Exception;

    /**
     * Appends a note to a profile's notes field and persists it immediately.
     *
     * <p>Use this for mid-execution persistence when important data (e.g., a
     * generated password) must survive even if the script fails later. Notes
     * are appended with a {@code " | "} separator if existing notes are present.</p>
     *
     * <pre>{@code
     * AutomationScript.persistNote(profile, "Password: " + password, profileRepo);
     * }</pre>
     *
     * @param profile    the profile to update (modified in place and saved)
     * @param note       the note to append
     * @param repository the repository to persist the profile with
     * @throws IllegalArgumentException if profile, note, or repository is null
     */
    static void persistNote(ProfileEntity profile, String note, ProfileRepository repository) {
        if (profile == null) {
            throw new IllegalArgumentException("Profile cannot be null");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Note cannot be null or blank");
        }
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }

        String existing = profile.notes();
        if (existing != null && !existing.isBlank()) {
            profile.notes(existing + " | " + note);
        } else {
            profile.notes(note);
        }

        repository.save(profile);
    }
}