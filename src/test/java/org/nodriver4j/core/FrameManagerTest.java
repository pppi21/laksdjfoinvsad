package org.nodriver4j.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nodriver4j.math.BoundingBox;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FrameManager — iframe interaction and cross-frame operations")
class FrameManagerTest extends BrowserTestBase {

    @BeforeEach
    void navigateToIframes() {
        navigateTo("iframes.html");
        // Give iframes time to load their content
        page.sleep(1000);
    }

    // ==================== getIframeInfo ====================

    @Test
    @DisplayName("getIframeInfo returns valid info for a same-origin iframe")
    void getIframeInfoReturnValidInfo() {
        Page.IframeInfo info = page.getIframeInfo("#form-iframe");
        assertNotNull(info, "IframeInfo should not be null");
        assertNotNull(info.frameId(), "frameId should not be null");
        assertFalse(info.frameId().isEmpty(), "frameId should not be empty");
        assertTrue(info.backendNodeId() > 0, "backendNodeId should be positive");
        assertNotNull(info.boundingBox(), "boundingBox should not be null");
        assertTrue(info.boundingBox().getWidth() > 0, "iframe should have non-zero width");
        assertTrue(info.boundingBox().getHeight() > 0, "iframe should have non-zero height");
    }

    @Test
    @DisplayName("getIframeInfo URL reflects the iframe src")
    void getIframeInfoUrlMatchesSrc() {
        Page.IframeInfo info = page.getIframeInfo("#form-iframe");
        assertNotNull(info.url());
        assertTrue(info.url().contains("iframe-content.html"),
                "URL should contain iframe-content.html, got: " + info.url());
    }

    // ==================== evaluateInFrame ====================

    @Test
    @DisplayName("evaluateInFrame executes JS inside the iframe context")
    void evaluateInFrameExecutesJs() {
        Page.IframeInfo info = page.getIframeInfo("#form-iframe");
        String title = page.evaluateInFrame(info, "document.title");
        assertEquals("Iframe Content", title);
    }

    @Test
    @DisplayName("evaluateInFrame returns values from iframe JS scope")
    void evaluateInFrameReadsJsScope() {
        Page.IframeInfo info = page.getIframeInfo("#form-iframe");
        String iframeTitle = page.evaluateInFrame(info, "window.getIframeTitle()");
        assertEquals("Iframe Content", iframeTitle);
    }

    // ==================== querySelectorInFrame ====================

    @Test
    @DisplayName("querySelectorInFrame finds elements inside the iframe")
    void querySelectorInFrameFindsElement() {
        Page.IframeInfo info = page.getIframeInfo("#form-iframe");
        BoundingBox box = page.querySelectorInFrame(info, "#iframe-name");
        assertNotNull(box, "Should find #iframe-name inside the iframe");
        assertTrue(box.getWidth() > 0 && box.getHeight() > 0);
    }

    @Test
    @DisplayName("querySelectorInFrame returns null for absent elements")
    void querySelectorInFrameReturnsNullForMissing() {
        Page.IframeInfo info = page.getIframeInfo("#form-iframe");
        BoundingBox box = page.querySelectorInFrame(info, "#nonexistent");
        assertNull(box, "Should return null for element not in the iframe");
    }

    // ==================== getTextInFrame ====================

    @Test
    @DisplayName("getTextInFrame reads text from inside an iframe")
    void getTextInFrameReadsText() {
        Page.IframeInfo info = page.getIframeInfo("#form-iframe");
        String text = page.getTextInFrame(info, "#shared-id");
        assertEquals("I am inside the iframe", text);
    }

    @Test
    @DisplayName("Frame isolation: same selector returns different content in parent vs iframe")
    void frameIsolationDifferentContent() {
        // Parent page has #shared-id with data-context="parent"
        String parentText = page.getText("#shared-id");
        assertEquals("I am in the parent page", parentText);

        // Iframe has #shared-id with data-context="iframe"
        Page.IframeInfo info = page.getIframeInfo("#form-iframe");
        String iframeText = page.getTextInFrame(info, "#shared-id");
        assertEquals("I am inside the iframe", iframeText);

        assertNotEquals(parentText, iframeText, "Parent and iframe should have different text");
    }

    // ==================== existsInFrame ====================

    @Test
    @DisplayName("existsInFrame returns true for element inside iframe")
    void existsInFrameReturnsTrueForPresent() {
        Page.IframeInfo info = page.getIframeInfo("#form-iframe");
        assertTrue(page.existsInFrame(info, "#iframe-name"));
        assertTrue(page.existsInFrame(info, "#iframe-submit"));
    }

    @Test
    @DisplayName("existsInFrame returns false for absent element")
    void existsInFrameReturnsFalseForMissing() {
        Page.IframeInfo info = page.getIframeInfo("#form-iframe");
        assertFalse(page.existsInFrame(info, "#nonexistent"));
    }

    @Test
    @DisplayName("existsInFrame does not find parent-only elements")
    void existsInFrameDoesNotFindParentElements() {
        Page.IframeInfo info = page.getIframeInfo("#form-iframe");
        // #parent-button exists in the parent page but NOT inside the iframe
        assertFalse(page.existsInFrame(info, "#parent-button"));
    }

    // ==================== clickInFrame ====================

    @Test
    @DisplayName("clickInFrame clicks a button inside an iframe and verifies the handler fired")
    void clickInFrameFiresHandler() {
        Page.IframeInfo info = page.getIframeInfo("#form-iframe");

        // Verify initial state — form not yet submitted
        String initialResult = page.getTextInFrame(info, "#iframe-result");
        assertEquals("No submission yet.", initialResult);

        // Click the submit button inside the iframe
        page.clickInFrame(info, "#iframe-submit");

        // The submit handler prevents default and writes to #iframe-result
        String result = page.getTextInFrame(info, "#iframe-result");
        assertNotEquals("No submission yet.", result,
                "Result should change after clicking submit");
    }

    @Test
    @DisplayName("clickInFrame using selector-based overload")
    void clickInFrameViaSelectorOverload() {
        // Uses the (iframeSelector, elementSelector) overload
        page.clickInFrame("#form-iframe", "#iframe-submit");

        Page.IframeInfo info = page.getIframeInfo("#form-iframe");
        String result = page.getTextInFrame(info, "#iframe-result");
        assertNotEquals("No submission yet.", result);
    }

    // ==================== Nested Iframe ====================

    @Test
    @DisplayName("getIframeInfo works for the outer level of nested iframes")
    void nestedIframeOuterLevel() {
        Page.IframeInfo outerInfo = page.getIframeInfo("#nested-iframe");
        assertNotNull(outerInfo);
        assertTrue(outerInfo.url().contains("iframe-nested.html"),
                "Outer iframe URL should point to iframe-nested.html, got: " + outerInfo.url());
    }

    @Test
    @DisplayName("evaluateInFrame reads content from the outer nested iframe")
    void nestedIframeEvaluateOuter() {
        Page.IframeInfo outerInfo = page.getIframeInfo("#nested-iframe");
        String text = page.getTextInFrame(outerInfo, "#outer-text");
        assertEquals("This is the outer nested iframe", text);
    }

    @Test
    @DisplayName("clickInFrame clicks a button in the outer nested iframe")
    void nestedIframeClickOuter() {
        Page.IframeInfo outerInfo = page.getIframeInfo("#nested-iframe");
        page.clickInFrame(outerInfo, "#outer-button");

        String clicked = page.evaluateInFrame(outerInfo, "window.isOuterClicked()");
        assertEquals("true", clicked);
    }

    // ==================== OOPIF / Cross-Origin ====================

    /**
     * Injects the cross-origin URL into the OOPIF iframe and waits for it to load.
     */
    private Page.IframeInfo loadOopifFrame() {
        // Set the iframe src to the cross-origin server
        page.evaluate("window.setOopifSrc('" + crossOriginBaseUrl + "oopif-content.html')");
        page.sleep(2000); // Wait for cross-origin iframe to load

        return page.getIframeInfo("#oopif-iframe");
    }

    @Test
    @DisplayName("OOPIF: getIframeInfo resolves for a cross-origin iframe")
    void oopifGetIframeInfo() {
        Page.IframeInfo info = loadOopifFrame();
        assertNotNull(info, "Should resolve OOPIF iframe info");
        assertNotNull(info.frameId(), "frameId should not be null");
        assertTrue(info.boundingBox().getWidth() > 0, "OOPIF should have non-zero width");
        assertNotNull(info.url(), "URL should not be null");
        assertTrue(info.url().contains("oopif-content.html"),
                "URL should contain oopif-content.html, got: " + info.url());
    }

    @Test
    @DisplayName("OOPIF: evaluateInFrame runs JS in the cross-origin frame scope")
    void oopifEvaluateInFrame() {
        Page.IframeInfo info = loadOopifFrame();
        String title = page.evaluateInFrame(info, "document.title");
        assertEquals("OOPIF Content", title);
    }

    @Test
    @DisplayName("OOPIF: clickInFrame clicks a button inside the cross-origin iframe")
    void oopifClickInFrame() {
        Page.IframeInfo info = loadOopifFrame();

        // Verify initial state
        String initial = page.evaluateInFrame(info, "window.getOopifClickCount()");
        assertEquals("0", initial, "Click count should start at 0");

        // Click the button
        page.clickInFrame(info, "#oopif-button");

        // Verify handler fired
        String count = page.evaluateInFrame(info, "window.getOopifClickCount()");
        assertEquals("1", count, "Click count should be 1 after clicking");
    }

    @Test
    @DisplayName("OOPIF: read text content from inside the cross-origin iframe")
    void oopifReadText() {
        Page.IframeInfo info = loadOopifFrame();
        String text = page.getTextInFrame(info, "#oopif-text");
        assertEquals("This content is served from a different origin.", text);
    }

    @Test
    @DisplayName("OOPIF: session caching reuses the session for the same URL")
    void oopifSessionCaching() {
        Page.IframeInfo info = loadOopifFrame();

        // First evaluation triggers attachment
        String firstResult = page.evaluateInFrame(info, "document.title");
        assertEquals("OOPIF Content", firstResult);

        // Second evaluation should reuse the cached session
        String secondResult = page.evaluateInFrame(info, "document.title");
        assertEquals("OOPIF Content", secondResult);
    }
}
