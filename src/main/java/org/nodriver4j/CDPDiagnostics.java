package org.nodriver4j;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.nodriver4j.cdp.CDPClient;
import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserManager;
import org.nodriver4j.core.Page;

import java.util.Scanner;

/**
 * Diagnostic tests for CDP frame and DOM inspection.
 * Used to validate the captcha solving approach.
 *
 * Run this with a page that has the PerimeterX captcha loaded.
 */
public class CDPDiagnostics {

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) {
        String executablePath = System.getenv("chromepath");

        if (executablePath == null || executablePath.isBlank()) {
            System.err.println("ERROR: 'chromepath' environment variable is not set.");
            return;
        }

        BrowserManager manager = BrowserManager.builder()
                .executablePath(executablePath)
                .fingerprintEnabled(false)
                .proxyEnabled(false)
                .warmProfile(false)
                .build();

        try (Browser browser = manager.createSession()) {
            Page page = browser.getPage();
            CDPClient cdp = page.getCdpClient();

            // Navigate to a page with the captcha
            String testUrl = "https://www.wayfair.com/";

            System.out.println("Navigating to test page...");
            page.navigate(testUrl, 15000);

            page.sleep(20000);

            System.out.println("Waiting for captcha to potentially load...");
            page.sleep(5000);

            // Check if captcha exists first
            boolean captchaExists = page.existsCss("#px-captcha");
            System.out.println("\n========================================");
            System.out.println("  Captcha element exists: " + captchaExists);
            System.out.println("========================================\n");

            if (!captchaExists) {
                System.out.println("No captcha found. You may need to trigger it or use a different URL.");
                System.out.println("Press ENTER to run tests anyway, or Ctrl+C to exit...");
                new Scanner(System.in).nextLine();
            }

            // Run tests
            System.out.println("\n========================================");
            System.out.println("  TEST 1: Page.getFrameTree()");
            System.out.println("========================================\n");
            runTest1_FrameTree(cdp);

            System.out.println("\nPress ENTER to continue to Test 2...");
            new Scanner(System.in).nextLine();

            System.out.println("\n========================================");
            System.out.println("  TEST 2: DOM.getDocument (pierce shadow)");
            System.out.println("========================================\n");
            runTest2_DOMPierce(cdp);

            System.out.println("\nPress ENTER to continue to Test 3...");
            new Scanner(System.in).nextLine();

            System.out.println("\n========================================");
            System.out.println("  TEST 3: Query for #px-captcha nodeId");
            System.out.println("========================================\n");
            runTest3_QueryCaptchaNode(cdp);

            System.out.println("\n========================================");
            System.out.println("  All tests complete!");
            System.out.println("========================================");
            System.out.println("\nPress ENTER to close browser...");
            new Scanner(System.in).nextLine();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            manager.shutdown();
        }
    }

    /**
     * Test 1: Get the frame tree to see all frames in the page.
     * We want to see if the captcha iframes appear here.
     */
    private static void runTest1_FrameTree(CDPClient cdp) {
        try {
            JsonObject result = cdp.send("Page.getFrameTree", null);
            System.out.println("Frame Tree Result:");
            System.out.println(PRETTY_GSON.toJson(result));
        } catch (Exception e) {
            System.err.println("Test 1 failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Test 2: Get the DOM document with shadow DOM piercing.
     * This should let us see inside #px-captcha's shadow root.
     *
     * We use depth=6 to go deep enough without getting the entire page.
     */
    private static void runTest2_DOMPierce(CDPClient cdp) {
        try {
            // First, get the document with pierce enabled
            JsonObject params = new JsonObject();
            params.addProperty("pierce", true);
            params.addProperty("depth", 6);  // Enough to see into shadow root

            JsonObject result = cdp.send("DOM.getDocument", params);
            System.out.println("DOM Document (pierce=true, depth=6):");
            System.out.println(PRETTY_GSON.toJson(result));

        } catch (Exception e) {
            System.err.println("Test 2 failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Test 3: Query specifically for the #px-captcha element and describe it.
     * This will give us its nodeId which we can use for further inspection.
     */
    private static void runTest3_QueryCaptchaNode(CDPClient cdp) {
        try {
            // First get document root
            JsonObject docParams = new JsonObject();
            docParams.addProperty("pierce", true);
            docParams.addProperty("depth", 0);
            JsonObject docResult = cdp.send("DOM.getDocument", docParams);

            int rootNodeId = docResult.getAsJsonObject("root").get("nodeId").getAsInt();
            System.out.println("Root nodeId: " + rootNodeId);

            // Query for #px-captcha
            JsonObject queryParams = new JsonObject();
            queryParams.addProperty("nodeId", rootNodeId);
            queryParams.addProperty("selector", "#px-captcha");

            JsonObject queryResult = cdp.send("DOM.querySelector", queryParams);
            System.out.println("\nQuery for #px-captcha:");
            System.out.println(PRETTY_GSON.toJson(queryResult));

            if (queryResult.has("nodeId") && queryResult.get("nodeId").getAsInt() != 0) {
                int captchaNodeId = queryResult.get("nodeId").getAsInt();
                System.out.println("\nFound #px-captcha with nodeId: " + captchaNodeId);

                // Describe the node to see its shadow root
                JsonObject describeParams = new JsonObject();
                describeParams.addProperty("nodeId", captchaNodeId);
                describeParams.addProperty("pierce", true);
                describeParams.addProperty("depth", 4);

                JsonObject describeResult = cdp.send("DOM.describeNode", describeParams);
                System.out.println("\nDescribe #px-captcha node (pierce=true, depth=4):");
                System.out.println(PRETTY_GSON.toJson(describeResult));

                // Try to get shadow root children
                JsonObject shadowParams = new JsonObject();
                shadowParams.addProperty("nodeId", captchaNodeId);

                try {
                    // Request child nodes of the captcha element
                    JsonObject requestParams = new JsonObject();
                    requestParams.addProperty("nodeId", captchaNodeId);
                    requestParams.addProperty("depth", 3);
                    requestParams.addProperty("pierce", true);

                    cdp.send("DOM.requestChildNodes", requestParams);
                    System.out.println("\nRequested child nodes for captcha element.");

                    // Give it a moment then re-describe
                    Thread.sleep(500);

                    JsonObject reDescribe = cdp.send("DOM.describeNode", describeParams);
                    System.out.println("\nRe-describe after requesting children:");
                    System.out.println(PRETTY_GSON.toJson(reDescribe));

                } catch (Exception e) {
                    System.out.println("Could not request child nodes: " + e.getMessage());
                }

            } else {
                System.out.println("\n#px-captcha element NOT FOUND via DOM.querySelector");
            }

        } catch (Exception e) {
            System.err.println("Test 3 failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}