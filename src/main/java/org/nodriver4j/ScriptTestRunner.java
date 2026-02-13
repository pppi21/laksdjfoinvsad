package org.nodriver4j;

import javafx.application.Platform;
import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserConfig;
import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.Settings;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.entity.TaskEntity;
import org.nodriver4j.persistence.entity.TaskGroupEntity;
import org.nodriver4j.persistence.repository.ProfileRepository;
import org.nodriver4j.persistence.repository.TaskGroupRepository;
import org.nodriver4j.persistence.repository.TaskRepository;
import org.nodriver4j.scripts.ScriptRegistry;
import org.nodriver4j.services.ScreencastService;
import org.nodriver4j.services.TaskExecutionService;
import org.nodriver4j.ui.windows.ViewBrowserWindow;

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
        System.out.println("  NoDriver4j - Screencast Test Runner");
        System.out.println("==========================================");
        System.out.println();

        Database.initialize();

        // Initialize JavaFX toolkit (needed for ViewBrowserWindow)
        Platform.startup(() -> {});
        Platform.setImplicitExit(false);

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
        System.out.println("  Profile:    " + (profile != null ? profile.displayName() : "Unknown"));
        System.out.println("  Email:      " + (profile != null ? profile.emailAddress() : "Unknown"));
        System.out.println();

        // Validate settings
        Settings settings = Settings.get();
        if (!settings.hasChromePath()) {
            System.err.println("Chrome path not configured in Settings.");
            return;
        }

        // Build headless config (no userdata dir — temp dir auto-generated and cleaned up)
        BrowserConfig config = BrowserConfig.builder()
                .executablePath(settings.chromePath())
                .headless(true)
                .headlessGpuAcceleration(true)
                .fingerprintEnabled(false)
                .resourceBlocking(false)
                .build();

        int port = 9222;

        System.out.println("Launching headless browser on port " + port + "...");

        Browser browser;
        try {
            browser = Browser.launch(config, port, p -> {});
        } catch (IOException e) {
            System.err.println("Failed to launch browser: " + e.getMessage());
            return;
        }

        System.out.println("Browser launched. Navigating to test page...");

        try {
            browser.page().navigate("https://www.google.com");
            Thread.sleep(3000); // Wait for page to render
        } catch (Exception e) {
            System.err.println("Navigation failed: " + e.getMessage());
            browser.close();
            return;
        }

        // Create screencast service (page-level CDP client)
        ScreencastService screencast = new ScreencastService(browser.cdpClient());

        // Latch to keep main thread alive until the view window is closed
        CountDownLatch windowClosed = new CountDownLatch(1);

        // Open view window on the JavaFX Application Thread
        Platform.runLater(() -> {
            ViewBrowserWindow window = new ViewBrowserWindow(
                    "Screencast Test — Task #" + taskId);

            window.setOnClose(() -> {
                System.out.println("View window closed. Cleaning up...");
                screencast.stop();
                browser.close();
                windowClosed.countDown();
            });

            // Start streaming — frames arrive on WebSocket thread, marshal to FX
            screencast.start(frameBytes ->
                    Platform.runLater(() -> window.updateFrame(frameBytes))
            );

            window.show();
            System.out.println("Screencast window opened. Close it to exit.");
        });

        // Block main thread until the user closes the view window
        try {
            windowClosed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            screencast.stop();
            browser.close();
        }

        Platform.exit();
        System.out.println("Done. Goodbye!");
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