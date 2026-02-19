package org.nodriver4j.services;

import org.nodriver4j.services.GmailClient.EmailMessage;
import org.nodriver4j.services.GmailClient.EmailSearchCriteria;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the account verification link from Funko verification emails.
 *
 * <p>Monitors a Gmail inbox for Funko verification emails and extracts
 * the "VERIFY ACCOUNT" URL. Only processes emails received after polling
 * begins, preventing confusion with older verification emails.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * try (GmailClient gmail = new GmailClient("user@gmail.com", "catchall@gmail.com", "app-password")) {
 *     gmail.connect();
 *
 *     FunkoVerificationExtractor extractor = new FunkoVerificationExtractor(gmail);
 *
 *     // Trigger Funko to send verification email...
 *     page.click(createAccountButton);
 *
 *     // Extract verification link (polls for up to 60 seconds by default)
 *     String verificationUrl = extractor.extractVerificationLink();
 *     page.navigate(verificationUrl);
 * }
 * }</pre>
 *
 * <h2>Email Identification</h2>
 * <ul>
 *   <li>Subject contains: "Please verify your Funko account!"</li>
 *   <li>Recipient: matches the profile email address</li>
 *   <li>Sender: not filtered (randomized iCloud addresses)</li>
 * </ul>
 *
 * <h2>Link Extraction</h2>
 * <p>Targets the {@code <a>} element whose text content contains
 * "VERIFY ACCOUNT", extracting its {@code href} attribute. The link
 * typically points to {@code https://trk.send.funko.com/...}.</p>
 *
 * @see EmailPollingBase
 * @see GmailClient
 */
public class FunkoVerificationExtractor extends EmailPollingBase<String> {

    // ==================== Funko Email Constants ====================

    private static final String FUNKO_SUBJECT = "Please verify your Funko account!";

    // ==================== Link Extraction Patterns ====================

    /**
     * Primary regex to extract the verification URL from the "VERIFY ACCOUNT" anchor.
     *
     * <p>Matches an {@code <a>} tag with an {@code href} pointing to
     * {@code trk.send.funko.com} whose text content contains "VERIFY ACCOUNT".</p>
     *
     * <p>Uses {@link Pattern#DOTALL} so {@code [^>]*} spans newlines between
     * attributes, and {@code \\s*} spans newlines before the link text.</p>
     */
    private static final Pattern VERIFICATION_LINK_PATTERN = Pattern.compile(
            "href=\"(https://trk\\.send\\.funko\\.com/[^\"]*)\"[^>]*>\\s*VERIFY ACCOUNT\\s*</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    /**
     * Fallback regex: any anchor whose text content contains "VERIFY ACCOUNT",
     * regardless of the link domain.
     */
    private static final Pattern VERIFICATION_LINK_FALLBACK_PATTERN = Pattern.compile(
            "href=\"(https?://[^\"]*)\"[^>]*>\\s*VERIFY ACCOUNT\\s*</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // ==================== Constructors ====================

    /**
     * Creates a FunkoVerificationExtractor with default settings (5s poll, INBOX).
     *
     * @param gmailClient the connected GmailClient to use
     */
    public FunkoVerificationExtractor(GmailClient gmailClient) {
        super(gmailClient);
    }

    /**
     * Creates a FunkoVerificationExtractor with a custom poll interval.
     *
     * @param gmailClient  the connected GmailClient to use
     * @param pollInterval how often to check for new emails
     */
    public FunkoVerificationExtractor(GmailClient gmailClient, Duration pollInterval) {
        super(gmailClient, pollInterval);
    }

    /**
     * Creates a FunkoVerificationExtractor with full customization.
     *
     * @param gmailClient  the connected GmailClient to use
     * @param pollInterval how often to check for new emails
     * @param folder       the folder to search (e.g., "INBOX", "Spam")
     */
    public FunkoVerificationExtractor(GmailClient gmailClient, Duration pollInterval, String folder) {
        super(gmailClient, pollInterval, folder);
    }

    // ==================== Public API ====================

    /**
     * Polls for a Funko verification email and extracts the verification link.
     *
     * <p>Uses the default timeout of 60 seconds.</p>
     *
     * @return the verification URL
     * @throws EmailExtractionException if the link cannot be extracted within timeout
     * @throws IllegalStateException    if GmailClient is not connected
     */
    public String extractVerificationLink() throws EmailExtractionException {
        return poll();
    }

    /**
     * Polls for a Funko verification email and extracts the verification link.
     *
     * @param timeout maximum time to wait for the verification email
     * @return the verification URL
     * @throws EmailExtractionException if the link cannot be extracted within timeout
     * @throws IllegalStateException    if GmailClient is not connected
     */
    public String extractVerificationLink(Duration timeout) throws EmailExtractionException {
        return poll(timeout);
    }

    // ==================== EmailPollingBase Implementation ====================

    @Override
    protected EmailSearchCriteria buildCriteria(Instant since) {
        return EmailSearchCriteria.builder()
                .subject(FUNKO_SUBJECT)
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
            System.err.println("[" + extractorName() + "] Email has no body content");
            return null;
        }

        // Try primary pattern (trk.send.funko.com domain)
        Matcher matcher = VERIFICATION_LINK_PATTERN.matcher(body);
        if (matcher.find()) {
            String url = matcher.group(1);
            System.out.println("[" + extractorName() + "] Extracted verification link using primary pattern: " + url);
            return url;
        }

        // Try fallback pattern (any domain with VERIFY ACCOUNT text)
        System.out.println("[" + extractorName() + "] Primary pattern failed, trying fallback...");
        matcher = VERIFICATION_LINK_FALLBACK_PATTERN.matcher(body);
        if (matcher.find()) {
            String url = matcher.group(1);
            System.out.println("[" + extractorName() + "] Extracted verification link using fallback pattern: " + url);
            return url;
        }

        System.err.println("[" + extractorName() + "] Could not find verification link in email body");
        return null;
    }

    @Override
    protected String extractorName() {
        return "FunkoVerificationExtractor";
    }
}