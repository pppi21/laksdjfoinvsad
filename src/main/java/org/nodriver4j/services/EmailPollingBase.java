package org.nodriver4j.services;

import org.nodriver4j.services.GmailClient.EmailMessage;
import org.nodriver4j.services.GmailClient.EmailSearchCriteria;
import org.nodriver4j.services.GmailClient.GmailClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Abstract base for polling a Gmail inbox and extracting a typed result
 * from the first matching email.
 *
 * <p>Subclasses define <strong>what</strong> to search for
 * ({@link #buildCriteria}) and <strong>how</strong> to extract a result
 * ({@link #extractFromEmail}). This class handles the polling loop,
 * timeout enforcement, shared connection lifecycle, and configuration.</p>
 *
 * <h2>Connection Lifecycle</h2>
 * <p>Instances accept IMAP credentials at construction time but do not
 * open a connection immediately. On the first {@link #poll()} call, a
 * shared {@link GmailClient} is acquired via
 * {@link GmailClient#shared(String, String)}. The shared instance is
 * reference-counted and may be reused across multiple extractors that
 * share the same catchall account. Call {@link #close()} (or use
 * try-with-resources) to release the shared connection when finished.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (MyExtractor extractor = new MyExtractor("user@gmail.com", "catchall@gmail.com", "app-pass")) {
 *     String result = extractor.poll();
 * }
 * }</pre>
 *
 * @param <T> the type of result extracted from an email (e.g. OTP string, URL)
 * @see GmailClient
 */
public abstract class EmailPollingBase<T> implements AutoCloseable {

    // ==================== Timing Defaults ====================

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);

    // ==================== Fields ====================

    private final String email;
    private final String catchallEmail;
    private final String appPassword;
    private final Duration pollInterval;

    /**
     * Lazily acquired shared GmailClient. Set on the first {@link #poll()} call,
     * released on {@link #close()}.
     */
    private GmailClient gmailClient;

    // ==================== Constructors ====================

    /**
     * Creates an extractor with default settings (5s poll interval).
     *
     * @param email         the profile email address (used for recipient matching)
     * @param catchallEmail the catchall email for IMAP authentication
     * @param appPassword   the app password for IMAP authentication
     * @throws IllegalArgumentException if any argument is null or blank
     */
    protected EmailPollingBase(String email, String catchallEmail, String appPassword) {
        this(email, catchallEmail, appPassword, DEFAULT_POLL_INTERVAL);
    }

    /**
     * Creates an extractor with a custom poll interval.
     *
     * @param email         the profile email address (used for recipient matching)
     * @param catchallEmail the catchall email for IMAP authentication
     * @param appPassword   the app password for IMAP authentication
     * @param pollInterval  how often to check for new emails
     * @throws IllegalArgumentException if any argument is null/blank or pollInterval is negative
     */
    protected EmailPollingBase(String email, String catchallEmail, String appPassword,
                               Duration pollInterval) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email cannot be null or blank");
        }
        if (catchallEmail == null || catchallEmail.isBlank()) {
            throw new IllegalArgumentException("Catchall email cannot be null or blank");
        }
        if (appPassword == null || appPassword.isBlank()) {
            throw new IllegalArgumentException("App password cannot be null or blank");
        }
        if (pollInterval == null || pollInterval.isNegative()) {
            throw new IllegalArgumentException("Poll interval must be a positive duration");
        }

        this.email = email;
        this.catchallEmail = catchallEmail;
        this.appPassword = appPassword;
        this.pollInterval = pollInterval;
    }

    // ==================== Abstract Methods ====================

    /**
     * Builds the search criteria for finding the target email.
     *
     * <p>Called on every poll iteration. Implementations should include
     * the {@code since} instant to avoid re-processing old emails.</p>
     *
     * @param since only consider emails received at or after this instant
     * @return the search criteria for this extractor
     */
    protected abstract EmailSearchCriteria buildCriteria(Instant since);

    /**
     * Extracts the desired result from a matching email.
     *
     * <p>Called once per candidate email, newest first. Return {@code null}
     * if this particular email does not contain a valid result (polling
     * will continue). Return a non-null value to stop polling and return
     * that value to the caller.</p>
     *
     * @param message the email to extract from
     * @return the extracted result, or {@code null} if not found in this email
     */
    protected abstract T extractFromEmail(EmailMessage message);

    /**
     * Returns a human-readable name for this extractor, used in log messages.
     *
     * @return the extractor name (e.g. "UberOtpExtractor", "FunkoVerificationExtractor")
     */
    protected abstract String extractorName();

    // ==================== Polling ====================

    /**
     * Polls for a matching email and extracts a result using the default timeout (60s).
     *
     * @return the extracted result
     * @throws EmailExtractionException if no result is found within the timeout
     * @throws GmailClientException     if connection fails
     */
    public T poll() throws EmailExtractionException, GmailClientException {
        return poll(DEFAULT_TIMEOUT);
    }

    /**
     * Polls for a matching email and extracts a result.
     *
     * <p>On the first call, acquires a shared {@link GmailClient} via
     * {@link GmailClient#shared(String, String)}. The shared connection
     * remains open until {@link #close()} is called.</p>
     *
     * <p>Records the current time (minus a small buffer) as the monitoring
     * start time. Only emails received after this point are considered,
     * preventing confusion with older emails.</p>
     *
     * @param timeout maximum time to wait for a matching email
     * @return the extracted result
     * @throws EmailExtractionException if no result is found within the timeout
     * @throws GmailClientException     if the shared connection fails to connect
     */
    public T poll(Duration timeout) throws EmailExtractionException, GmailClientException {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be a positive duration");
        }

        ensureConnected();

        Instant monitoringStartTime = Instant.now().minusSeconds(2);
        Instant deadline = Instant.now().plus(timeout);

        int pollCount = 0;

        while (Instant.now().isBefore(deadline)) {
            pollCount++;

            try {
                T result = pollOnce(monitoringStartTime);
                if (result != null) {
                    return result;
                }
            } catch (GmailClientException e) {
                System.err.println("[" + extractorName() + "] Poll #" + pollCount +
                        " failed: " + e.getMessage());
            }

            Duration remainingTime = Duration.between(Instant.now(), deadline);
            if (remainingTime.compareTo(pollInterval) > 0) {
                sleep(pollInterval);
            } else if (!remainingTime.isNegative() && !remainingTime.isZero()) {
                sleep(remainingTime);
            }
        }

        throw new EmailExtractionException(
                "Timeout: No matching email found within " + timeout.toSeconds() + " seconds " +
                        "after " + pollCount + " polls"
        );
    }

    /**
     * Performs a single poll iteration: fetches matching emails and attempts extraction.
     *
     * @param monitoringStartTime only consider emails after this time
     * @return the extracted result, or {@code null} if not found
     * @throws GmailClientException if the email fetch fails
     */
    private T pollOnce(Instant monitoringStartTime) throws GmailClientException {
        EmailSearchCriteria criteria = buildCriteria(monitoringStartTime);
        List<EmailMessage> messages = gmailClient.fetchMessages(criteria);

        if (messages.isEmpty()) {
            return null;
        }

        for (EmailMessage message : messages) {
            if (message.receivedDate().isBefore(monitoringStartTime)) {
                continue;
            }

            T result = extractFromEmail(message);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    // ==================== Connection Lifecycle ====================

    /**
     * Acquires a shared {@link GmailClient} if one has not been acquired yet.
     *
     * <p>The shared instance is already connected when returned by
     * {@link GmailClient#shared(String, String)}. This method is
     * idempotent — calling it multiple times is safe.</p>
     *
     * @throws GmailClientException if the connection fails
     */
    private void ensureConnected() throws GmailClientException {
        if (gmailClient == null) {
            gmailClient = GmailClient.shared(catchallEmail, appPassword);
        }
    }

    /**
     * Releases the shared {@link GmailClient} connection.
     *
     * <p>Decrements the reference count on the shared instance. When the
     * last consumer releases, the underlying IMAP connection is closed.
     * Safe to call multiple times — subsequent calls are no-ops.</p>
     */
    @Override
    public void close() {
        if (gmailClient != null) {
            gmailClient.close();
            gmailClient = null;
        }
    }

    // ==================== Utility ====================

    /**
     * Sleeps for the specified duration.
     *
     * @param duration the duration to sleep
     */
    protected void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for email", e);
        }
    }

    // ==================== Accessors ====================

    /**
     * Returns the profile email address used for recipient matching.
     *
     * @return the profile email
     */
    public String recipient() {
        return email;
    }

    /**
     * Returns the catchall email used for IMAP authentication.
     *
     * @return the catchall email
     */
    public String catchallEmail() {
        return catchallEmail;
    }

    public Duration pollInterval() {
        return pollInterval;
    }

    @Override
    public String toString() {
        return String.format("%s{pollInterval=%ss, email=%s, catchall=%s}",
                extractorName(), pollInterval.toSeconds(), email, catchallEmail);
    }

    // ==================== Exception ====================

    /**
     * Exception thrown when email-based extraction fails.
     */
    public static class EmailExtractionException extends Exception {

        public EmailExtractionException(String message) {
            super(message);
        }

        public EmailExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}