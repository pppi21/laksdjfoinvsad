package org.nodriver4j.services.exceptions;

/**
 * Exception thrown when AutoSolve AI service operations fail.
 *
 * <p>This exception is thrown when:</p>
 * <ul>
 *   <li>API request fails (network error, timeout)</li>
 *   <li>API returns an error response</li>
 *   <li>Response parsing fails</li>
 *   <li>Authentication fails (invalid API key)</li>
 * </ul>
 *
 * @see org.nodriver4j.services.AutoSolveAIService
 */
public class AutoSolveAIException extends Exception {

    /**
     * Constructs an exception with no detail message.
     */
    public AutoSolveAIException() {
        super();
    }

    /**
     * Constructs an exception with the specified detail message.
     *
     * @param message the detail message
     */
    public AutoSolveAIException(String message) {
        super(message);
    }

    /**
     * Constructs an exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public AutoSolveAIException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs an exception with the specified cause.
     *
     * @param cause the underlying cause
     */
    public AutoSolveAIException(Throwable cause) {
        super(cause);
    }
}