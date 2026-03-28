package org.nodriver4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nodriver4j.core.exceptions.ElementNotInteractableException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Actionability — element state checks and pre-action validation")
class ActionabilityTest extends BrowserTestBase {

    // ==================== checkState: visible ====================

    @Test
    @DisplayName("visible returns true for a normally visible element")
    void visibleTrueForVisibleElement() {
        navigateTo("visibility.html");
        assertTrue(page.checkState("#visible-element", "visible"));
    }

    @Test
    @DisplayName("visible returns false for display:none")
    void visibleFalseForDisplayNone() {
        navigateTo("visibility.html");
        assertFalse(page.checkState("#display-none", "visible"));
    }

    @Test
    @DisplayName("visible returns false for visibility:hidden")
    void visibleFalseForVisibilityHidden() {
        navigateTo("visibility.html");
        assertFalse(page.checkState("#visibility-hidden", "visible"));
    }

    @Test
    @DisplayName("visible returns false for opacity:0")
    void visibleFalseForOpacityZero() {
        navigateTo("visibility.html");
        assertFalse(page.checkState("#opacity-zero", "visible"));
    }

    @Test
    @DisplayName("visible returns false for zero-size element")
    void visibleFalseForZeroSize() {
        navigateTo("visibility.html");
        assertFalse(page.checkState("#zero-size", "visible"));
    }

    // ==================== checkState: enabled / disabled ====================

    @Test
    @DisplayName("enabled returns true for a normal button")
    void enabledTrueForNormalButton() {
        navigateTo("visibility.html");
        assertTrue(page.checkState("#enabled-button", "enabled"));
    }

    @Test
    @DisplayName("enabled returns false for a disabled button")
    void enabledFalseForDisabledButton() {
        navigateTo("visibility.html");
        assertFalse(page.checkState("#disabled-button", "enabled"));
    }

    @Test
    @DisplayName("enabled returns false for a button inside a disabled fieldset")
    void enabledFalseForFieldsetDisabledButton() {
        navigateTo("visibility.html");
        assertFalse(page.checkState("#fieldset-disabled-button", "enabled"));
    }

    @Test
    @DisplayName("enabled returns false for an element with aria-disabled=true")
    void enabledFalseForAriaDisabled() {
        navigateTo("visibility.html");
        assertFalse(page.checkState("#aria-disabled-button", "enabled"));
    }

    // ==================== checkState: editable ====================

    @Test
    @DisplayName("editable returns true for a normal input")
    void editableTrueForNormalInput() {
        navigateTo("visibility.html");
        assertTrue(page.checkState("#enabled-input", "editable"));
    }

    @Test
    @DisplayName("editable returns false for a readonly input")
    void editableFalseForReadonlyInput() {
        navigateTo("visibility.html");
        assertFalse(page.checkState("#readonly-input", "editable"));
    }

    @Test
    @DisplayName("editable returns false for a disabled input")
    void editableFalseForDisabledInput() {
        navigateTo("visibility.html");
        assertFalse(page.checkState("#disabled-input", "editable"));
    }

    // ==================== checkState: stable ====================

    @Test
    @DisplayName("stable returns true for a static element")
    void stableTrueForStaticElement() {
        navigateTo("visibility.html");
        assertTrue(page.checkState("#visible-element", "stable"));
    }

    // ==================== checkState: checked / unchecked ====================

    @Test
    @DisplayName("checked returns false and unchecked returns true for an unchecked checkbox")
    void checkedStateForUncheckedCheckbox() {
        navigateTo("forms.html");
        assertFalse(page.checkState("#agree", "checked"));
        assertTrue(page.checkState("#agree", "unchecked"));
    }

    @Test
    @DisplayName("checked returns true and unchecked returns false for a checked checkbox")
    void checkedStateForCheckedCheckbox() {
        navigateTo("forms.html");
        assertTrue(page.checkState("#newsletter", "checked"));
        assertFalse(page.checkState("#newsletter", "unchecked"));
    }

    // ==================== waitForActionable ====================

    @Test
    @DisplayName("waitForActionable succeeds on a visible and enabled element")
    void waitForActionableSucceedsOnVisibleEnabledElement() {
        navigateTo("overlays.html");
        assertDoesNotThrow(() ->
                page.actionability().waitForActionable("#clear-btn",
                        new String[]{"visible", "enabled"}, 5000));
    }

    @Test
    @DisplayName("waitForActionable throws on a disabled element")
    void waitForActionableThrowsOnDisabledElement() {
        navigateTo("visibility.html");
        assertThrows(ElementNotInteractableException.class, () ->
                page.actionability().waitForActionable("#disabled-button",
                        new String[]{"visible", "enabled"}, 2000));
    }

    @Test
    @DisplayName("waitForActionable throws on a hidden element")
    void waitForActionableThrowsOnHiddenElement() {
        navigateTo("visibility.html");
        assertThrows(ElementNotInteractableException.class, () ->
                page.actionability().waitForActionable("#display-none",
                        new String[]{"visible"}, 2000));
    }

    // ==================== Hit target verification ====================

    @Test
    @DisplayName("click behind overlay with force=false throws ElementNotInteractableException")
    void clickBehindOverlayThrows() {
        navigateTo("overlays.html");
        assertThrows(ElementNotInteractableException.class, () ->
                page.click("#modal-btn"));
    }

    @Test
    @DisplayName("click behind overlay with force=true does not throw")
    void clickBehindOverlayForceSucceeds() {
        navigateTo("overlays.html");
        assertDoesNotThrow(() -> page.click("#modal-btn", true));
    }

    // ==================== Smart retargeting ====================

    @Test
    @DisplayName("clicking text inside a button triggers the button click handler")
    void clickTextInsideButtonRetargets() {
        navigateTo("overlays.html");
        page.click("#retarget-text");
        assertEquals("retarget-btn", page.evaluate("window.getLastClicked()"));
    }
}
