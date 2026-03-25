package org.nodriver4j.core.exceptions;

/**
 * Thrown when JavaScript evaluation via CDP fails.
 *
 * <p>Wraps errors returned by {@code Runtime.evaluate} and related
 * CDP commands. The {@link #getCause() cause} typically contains the
 * original transport-level exception.</p>
 */
public class ScriptExecutionException extends AutomationException {

    public ScriptExecutionException(String message) {
        super(message);
    }

    public ScriptExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
