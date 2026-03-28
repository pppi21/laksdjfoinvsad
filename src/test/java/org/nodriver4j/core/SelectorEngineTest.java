package org.nodriver4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nodriver4j.math.BoundingBox;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SelectorEngine — text, role, shadow-piercing, and chained selectors")
class SelectorEngineTest extends BrowserTestBase {

    // ==================== 2a: Text selector ====================

    @Test
    @DisplayName("text= finds an element by exact visible text")
    void textSelectorExactMatch() {
        navigateTo("text-content.html");
        BoundingBox box = page.findByText("Hello, World!");
        assertNotNull(box, "Should find element with exact text");
        assertTrue(box.isValid());
    }

    @Test
    @DisplayName("text/i= finds an element by case-insensitive substring")
    void textSelectorCaseInsensitive() {
        navigateTo("text-content.html");
        BoundingBox box = page.findByText("hello, world!", false);
        assertNotNull(box, "Should find element with case-insensitive text");
    }

    @Test
    @DisplayName("text= returns null for text that does not exist")
    void textSelectorNoMatch() {
        navigateTo("text-content.html");
        BoundingBox box = page.findByText("This text does not exist on the page");
        assertNull(box);
    }

    // ==================== 2b: Role selector ====================

    @Test
    @DisplayName("role=button finds a <button> element")
    void roleSelectorFindsButton() {
        navigateTo("forms.html");
        BoundingBox box = page.findByRole("button");
        assertNotNull(box, "Should find a button element");
    }

    @Test
    @DisplayName("role=button with name filter finds the matching button")
    void roleSelectorFindsButtonByName() {
        navigateTo("forms.html");
        BoundingBox box = page.findByRole("button", "Submit");
        assertNotNull(box, "Should find the Submit button");
    }

    @Test
    @DisplayName("role=checkbox finds checkbox inputs")
    void roleSelectorFindsCheckbox() {
        navigateTo("forms.html");
        BoundingBox box = page.findByRole("checkbox");
        assertNotNull(box, "Should find a checkbox element");
    }

    @Test
    @DisplayName("role=textbox finds text inputs")
    void roleSelectorFindsTextbox() {
        navigateTo("forms.html");
        BoundingBox box = page.findByRole("textbox");
        assertNotNull(box, "Should find a textbox element");
    }

    // ==================== 2c: Shadow-piercing CSS ====================

    @Test
    @DisplayName("querySelectorPiercing finds element inside open shadow root")
    void shadowPiercingFindsShadowElement() {
        navigateTo("shadow-dom.html");
        // Normal querySelector cannot see inside shadow roots
        BoundingBox normal = page.querySelector("#shadow-button", 0);
        assertNull(normal, "Normal querySelector should not find shadow element");

        // Shadow-piercing should find it
        BoundingBox piercing = page.querySelectorPiercing("#shadow-button");
        assertNotNull(piercing, "Shadow-piercing should find element inside open shadow root");
        assertTrue(piercing.isValid());
    }

    // ==================== 2d: Chained >> selectors ====================

    @Test
    @DisplayName("chained selector narrows scope correctly")
    void chainedSelectorNarrowsScope() {
        navigateTo("forms.html");
        // Find the submit button inside the form
        BoundingBox box = page.find("#test-form >> role=button");
        assertNotNull(box, "Chained selector should find button inside form");
    }

    @Test
    @DisplayName("chained selector with text narrows scope")
    void chainedSelectorWithText() {
        navigateTo("text-content.html");
        // Find "World" text inside the nested-text paragraph
        BoundingBox box = page.find("#nested-text >> text=World");
        assertNotNull(box, "Should find text within narrowed scope");
    }

    // ==================== 2e: Transparent routing ====================

    @Test
    @DisplayName("page.click with text= selector works transparently")
    void clickWithTextSelector() {
        navigateTo("overlays.html");
        page.click("text=Click Me");
        assertEquals("clear-btn", page.evaluate("window.getLastClicked()"),
                "Text selector should have clicked the 'Click Me' button");
    }

    @Test
    @DisplayName("page.click with role= selector works transparently")
    void clickWithRoleSelector() {
        navigateTo("forms.html");
        // Click the Submit button via role selector
        assertDoesNotThrow(() -> page.click("role=button[name='Submit']"));
    }

    @Test
    @DisplayName("regular CSS selectors still work through routing")
    void cssSelectorsNotBroken() {
        navigateTo("forms.html");
        BoundingBox box = page.querySelector("#username");
        assertNotNull(box, "CSS selector should still work");
        assertTrue(page.exists("#submit-btn"), "exists() should work with CSS");
    }

    @Test
    @DisplayName("regular XPath selectors still work through routing")
    void xpathSelectorsNotBroken() {
        navigateTo("forms.html");
        BoundingBox box = page.querySelector("//button[@id='submit-btn']");
        assertNotNull(box, "XPath selector should still work");
    }
}
