package org.nodriver4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NavigationController — page navigation, history, and load states")
class NavigationControllerTest extends BrowserTestBase {

    // ==================== navigate / currentUrl ====================

    @Test
    @DisplayName("navigate loads a page and currentUrl reflects the URL")
    void navigateAndCurrentUrl() {
        page.navigate(baseUrl + "navigation.html");
        String url = page.currentUrl();
        assertTrue(url.contains("navigation.html"), "URL should contain the page name, got: " + url);
    }

    @Test
    @DisplayName("navigate to a different page changes the URL")
    void navigateToAnotherPage() {
        page.navigate(baseUrl + "forms.html");
        assertTrue(page.currentUrl().contains("forms.html"));
        page.navigate(baseUrl + "text-content.html");
        assertTrue(page.currentUrl().contains("text-content.html"));
    }

    @Test
    @DisplayName("navigate to an unreachable host lands on a chrome-error page")
    void navigateToNonExistentShowsChromeError() {
        // .invalid TLD is guaranteed by IANA to never resolve.
        // Chrome loads its built-in error page rather than failing navigation.
        page.navigate("http://this-host-does-not-exist.invalid/", 5000);
        String url = page.currentUrl();
        assertTrue(url.contains("chrome-error"),
                "Should land on a chrome-error page, got: " + url);
    }

    // ==================== title ====================

    @Test
    @DisplayName("title returns the page title")
    void titleReturnsPageTitle() {
        navigateTo("navigation.html");
        assertEquals("Navigation Test Page", page.title());
    }

    @Test
    @DisplayName("title changes after navigating to a new page")
    void titleChangesAfterNavigation() {
        navigateTo("navigation.html");
        assertEquals("Navigation Test Page", page.title());
        navigateTo("forms.html");
        assertEquals("Forms Test Page", page.title());
    }

    // ==================== reload ====================

    @Test
    @DisplayName("reload reloads the page and resets JS state")
    void reloadResetsJsState() {
        navigateTo("navigation.html");
        // Read the page load timestamp, then reload and verify it changed
        String firstTimestamp = page.evaluate("String(window.pageLoadTimestamp)");
        assertNotNull(firstTimestamp);

        page.sleep(50); // ensure timestamp differs
        page.reload();

        String secondTimestamp = page.evaluate("String(window.pageLoadTimestamp)");
        assertNotNull(secondTimestamp);
        assertNotEquals(firstTimestamp, secondTimestamp,
                "Timestamp should differ after reload");
    }

    // ==================== goBack / goForward ====================

    @Test
    @DisplayName("goBack navigates to the previous page in history")
    void goBackReturnsToPreviousPage() {
        navigateTo("navigation.html");
        assertTrue(page.currentUrl().contains("navigation.html"));

        navigateTo("navigation-target.html");
        assertTrue(page.currentUrl().contains("navigation-target.html"));

        page.goBack();
        assertTrue(page.currentUrl().contains("navigation.html"),
                "Should be back on navigation.html after goBack, got: " + page.currentUrl());
    }

    @Test
    @DisplayName("goForward navigates forward after goBack")
    void goForwardAfterGoBack() {
        navigateTo("navigation.html");
        navigateTo("navigation-target.html");

        page.goBack();
        assertTrue(page.currentUrl().contains("navigation.html"));

        page.goForward();
        assertTrue(page.currentUrl().contains("navigation-target.html"),
                "Should be on target page after goForward, got: " + page.currentUrl());
    }

    // ==================== waitForNavigation ====================

    @Test
    @DisplayName("waitForNavigation completes after JS-triggered navigation")
    void waitForNavigationAfterJsNav() {
        navigateTo("navigation.html");
        // Click the JS nav button which does window.location.href = 'navigation-target.html'
        page.jsClick("#js-nav-btn");
        page.waitForNavigation(10000);
        assertTrue(page.currentUrl().contains("navigation-target.html"),
                "Should have navigated to target, got: " + page.currentUrl());
    }

    // ==================== waitForNetworkIdle ====================

    @Test
    @DisplayName("waitForNetworkIdle resolves on a static page with no pending requests")
    void waitForNetworkIdleOnStaticPage() {
        navigateTo("text-content.html");
        // Static page with no AJAX — network should already be idle
        assertDoesNotThrow(() -> page.waitForNetworkIdle(500, 5000));
    }
}
