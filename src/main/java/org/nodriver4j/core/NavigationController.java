package org.nodriver4j.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.nodriver4j.core.exceptions.NavigationException;
import org.nodriver4j.core.exceptions.ScriptExecutionException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
        navigate(url, DEFAULT_NAVIGATION_TIMEOUT, WaitUntil.DOM_CONTENT_LOADED);
    }

    void navigate(String url, int timeoutMs) {
        navigate(url, timeoutMs, WaitUntil.DOM_CONTENT_LOADED);
    }

    void navigate(String url, int timeoutMs, WaitUntil waitUntil) {
        try {
            page.ensurePageEnabled();

            // Register lifecycle listener BEFORE navigation to avoid missing fast events
            LifecycleWait wait = prepareLifecycleWait(waitUntil);

            JsonObject params = new JsonObject();
            params.addProperty("url", url);
            page.cdpSession().send("Page.navigate", params);

            completeLifecycleWait(waitUntil, timeoutMs, wait);
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

            LifecycleWait wait = prepareLifecycleWait(WaitUntil.DOM_CONTENT_LOADED);

            JsonObject params = new JsonObject();
            params.addProperty("ignoreCache", ignoreCache);
            page.cdpSession().send("Page.reload", params);

            completeLifecycleWait(WaitUntil.DOM_CONTENT_LOADED, timeoutMs, wait);
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

                // Use frameNavigated instead of domContentEventFired because
                // BFCache restorations don't fire DOMContentLoaded
                LifecycleWait wait = prepareFrameNavigatedWait();

                JsonObject params = new JsonObject();
                params.addProperty("entryId", entryId);
                page.cdpSession().send("Page.navigateToHistoryEntry", params);

                completeFrameNavigatedWait(DEFAULT_NAVIGATION_TIMEOUT, wait);
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

                LifecycleWait wait = prepareFrameNavigatedWait();

                JsonObject params = new JsonObject();
                params.addProperty("entryId", entryId);
                page.cdpSession().send("Page.navigateToHistoryEntry", params);

                completeFrameNavigatedWait(DEFAULT_NAVIGATION_TIMEOUT, wait);
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

    // ==================== Lifecycle Helpers ====================

    /**
     * Holds the one-shot event listener registered before a navigation command,
     * so that fast-firing lifecycle events are not missed.
     */
    private record LifecycleWait(CountDownLatch latch, String eventName, Consumer<JsonObject> listener) {}

    /**
     * Registers a one-shot event listener for the lifecycle event matching
     * the given {@link WaitUntil} strategy. Must be called <em>before</em>
     * the CDP command that triggers the navigation.
     *
     * @return the wait handle, or {@code null} for {@link WaitUntil#COMMIT}
     */
    private LifecycleWait prepareLifecycleWait(WaitUntil waitUntil) {
        String eventName = switch (waitUntil) {
            case COMMIT -> null;
            case DOM_CONTENT_LOADED -> "Page.domContentEventFired";
            case LOAD, NETWORK_IDLE -> "Page.loadEventFired";
        };

        if (eventName == null) return null;

        CountDownLatch latch = new CountDownLatch(1);
        Consumer<JsonObject> listener = event -> latch.countDown();
        page.cdpSession().addEventListener(eventName, listener);

        return new LifecycleWait(latch, eventName, listener);
    }

    /**
     * Registers a one-shot listener for {@code Page.frameNavigated}.
     * Used by goBack/goForward where BFCache restorations skip DOMContentLoaded.
     */
    private LifecycleWait prepareFrameNavigatedWait() {
        CountDownLatch latch = new CountDownLatch(1);
        Consumer<JsonObject> listener = event -> latch.countDown();
        page.cdpSession().addEventListener("Page.frameNavigated", listener);
        return new LifecycleWait(latch, "Page.frameNavigated", listener);
    }

    /**
     * Blocks until {@code Page.frameNavigated} fires, then cleans up.
     */
    private void completeFrameNavigatedWait(int timeoutMs, LifecycleWait wait) {
        if (wait == null) return;
        try {
            if (!wait.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                System.err.println("[Page] Warning: frameNavigated timeout, continuing...");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            page.cdpSession().removeEventListener(wait.eventName, wait.listener);
        }
    }

    /**
     * Blocks until the lifecycle event fires or the timeout expires,
     * then removes the one-shot listener.
     */
    private void completeLifecycleWait(WaitUntil waitUntil, int timeoutMs, LifecycleWait wait) {
        if (wait == null) return;

        try {
            if (!wait.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                System.err.println("[Page] Warning: " + wait.eventName + " timeout, continuing...");
            }

            if (waitUntil == WaitUntil.NETWORK_IDLE) {
                waitForNetworkIdle(2000, timeoutMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            page.cdpSession().removeEventListener(wait.eventName, wait.listener);
        }
    }
}
