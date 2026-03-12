package org.nodriver4j.services.exceptions;

import org.nodriver4j.services.SmsActivation;
import org.nodriver4j.services.SmsServiceBase;

/**
 * Checked exception thrown when an SMS verification provider operation fails.
 *
 * <p>Analogous to {@link GmailClient.GmailClientException} for the email
 * subsystem. Used for both transient failures (network errors, rate limits)
 * and permanent failures (insufficient balance, activation cancelled
 * server-side, invalid API key).</p>
 *
 * <p>When thrown from {@link SmsServiceBase#checkForCode(String)}, the
 * polling loop treats it as a permanent error and aborts immediately.
 * Scripts should catch this at the top level to log and fail the task.</p>
 *
 * @see SmsServiceBase
 * @see SmsActivation
 */
public class SmsProviderException extends Exception {

  public SmsProviderException(String message) {
    super(message);
  }

  public SmsProviderException(String message, Throwable cause) {
    super(message, cause);
  }
}