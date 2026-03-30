package org.nodriver4j;

import org.nodriver4j.cdp.CDPSession;
import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserConfig;
import org.nodriver4j.core.BrowserManager;
import org.nodriver4j.core.Page;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Runs the detection script ({@code detection-test.js}) as page JS via a data URL
 * in two modes: once with Runtime disabled during execution, once with it enabled.
 *
 * <p>Requires the {@code chromepath} environment variable.</p>
 */
public class DetectionTest {

    public static void main(String[] args) throws IOException {
        String executablePath = System.getenv("chromepath");
        if (executablePath == null || executablePath.isBlank()) {
            System.err.println("ERROR: 'chromepath' environment variable is not set.");
            return;
        }

        String dataUrl = buildDataUrl();

        BrowserConfig config = BrowserConfig.builder()
                .executablePath(executablePath)
                .build();

        BrowserManager manager = BrowserManager.builder()
                .config(config)
                .proxyEnabled(false)
                .warmProfile(false)
                .build();

        try {
            runEnabledTest(manager, dataUrl);
            runDisabledTest(manager, dataUrl);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            manager.shutdown();
        }
    }

    /**
     * Test 1: Runtime disabled during page load.
     * Disables Runtime (auto-enabled by attachSession), navigates, then
     * re-enables Runtime only to read results.
     */
    private static void runDisabledTest(BrowserManager manager, String dataUrl) throws Exception {
        System.out.println("========================================");
        System.out.println("  Test 1: Runtime DISABLED");
        System.out.println("========================================\n");

        try (Browser browser = manager.createSession()) {
            Page page = browser.page();
            CDPSession cdp = page.cdpSession();

            // Disable Runtime before navigation — detection script runs without CDP observation
            cdp.send("Runtime.disable", null);

            page.navigate(dataUrl);
            page.sleep(2000);

            // Re-enable Runtime to read results
            cdp.send("Runtime.enable", null);

            printResults(page);
        }
    }

    /**
     * Test 2: Runtime enabled during page load (default state from attachSession).
     */
    private static void runEnabledTest(BrowserManager manager, String dataUrl) throws Exception {
        System.out.println("\n========================================");
        System.out.println("  Test 2: Runtime ENABLED");
        System.out.println("========================================\n");

        try (Browser browser = manager.createSession()) {
            Page page = browser.page();

            page.navigate(dataUrl);
            page.sleep(2000);

            printResults(page);
        }
    }

    private static void printResults(Page page) {
        String results = page.evaluate("JSON.stringify(window.__detectionResults, null, 2)");
        if (results != null) {
            System.out.println(results);
        } else {
            System.err.println("No results — detection script may not have executed.");
        }
    }

    /**
     * Reads detection-test.js from classpath, wraps it in HTML, and returns
     * a base64 data URL so the script executes as page JS during load.
     */
    private static String buildDataUrl() throws IOException {
        try (InputStream is = DetectionTest.class.getResourceAsStream("/detection-test.js")) {
            if (is == null) {
                throw new IOException("detection-test.js not found in src/main/resources/");
            }
            String script = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String html = "<html><head><script>" + script + "</script></head><body></body></html>";
            return "data:text/html;base64,"
                    + Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
        }
    }
}