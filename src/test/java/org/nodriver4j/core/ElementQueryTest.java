package org.nodriver4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nodriver4j.core.exceptions.ElementNotFoundException;
import org.nodriver4j.math.BoundingBox;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ElementQuery — element finding, state inspection, and waiting")
class ElementQueryTest extends BrowserTestBase {

    // ==================== Finding Elements ====================

    @Test
    @DisplayName("querySelector finds element by CSS selector")
    void findsByCssSelector() {
        navigateTo("forms.html");
        BoundingBox box = page.querySelector("#username");
        assertNotNull(box, "BoundingBox should be non-null for existing element");
        assertTrue(box.getWidth() > 0 && box.getHeight() > 0);
    }

    @Test
    @DisplayName("querySelector finds element by XPath selector")
    void findsByXPathSelector() {
        navigateTo("forms.html");
        BoundingBox box = page.querySelector("//input[@id='username']");
        assertNotNull(box, "BoundingBox should be non-null for XPath match");
        assertTrue(box.getWidth() > 0 && box.getHeight() > 0);
    }

    @Test
    @DisplayName("querySelector returns null for non-existent element")
    void returnsNullForMissingElement() {
        navigateTo("forms.html");
        BoundingBox box = page.querySelector("#nonexistent");
        assertNull(box, "Should return null when element does not exist");
    }

    // ==================== exists() ====================

    @Test
    @DisplayName("exists returns true for present element")
    void existsReturnsTrueForPresentElement() {
        navigateTo("forms.html");
        assertTrue(page.exists("#username"));
    }

    @Test
    @DisplayName("exists returns false for absent element")
    void existsReturnsFalseForAbsentElement() {
        navigateTo("forms.html");
        assertFalse(page.exists("#nonexistent"));
    }

    @Test
    @DisplayName("exists works with XPath selector")
    void existsWorksWithXPath() {
        navigateTo("forms.html");
        assertTrue(page.exists("//button[@id='submit-btn']"));
        assertFalse(page.exists("//button[@id='nonexistent']"));
    }

    // ==================== isVisible() ====================

    @Test
    @DisplayName("isVisible returns true for normally visible element")
    void isVisibleTrueForVisibleElement() {
        navigateTo("visibility.html");
        assertTrue(page.isVisible("#visible-element"));
    }

    @Test
    @DisplayName("isVisible returns false for display:none element")
    void isVisibleFalseForDisplayNone() {
        navigateTo("visibility.html");
        assertFalse(page.isVisible("#display-none"));
    }

    @Test
    @DisplayName("isVisible returns false for visibility:hidden element")
    void isVisibleFalseForVisibilityHidden() {
        navigateTo("visibility.html");
        assertFalse(page.isVisible("#visibility-hidden"));
    }

    @Test
    @DisplayName("isVisible returns false for opacity:0 element")
    void isVisibleFalseForOpacityZero() {
        navigateTo("visibility.html");
        assertFalse(page.isVisible("#opacity-zero"));
    }

    @Test
    @DisplayName("isVisible returns false for zero-dimension element")
    void isVisibleFalseForZeroDimensions() {
        navigateTo("visibility.html");
        assertFalse(page.isVisible("#zero-size"));
    }

    @Test
    @DisplayName("isVisible returns false for non-existent element")
    void isVisibleFalseForNonExistent() {
        navigateTo("visibility.html");
        assertFalse(page.isVisible("#nonexistent"));
    }

    // ==================== getText() ====================

    @Test
    @DisplayName("getText returns simple text content")
    void getTextReturnsSimpleContent() {
        navigateTo("text-content.html");
        assertEquals("Hello, World!", page.getText("#simple-text"));
    }

    @Test
    @DisplayName("getText returns combined text from nested elements")
    void getTextReturnsNestedContent() {
        navigateTo("text-content.html");
        // innerText of <p>Hello <span>World</span></p>
        assertEquals("Hello World", page.getText("#nested-text"));
    }

    @Test
    @DisplayName("getText returns null for non-existent element")
    void getTextReturnsNullForMissing() {
        navigateTo("text-content.html");
        assertNull(page.getText("#nonexistent"));
    }

    @Test
    @DisplayName("getText returns empty string for empty element")
    void getTextReturnsEmptyForEmptyDiv() {
        navigateTo("text-content.html");
        assertEquals("", page.getText("#empty-div"));
    }

    // ==================== getAttribute() ====================

    @Test
    @DisplayName("getAttribute reads data-* attributes")
    void getAttributeReadsDataAttributes() {
        navigateTo("text-content.html");
        assertEquals("test-item", page.getAttribute("#data-element", "data-name"));
        assertEquals("42", page.getAttribute("#data-element", "data-value"));
        assertEquals("automation", page.getAttribute("#data-element", "data-category"));
    }

    @Test
    @DisplayName("getAttribute reads empty data attribute")
    void getAttributeReadsEmptyAttribute() {
        navigateTo("text-content.html");
        assertEquals("", page.getAttribute("#data-element", "data-empty"));
    }

    @Test
    @DisplayName("getAttribute reads standard attributes")
    void getAttributeReadsStandardAttributes() {
        navigateTo("text-content.html");
        assertEquals("https://example.com", page.getAttribute("#attr-link", "href"));
        assertEquals("Example Link", page.getAttribute("#attr-link", "title"));
    }

    @Test
    @DisplayName("getAttribute returns null for missing attribute")
    void getAttributeReturnsNullForMissing() {
        navigateTo("text-content.html");
        assertNull(page.getAttribute("#data-element", "data-nonexistent"));
    }

    // ==================== getValue() ====================

    @Test
    @DisplayName("getValue reads pre-filled text input value")
    void getValueReadsPrefilled() {
        navigateTo("text-content.html");
        assertEquals("prefilled text", page.getValue("#value-text"));
    }

    @Test
    @DisplayName("getValue reads number input value")
    void getValueReadsNumberInput() {
        navigateTo("text-content.html");
        assertEquals("42", page.getValue("#value-number"));
    }

    @Test
    @DisplayName("getValue reads empty input value")
    void getValueReadsEmptyInput() {
        navigateTo("text-content.html");
        assertEquals("", page.getValue("#value-empty"));
    }

    @Test
    @DisplayName("getValue reads selected option value")
    void getValueReadsSelectedOption() {
        navigateTo("text-content.html");
        // <option value="opt-b" selected>
        assertEquals("opt-b", page.getValue("#value-select"));
    }

    @Test
    @DisplayName("getValue reads textarea content")
    void getValueReadsTextarea() {
        navigateTo("text-content.html");
        assertEquals("Textarea content here", page.getValue("#value-textarea"));
    }

    // ==================== waitForSelector() ====================

    @Test
    @DisplayName("waitForSelector waits for a delayed element to appear")
    void waitForSelectorSucceedsOnDelayedElement() {
        navigateTo("delayed.html");
        // #delayed-element is injected after 2 seconds
        BoundingBox box = page.waitForSelector("#delayed-element", 5000);
        assertNotNull(box);
        assertTrue(page.exists("#delayed-element"));
    }

    @Test
    @DisplayName("waitForSelector throws ElementNotFoundException on timeout")
    void waitForSelectorThrowsOnTimeout() {
        navigateTo("delayed.html");
        assertThrows(ElementNotFoundException.class,
                () -> page.waitForSelector("#never-exists", 1000));
    }

    // ==================== waitForSelectorHidden() ====================

    @Test
    @DisplayName("waitForSelectorHidden waits for element removal from DOM")
    void waitForSelectorHiddenSucceeds() {
        navigateTo("delayed.html");
        // #disappearing is removed from DOM after 2 seconds
        assertTrue(page.exists("#disappearing"), "Element should exist initially");
        page.waitForSelectorHidden("#disappearing", 5000);
        assertFalse(page.exists("#disappearing"), "Element should be gone after waiting");
    }

    // ==================== waitForVisible() ====================

    @Test
    @DisplayName("waitForVisible waits for a hidden element to become visible")
    void waitForVisibleSucceeds() {
        navigateTo("delayed.html");
        // #initially-hidden has display:none, shown after 1 second
        assertFalse(page.isVisible("#initially-hidden"), "Should be hidden initially");
        page.waitForVisible("#initially-hidden", 5000);
        assertTrue(page.isVisible("#initially-hidden"), "Should be visible after waiting");
    }

    // ==================== containsText / containsTextTrimmed ====================

    @Test
    @DisplayName("containsText matches exact text content")
    void containsTextExactMatch() {
        navigateTo("text-content.html");
        assertTrue(page.containsText("#simple-text", "Hello, World!"));
    }

    @Test
    @DisplayName("containsText rejects partial text (exact match required)")
    void containsTextRejectsPartialMatch() {
        navigateTo("text-content.html");
        // containsText does an exact equals comparison, not substring
        assertFalse(page.containsText("#simple-text", "Hello"));
    }

    @Test
    @DisplayName("containsTextTrimmed matches after trimming whitespace")
    void containsTextTrimmedIgnoresWhitespace() {
        navigateTo("text-content.html");
        // <pre> preserves whitespace: "  Leading and trailing spaces  "
        assertTrue(page.containsTextTrimmed("#pre-whitespace", "Leading and trailing spaces"));
    }

    @Test
    @DisplayName("containsText returns false for non-existent element")
    void containsTextFalseForNonExistent() {
        navigateTo("text-content.html");
        assertFalse(page.containsText("#nonexistent", "anything"));
    }

    // ==================== querySelectorAll() ====================

    @Test
    @DisplayName("querySelectorAll returns all matching elements")
    void querySelectorAllReturnsMultiple() {
        navigateTo("text-content.html");
        List<BoundingBox> items = page.querySelectorAll(".list-item");
        assertEquals(5, items.size(), "Should find all 5 list items");
        for (BoundingBox box : items) {
            assertNotNull(box);
            assertTrue(box.getWidth() > 0 && box.getHeight() > 0);
        }
    }

    @Test
    @DisplayName("querySelectorAll returns empty list when nothing matches")
    void querySelectorAllReturnsEmptyForNoMatch() {
        navigateTo("text-content.html");
        List<BoundingBox> items = page.querySelectorAll(".nonexistent-class");
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }
}
