package org.nodriver4j.services.captcha.twocaptcha;

/**
 * Exception thrown when a 2Captcha API operation fails.
 *
 * <p>This includes network errors, authentication failures, task timeouts,
 * insufficient balance, and any error codes returned by the 2Captcha API.</p>
 *
 * @see TwoCaptchaService
 */
public class TwoCaptchaException extends Exception {

    public TwoCaptchaException(String message) {
        super(message);
    }

    public TwoCaptchaException(String message, Throwable cause) {
        super(message, cause);
    }
}