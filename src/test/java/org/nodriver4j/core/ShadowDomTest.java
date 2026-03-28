package org.nodriver4j.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Shadow DOM — querying and interacting with elements inside shadow roots")
class ShadowDomTest extends BrowserTestBase {

    @BeforeEach
    void navigateToShadowDom() {
        navigateTo("shadow-dom.html");
    }

    // ==================== Open Shadow Root ====================

    @Test
    @DisplayName("Query element text inside an open shadow root")
    void readTextInOpenShadowRoot() {
        String text = page.evaluate(
                "document.getElementById('open-shadow-host')" +
                        ".shadowRoot.getElementById('shadow-text').innerText"
        );
        assertEquals("Text inside open shadow root", text);
    }

    @Test
    @DisplayName("Click a button inside an open shadow root and verify handler fires")
    void clickButtonInOpenShadowRoot() {
        page.evaluate(
                "document.getElementById('open-shadow-host')" +
                        ".shadowRoot.getElementById('shadow-button').click()"
        );
        String lastClicked = page.evaluate(
                "window.shadowClickLog[window.shadowClickLog.length - 1]"
        );
        assertEquals("shadow-button", lastClicked);
    }

    @Test
    @DisplayName("Type into an input inside an open shadow root and verify value")
    void typeIntoInputInOpenShadowRoot() {
        // Focus the shadow input via JS, then use page.type()
        page.evaluate(
                "document.getElementById('open-shadow-host')" +
                        ".shadowRoot.getElementById('shadow-input').focus()"
        );
        page.type("shadow text value");
        String value = page.evaluate(
                "document.getElementById('open-shadow-host')" +
                        ".shadowRoot.getElementById('shadow-input').value"
        );
        assertEquals("shadow text value", value);
    }

    @Test
    @DisplayName("Verify light DOM and shadow DOM elements coexist")
    void lightDomAndShadowDomCoexist() {
        // Light DOM element should be accessible normally
        assertTrue(page.exists("#light-element"));
        assertEquals("This is a light DOM element", page.getText("#light-element"));

        // Shadow DOM element should be accessible via JS through the shadow root
        String shadowText = page.evaluate(
                "document.getElementById('open-shadow-host')" +
                        ".shadowRoot.getElementById('shadow-text').innerText"
        );
        assertNotNull(shadowText);
        assertFalse(shadowText.isEmpty());
    }

    // ==================== Nested Shadow DOM ====================

    @Test
    @DisplayName("Query text inside nested shadow DOMs (2 levels deep)")
    void readTextInNestedShadowDom() {
        String outerText = page.evaluate(
                "document.getElementById('nested-shadow-host')" +
                        ".shadowRoot.getElementById('outer-text').innerText"
        );
        assertEquals("Outer shadow level", outerText);

        String innerText = page.evaluate(
                "document.getElementById('nested-shadow-host')" +
                        ".shadowRoot.getElementById('inner-shadow-host')" +
                        ".shadowRoot.getElementById('inner-text').innerText"
        );
        assertEquals("Inner shadow level (nested 2 deep)", innerText);
    }

    @Test
    @DisplayName("Click a button inside a nested shadow root (level 2)")
    void clickButtonInNestedShadowDom() {
        page.evaluate(
                "document.getElementById('nested-shadow-host')" +
                        ".shadowRoot.getElementById('inner-shadow-host')" +
                        ".shadowRoot.getElementById('inner-button').click()"
        );
        String lastClicked = page.evaluate(
                "window.shadowClickLog[window.shadowClickLog.length - 1]"
        );
        assertEquals("inner-button", lastClicked);
    }

    @Test
    @DisplayName("Type into an input inside a nested shadow root (level 2)")
    void typeIntoNestedShadowInput() {
        page.evaluate(
                "document.getElementById('nested-shadow-host')" +
                        ".shadowRoot.getElementById('inner-shadow-host')" +
                        ".shadowRoot.getElementById('inner-input').focus()"
        );
        page.type("nested input text");
        String value = page.evaluate(
                "document.getElementById('nested-shadow-host')" +
                        ".shadowRoot.getElementById('inner-shadow-host')" +
                        ".shadowRoot.getElementById('inner-input').value"
        );
        assertEquals("nested input text", value);
    }

    // ==================== Styled Shadow Content ====================

    @Test
    @DisplayName("Read text and attributes from styled shadow DOM element")
    void readStyledShadowContent() {
        String text = page.evaluate(
                "document.getElementById('styled-shadow-host')" +
                        ".shadowRoot.getElementById('styled-element').innerText"
        );
        assertEquals("Styled content inside shadow DOM", text);

        String attr = page.evaluate(
                "document.getElementById('styled-shadow-host')" +
                        ".shadowRoot.getElementById('styled-element').getAttribute('data-info')"
        );
        assertEquals("styled-shadow", attr);
    }

    // ==================== Closed Shadow Root (Custom Chromium Patch) ====================

    @Test
    @Disabled("Requires CDP-level shadow DOM query method — Phase 3. " +
            "Our Chromium patch exposes closed shadow roots via DOM.getShadowRoot (CDP), " +
            "not via the JS element.shadowRoot property.")
    @DisplayName("Closed shadow root: access elements via custom Chromium CDP patch")
    void accessClosedShadowRoot() {
        // Standard JS: element.shadowRoot returns null for mode:'closed'.
        // Our custom Chromium patch adds DOM.getShadowRoot at the CDP level,
        // which can pierce closed shadow roots. A Page-level helper method
        // that wraps this CDP command is needed before this test can be implemented.
    }
}
