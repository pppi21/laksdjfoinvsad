package org.nodriver4j.services;

import org.nodriver4j.services.exceptions.SmsProviderException;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Abstract base for SMS verification services that rent a phone number,
 * poll for an incoming OTP code, and report completion or cancellation.
 *
 * <p>Subclasses implement the provider-specific HTTP calls — this class
 * handles the polling loop, timeout enforcement, phone number
 * normalization, shared {@link HttpClient} lifecycle, and cooperative
 * cancellation via {@link TaskContext}.</p>
 *
 * <h2>Provider Lifecycle</h2>
 * <ol>
 *   <li>{@link #requestNumber(SmsService)} — rents a number from the provider</li>
 *   <li>{@link #pollForCode(SmsActivation)} or
 *       {@link #pollForCode(SmsActivation, Duration)} — blocks until an OTP
 *       arrives or the timeout expires</li>
 *   <li>{@link #completeActivation(String)} — marks the activation as done</li>
 *   <li>{@link #cancelActivation(String)} — cancels without completing</li>
 * </ol>
 *
 * <h2>Cancellation</h2>
 * <p>The polling loop checks {@link TaskContext#checkCancelled()} on every
 * iteration. When the user clicks Stop, {@link TaskContext#cancel()} fires,
 * which also closes this service (via {@link AutoCloseable} registration).
 * The {@link #close()} implementation shuts down the shared
 * {@link HttpClient}, causing any in-flight HTTP request to fail
 * immediately with an {@code IOException} — unblocking the polling thread
 * without waiting for the next sleep cycle.</p>
 *
 * <h2>Phone Number Normalization</h2>
 * <p>All phone numbers returned by {@link #requestNumber} are normalized
 * via {@link #normalizePhoneNumber(String)} to strip the US country code
 * ({@code +1} or {@code 1} prefix), leaving a bare 10-digit number
 * suitable for direct entry into web forms.</p>
 *
 * <h2>Usage in Scripts</h2>
 * <pre>{@code
 * try (SmsManService sms = context.register(new SmsManService(apiKey, context))) {
 *     SmsActivation activation = sms.requestNumber(SmsService.UBER);
 *     // activation.phoneNumber() → "2125551234" (normalized, no +1)
 *
 *     fillFormField(PHONE_TEXT, activation.phoneNumber(), true);
 *
 *     String code = sms.pollForCode(activation);
 *     fillFormField(OTP_TEXT, code, false);
 *
 *     sms.completeActivation(activation.activationId());
 * }
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Shared {@link HttpClient} lifecycle</li>
 *   <li>Polling loop with timeout and cancellation</li>
 *   <li>Phone number normalization (strip US +1)</li>
 *   <li>Common timing defaults</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Provider-specific API calls (subclasses)</li>
 *   <li>API key storage or management ({@link org.nodriver4j.persistence.Settings})</li>
 *   <li>Task status updates ({@link TaskExecutionService})</li>
 *   <li>Logging to UI ({@link TaskLogger})</li>
 *   <li>Browser lifecycle ({@link TaskExecutionService})</li>
 * </ul>
 *
 * @see SmsActivation
 * @see SmsService
 * @see SmsProviderException
 * @see TaskContext
 */
public abstract class SmsServiceBase implements AutoCloseable {

    // ==================== Timing Defaults ====================

    /** Default maximum time to wait for an OTP code. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /**
     * Default interval between poll attempts.
     *
     * <p>DaisySMS requires ≥3s between polls. SMS-Man and TextVerified
     * have similar rate expectations. 4s provides headroom across all
     * providers without adding noticeable latency.</p>
     */
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(4);

    /** Connection timeout for individual HTTP requests. */
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(15);

    // ==================== Fields ====================

    /**
     * Shared HTTP client used for all provider API calls. Shut down on
     * {@link #close()} to cancel any in-flight requests immediately.
     */
    protected final HttpClient httpClient;

    /**
     * Task context for cooperative cancellation. Checked on every poll
     * iteration. May be {@code null} for standalone/testing use — in
     * which case cancellation checks are skipped.
     */
    protected final TaskContext context;

    /** How often to poll for the OTP code. */
    private final Duration pollInterval;

    // ==================== Constructor ====================

    /**
     * Creates a service with a custom poll interval.
     *
     * @param context      the task context for cancellation (may be null for testing)
     * @param pollInterval how often to poll for OTP codes
     * @throws IllegalArgumentException if pollInterval is null or not positive
     */
    protected SmsServiceBase(TaskContext context, Duration pollInterval) {
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("Poll interval must be a positive duration");
        }

        this.context = context;
        this.pollInterval = pollInterval;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Creates a service with the default poll interval (4s).
     *
     * @param context the task context for cancellation (may be null for testing)
     */
    protected SmsServiceBase(TaskContext context) {
        this(context, DEFAULT_POLL_INTERVAL);
    }

    // ==================== Abstract Methods ====================

    /**
     * Rents a phone number from the provider for the specified service.
     *
     * <p>Implementations must normalize the phone number via
     * {@link #normalizePhoneNumber(String)} before constructing the
     * returned {@link SmsActivation}.</p>
     *
     * @param service the target service (e.g., {@link SmsService#UBER})
     * @return an activation containing the rented number and activation ID
     * @throws SmsProviderException if the provider rejects the request
     *                              (insufficient balance, no numbers, etc.)
     */
    public abstract SmsActivation requestNumber(SmsService service) throws SmsProviderException;

    /**
     * Checks the provider for an OTP code on the given activation.
     *
     * <p>Called by the polling loop on every iteration. Implementations
     * should return {@code null} if the code has not arrived yet, or the
     * OTP string if it has. Throwing {@link SmsProviderException} indicates
     * a permanent failure (e.g., activation cancelled server-side).</p>
     *
     * @param activationId the provider-specific activation ID
     * @return the OTP code, or {@code null} if not yet received
     * @throws SmsProviderException if the activation is in a terminal error state
     */
    protected abstract String checkForCode(String activationId) throws SmsProviderException;

    /**
     * Marks an activation as successfully completed.
     *
     * <p>Called after the OTP has been used. Tells the provider the number
     * is no longer needed and the transaction was successful.</p>
     *
     * @param activationId the provider-specific activation ID
     * @throws SmsProviderException if the completion request fails
     */
    public abstract void completeActivation(String activationId) throws SmsProviderException;

    /**
     * Cancels an activation without completing it.
     *
     * <p>Called when the number is no longer needed (e.g., the script
     * failed before using the OTP). Some providers refund the charge
     * on cancellation.</p>
     *
     * @param activationId the provider-specific activation ID
     * @throws SmsProviderException if the cancellation request fails
     */
    public abstract void cancelActivation(String activationId) throws SmsProviderException;

    /**
     * Returns a human-readable name for this provider, used in log messages.
     *
     * @return the provider name (e.g., "SMS-Man", "DaisySMS", "TextVerified")
     */
    public abstract String providerName();

    // ==================== Polling ====================

    /**
     * Polls for an OTP code using the default timeout (120s).
     *
     * @param activation the activation to poll
     * @return the OTP code
     * @throws SmsProviderException if polling fails or the activation enters an error state
     * @throws InterruptedException if the task is cancelled via {@link TaskContext}
     */
    public String pollForCode(SmsActivation activation) throws SmsProviderException, InterruptedException {
        return pollForCode(activation, DEFAULT_TIMEOUT);
    }

    /**
     * Polls the provider for an OTP code, blocking until it arrives or the
     * timeout expires.
     *
     * <p>On each iteration:</p>
     * <ol>
     *   <li>Checks {@link TaskContext#checkCancelled()} (if context is non-null)</li>
     *   <li>Calls {@link #checkForCode(String)} — returns immediately on non-null</li>
     *   <li>Sleeps for the configured poll interval (or remaining time, whichever is shorter)</li>
     * </ol>
     *
     * <p>Transient HTTP errors during individual poll attempts are caught
     * and logged — polling continues. Only permanent provider errors
     * (thrown as {@link SmsProviderException}) abort the loop.</p>
     *
     * @param activation the activation to poll
     * @param timeout    maximum time to wait for the code
     * @return the OTP code
     * @throws SmsProviderException     if the activation enters a terminal error state
     * @throws InterruptedException     if the task is cancelled
     * @throws IllegalArgumentException if timeout is null or not positive
     */
    public String pollForCode(SmsActivation activation, Duration timeout)
            throws SmsProviderException, InterruptedException {

        if (activation == null) {
            throw new IllegalArgumentException("Activation cannot be null");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be a positive duration");
        }

        Instant deadline = Instant.now().plus(timeout);
        int pollCount = 0;

        while (Instant.now().isBefore(deadline)) {
            checkCancelled();
            pollCount++;

            try {
                String code = checkForCode(activation.activationId());
                if (code != null && !code.isBlank()) {
                    return code;
                }
            } catch (SmsProviderException e) {
                // Permanent provider error — abort immediately
                throw e;
            } catch (Exception e) {
                // Transient error (network timeout, etc.) — log and retry
                System.err.println("[" + providerName() + "] Poll #" + pollCount +
                        " failed: " + e.getMessage());
            }

            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.compareTo(pollInterval) > 0) {
                sleep(pollInterval);
            } else if (!remaining.isNegative() && !remaining.isZero()) {
                sleep(remaining);
            }
        }

        throw new SmsProviderException(
                "Timeout: No OTP received within " + timeout.toSeconds() + " seconds " +
                        "after " + pollCount + " polls (" + providerName() + ")"
        );
    }

    // ==================== Cancellation ====================

    /**
     * Checks if the task has been cancelled, throwing if so.
     *
     * <p>No-op if the context is {@code null} (standalone/testing mode).</p>
     *
     * @throws InterruptedException if the task has been cancelled
     */
    protected void checkCancelled() throws InterruptedException {
        if (context != null) {
            context.checkCancelled();
        }
    }

    // ==================== Phone Number Normalization ====================

    /**
     * Normalizes a US phone number by stripping the country code prefix.
     *
     * <p>Handles the following input formats:</p>
     * <ul>
     *   <li>{@code "+12125551234"} → {@code "2125551234"}</li>
     *   <li>{@code "12125551234"} → {@code "2125551234"}</li>
     *   <li>{@code "2125551234"} → {@code "2125551234"} (no change)</li>
     * </ul>
     *
     * <p>Only strips the prefix when the resulting number is exactly
     * 10 digits, preventing incorrect truncation of non-US numbers
     * or malformed inputs.</p>
     *
     * @param phoneNumber the raw phone number from the provider
     * @return the normalized number without country code
     */
    protected static String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null) {
            return "";
        }

        String digits = phoneNumber.replaceAll("[^0-9]", "");

        // Strip leading "1" if it results in a valid 10-digit US number
        if (digits.length() == 11 && digits.startsWith("1")) {
            digits = digits.substring(1);
        }

        return digits;
    }

    // ==================== HTTP Helpers ====================

    /**
     * Validates that an HTTP response has a successful status code (2xx).
     *
     * @param response    the HTTP response to check
     * @param description a short description of the request for error messages
     * @throws SmsProviderException if the status code is not 2xx
     */
    protected void ensureSuccess(HttpResponse<String> response, String description)
            throws SmsProviderException {

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new SmsProviderException(
                    providerName() + " " + description + " failed (HTTP " + status + "): " +
                            truncateBody(response.body())
            );
        }
    }

    /**
     * Truncates a response body to a reasonable length for error messages.
     *
     * @param body the response body
     * @return the truncated body (max 200 chars)
     */
    private static String truncateBody(String body) {
        if (body == null) {
            return "<empty>";
        }
        if (body.length() <= 200) {
            return body;
        }
        return body.substring(0, 200) + "...";
    }

    // ==================== Utility ====================

    /**
     * Sleeps for the specified duration, respecting thread interruption.
     *
     * @param duration the duration to sleep
     * @throws InterruptedException if the thread is interrupted during sleep
     */
    protected void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    // ==================== AutoCloseable ====================

    /**
     * Shuts down the shared {@link HttpClient}.
     *
     * <p>Forces any in-flight HTTP request to fail immediately, which
     * unblocks the polling thread without waiting for the next sleep
     * cycle. Safe to call multiple times.</p>
     */
    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (Exception e) {
            System.err.println("[" + providerName() + "] Error closing HttpClient: " + e.getMessage());
        }
    }

    // ==================== Accessors ====================

    /**
     * Returns the configured poll interval.
     *
     * @return the poll interval
     */
    public Duration pollInterval() {
        return pollInterval;
    }

    @Override
    public String toString() {
        return String.format("%s{pollInterval=%ss}", providerName(), pollInterval.toSeconds());
    }
}