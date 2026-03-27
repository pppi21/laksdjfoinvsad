package org.nodriver4j.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.nodriver4j.core.exceptions.NavigationException;
import org.nodriver4j.core.exceptions.ScriptExecutionException;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * URL navigation, page load waiting, and history traversal.
 *
 * <p>This is an internal implementation class — scripts interact with
 * these operations through {@link Page}'s public API.</p>
 */
class NavigationController {

    static final int DEFAULT_NAVIGATION_TIMEOUT = 30000;

    private final Page page;

    NavigationController(Page page) {
        this.page = page;
    }

    // ==================== Navigation ====================

    void navigate(String url) {
        navigate(url, DEFAULT_NAVIGATION_TIMEOUT);
    }

    void navigate(String url, int timeoutMs) {
        try {
            page.ensurePageEnabled();

            JsonObject params = new JsonObject();
            params.addProperty("url", url);
            page.cdpSession().send("Page.navigate", params);

            waitForLoadEvent(timeoutMs);
        } catch (NavigationException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new NavigationException("Navigation to " + url + " failed", e);
        }
    }

    void reload() {
        reload(false, DEFAULT_NAVIGATION_TIMEOUT);
    }

    void reload(boolean ignoreCache, int timeoutMs) {
        try {
            page.ensurePageEnabled();

            JsonObject params = new JsonObject();
            params.addProperty("ignoreCache", ignoreCache);
            page.cdpSession().send("Page.reload", params);

            waitForLoadEvent(timeoutMs);
        } catch (NavigationException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new NavigationException("Page reload failed", e);
        }
    }

    void goBack() {
        try {
            page.ensurePageEnabled();

            JsonObject history = page.cdpSession().send("Page.getNavigationHistory", null);
            int currentIndex = history.get("currentIndex").getAsInt();

            if (currentIndex > 0) {
                JsonArray entries = history.getAsJsonArray("entries");
                JsonObject previousEntry = entries.get(currentIndex - 1).getAsJsonObject();
                int entryId = previousEntry.get("id").getAsInt();

                JsonObject params = new JsonObject();
                params.addProperty("entryId", entryId);
                page.cdpSession().send("Page.navigateToHistoryEntry", params);

                waitForLoadEvent(DEFAULT_NAVIGATION_TIMEOUT);
            }
        } catch (NavigationException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new NavigationException("Go back failed", e);
        }
    }

    void goForward() {
        try {
            page.ensurePageEnabled();

            JsonObject history = page.cdpSession().send("Page.getNavigationHistory", null);
            int currentIndex = history.get("currentIndex").getAsInt();
            JsonArray entries = history.getAsJsonArray("entries");

            if (currentIndex < entries.size() - 1) {
                JsonObject nextEntry = entries.get(currentIndex + 1).getAsJsonObject();
                int entryId = nextEntry.get("id").getAsInt();

                JsonObject params = new JsonObject();
                params.addProperty("entryId", entryId);
                page.cdpSession().send("Page.navigateToHistoryEntry", params);

                waitForLoadEvent(DEFAULT_NAVIGATION_TIMEOUT);
            }
        } catch (NavigationException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new NavigationException("Go forward failed", e);
        }
    }

    // ==================== URL & Title ====================

    String currentUrl() {
        try {
            page.ensureRuntimeEnabled();
            return page.evaluate("window.location.href");
        } catch (TimeoutException e) {
            throw new ScriptExecutionException("Failed to get current URL", e);
        }
    }

    String title() {
        try {
            page.ensureRuntimeEnabled();
            return page.evaluate("document.title");
        } catch (TimeoutException e) {
            throw new ScriptExecutionException("Failed to get page title", e);
        }
    }

    // ==================== Wait Methods ====================

    void waitForLoadEvent(int timeoutMs) {
        try {
            page.cdpSession().waitForEvent("Page.loadEventFired", timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.err.println("[Page] Warning: Page load timeout, continuing...");
        }
    }

    void waitForNavigation() {
        waitForNavigation(DEFAULT_NAVIGATION_TIMEOUT);
    }

    void waitForNavigation(int timeoutMs) {
        try {
            page.ensurePageEnabled();
            page.cdpSession().waitForEvent("Page.frameNavigated", timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new NavigationException("Navigation wait timed out after " + timeoutMs + "ms", e);
        }
    }

    void waitForNetworkIdle(int idleTimeMs) {
        waitForNetworkIdle(idleTimeMs, page.options().getDefaultTimeout());
    }

    void waitForNetworkIdle(int idleTimeMs, int timeoutMs) {
        try {
            page.ensureNetworkEnabled();
        } catch (TimeoutException e) {
            throw new NavigationException("Failed to enable Network domain", e);
        }
        page.sleep(idleTimeMs);
    }
}
