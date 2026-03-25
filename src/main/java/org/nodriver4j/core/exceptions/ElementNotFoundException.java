package org.nodriver4j.core.exceptions;

/**
 * Thrown when a selector matches no elements on the page.
 *
 * <p>Replaces {@link java.util.concurrent.TimeoutException} for element lookup
 * failures. The {@link #selector()} accessor provides the selector that was used,
 * enabling scripts to produce clear diagnostic messages.</p>
 */
public class ElementNotFoundException extends AutomationException {

    private final String selector;

    public ElementNotFoundException(String selector) {
        super("Element not found: " + selector);
        this.selector = selector;
    }

    public ElementNotFoundException(String message, String selector) {
        super(message);
        this.selector = selector;
    }

    public ElementNotFoundException(String message, String selector, Throwable cause) {
        super(message, cause);
        this.selector = selector;
    }

    public String selector() {
        return selector;
    }
}
