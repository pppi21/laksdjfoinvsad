package org.nodriver4j;

import com.google.gson.JsonObject;
import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserConfig;
import org.nodriver4j.core.BrowserManager;
import org.nodriver4j.core.Page;

import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests whether CDP fires mouseenter/mouseleave/mouseover/mouseout events
 * when dispatching mouse moves to coordinates outside the viewport, and
 * whether movementX/movementY reflect correct deltas between positions.
 *
 * <p>Requires the {@code chromepath} environment variable.</p>
 */
public class MouseBoundaryTest {

    public static void main(String[] args) {
        String executablePath = System.getenv("chromepath");
        if (executablePath == null || executablePath.isBlank()) {
            System.err.println("ERROR: 'chromepath' environment variable is not set.");
            return;
        }

        BrowserConfig config = BrowserConfig.builder()
                .executablePath(executablePath)
                .build();

        BrowserManager manager = BrowserManager.builder()
                .config(config)
                .proxyEnabled(false)
                .warmProfile(false)
                .build();

        try {
            run(manager);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            manager.shutdown();
        }
    }

    private static void run(BrowserManager manager) throws Exception {
        runBoundaryTest(manager);
        runMovementDeltaTest(manager);
    }

    private static void runBoundaryTest(BrowserManager manager) throws Exception {
        String html = """
                <html>
                <body style="margin:0; padding:0; width:100vw; height:100vh; background:#222; color:#fff; font-family:monospace;">
                <div id="log" style="padding:20px; white-space:pre-wrap;"></div>
                <script>
                window.__events = [];
                ['mouseenter', 'mouseleave', 'mouseout', 'mouseover', 'mousemove'].forEach(type => {
                    document.addEventListener(type, e => {
                        const entry = {
                            type: e.type,
                            clientX: e.clientX,
                            clientY: e.clientY,
                            target: e.target.tagName + (e.target.id ? '#' + e.target.id : '')
                        };
                        window.__events.push(entry);
                        document.getElementById('log').textContent += JSON.stringify(entry) + '\\n';
                    });
                });
                </script>
                </body>
                </html>
                """;
        String dataUrl = "data:text/html;base64,"
                + Base64.getEncoder().encodeToString(html.getBytes());

        try (Browser browser = manager.createSession()) {
            Page page = browser.page();

            page.navigate(dataUrl);
            page.sleep(1000);

            System.out.println("[1] Moving to (400, 300) — inside viewport");
            dispatchMouseMove(page, 400, 300);
            page.sleep(500);

            System.out.println("[2] Moving to (-50, 300) — left of viewport");
            dispatchMouseMove(page, -50, 300);
            page.sleep(300);

            System.out.println("[3] Moving to (400, -50) — above viewport");
            dispatchMouseMove(page, 400, -50);
            page.sleep(300);

            System.out.println("[4] Moving to (2000, 300) — right of viewport");
            dispatchMouseMove(page, 2000, 300);
            page.sleep(500);

            System.out.println("[5] Moving back to (400, 300) — inside viewport");
            dispatchMouseMove(page, 400, 300);
            page.sleep(500);

            String results = page.evaluate("JSON.stringify(window.__events, null, 2)");
            System.out.println("\n========================================");
            System.out.println("  Mouse Boundary Event Results");
            System.out.println("========================================\n");
            System.out.println(results);
            System.out.println("\nTotal events captured: "
                    + page.evaluate("String(window.__events.length)"));
        }
    }

    private static void runMovementDeltaTest(BrowserManager manager) throws Exception {
        String html = """
                <html>
                <body style="margin:0; padding:0; width:100vw; height:100vh; background:#222; color:#fff; font-family:monospace;">
                <div id="log" style="padding:20px; white-space:pre-wrap;"></div>
                <script>
                window.__moves = [];
                document.addEventListener('mousemove', e => {
                    const entry = {
                        clientX: e.clientX,
                        clientY: e.clientY,
                        movementX: e.movementX,
                        movementY: e.movementY
                    };
                    window.__moves.push(entry);
                    document.getElementById('log').textContent += JSON.stringify(entry) + '\\n';
                });
                </script>
                </body>
                </html>
                """;
        String dataUrl = "data:text/html;base64,"
                + Base64.getEncoder().encodeToString(html.getBytes());

        try (Browser browser = manager.createSession()) {
            Page page = browser.page();

            page.navigate(dataUrl);
            page.sleep(1000);

            double[][] positions = {
                    {100, 100},  // start
                    {150, 130},  // expected delta: +50, +30
                    {200, 200},  // expected delta: +50, +70
                    {195, 210},  // expected delta:  -5, +10
            };

            for (int i = 0; i < positions.length; i++) {
                double x = positions[i][0];
                double y = positions[i][1];
                System.out.printf("[%d] Moving to (%.0f, %.0f)%n", i + 1, x, y);
                dispatchMouseMove(page, x, y);
                page.sleep(300);
            }

            String results = page.evaluate("JSON.stringify(window.__moves, null, 2)");
            System.out.println("\n========================================");
            System.out.println("  movementX/movementY Delta Results");
            System.out.println("========================================\n");
            System.out.println(results);
            System.out.println("\nTotal mousemove events: "
                    + page.evaluate("String(window.__moves.length)"));
        }
    }

    private static void dispatchMouseMove(Page page, double x, double y) throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("type", "mouseMoved");
        params.addProperty("x", x);
        params.addProperty("y", y);
        page.cdpSession().send("Input.dispatchMouseEvent", params, 5, TimeUnit.SECONDS);
    }
}
