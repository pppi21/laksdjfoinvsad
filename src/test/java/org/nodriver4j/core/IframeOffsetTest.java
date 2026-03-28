package org.nodriver4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nodriver4j.math.BoundingBox;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Iframe offsets — border, padding, and nested coordinate accumulation")
class IframeOffsetTest extends BrowserTestBase {

    // ==================== Border + Padding offset ====================

    @Test
    @DisplayName("click inside bordered+padded iframe hits the correct element")
    void clickInBorderedPaddedIframe() {
        navigateTo("iframes.html");
        page.sleep(1000); // Let iframes fully load

        Page.IframeInfo info = page.getIframeInfo("#bordered-iframe");
        assertNotNull(info, "Should resolve bordered iframe info");

        // Click the submit button inside the iframe
        page.clickInFrame(info, "#iframe-submit");

        // Verify the click actually landed on the button (form submitted)
        String submitted = page.evaluateInFrame(info, "window.isFormSubmitted()");
        assertEquals("true", submitted,
                "Click should have hit the submit button inside the bordered+padded iframe");
    }

    @Test
    @DisplayName("getIframeInfo returns content-area dimensions for bordered iframe")
    void iframeInfoAccountsForBorderAndPadding() {
        navigateTo("iframes.html");
        page.sleep(500);

        // scrollIntoView happens automatically inside getIframeInfo → clickInFrame,
        // but for this test we just query dimensions — scroll manually
        page.scrollIntoView("#bordered-iframe");
        Page.IframeInfo bordered = page.getIframeInfo("#bordered-iframe");

        // The bordered iframe uses box-sizing:border-box with width=400,
        // border:10px, padding:5px → content width = 400 - 2*(10+5) = 370
        BoundingBox bBox = bordered.boundingBox();
        assertTrue(bBox.getWidth() < 400,
                "Content width should be less than border-box width (400), got: " + bBox.getWidth());
        assertTrue(bBox.getWidth() >= 360 && bBox.getWidth() <= 380,
                "Content width should be ~370px, got: " + bBox.getWidth());
    }

    // ==================== Standard iframe click (regression) ====================

    @Test
    @DisplayName("click inside standard iframe still works")
    void clickInStandardIframe() {
        navigateTo("iframes.html");
        page.sleep(500);

        Page.IframeInfo info = page.getIframeInfo("#form-iframe");
        page.clickInFrame(info, "#iframe-submit");

        String submitted = page.evaluateInFrame(info, "window.isFormSubmitted()");
        assertEquals("true", submitted, "Click should work in a standard iframe");
    }

    // ==================== Nested iframe (2 levels) ====================

    @Test
    @DisplayName("click in the outer level of a nested iframe works")
    void clickInOuterNestedIframe() {
        navigateTo("iframes.html");
        page.sleep(500);

        Page.IframeInfo outer = page.getIframeInfo("#nested-iframe");
        page.clickInFrame(outer, "#outer-button");

        String clicked = page.evaluateInFrame(outer, "window.isOuterClicked()");
        assertEquals("true", clicked,
                "Click should work in the outer level of the nested iframe");
    }
}
