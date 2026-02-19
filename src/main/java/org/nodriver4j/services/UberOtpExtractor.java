package org.nodriver4j.services;

import org.nodriver4j.services.GmailClient.EmailMessage;
import org.nodriver4j.services.GmailClient.EmailSearchCriteria;

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
 * try (GmailClient gmail = new GmailClient("user@gmail.com", "catchall@gmail.com", "app-password")) {
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
     * Creates an UberOtpExtractor with default settings (5s poll, INBOX).
     *
     * @param gmailClient the connected GmailClient to use
     */
    public UberOtpExtractor(GmailClient gmailClient) {
        super(gmailClient);
    }

    /**
     * Creates an UberOtpExtractor with a custom poll interval.
     *
     * @param gmailClient  the connected GmailClient to use
     * @param pollInterval how often to check for new emails
     */
    public UberOtpExtractor(GmailClient gmailClient, Duration pollInterval) {
        super(gmailClient, pollInterval);
    }

    /**
     * Creates an UberOtpExtractor with full customization.
     *
     * @param gmailClient  the connected GmailClient to use
     * @param pollInterval how often to check for new emails
     * @param folder       the folder to search (e.g., "INBOX", "Spam")
     */
    public UberOtpExtractor(GmailClient gmailClient, Duration pollInterval, String folder) {
        super(gmailClient, pollInterval, folder);
    }

    // ==================== Public API ====================

    /**
     * Polls for an Uber verification email and extracts the OTP.
     *
     * <p>Uses the default timeout of 60 seconds.</p>
     *
     * @return the 4-digit OTP code
     * @throws EmailExtractionException if OTP cannot be extracted within timeout
     * @throws IllegalStateException    if GmailClient is not connected
     */
    public String extractOtp() throws EmailExtractionException {
        return poll();
    }

    /**
     * Polls for an Uber verification email and extracts the OTP.
     *
     * @param timeout maximum time to wait for the OTP email
     * @return the 4-digit OTP code
     * @throws EmailExtractionException if OTP cannot be extracted within timeout
     * @throws IllegalStateException    if GmailClient is not connected
     */
    public String extractOtp(Duration timeout) throws EmailExtractionException {
        return poll(timeout);
    }

    // ==================== EmailPollingBase Implementation ====================

    @Override
    protected EmailSearchCriteria buildCriteria(Instant since) {
        return EmailSearchCriteria.builder()
                .subject(UBER_SUBJECT)
                .recipient(recipient())
                .senderDomain(UBER_SENDER_DOMAIN)
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
            System.err.println("[" + extractorName() + "] Email has no body content");
            return null;
        }

        // Try primary pattern
        Matcher matcher = OTP_PATTERN.matcher(body);
        if (matcher.find()) {
            String otp = matcher.group(1);
            System.out.println("[" + extractorName() + "] Extracted OTP using primary pattern: " + otp);
            return otp;
        }

        // Try fallback pattern
        System.out.println("[" + extractorName() + "] Primary pattern failed, trying fallback...");
        matcher = OTP_FALLBACK_PATTERN.matcher(body);
        if (matcher.find()) {
            String otp = matcher.group(1);
            System.out.println("[" + extractorName() + "] Extracted OTP using fallback pattern: " + otp);
            return otp;
        }

        System.err.println("[" + extractorName() + "] Could not find OTP in email body");
        return null;
    }

    @Override
    protected String extractorName() {
        return "UberOtpExtractor";
    }
}