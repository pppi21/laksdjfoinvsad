package org.nodriver4j.services;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.*;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Generic IMAP client for Gmail using app password authentication.
 *
 * <p>This client provides a simplified interface for connecting to Gmail via IMAP
 * and fetching messages with flexible search criteria.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * try (GmailClient client = new GmailClient("user@gmail.com", "app-password")) {
 *     client.connect();
 *
 *     EmailSearchCriteria criteria = GmailClient.EmailSearchCriteria.builder()
 *         .subject("Welcome to Uber")
 *         .senderDomainContains("icloud")
 *         .since(Instant.now().minusSeconds(300))
 *         .build();
 *
 *     List<GmailClient.EmailMessage> messages = client.fetchMessages("INBOX", criteria);
 *     for (GmailClient.EmailMessage msg : messages) {
 *         System.out.println("Subject: " + msg.subject());
 *         System.out.println("Body: " + msg.htmlBody());
 *     }
 * }
 * }</pre>
 *
 * <h2>Sender Filtering Modes</h2>
 * <ul>
 *   <li><strong>Exact match:</strong> {@code senderExact("noreply@uber.com")} - matches exactly</li>
 *   <li><strong>Domain match:</strong> {@code senderDomain("icloud.com")} - matches *@icloud.com</li>
 *   <li><strong>Domain contains:</strong> {@code senderDomainContains("icloud")} - matches *@*.icloud.* (subdomains)</li>
 * </ul>
 *
 * @see EmailSearchCriteria
 * @see EmailMessage
 */
public class GmailClient implements AutoCloseable {

    private static final String GMAIL_IMAP_HOST = "imap.gmail.com";
    private static final int GMAIL_IMAP_PORT = 993;

    private final String email;
    private final String catchallEmail;
    private final String appPassword;

    private Session session;
    private Store store;
    private boolean connected = false;

    /**
     * Creates a new GmailClient with the specified credentials.
     *
     * <p>Note: This does not establish a connection. Call {@link #connect()} before
     * fetching messages.</p>
     *
     * @param email       the Gmail address
     * @param appPassword the app password (not the account password)
     * @throws IllegalArgumentException if email or appPassword is null/blank
     */
    public GmailClient(String email, String catchallEmail, String appPassword) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email cannot be null or blank");
        }
        if (catchallEmail == null || catchallEmail.isBlank()) {
            throw new IllegalArgumentException("Catchall email cannot be null or blank");
        }
        if (appPassword == null || appPassword.isBlank()) {
            throw new IllegalArgumentException("App password cannot be null or blank");
        }

        this.email = email;
        this.catchallEmail = catchallEmail;
        this.appPassword = appPassword;
    }

    // ==================== Connection Management ====================

    /**
     * Establishes a connection to Gmail's IMAP server.
     *
     * @throws GmailClientException if connection or authentication fails
     */
    public void connect() throws GmailClientException {
        if (connected) {
            return;
        }

        try {
            Properties props = new Properties();
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.host", GMAIL_IMAP_HOST);
            props.put("mail.imaps.port", String.valueOf(GMAIL_IMAP_PORT));
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.ssl.trust", GMAIL_IMAP_HOST);

            session = Session.getInstance(props);
            store = session.getStore("imaps");
            store.connect(GMAIL_IMAP_HOST, GMAIL_IMAP_PORT, catchallEmail, appPassword);

            connected = true;
            System.out.println("[GmailClient] Connected to Gmail IMAP on " + catchallEmail + " for: " + email);

        } catch (AuthenticationFailedException e) {
            throw new GmailClientException("Authentication failed. Check email and app password.", e);
        } catch (MessagingException e) {
            throw new GmailClientException("Failed to connect to Gmail IMAP server.", e);
        }
    }

    /**
     * Disconnects from the Gmail IMAP server.
     */
    public void disconnect() {
        if (!connected || store == null) {
            return;
        }

        try {
            store.close();
            System.out.println("[GmailClient] Disconnected from Gmail IMAP");
        } catch (MessagingException e) {
            System.err.println("[GmailClient] Error during disconnect: " + e.getMessage());
        } finally {
            connected = false;
            store = null;
            session = null;
        }
    }

    /**
     * Checks if the client is currently connected.
     *
     * @return true if connected to Gmail IMAP
     */
    public boolean isConnected() {
        return connected && store != null && store.isConnected();
    }

    // ==================== Message Fetching ====================

    /**
     * Fetches messages from a folder matching the specified criteria.
     *
     * @param folderName the folder to search (e.g., "INBOX", "Spam", "[Gmail]/All Mail")
     * @param criteria   the search criteria (can be null for all messages)
     * @return list of matching messages, newest first
     * @throws GmailClientException  if not connected or fetch fails
     * @throws IllegalStateException if not connected
     */
    public List<EmailMessage> fetchMessages(String folderName, EmailSearchCriteria criteria)
            throws GmailClientException {

        ensureConnected();

        if (folderName == null || folderName.isBlank()) {
            folderName = "INBOX";
        }

        List<EmailMessage> results = new ArrayList<>();

        try (Folder folder = store.getFolder(folderName)) {
            folder.open(Folder.READ_ONLY);

            // Build IMAP search term
            SearchTerm searchTerm = buildSearchTerm(criteria);

            // Fetch messages
            Message[] messages;
            if (searchTerm != null) {
                messages = folder.search(searchTerm);
            } else {
                messages = folder.getMessages();
            }

            System.out.println("[GmailClient] Found " + messages.length + " messages in " + folderName);

            // Convert to EmailMessage and apply additional filters
            for (Message message : messages) {
                try {
                    EmailMessage emailMessage = convertMessage(message);

                    // Apply sender filters that IMAP can't handle natively
                    if (matchesSenderCriteria(emailMessage, criteria) && matchesRecipientCriteria(emailMessage, criteria)) {
                        // Apply timestamp filter (IMAP SINCE only works at day granularity)
                        if (criteria == null || criteria.since == null ||
                                emailMessage.receivedDate().isAfter(criteria.since) ||
                                emailMessage.receivedDate().equals(criteria.since)) {
                            results.add(emailMessage);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[GmailClient] Failed to parse message: " + e.getMessage());
                }
            }

            // Sort by received date, newest first
            results.sort((a, b) -> b.receivedDate().compareTo(a.receivedDate()));

        } catch (MessagingException e) {
            throw new GmailClientException("Failed to fetch messages from folder: " + folderName, e);
        }

        return results;
    }

    /**
     * Fetches messages from INBOX matching the specified criteria.
     *
     * @param criteria the search criteria
     * @return list of matching messages, newest first
     * @throws GmailClientException if fetch fails
     */
    public List<EmailMessage> fetchMessages(EmailSearchCriteria criteria) throws GmailClientException {
        return fetchMessages("INBOX", criteria);
    }

    /**
     * Lists available folders in the mailbox.
     *
     * @return list of folder names
     * @throws GmailClientException if listing fails
     */
    public List<String> listFolders() throws GmailClientException {
        ensureConnected();

        List<String> folderNames = new ArrayList<>();

        try {
            Folder defaultFolder = store.getDefaultFolder();
            Folder[] folders = defaultFolder.list("*");

            for (Folder folder : folders) {
                folderNames.add(folder.getFullName());
            }
        } catch (MessagingException e) {
            throw new GmailClientException("Failed to list folders", e);
        }

        return folderNames;
    }

    // ==================== Search Term Building ====================

    /**
     * Builds an IMAP SearchTerm from the criteria.
     * Note: Some filters (sender domain matching) are applied post-fetch.
     */
    private SearchTerm buildSearchTerm(EmailSearchCriteria criteria) {
        if (criteria == null) {
            return null;
        }

        List<SearchTerm> terms = new ArrayList<>();

        // Subject filter
        if (criteria.subject != null && !criteria.subject.isBlank()) {
            terms.add(new SubjectTerm(criteria.subject));
        }

        if (criteria.recipient != null && !criteria.recipient.isBlank()) {
            terms.add(new RecipientStringTerm(Message.RecipientType.TO, criteria.recipient));
        }

        // Exact sender filter (IMAP can handle this)
        if (criteria.senderExact != null && !criteria.senderExact.isBlank()) {
            terms.add(new FromStringTerm(criteria.senderExact));
        }

        // Since date filter (IMAP only supports day granularity)
        if (criteria.since != null) {
            Date sinceDate = Date.from(criteria.since);
            terms.add(new ReceivedDateTerm(ComparisonTerm.GE, sinceDate));
        }

        if (terms.isEmpty()) {
            return null;
        }

        if (terms.size() == 1) {
            return terms.get(0);
        }

        return new AndTerm(terms.toArray(new SearchTerm[0]));
    }

    /**
     * Checks if an email matches the sender criteria.
     * Handles domain and domain-contains matching that IMAP can't do natively.
     */
    private boolean matchesRecipientCriteria(EmailMessage message, EmailSearchCriteria criteria) {
        if (criteria == null) {
            return true;
        }

        String recipient = extractBetweenBrackets(message.recipient());
        System.out.println(recipient);
        if (recipient == null) {
            return false;
        }

        recipient = recipient.toLowerCase();

        // Exact match (already filtered by IMAP, but double-check)
        if (criteria.recipient != null && !criteria.recipient.isBlank()) {
            return recipient.equals(criteria.recipient.toLowerCase());
        }

        return true;
    }

    /**
     * Checks if an email matches the sender criteria.
     * Handles domain and domain-contains matching that IMAP can't do natively.
     */
    private boolean matchesSenderCriteria(EmailMessage message, EmailSearchCriteria criteria) {
        if (criteria == null) {
            return true;
        }

        String sender = extractBetweenBrackets(message.sender());
        System.out.println(sender);
        if (sender == null) {
            return false;
        }

        sender = sender.toLowerCase();


        // Exact match (already filtered by IMAP, but double-check)
        if (criteria.senderExact != null && !criteria.senderExact.isBlank()) {
            if (!sender.contains(criteria.senderExact.toLowerCase())) {
                return false;
            }
        }

        // Domain match: *@domain.com
        if (criteria.senderDomain != null && !criteria.senderDomain.isBlank()) {
            String domain = criteria.senderDomain.toLowerCase();
            if (!domain.startsWith("@")) {
                domain = "@" + domain;
            }
            if (!sender.endsWith(domain)) {
                return false;
            }
        }

        // Domain contains: sender's domain part contains the string
        if (criteria.senderDomainContains != null && !criteria.senderDomainContains.isBlank()) {
            int atIndex = sender.indexOf('@');
            if (atIndex < 0) {
                return false;
            }
            String domainPart = sender.substring(atIndex + 1);
            if (!domainPart.contains(criteria.senderDomainContains.toLowerCase())) {
                return false;
            }
        }

        return true;
    }

    // ==================== Message Conversion ====================

    /**
     * Converts a JavaMail Message to our EmailMessage record.
     */
    private EmailMessage convertMessage(Message message) throws MessagingException, IOException {
        String subject = message.getSubject();

        Address[] toAddress = message.getRecipients(Message.RecipientType.TO);
        String recipient = (toAddress != null && toAddress.length > 0)
                ? toAddress[0].toString()
                : null;

        // Extract sender
        Address[] fromAddresses = message.getFrom();
        String sender = (fromAddresses != null && fromAddresses.length > 0)
                ? fromAddresses[0].toString()
                : null;

        // Extract received date
        Date receivedDate = message.getReceivedDate();
        Instant receivedInstant = (receivedDate != null)
                ? receivedDate.toInstant()
                : Instant.now();

        // Extract body content
        String htmlBody = null;
        String textBody = null;

        Object content = message.getContent();

        if (content instanceof String) {
            // Plain text or HTML directly
            if (message.isMimeType("text/html")) {
                htmlBody = (String) content;
            } else {
                textBody = (String) content;
            }
        } else if (content instanceof MimeMultipart) {
            BodyContent bodyContent = extractBodyFromMultipart((MimeMultipart) content);
            htmlBody = bodyContent.html;
            textBody = bodyContent.text;
        }

        return new EmailMessage(subject, recipient,sender, htmlBody, textBody, receivedInstant);
    }

    /**
     * Recursively extracts text and HTML content from a multipart message.
     */
    private BodyContent extractBodyFromMultipart(MimeMultipart multipart)
            throws MessagingException, IOException {

        String html = null;
        String text = null;

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);

            if (part.isMimeType("text/plain") && text == null) {
                text = (String) part.getContent();
            } else if (part.isMimeType("text/html") && html == null) {
                html = (String) part.getContent();
            } else if (part.getContent() instanceof MimeMultipart) {
                // Recursively handle nested multipart
                BodyContent nested = extractBodyFromMultipart((MimeMultipart) part.getContent());
                if (nested.html != null && html == null) {
                    html = nested.html;
                }
                if (nested.text != null && text == null) {
                    text = nested.text;
                }
            }
        }

        return new BodyContent(html, text);
    }

    /**
     * Helper record for body extraction.
     */
    private record BodyContent(String html, String text) {}

    // ==================== Utility Methods ====================

    private static String extractBetweenBrackets(String input) {
        int start = input.indexOf('<');
        int end = input.indexOf('>');

        if (start != -1 && end != -1 && start < end) {
            return input.substring(start + 1, end);
        }

        return ""; // or return null, depending on your preference
    }

    /**
     * Ensures the client is connected, throwing if not.
     */
    private void ensureConnected() {
        if (!isConnected()) {
            throw new IllegalStateException("GmailClient is not connected. Call connect() first.");
        }
    }

    /**
     * Returns the email address this client is authenticated with.
     *
     * @return the email address
     */
    public String email() {
        return email;
    }

    public String catchallEmail() {
        return catchallEmail;
    }

    @Override
    public void close() {
        disconnect();
    }

    @Override
    public String toString() {
        return String.format("GmailClient{email=%s, catchall=%s, connected=%s}", email, catchallEmail, connected);
    }

    // ==================== Inner Classes ====================

    /**
     * Immutable container for email message data.
     *
     * @param subject      the email subject line
     * @param sender       the sender address (may include display name)
     * @param htmlBody     the HTML body content, or null if not available
     * @param textBody     the plain text body content, or null if not available
     * @param receivedDate when the email was received
     */
    public record EmailMessage(
            String subject,
            String recipient,
            String sender,
            String htmlBody,
            String textBody,
            Instant receivedDate
    ) {
        /**
         * Returns the best available body content, preferring HTML.
         *
         * @return HTML body if available, otherwise text body
         */
        public String body() {
            return htmlBody != null ? htmlBody : textBody;
        }

        /**
         * Checks if this message has HTML content.
         *
         * @return true if HTML body is available
         */
        public boolean hasHtmlBody() {
            return htmlBody != null && !htmlBody.isBlank();
        }

        /**
         * Checks if this message has plain text content.
         *
         * @return true if text body is available
         */
        public boolean hasTextBody() {
            return textBody != null && !textBody.isBlank();
        }
    }

    /**
     * Search criteria for filtering emails.
     *
     * <p>Use the {@link Builder} to construct criteria:</p>
     * <pre>{@code
     * EmailSearchCriteria criteria = EmailSearchCriteria.builder()
     *     .subject("Welcome to Uber")
     *     .senderDomainContains("icloud")
     *     .since(Instant.now().minusSeconds(60))
     *     .build();
     * }</pre>
     */
    public static final class EmailSearchCriteria {

        final String subject;
        final String recipient;
        final String senderExact;
        final String senderDomain;
        final String senderDomainContains;
        final Instant since;

        private EmailSearchCriteria(Builder builder) {
            this.subject = builder.subject;
            this.recipient = builder.recipient;
            this.senderExact = builder.senderExact;
            this.senderDomain = builder.senderDomain;
            this.senderDomainContains = builder.senderDomainContains;
            this.since = builder.since;
        }

        /**
         * Creates a new builder for EmailSearchCriteria.
         *
         * @return a new Builder instance
         */
        public static Builder builder() {
            return new Builder();
        }

        public String subject() {
            return subject;
        }

        public String recipient() {
            return recipient;
        }

        public String senderExact() {
            return senderExact;
        }

        public String senderDomain() {
            return senderDomain;
        }

        public String senderDomainContains() {
            return senderDomainContains;
        }

        public Instant since() {
            return since;
        }

        @Override
        public String toString() {
            return String.format(
                    "EmailSearchCriteria{subject=%s, senderExact=%s, senderDomain=%s, senderDomainContains=%s, since=%s}",
                    subject, senderExact, senderDomain, senderDomainContains, since
            );
        }

        /**
         * Builder for EmailSearchCriteria.
         */
        public static final class Builder {

            private String subject;
            private String recipient;
            private String senderExact;
            private String senderDomain;
            private String senderDomainContains;
            private Instant since;

            private Builder() {}

            /**
             * Filters by subject line (substring match).
             *
             * @param subject the subject to search for
             * @return this builder
             */
            public Builder subject(String subject) {
                this.subject = subject;
                return this;
            }

            public Builder recipient(String recipient) {
                this.recipient = recipient;
                return this;
            }

            /**
             * Filters by exact sender address.
             *
             * @param senderExact the exact email address (e.g., "noreply@uber.com")
             * @return this builder
             */
            public Builder senderExact(String senderExact) {
                this.senderExact = senderExact;
                return this;
            }

            /**
             * Filters by sender domain (exact domain match).
             *
             * @param domain the domain (e.g., "icloud.com" matches *@icloud.com)
             * @return this builder
             */
            public Builder senderDomain(String domain) {
                this.senderDomain = domain;
                return this;
            }

            /**
             * Filters by sender domain containing a string.
             *
             * @param domainPart the string to find in the domain (e.g., "icloud" matches *@*.icloud.*)
             * @return this builder
             */
            public Builder senderDomainContains(String domainPart) {
                this.senderDomainContains = domainPart;
                return this;
            }

            /**
             * Filters to emails received at or after the specified instant.
             *
             * @param since the earliest receive time
             * @return this builder
             */
            public Builder since(Instant since) {
                this.since = since;
                return this;
            }

            /**
             * Builds the EmailSearchCriteria.
             *
             * @return the constructed criteria
             */
            public EmailSearchCriteria build() {
                return new EmailSearchCriteria(this);
            }
        }
    }

    /**
     * Exception thrown when GmailClient operations fail.
     */
    public static class GmailClientException extends Exception {

        public GmailClientException(String message) {
            super(message);
        }

        public GmailClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}