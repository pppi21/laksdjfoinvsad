package org.nodriver4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InputController — mouse, keyboard, and scroll interactions")
class InputControllerTest extends BrowserTestBase {

    // ==================== Click ====================

    @Test
    @DisplayName("click on button fires its click handler")
    void clickFiresHandler() {
        navigateTo("overlays.html");
        page.click("#clear-btn");
        assertEquals("clear-btn", page.evaluate("window.getLastClicked()"));
    }

    @Test
    @DisplayName("click on checkbox toggles its checked state")
    void clickTogglesCheckbox() {
        navigateTo("forms.html");
        // #agree is unchecked by default
        assertFalse(page.evaluateBoolean("document.getElementById('agree').checked"));
        page.click("#agree");
        assertTrue(page.evaluateBoolean("document.getElementById('agree').checked"));
    }

    @Test
    @DisplayName("click on checked checkbox unchecks it")
    void clickUnchecksCheckbox() {
        navigateTo("forms.html");
        // #newsletter is checked by default
        assertTrue(page.evaluateBoolean("document.getElementById('newsletter').checked"));
        page.click("#newsletter");
        assertFalse(page.evaluateBoolean("document.getElementById('newsletter').checked"));
    }

    // ==================== Type ====================

    @Test
    @DisplayName("type enters text into a focused input")
    void typeEntersText() {
        navigateTo("forms.html");
        page.click("#username");
        page.type("testuser123");
        assertEquals("testuser123", page.getValue("#username"));
    }

    @Test
    @DisplayName("type handles uppercase, symbols, and special characters")
    void typeHandlesSpecialCharacters() {
        navigateTo("forms.html");
        page.click("#email");
        page.type("User@Example.COM");
        assertEquals("User@Example.COM", page.getValue("#email"));
    }

    @Test
    @DisplayName("type into a textarea")
    void typeIntoTextarea() {
        navigateTo("forms.html");
        page.click("#bio");
        page.type("Hello, this is a bio.");
        assertEquals("Hello, this is a bio.", page.getValue("#bio"));
    }

    // ==================== Clear ====================

    @Test
    @DisplayName("clear removes existing text from a pre-filled input")
    void clearRemovesText() {
        navigateTo("forms.html");
        // #prefilled has value "initial value"
        assertEquals("initial value", page.getValue("#prefilled"));
        page.clear("#prefilled");
        assertEquals("", page.getValue("#prefilled"));
    }

    // ==================== Focus ====================

    @Test
    @DisplayName("focus moves focus to the specified element")
    void focusMovesToElement() {
        navigateTo("forms.html");
        page.focus("#password");
        String focusedId = page.evaluate("document.activeElement.id");
        assertEquals("password", focusedId);
    }

    // ==================== Select ====================

    @Test
    @DisplayName("select changes the selected option in a dropdown")
    void selectChangesDropdownValue() {
        navigateTo("forms.html");
        // Default is "" (-- Select --)
        page.select("#color", "blue");
        assertEquals("blue", page.getValue("#color"));
    }

    @Test
    @DisplayName("select changes to a different option")
    void selectChangesBetweenOptions() {
        navigateTo("forms.html");
        page.select("#color", "red");
        assertEquals("red", page.getValue("#color"));
        page.select("#color", "green");
        assertEquals("green", page.getValue("#color"));
    }

    // ==================== pressKey ====================

    @Test
    @DisplayName("pressKey Enter submits the form")
    void pressKeyEnterSubmitsForm() {
        navigateTo("forms.html");
        page.click("#username");
        page.type("myuser");
        page.pressKey("Enter", false, false, false);
        // The form submit handler writes JSON to #form-result
        String result = page.getText("#form-result");
        assertNotNull(result);
        assertTrue(result.contains("myuser"), "Form result should contain the typed username");
    }

    @Test
    @DisplayName("pressKey Tab moves focus to the next field")
    void pressKeyTabMovesFocus() {
        navigateTo("forms.html");
        page.focus("#username");
        assertEquals("username", page.evaluate("document.activeElement.id"));
        page.pressKey("Tab", false, false, false);
        // Tab should move to the next focusable element (password)
        assertEquals("password", page.evaluate("document.activeElement.id"));
    }

    @Test
    @DisplayName("pressKey Escape fires a keydown event")
    void pressKeyEscape() {
        navigateTo("forms.html");
        page.focus("#username");
        page.pressKey("Escape", false, false, false);
        // Verify the Escape keydown event was captured by the document listener
        assertEquals("Escape", page.evaluate("window.lastKeyDown"));
    }

    // ==================== Scroll ====================

    @Test
    @DisplayName("scrollIntoView brings off-screen element into viewport")
    void scrollIntoViewBringsElementIntoViewport() {
        navigateTo("scroll.html");
        // #bottom-element is far below the fold
        assertFalse(page.evaluateBoolean("window.isElementInViewport('bottom-element')"),
                "Bottom element should be out of viewport initially");
        page.scrollIntoView("#bottom-element");
        assertTrue(page.evaluateBoolean("window.isElementInViewport('bottom-element')"),
                "Bottom element should be in viewport after scrollIntoView");
    }

    @Test
    @DisplayName("scrollToBottom scrolls to the bottom of the page")
    void scrollToBottomScrollsDown() {
        navigateTo("scroll.html");
        int initialScroll = page.evaluateInt("window.getPageScrollY()");
        assertEquals(0, initialScroll, "Should start at top");
        page.scrollToBottom();
        int scrollY = page.evaluateInt("window.getPageScrollY()");
        assertTrue(scrollY > 500, "Scroll position should be well below initial position");
    }

    @Test
    @DisplayName("scrollToTop returns to the top of the page")
    void scrollToTopScrollsUp() {
        navigateTo("scroll.html");
        page.scrollToBottom();
        assertTrue(page.evaluateInt("window.getPageScrollY()") > 0);
        page.scrollToTop();
        // Allow a small tolerance — human-like scrolling may not land exactly at 0
        int scrollY = page.evaluateInt("window.getPageScrollY()");
        assertTrue(scrollY <= 50, "Should be at or near the top after scrollToTop, got: " + scrollY);
    }

    // ==================== fillFormField ====================

    @Test
    @DisplayName("fillFormField clicks, clears, and types in one step")
    void fillFormFieldEndToEnd() {
        navigateTo("forms.html");
        // Use the pre-filled input to test the full clear + type cycle
        assertEquals("initial value", page.getValue("#prefilled"));
        page.fillFormField("#prefilled", "new value", 50, 50);
        assertEquals("new value", page.getValue("#prefilled"));
    }

    @Test
    @DisplayName("fillFormField works on an empty input")
    void fillFormFieldOnEmptyInput() {
        navigateTo("forms.html");
        page.fillFormField("#username", "filled_user", 50, 50);
        assertEquals("filled_user", page.getValue("#username"));
    }

    // ==================== Hover ====================

    @Test
    @DisplayName("hover shows a tooltip on the hovered element")
    void hoverRevealsTooltip() {
        navigateTo("overlays.html");
        // #hover-tooltip is display:none by default, shown on :hover of .tooltip-container
        assertFalse(page.isVisible("#hover-tooltip"), "Tooltip should be hidden before hover");
        page.hover("#tooltip-btn");
        assertTrue(page.isVisible("#hover-tooltip"), "Tooltip should be visible after hover");
    }

    // ==================== jsClick ====================

    @Test
    @DisplayName("jsClick fires handler on an element behind an overlay")
    void jsClickBypassesOverlay() {
        navigateTo("overlays.html");
        // #modal-btn is covered by #modal-backdrop, so a normal click would hit the backdrop
        page.jsClick("#modal-btn");
        assertEquals("modal-btn", page.evaluate("window.getLastClicked()"));
    }

    @Test
    @DisplayName("jsClick works on a normally clickable element")
    void jsClickOnVisibleElement() {
        navigateTo("overlays.html");
        page.jsClick("#clear-btn");
        assertEquals("clear-btn", page.evaluate("window.getLastClicked()"));
    }
}
