package org.nodriver4j.core.exceptions;

/**
 * Thrown when an element is found but cannot be interacted with.
 *
 * <p>Common causes: element is disabled, obscured by another element,
 * currently animating, or otherwise not in an actionable state.</p>
 */
public class ElementNotInteractableException extends AutomationException {

    private final String selector;

    public ElementNotInteractableException(String selector) {
        super("Element not interactable: " + selector);
        this.selector = selector;
    }

    public ElementNotInteractableException(String message, String selector) {
        super(message);
        this.selector = selector;
    }

    public ElementNotInteractableException(String message, String selector, Throwable cause) {
        super(message, cause);
        this.selector = selector;
    }

    public String selector() {
        return selector;
    }
}
