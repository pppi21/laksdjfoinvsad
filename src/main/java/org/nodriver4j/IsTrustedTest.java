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
 * Tests whether CDP-dispatched input events are marked as trusted by the browser.
 * Registers listeners for click, keydown, mousemove, and wheel events, then
 * dispatches each via CDP and logs the isTrusted value.
 *
 * <p>Requires the {@code chromepath} environment variable.</p>
 */
public class IsTrustedTest {

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
        String html = """
                <html>
                <body>
                <input id="inp" type="text" style="margin:100px; padding:20px; font-size:24px;" placeholder="type here"/>
                <script>
                window.__results = [];
                function log(e) {
                    window.__results.push(e.type + ' isTrusted=' + e.isTrusted);
                }
                document.addEventListener('click', log);
                document.addEventListener('keydown', log);
                document.addEventListener('mousemove', log);
                document.addEventListener('wheel', log);
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

            // 1. Mouse move via CDP
            dispatchMouseMove(page, 150, 120);
            page.sleep(200);

            // 2. Click via CDP
            dispatchClick(page, 150, 120);
            page.sleep(200);

            // 3. Key press via CDP
            dispatchKey(page, "a");
            page.sleep(200);

            // 4. Wheel event via CDP
            dispatchWheel(page, 150, 120, 0, 100);
            page.sleep(500);

            // Read results
            String results = page.evaluate("JSON.stringify(window.__results, null, 2)");
            System.out.println("\n========================================");
            System.out.println("  CDP Input isTrusted Results");
            System.out.println("========================================\n");
            System.out.println(results);
        }
    }

    private static void dispatchMouseMove(Page page, double x, double y) throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("type", "mouseMoved");
        params.addProperty("x", x);
        params.addProperty("y", y);
        page.cdpSession().send("Input.dispatchMouseEvent", params, 5, TimeUnit.SECONDS);
    }

    private static void dispatchClick(Page page, double x, double y) throws TimeoutException {
        JsonObject down = new JsonObject();
        down.addProperty("type", "mousePressed");
        down.addProperty("x", x);
        down.addProperty("y", y);
        down.addProperty("button", "left");
        down.addProperty("clickCount", 1);
        page.cdpSession().send("Input.dispatchMouseEvent", down, 5, TimeUnit.SECONDS);

        JsonObject up = new JsonObject();
        up.addProperty("type", "mouseReleased");
        up.addProperty("x", x);
        up.addProperty("y", y);
        up.addProperty("button", "left");
        up.addProperty("clickCount", 1);
        page.cdpSession().send("Input.dispatchMouseEvent", up, 5, TimeUnit.SECONDS);
    }

    private static void dispatchKey(Page page, String key) throws TimeoutException {
        JsonObject keyDown = new JsonObject();
        keyDown.addProperty("type", "keyDown");
        keyDown.addProperty("key", key);
        keyDown.addProperty("code", "Key" + key.toUpperCase());
        keyDown.addProperty("text", key);
        keyDown.addProperty("windowsVirtualKeyCode", key.charAt(0) - 'a' + 65);
        page.cdpSession().send("Input.dispatchKeyEvent", keyDown, 5, TimeUnit.SECONDS);

        JsonObject keyUp = new JsonObject();
        keyUp.addProperty("type", "keyUp");
        keyUp.addProperty("key", key);
        keyUp.addProperty("code", "Key" + key.toUpperCase());
        keyUp.addProperty("windowsVirtualKeyCode", key.charAt(0) - 'a' + 65);
        page.cdpSession().send("Input.dispatchKeyEvent", keyUp, 5, TimeUnit.SECONDS);
    }

    private static void dispatchWheel(Page page, double x, double y, int deltaX, int deltaY)
            throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("type", "mouseWheel");
        params.addProperty("x", x);
        params.addProperty("y", y);
        params.addProperty("deltaX", deltaX);
        params.addProperty("deltaY", deltaY);
        page.cdpSession().send("Input.dispatchMouseEvent", params, 5, TimeUnit.SECONDS);
    }
}
