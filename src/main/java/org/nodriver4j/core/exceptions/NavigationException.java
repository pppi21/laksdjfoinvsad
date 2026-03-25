package org.nodriver4j.core.exceptions;

/**
 * Thrown when page navigation or load-state waiting fails.
 *
 * <p>Covers failures in {@code navigate()}, {@code waitForNavigation()},
 * {@code waitForNetworkIdle()}, and related operations.</p>
 */
public class NavigationException extends AutomationException {

    public NavigationException(String message) {
        super(message);
    }

    public NavigationException(String message, Throwable cause) {
        super(message, cause);
    }
}
