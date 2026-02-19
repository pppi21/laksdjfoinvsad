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
 * timeout enforcement, and shared configuration.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyExtractor extends EmailPollingBase<String> {
 *
 *     public MyExtractor(GmailClient gmailClient) {
 *         super(gmailClient);
 *     }
 *
 *     @Override
 *     protected EmailSearchCriteria buildCriteria(Instant since) { ... }
 *
 *     @Override
 *     protected String extractFromEmail(EmailMessage message) { ... }
 * }
 * }</pre>
 *
 * @param <T> the type of result extracted from an email (e.g. OTP string, URL)
 * @see GmailClient
 */
public abstract class EmailPollingBase<T> {

    // ==================== Timing Defaults ====================

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);

    // ==================== Fields ====================

    private final GmailClient gmailClient;
    private final Duration pollInterval;
    private final String folder;
    private final String recipient;

    // ==================== Constructors ====================

    /**
     * Creates an extractor with default settings (5s poll interval, INBOX).
     *
     * @param gmailClient the connected GmailClient to use
     * @throws IllegalArgumentException if gmailClient is null
     */
    protected EmailPollingBase(GmailClient gmailClient) {
        this(gmailClient, DEFAULT_POLL_INTERVAL, "INBOX");
    }

    /**
     * Creates an extractor with a custom poll interval.
     *
     * @param gmailClient  the connected GmailClient to use
     * @param pollInterval how often to check for new emails
     * @throws IllegalArgumentException if gmailClient is null or pollInterval is negative
     */
    protected EmailPollingBase(GmailClient gmailClient, Duration pollInterval) {
        this(gmailClient, pollInterval, "INBOX");
    }

    /**
     * Creates an extractor with full customization.
     *
     * @param gmailClient  the connected GmailClient to use
     * @param pollInterval how often to check for new emails
     * @param folder       the folder to search (e.g., "INBOX", "Spam")
     * @throws IllegalArgumentException if gmailClient is null or pollInterval is negative
     */
    protected EmailPollingBase(GmailClient gmailClient, Duration pollInterval, String folder) {
        if (gmailClient == null) {
            throw new IllegalArgumentException("GmailClient cannot be null");
        }
        if (pollInterval == null || pollInterval.isNegative()) {
            throw new IllegalArgumentException("Poll interval must be a positive duration");
        }
        if (folder == null || folder.isBlank()) {
            folder = "INBOX";
        }

        this.gmailClient = gmailClient;
        this.pollInterval = pollInterval;
        this.folder = folder;
        this.recipient = gmailClient.email();
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
     * @throws IllegalStateException    if GmailClient is not connected
     */
    public T poll() throws EmailExtractionException {
        return poll(DEFAULT_TIMEOUT);
    }

    /**
     * Polls for a matching email and extracts a result.
     *
     * <p>Records the current time (minus a small buffer) as the monitoring
     * start time. Only emails received after this point are considered,
     * preventing confusion with older emails.</p>
     *
     * @param timeout maximum time to wait for a matching email
     * @return the extracted result
     * @throws EmailExtractionException if no result is found within the timeout
     * @throws IllegalStateException    if GmailClient is not connected
     */
    public T poll(Duration timeout) throws EmailExtractionException {
        if (!gmailClient.isConnected()) {
            throw new IllegalStateException("GmailClient is not connected. Call connect() first.");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be a positive duration");
        }

        Instant monitoringStartTime = Instant.now().minusSeconds(2);
        Instant deadline = Instant.now().plus(timeout);

        System.out.println("[" + extractorName() + "] Starting extraction, monitoring from: " + monitoringStartTime);
        System.out.println("[" + extractorName() + "] Timeout: " + timeout.toSeconds() + "s, polling every " +
                pollInterval.toSeconds() + "s");

        int pollCount = 0;

        while (Instant.now().isBefore(deadline)) {
            pollCount++;

            try {
                T result = pollOnce(monitoringStartTime);
                if (result != null) {
                    System.out.println("[" + extractorName() + "] ✓ Result extracted after " + pollCount + " poll(s)");
                    return result;
                }
            } catch (GmailClientException e) {
                System.err.println("[" + extractorName() + "] Poll #" + pollCount + " failed: " + e.getMessage());
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
        List<EmailMessage> messages = gmailClient.fetchMessages(folder, criteria);

        if (messages.isEmpty()) {
            System.out.println("[" + extractorName() + "] No matching emails found");
            return null;
        }

        System.out.println("[" + extractorName() + "] Found " + messages.size() + " matching email(s)");

        for (EmailMessage message : messages) {
            if (message.receivedDate().isBefore(monitoringStartTime)) {
                System.out.println("[" + extractorName() + "] Skipping old email from: " + message.receivedDate());
                continue;
            }

            T result = extractFromEmail(message);
            if (result != null) {
                return result;
            }
        }

        return null;
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

    public GmailClient gmailClient() {
        return gmailClient;
    }

    public Duration pollInterval() {
        return pollInterval;
    }

    public String folder() {
        return folder;
    }

    public String recipient() {
        return recipient;
    }

    @Override
    public String toString() {
        return String.format("%s{folder=%s, pollInterval=%ss, email=%s}",
                extractorName(), folder, pollInterval.toSeconds(), gmailClient.email());
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