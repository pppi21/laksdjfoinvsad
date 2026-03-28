package org.nodriver4j.core;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.nodriver4j.util.TestHttpServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeoutException;

/**
 * Base class for integration tests that need a live browser and a local HTTP server.
 *
 * <p>Lifecycle:</p>
 * <ul>
 *   <li>{@code @BeforeAll} — reads the Chromium path from {@code NODRIVER_CHROME_PATH},
 *       starts an embedded HTTP server on a random port, and launches the browser.</li>
 *   <li>{@code @BeforeEach} — opens a fresh page/tab for the test method.</li>
 *   <li>{@code @AfterEach} — closes the per-test page.</li>
 *   <li>{@code @AfterAll} — closes the browser and stops the HTTP server.</li>
 * </ul>
 *
 * <p>TODO: CI support — if the custom Chromium binary is published as a GitHub Actions artifact,
 * these tests could run in CI. For now they require a local build and the env var to be set.</p>
 */
public abstract class BrowserTestBase {

    protected static Browser browser;
    protected static TestHttpServer server;
    protected static TestHttpServer crossOriginServer;
    protected static String baseUrl;
    protected static String crossOriginBaseUrl;

    protected Page page;

    @BeforeAll
    static void setUpBrowserAndServer() throws IOException {
        String chromePath = System.getenv("chromepath");
        if (chromePath == null || chromePath.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable chromepath is not set. "
                            + "Set it to the path of the custom Chromium executable before running tests."
            );
        }

        // Start the primary test-site server
        server = TestHttpServer.start("test-site");
        baseUrl = server.baseUrl();

        // Start a second server on a different port for cross-origin / OOPIF tests
        crossOriginServer = TestHttpServer.start("test-site/oopif");
        crossOriginBaseUrl = crossOriginServer.baseUrl();

        // Find a free port for the CDP debugging connection
        int cdpPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            cdpPort = ss.getLocalPort();
        }

        BrowserConfig config = BrowserConfig.builder()
                .executablePath(chromePath)
                .headless(true)
                .build();

        browser = Browser.launch(config, cdpPort, port -> {});
    }

    @BeforeEach
    void setUpPage() throws TimeoutException {
        page = browser.newPage();
    }

    @AfterEach
    void tearDownPage() throws TimeoutException {
        if (page != null && page.isAttached()) {
            browser.closePage(page);
            page = null;
        }
    }

    @AfterAll
    static void tearDownBrowserAndServer() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        if (crossOriginServer != null) {
            crossOriginServer.stop();
            crossOriginServer = null;
        }
    }

    /**
     * Navigate the current test page to a file served by the primary test-site server.
     *
     * @param fileName the file name relative to test-site/ (e.g. "forms.html")
     */
    protected void navigateTo(String fileName) {
        page.navigate(baseUrl + fileName);
    }
}
