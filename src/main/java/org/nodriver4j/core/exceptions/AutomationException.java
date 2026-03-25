package org.nodriver4j.core.exceptions;

/**
 * Base unchecked exception for all browser automation failures.
 *
 * <p>Subtypes represent specific failure categories:</p>
 * <ul>
 *   <li>{@link ElementNotFoundException} — selector matched nothing</li>
 *   <li>{@link ElementNotInteractableException} — element found but cannot be acted on</li>
 *   <li>{@link NavigationException} — page load or navigation failure</li>
 *   <li>{@link FrameException} — iframe attachment or context failure</li>
 *   <li>{@link ScriptExecutionException} — JavaScript evaluation failure</li>
 * </ul>
 */
public class AutomationException extends RuntimeException {

    public AutomationException(String message) {
        super(message);
    }

    public AutomationException(String message, Throwable cause) {
        super(message, cause);
    }
}
