package org.nodriver4j;

import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.entity.TaskEntity;
import org.nodriver4j.persistence.entity.TaskGroupEntity;
import org.nodriver4j.persistence.repository.ProfileRepository;
import org.nodriver4j.persistence.repository.TaskGroupRepository;
import org.nodriver4j.persistence.repository.TaskRepository;
import org.nodriver4j.scripts.ScriptRegistry;
import org.nodriver4j.services.TaskExecutionService;

import java.io.IOException;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test harness for Stage 3B — runs a scripted task through the full
 * {@link TaskExecutionService} pipeline with console output.
 *
 * <p>Usage:</p>
 * <pre>
 *   // Run with an existing task ID from the database
 *   java ScriptTestRunner 42
 *
 *   // Run without args to list available tasks and pick one
 *   java ScriptTestRunner
 * </pre>
 *
 * <p>Prerequisites:</p>
 * <ul>
 *   <li>Settings must have a valid Chrome path configured</li>
 *   <li>The task must exist in the database with a valid profile and task group</li>
 *   <li>The task group's script name must be registered in {@link ScriptRegistry}</li>
 * </ul>
 */
public class ScriptTestRunner {

    private static final TaskRepository taskRepository = new TaskRepository();
    private static final TaskGroupRepository taskGroupRepository = new TaskGroupRepository();
    private static final ProfileRepository profileRepository = new ProfileRepository();
    public static void main(String[] args) {
        System.out.println("==========================================");
        System.out.println("  NoDriver4j - Script Test Runner");
        System.out.println("==========================================");
        System.out.println();

        Database.initialize();

        // Resolve task ID
        long taskId;
        if (args.length > 0) {
            taskId = Long.parseLong(args[0]);
        } else {
            taskId = promptForTask();
            if (taskId < 0) {
                return;
            }
        }

        // Load and display task details
        TaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            System.err.println("Task not found: " + taskId);
            return;
        }

        TaskGroupEntity group = taskGroupRepository.findById(task.groupId()).orElse(null);
        ProfileEntity profile = profileRepository.findById(task.profileId()).orElse(null);

        System.out.println("--- Task Details ---");
        System.out.println("  Task ID:    " + task.id());
        System.out.println("  Group:      " + (group != null ? group.name() : "Unknown"));
        System.out.println("  Script:     " + (group != null ? group.scriptName() : "Unknown"));
        System.out.println("  Profile:    " + (profile != null ? profile.displayName() : "Unknown"));
        System.out.println("  Email:      " + (profile != null ? profile.emailAddress() : "Unknown"));
        System.out.println("  Status:     " + task.status());
        System.out.println("  Warm:       " + task.warmSession());
        System.out.println("  Proxy:      " + (task.hasProxy() ? "ID " + task.proxyId() : "none"));
        System.out.println("  Userdata:   " + (task.hasUserdataPath() ? task.userdataPath() : "not assigned yet"));
        System.out.println();

        // Validate script registration
        String scriptName = group != null ? group.scriptName() : null;
        if (scriptName == null || !ScriptRegistry.isRegistered(scriptName)) {
            System.err.println("Script not registered: " + scriptName);
            System.err.println("Registered scripts: " + ScriptRegistry.scriptNames());
            return;
        }

        // Latch to wait for completion
        CountDownLatch completionLatch = new CountDownLatch(1);

        // Start the task with console callbacks
        TaskExecutionService service = TaskExecutionService.instance();

        System.out.println("==========================================");
        System.out.println("  Starting task " + taskId + " (" + scriptName + ")");
        System.out.println("==========================================");
        System.out.println();

        try {
            service.startManualBrowser(taskId);
//            service.startTask(
//                    taskId,
//                    // Log callback — print to console with color indicator
//                    (message, color) -> {
//                        String prefix = switch (color) {
//                            case "log-error" -> "  [ERROR] ";
//                            case "log-success" -> "  [SUCCESS] ";
//                            default -> "  [LOG] ";
//                        };
//                        System.out.println(prefix + (message != null ? message : "(cleared)"));
//                    },
//                    // Status callback — print transitions and release latch on terminal states
//                    status -> {
//                        System.out.println("  [STATUS] → " + status);
//                        if (isTerminal(status)) {
//                            completionLatch.countDown();
//                        }
//                    }
//            );
        } catch (IllegalStateException e) {
            System.err.println("Failed to start task: " + e.getMessage());
            return;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        try {
            Thread.sleep(100000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // Register shutdown hook for Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.println("Shutting down...");
            service.shutdown();
        }, "TestRunner-ShutdownHook"));

        // Wait for the task to finish
        System.out.println("Task is running. Press Ctrl+C to abort, or wait for completion...");
        System.out.println();

        try {
            boolean completed = completionLatch.await(10, TimeUnit.MINUTES);
            if (!completed) {
                System.err.println("Task did not complete within 10 minutes. Stopping...");
                service.stopBrowser(taskId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting for task.");
            service.stopBrowser(taskId);
        }

        // Reload task to show final state
        System.out.println();
        System.out.println("==========================================");
        System.out.println("  Final State");
        System.out.println("==========================================");

        TaskEntity finalTask = taskRepository.findById(taskId).orElse(null);
        if (finalTask != null) {
            System.out.println("  Status:  " + finalTask.status());
            System.out.println("  Log:     " + (finalTask.hasLogMessage() ? finalTask.logMessage() : "(none)"));
        }

        ProfileEntity finalProfile = profileRepository.findById(task.profileId()).orElse(null);
        if (finalProfile != null) {
            System.out.println("  Notes:   " + (finalProfile.notes() != null ? finalProfile.notes() : "(none)"));
        }

        System.out.println();
        System.out.println("Done. Goodbye!");

        service.shutdown();
    }

    /**
     * Lists available tasks and prompts the user to pick one.
     *
     * @return the selected task ID, or -1 to exit
     */
    private static long promptForTask() {
        List<TaskEntity> tasks = taskRepository.findAll();

        if (tasks.isEmpty()) {
            System.err.println("No tasks found in the database.");
            System.err.println("Create tasks through the UI first, then run this test.");
            return -1;
        }

        System.out.println("Available tasks:");
        System.out.println();

        for (TaskEntity task : tasks) {
            ProfileEntity profile = profileRepository.findById(task.profileId()).orElse(null);
            TaskGroupEntity group = taskGroupRepository.findById(task.groupId()).orElse(null);

            String profileName = profile != null ? profile.displayName() : "Unknown";
            String email = profile != null ? profile.emailAddress() : "";
            String groupName = group != null ? group.name() : "Unknown";
            String script = group != null ? group.scriptName() : "Unknown";

            System.out.printf("  [%d] %s (%s) — Group: %s — Script: %s — Status: %s%n",
                    task.id(), profileName, email, groupName, script, task.status());
        }

        System.out.println();
        System.out.print("Enter task ID (or 'q' to quit): ");

        try (Scanner scanner = new Scanner(System.in)) {
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("q")) {
                return -1;
            }
            return Long.parseLong(input);
        } catch (NumberFormatException e) {
            System.err.println("Invalid task ID.");
            return -1;
        }
    }

    /**
     * Checks if a status represents a terminal state.
     */
    private static boolean isTerminal(String status) {
        return TaskEntity.STATUS_COMPLETED.equals(status)
                || TaskEntity.STATUS_FAILED.equals(status)
                || TaskEntity.STATUS_STOPPED.equals(status);
    }
}