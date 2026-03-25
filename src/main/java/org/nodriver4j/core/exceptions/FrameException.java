package org.nodriver4j.core.exceptions;

/**
 * Thrown when iframe attachment, context creation, or cross-frame
 * operations fail.
 *
 * <p>Covers failures in {@code getIframeInfo()}, {@code createIframeContext()},
 * {@code evaluateInFrame()}, and OOPIF attachment.</p>
 */
public class FrameException extends AutomationException {

    public FrameException(String message) {
        super(message);
    }

    public FrameException(String message, Throwable cause) {
        super(message, cause);
    }
}
