package org.nodriver4j;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserConfig;
import org.nodriver4j.fingerprint.Fingerprint;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Test application for fingerprint spoofing.
 *
 * Loads a fingerprint from the JSONL file, launches a browser with spoofing enabled,
 * and navigates to a test page to verify the spoofs are working.
 */
public class App {

    private static final String FINGERPRINT_FILE = "data/fingerprints.jsonl";
    private static final Gson GSON = new Gson();

    public static void main(String[] args) {
        String executablePath = System.getenv("chromepath");
        if (executablePath == null || executablePath.isBlank()) {
            System.err.println("ERROR: Set 'chromepath' environment variable to Chrome executable path");
            System.err.println("Example: export chromepath=\"/usr/bin/google-chrome\"");
            return;
        }

        // Load fingerprint from file
        Fingerprint fingerprint = loadFingerprint();
        if (fingerprint == null) {
            System.err.println("ERROR: Failed to load fingerprint");
            return;
        }

        JsonObject json = fingerprint.getRawJson();

        System.out.println("==============================================");
        System.out.println("  NoDriver4j - Hardware Fingerprint Test");
        System.out.println("==============================================");
        System.out.println();
        System.out.println("Loaded fingerprint:");
        System.out.println("  WebGL Vendor: " + getJsonString(json, "vendor"));
        System.out.println("  WebGL Renderer: " + truncate(getJsonString(json, "renderer"), 60));
        System.out.println("  Screen: " + getJsonInt(json, "width") + "x" + getJsonInt(json, "height"));
        System.out.println("  Avail: " + getJsonInt(json, "availWidth") + "x" + getJsonInt(json, "availHeight"));
        System.out.println();

        BrowserConfig config = BrowserConfig.builder()
                .executablePath(executablePath)
                .fingerprint(fingerprint)
                .build();

        Browser browser = null;
        try {
            System.out.println("Launching browser with fingerprint spoofing...");
            System.out.println("User data dir: " + config.getUserDataDir());
            System.out.println();

            browser = Browser.launch(config);

            System.out.println("Browser launched successfully on port " + config.getPort());
            System.out.println();

            // Navigate to a test page
            System.out.println("Navigating to browser fingerprint test page...");
            navigateAndWait(browser, "https://browserleaks.com/webgl");

            System.out.println();
            System.out.println("==============================================");
            System.out.println("  Browser is ready - verify spoofs manually");
            System.out.println("==============================================");
            System.out.println();
            System.out.println("Check the page for WebGL info. In DevTools Console (F12):");
            System.out.println("  const gl = document.createElement('canvas').getContext('webgl');");
            System.out.println("  const ext = gl.getExtension('WEBGL_debug_renderer_info');");
            System.out.println("  gl.getParameter(ext.UNMASKED_VENDOR_WEBGL);");
            System.out.println("  gl.getParameter(ext.UNMASKED_RENDERER_WEBGL);");
            System.out.println();
            System.out.println("Press Enter to close browser...");
            System.in.read();

        } catch (IOException e) {
            System.err.println("Failed to launch browser: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (browser != null) {
                browser.close();
                System.out.println("Browser closed.");
            }
        }
    }

    /**
     * Navigates to a URL and waits for page load.
     */
    private static void navigateAndWait(Browser browser, String url) {
        try {
            browser.getCdpClient().clearEvents();

            JsonObject params = new JsonObject();
            params.addProperty("url", url);
            browser.getCdpClient().send("Page.navigate", params);

            // Wait for page to load
            browser.getCdpClient().waitForEvent("Page.loadEventFired", 30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.err.println("Page load timeout, continuing anyway...");
        }
    }

    /**
     * Loads the first fingerprint from the JSONL file.
     */
    private static Fingerprint loadFingerprint() {
        Path path = Path.of(FINGERPRINT_FILE);

        if (!Files.exists(path)) {
            System.err.println("Fingerprint file not found: " + path.toAbsolutePath());
            System.err.println("Run FingerprintCollector first to generate fingerprints.");
            return null;
        }

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                System.err.println("Fingerprint file is empty");
                return null;
            }

            JsonObject json = GSON.fromJson(line, JsonObject.class);
            return new Fingerprint(json);

        } catch (IOException e) {
            System.err.println("Error reading fingerprint file: " + e.getMessage());
            return null;
        } catch (Exception e) {
            System.err.println("Error parsing fingerprint: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static String getJsonString(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsString() : "N/A";
    }

    private static int getJsonInt(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsInt() : 0;
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return "null";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...";
    }
}