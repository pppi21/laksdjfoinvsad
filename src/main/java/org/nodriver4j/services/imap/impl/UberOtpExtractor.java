package org.nodriver4j.services.imap.impl;

import org.nodriver4j.services.imap.GmailClient;
import org.nodriver4j.services.imap.GmailClient.EmailMessage;
import org.nodriver4j.services.imap.GmailClient.EmailSearchCriteria;
import org.nodriver4j.services.imap.EmailPollingBase;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts OTP verification codes from Uber emails.
 *
 * <p>Monitors a Gmail inbox for Uber verification emails and extracts
 * the 4-digit OTP code. Only processes emails received after polling begins,
 * preventing confusion with older verification emails.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * try (UberOtpExtractor extractor = new UberOtpExtractor(
 *         "user@gmail.com", "catchall@gmail.com", "app-password")) {
 *
 *     // Trigger Uber to send verification email...
 *     page.click(signUpButton);
 *
 *     // Extract OTP (polls for up to 60 seconds by default)
 *     String otp = extractor.extractOtp();
 *     System.out.println("OTP: " + otp);
 * }
 * }</pre>
 *
 * <h2>Email Identification</h2>
 * <ul>
 *   <li>Subject: "Welcome to Uber"</li>
 *   <li>Sender domain: "uber.com"</li>
 * </ul>
 *
 * <h2>OTP Format</h2>
 * <p>The OTP is a 4-digit numeric code embedded in the HTML body.</p>
 *
 * @see EmailPollingBase
 * @see GmailClient
 */
public class UberOtpExtractor extends EmailPollingBase<String> {

    // ==================== Uber Email Constants ====================

    private static final String UBER_SUBJECT = "Welcome to Uber";
    private static final String UBER_SENDER_DOMAIN = "uber.com";

    // ==================== OTP Extraction Patterns ====================

    /**
     * Primary regex to extract the 4-digit OTP from Uber's HTML email.
     * Matches: ;"><p>1234</p> with optional whitespace.
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

    // ==================== Constructors ====================

    /**
     * Creates an UberOtpExtractor with default settings (5s poll interval).
     *
     * @param email         the profile email address (used for recipient matching)
     * @param catchallEmail the catchall email for IMAP authentication
     * @param appPassword   the app password for IMAP authentication
     */
    public UberOtpExtractor(String email, String catchallEmail, String appPassword) {
        super(email, catchallEmail, appPassword);
    }

    /**
     * Creates an UberOtpExtractor with a custom poll interval.
     *
     * @param email         the profile email address (used for recipient matching)
     * @param catchallEmail the catchall email for IMAP authentication
     * @param appPassword   the app password for IMAP authentication
     * @param pollInterval  how often to check for new emails
     */
    public UberOtpExtractor(String email, String catchallEmail, String appPassword,
                            Duration pollInterval) {
        super(email, catchallEmail, appPassword, pollInterval);
    }

    // ==================== Public API ====================

    /**
     * Polls for an Uber verification email and extracts the OTP.
     *
     * <p>Uses the default timeout of 60 seconds.</p>
     *
     * @return the 4-digit OTP code
     * @throws EmailExtractionException         if OTP cannot be extracted within timeout
     * @throws GmailClient.GmailClientException if connection fails
     */
    public String extractOtp() throws EmailExtractionException, GmailClient.GmailClientException {
        return poll();
    }

    /**
     * Polls for an Uber verification email and extracts the OTP.
     *
     * @param timeout maximum time to wait for the OTP email
     * @return the 4-digit OTP code
     * @throws EmailExtractionException         if OTP cannot be extracted within timeout
     * @throws GmailClient.GmailClientException if connection fails
     */
    public String extractOtp(Duration timeout) throws EmailExtractionException,
            GmailClient.GmailClientException {
        return poll(timeout);
    }

    // ==================== EmailPollingBase Implementation ====================

    @Override
    protected EmailSearchCriteria buildCriteria(Instant since) {
        return EmailSearchCriteria.builder()
                .subject(UBER_SUBJECT)
                .recipient(recipient())
                .since(since)
                .build();
    }

    @Override
    protected String extractFromEmail(EmailMessage message) {
        String body = message.htmlBody();

        if (body == null || body.isBlank()) {
            body = message.textBody();
        }

        if (body == null || body.isBlank()) {
            return null;
        }

        // Try primary pattern
        Matcher matcher = OTP_PATTERN.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // Try fallback pattern
        matcher = OTP_FALLBACK_PATTERN.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    @Override
    protected String extractorName() {
        return "UberOtpExtractor";
    }
}