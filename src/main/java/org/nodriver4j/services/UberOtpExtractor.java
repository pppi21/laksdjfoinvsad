package org.nodriver4j.services;

import org.nodriver4j.services.GmailClient.EmailMessage;
import org.nodriver4j.services.GmailClient.EmailSearchCriteria;
import org.nodriver4j.services.GmailClient.GmailClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts OTP verification codes from Uber emails.
 *
 * <p>This class monitors a Gmail inbox for Uber verification emails and extracts
 * the 4-digit OTP code. It only processes emails received after monitoring begins,
 * preventing confusion with older verification emails.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * try (GmailClient gmail = new GmailClient("user@gmail.com", "app-password")) {
 *     gmail.connect();
 *
 *     UberOtpExtractor extractor = new UberOtpExtractor(gmail);
 *
 *     // Trigger Uber to send verification email...
 *     page.click(signUpButton);
 *
 *     // Extract OTP (polls for up to 60 seconds by default)
 *     String otp = extractor.extractOtp();
 *     System.out.println("OTP: " + otp);
 *
 *     // Use the OTP
 *     page.type(otpField, otp);
 * }
 * }</pre>
 *
 * <h2>Email Identification</h2>
 * <p>Uber verification emails are identified by:</p>
 * <ul>
 *   <li>Subject: "Welcome to Uber"</li>
 *   <li>Sender domain: contains "icloud"</li>
 * </ul>
 *
 * <h2>OTP Format</h2>
 * <p>The OTP is a 4-digit numeric code embedded in the HTML body.</p>
 *
 * @see GmailClient
 */
public class UberOtpExtractor {

    // ==================== Uber Email Constants ====================

    private static final String UBER_SUBJECT = "Welcome to Uber";
    private static final String UBER_SENDER_DOMAIN_CONTAINS = "uber.com";

    // ==================== OTP Extraction Pattern ====================

    /**
     * Regex pattern to extract the 4-digit OTP from Uber's HTML email.
     * Matches: ;"><p>1234</p> with optional whitespace
     */
    private static final Pattern OTP_PATTERN = Pattern.compile(
            ";\">\\s*<p>\\s*(\\d{4})\\s*</p>",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Fallback pattern: any standalone 4-digit number in the HTML.
     * Used if the primary pattern doesn't match.
     */
    private static final Pattern OTP_FALLBACK_PATTERN = Pattern.compile(
            "\\b(\\d{4})\\b"
    );

    // ==================== Timing Defaults ====================

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);

    // ==================== Fields ====================

    private final GmailClient gmailClient;
    private final Duration pollInterval;
    private final String folder;
    private final String recipient;

    /**
     * Creates an UberOtpExtractor with default settings.
     *
     * <p>Default poll interval is 5 seconds, searches INBOX.</p>
     *
     * @param gmailClient the connected GmailClient to use
     * @throws IllegalArgumentException if gmailClient is null
     */
    public UberOtpExtractor(GmailClient gmailClient) {
        this(gmailClient, DEFAULT_POLL_INTERVAL, "INBOX");
    }

    /**
     * Creates an UberOtpExtractor with custom poll interval.
     *
     * @param gmailClient  the connected GmailClient to use
     * @param pollInterval how often to check for new emails
     * @throws IllegalArgumentException if gmailClient is null or pollInterval is negative
     */
    public UberOtpExtractor(GmailClient gmailClient, Duration pollInterval) {
        this(gmailClient, pollInterval, "INBOX");
    }

    /**
     * Creates an UberOtpExtractor with full customization.
     *
     * @param gmailClient  the connected GmailClient to use
     * @param pollInterval how often to check for new emails
     * @param folder       the folder to search (e.g., "INBOX", "Spam")
     * @throws IllegalArgumentException if gmailClient is null or pollInterval is negative
     */
    public UberOtpExtractor(GmailClient gmailClient, Duration pollInterval, String folder) {
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

    // ==================== OTP Extraction ====================

    /**
     * Polls for an Uber verification email and extracts the OTP.
     *
     * <p>Uses the default timeout of 60 seconds.</p>
     *
     * <p>This method records the current time as the monitoring start time.
     * Only emails received after this point will be considered, preventing
     * confusion with older verification emails.</p>
     *
     * @return the 4-digit OTP code
     * @throws OtpExtractionException if OTP cannot be extracted within timeout
     * @throws IllegalStateException  if GmailClient is not connected
     */
    public String extractOtp() throws OtpExtractionException {
        return extractOtp(DEFAULT_TIMEOUT);
    }

    /**
     * Polls for an Uber verification email and extracts the OTP.
     *
     * <p>This method records the current time as the monitoring start time.
     * Only emails received after this point will be considered, preventing
     * confusion with older verification emails.</p>
     *
     * @param timeout maximum time to wait for the OTP email
     * @return the 4-digit OTP code
     * @throws OtpExtractionException if OTP cannot be extracted within timeout
     * @throws IllegalStateException  if GmailClient is not connected
     */
    public String extractOtp(Duration timeout) throws OtpExtractionException {
        if (!gmailClient.isConnected()) {
            throw new IllegalStateException("GmailClient is not connected. Call connect() first.");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be a positive duration");
        }

        // Record monitoring start time (slightly in the past to avoid race conditions)
        Instant monitoringStartTime = Instant.now().minusSeconds(2);
        Instant deadline = Instant.now().plus(timeout);

        System.out.println("[UberOtpExtractor] Starting OTP extraction, monitoring from: " + monitoringStartTime);
        System.out.println("[UberOtpExtractor] Timeout: " + timeout.toSeconds() + "s, polling every " +
                pollInterval.toSeconds() + "s");

        int pollCount = 0;

        while (Instant.now().isBefore(deadline)) {
            pollCount++;

            try {
                String otp = pollForOtp(monitoringStartTime);
                if (otp != null) {
                    System.out.println("[UberOtpExtractor] ✓ OTP extracted after " + pollCount + " poll(s): " + otp);
                    return otp;
                }
            } catch (GmailClientException e) {
                System.err.println("[UberOtpExtractor] Poll #" + pollCount + " failed: " + e.getMessage());
                // Continue polling - transient errors are expected
            }

            // Wait before next poll (unless we'd exceed deadline)
            Duration remainingTime = Duration.between(Instant.now(), deadline);
            if (remainingTime.compareTo(pollInterval) > 0) {
                sleep(pollInterval);
            } else if (!remainingTime.isNegative() && !remainingTime.isZero()) {
                sleep(remainingTime);
            }
        }

        throw new OtpExtractionException(
                "Timeout: No Uber OTP email received within " + timeout.toSeconds() + " seconds " +
                        "after " + pollCount + " polls"
        );
    }

    /**
     * Performs a single poll for Uber OTP emails.
     *
     * @param monitoringStartTime only consider emails after this time
     * @return the OTP if found, null otherwise
     * @throws GmailClientException if email fetch fails
     */
    private String pollForOtp(Instant monitoringStartTime) throws GmailClientException {
        EmailSearchCriteria criteria = EmailSearchCriteria.builder()
                .subject(UBER_SUBJECT)
                .recipient(recipient)
                .senderDomain(UBER_SENDER_DOMAIN_CONTAINS)
                .since(monitoringStartTime)
                .build();

        List<EmailMessage> messages = gmailClient.fetchMessages(folder, criteria);

        if (messages.isEmpty()) {
            System.out.println("[UberOtpExtractor] No matching emails found");
            return null;
        }

        System.out.println("[UberOtpExtractor] Found " + messages.size() + " matching email(s)");

        // Process newest first (list is already sorted newest-first by GmailClient)
        for (EmailMessage message : messages) {
            // Double-check the timestamp filter
            if (message.receivedDate().isBefore(monitoringStartTime)) {
                System.out.println("[UberOtpExtractor] Skipping old email from: " + message.receivedDate());
                continue;
            }

            String otp = extractOtpFromEmail(message);
            if (otp != null) {
                return otp;
            }
        }

        return null;
    }

    /**
     * Extracts the OTP from an email's HTML body.
     *
     * @param message the email message
     * @return the 4-digit OTP, or null if not found
     */
    private String extractOtpFromEmail(EmailMessage message) {
        String body = message.htmlBody();

        if (body == null || body.isBlank()) {
            // Fall back to text body
            body = message.textBody();
        }

        if (body == null || body.isBlank()) {
            System.err.println("[UberOtpExtractor] Email has no body content");
            return null;
        }

        // Try primary pattern first
        Matcher matcher = OTP_PATTERN.matcher(body);
        if (matcher.find()) {
            String otp = matcher.group(1);
            System.out.println("[UberOtpExtractor] Extracted OTP using primary pattern: " + otp);
            return otp;
        }

        // Try fallback pattern
        System.out.println("[UberOtpExtractor] Primary pattern failed, trying fallback...");
        matcher = OTP_FALLBACK_PATTERN.matcher(body);

        // Find all 4-digit numbers and return the first one
        // (Could be improved to find the most likely OTP based on context)
        if (matcher.find()) {
            String otp = matcher.group(1);
            System.out.println("[UberOtpExtractor] Extracted OTP using fallback pattern: " + otp);
            return otp;
        }

        System.err.println("[UberOtpExtractor] Could not find OTP in email body");
        return null;
    }

    // ==================== Utility Methods ====================

    /**
     * Sleeps for the specified duration.
     */
    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for OTP email", e);
        }
    }

    /**
     * Returns the GmailClient used by this extractor.
     *
     * @return the GmailClient instance
     */
    public GmailClient gmailClient() {
        return gmailClient;
    }

    /**
     * Returns the poll interval.
     *
     * @return the duration between polls
     */
    public Duration pollInterval() {
        return pollInterval;
    }

    /**
     * Returns the folder being searched.
     *
     * @return the folder name
     */
    public String folder() {
        return folder;
    }

    @Override
    public String toString() {
        return String.format("UberOtpExtractor{folder=%s, pollInterval=%ss, email=%s}",
                folder, pollInterval.toSeconds(), gmailClient.email());
    }

    // ==================== Exception ====================

    /**
     * Exception thrown when OTP extraction fails.
     */
    public static class OtpExtractionException extends Exception {

        public OtpExtractionException(String message) {
            super(message);
        }

        public OtpExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}