package org.nodriver4j.services.imap;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Generic IMAP client for Gmail using app password authentication.
 *
 * <p>This client provides a simplified interface for connecting to Gmail via IMAP
 * and fetching messages with flexible search criteria.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>A single {@code GmailClient} instance can be safely shared across multiple
 * threads. Connection lifecycle methods ({@link #connect()}, {@link #disconnect()})
 * acquire an exclusive write lock, while message reads use a shared read lock
 * or a lock-free volatile snapshot.</p>
 *
 * <h2>Shared Instances (Connection Pooling)</h2>
 * <p>Use {@link #shared(String, String)} to obtain a reference-counted instance
 * that is cached by catchall email + app password. Multiple consumers sharing the
 * same catchall account receive the same {@code GmailClient} instance and the same
 * underlying IMAP connection. Call {@link #release()} (or {@link #close()}) when
 * finished — the connection is closed automatically when the last consumer releases.</p>
 *
 * <h2>Background Polling (Shared Instances)</h2>
 * <p>Shared instances use a background poller thread that fetches from INBOX every
 * 2 seconds. Extractors register their monitoring start time via
 * {@link #registerSinceTime(Object, Instant)}, and the poller fetches all emails
 * since the earliest registered time. {@link #fetchMessages} on shared instances
 * performs an instant in-memory filter on the latest polled snapshot — no IMAP
 * latency in the critical path. This design ensures at most one IMAP operation
 * every 2 seconds per Gmail account, regardless of how many tasks are running.</p>
 *
 * @see EmailSearchCriteria
 * @see EmailMessage
 */
public class GmailClient implements AutoCloseable {

    // ==================== Constants ====================

    private static final String GMAIL_IMAP_HOST = "imap.gmail.com";
    private static final int GMAIL_IMAP_PORT = 993;

    /**
     * How often the background poller fetches from INBOX (shared instances only).
     * Kept short to ensure near-real-time email detection while guaranteeing
     * only one IMAP operation per interval per Gmail account.
     */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    // ==================== Shared Instance Pool ====================

    /**
     * Global cache of shared instances keyed by {@code catchallEmail:appPassword}.
     * Access is guarded by {@link #SHARED_LOCK} for atomic ref-count + map operations.
     */
    private static final ConcurrentHashMap<String, GmailClient> sharedInstances = new ConcurrentHashMap<>();

    /**
     * Guards {@link #sharedInstances} mutations and ref-count transitions to
     * prevent races between {@link #shared} acquisition and {@link #release()}.
     *
     * <p>Held only briefly for map get/put/remove + ref-count increment/decrement.
     * {@link #connect()} and {@link #disconnect()} are called outside this lock
     * to avoid blocking the pool during network I/O.</p>
     */
    private static final Object SHARED_LOCK = new Object();

    // ==================== Instance Fields ====================

    private final String email;
    private final String catchallEmail;
    private final String appPassword;

    /** Whether this instance was created via {@link #shared} (pooled + polled). */
    private final boolean isShared;

    /** Cache key used for {@link #sharedInstances} lookup. Null for standalone instances. */
    private final String cacheKey;

    /** Number of active consumers. Only meaningful for shared instances. */
    private final AtomicInteger refCount = new AtomicInteger(0);

    /**
     * Guards all access to {@link #session}, {@link #store}, and {@link #connected}.
     *
     * <p>Read lock: held during IMAP fetch operations (poller thread, standalone fetches).</p>
     * <p>Write lock: held during {@link #connect()}, {@link #disconnect()}, and
     * {@link #tryReconnect()} — exclusive access while mutating connection state.</p>
     */
    private final ReentrantReadWriteLock connectionLock = new ReentrantReadWriteLock();

    // --- Background Poller (shared instances only) ---

    /**
     * Background poller that fetches from INBOX every {@link #POLL_INTERVAL}.
     * Created lazily on first extractor registration, shut down when the last
     * extractor unregisters or the instance disconnects.
     */
    private ScheduledExecutorService pollerExecutor;

    /**
     * Latest polled messages snapshot. Written by the poller thread, read by
     * any extractor thread. Volatile for safe publication without locking.
     * Always an immutable list ({@link List#copyOf}).
     */
    private volatile List<EmailMessage> polledMessages = List.of();

    /**
     * Registered monitoring start times, keyed by extractor identity. The poller
     * uses the earliest value as its IMAP "since" parameter to ensure all
     * extractors' time ranges are covered.
     */
    private final ConcurrentHashMap<Object, Instant> registeredSinceTimes = new ConcurrentHashMap<>();

    /**
     * Guards poller lifecycle transitions (start/stop) to prevent races between
     * concurrent {@link #registerSinceTime} and {@link #unregisterSinceTime} calls.
     */
    private final Object pollerLifecycleLock = new Object();

    // --- IMAP connection state ---

    private Session session;
    private Store store;
    private boolean connected = false;

    // ==================== Constructors ====================

    /**
     * Creates a new standalone GmailClient with the specified credentials.
     *
     * <p>Standalone instances are not pooled and do not use background polling.
     * Each {@link #fetchMessages} call executes a direct IMAP operation
     * against the requested folder.</p>
     *
     * <p>Note: This does not establish a connection. Call {@link #connect()} before
     * fetching messages.</p>
     *
     * @param email         the Gmail address (the profile's email for recipient matching)
     * @param catchallEmail the catchall email used for IMAP authentication
     * @param appPassword   the app password (not the account password)
     * @throws IllegalArgumentException if any argument is null or blank
     */
    public GmailClient(String email, String catchallEmail, String appPassword) {
        this(email, catchallEmail, appPassword, false, null);

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email cannot be null or blank");
        }
    }

    /**
     * All-args private constructor used by both the public constructor and
     * the {@link #shared} factory.
     *
     * @param email         the profile email (null for shared instances)
     * @param catchallEmail the catchall email for IMAP authentication
     * @param appPassword   the app password
     * @param isShared      true if this is a pooled instance
     * @param cacheKey      the pool key (null for standalone instances)
     */
    private GmailClient(String email, String catchallEmail, String appPassword,
                        boolean isShared, String cacheKey) {
        if (catchallEmail == null || catchallEmail.isBlank()) {
            throw new IllegalArgumentException("Catchall email cannot be null or blank");
        }
        if (appPassword == null || appPassword.isBlank()) {
            throw new IllegalArgumentException("App password cannot be null or blank");
        }

        this.email = email;
        this.catchallEmail = catchallEmail;
        this.appPassword = appPassword;
        this.isShared = isShared;
        this.cacheKey = cacheKey;
    }

    // ==================== Shared Instance Factory ====================

    /**
     * Acquires a shared, reference-counted GmailClient for the given catchall account.
     *
     * <p>If a shared instance already exists for this catchall + password combination,
     * its reference count is incremented and the existing instance is returned.
     * Otherwise, a new instance is created, connected, and cached.</p>
     *
     * <p>The caller <strong>must</strong> call {@link #release()} (or {@link #close()})
     * when finished. The underlying IMAP connection is closed automatically when the
     * last consumer releases.</p>
     *
     * <p>Shared instances use a background poller: INBOX is fetched every 2 seconds,
     * and {@link #fetchMessages} performs an instant in-memory filter on the latest
     * results.</p>
     *
     * @param catchallEmail the catchall email for IMAP authentication
     * @param appPassword   the app password
     * @return a shared GmailClient instance (already connected)
     * @throws GmailClientException     if connection fails
     * @throws IllegalArgumentException if arguments are null or blank
     */
    public static GmailClient shared(String catchallEmail, String appPassword) throws GmailClientException {
        if (catchallEmail == null || catchallEmail.isBlank()) {
            throw new IllegalArgumentException("Catchall email cannot be null or blank");
        }
        if (appPassword == null || appPassword.isBlank()) {
            throw new IllegalArgumentException("App password cannot be null or blank");
        }

        String key = catchallEmail.toLowerCase() + ":" + appPassword;

        GmailClient client;
        synchronized (SHARED_LOCK) {
            client = sharedInstances.computeIfAbsent(key,
                    k -> new GmailClient(null, catchallEmail, appPassword, true, k));
            client.refCount.incrementAndGet();
        }

        // connect() is idempotent and thread-safe, safe to call outside SHARED_LOCK
        client.connect();

        return client;
    }

    /**
     * Releases this consumer's reference to a shared instance.
     *
     * <p>Decrements the reference count. When the last consumer releases,
     * the instance is removed from the shared pool and disconnected.</p>
     *
     * <p>Safe to call multiple times — subsequent calls after the instance
     * has been removed from the pool will simply disconnect again (which
     * is itself idempotent).</p>
     *
     * @throws IllegalStateException if called on a standalone (non-shared) instance
     */
    public void release() {
        if (!isShared) {
            throw new IllegalStateException(
                    "release() can only be called on shared instances. Use close() for standalone instances.");
        }

        boolean shouldDisconnect = false;

        synchronized (SHARED_LOCK) {
            int remaining = refCount.decrementAndGet();
            if (remaining <= 0) {
                sharedInstances.remove(cacheKey);
                shouldDisconnect = true;
            }
        }

        // Disconnect outside SHARED_LOCK to avoid blocking the pool during I/O
        if (shouldDisconnect) {
            disconnect();
        }
    }

    // ==================== Connection Management ====================

    /**
     * Establishes a connection to Gmail's IMAP server.
     *
     * <p>This method is idempotent — calling it on an already-connected client
     * is a no-op. Acquires the write lock to ensure exclusive access during
     * connection setup.</p>
     *
     * @throws GmailClientException if connection or authentication fails
     */
    public void connect() throws GmailClientException {
        connectionLock.writeLock().lock();
        try {
            connectInternal();
        } finally {
            connectionLock.writeLock().unlock();
        }
    }

    /**
     * Establishes the IMAP connection. Must be called while holding the write lock.
     *
     * <p>Idempotent — returns immediately if already connected.</p>
     *
     * @throws GmailClientException if connection or authentication fails
     */
    private void connectInternal() throws GmailClientException {
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

        } catch (AuthenticationFailedException e) {
            throw new GmailClientException("Authentication failed. Check email and app password.", e);
        } catch (MessagingException e) {
            throw new GmailClientException("Failed to connect to Gmail IMAP server.", e);
        }
    }

    /**
     * Disconnects from the Gmail IMAP server.
     *
     * <p>Stops the background poller (if running) before closing the IMAP store.
     * Acquires the write lock to ensure no fetches are in progress when the store
     * is closed. Safe to call multiple times.</p>
     */
    public void disconnect() {
        // Stop poller before closing connection (lock ordering: pollerLifecycleLock → connectionLock)
        synchronized (pollerLifecycleLock) {
            stopPoller();
        }

        connectionLock.writeLock().lock();
        try {
            if (!connected || store == null) {
                return;
            }

            try {
                store.close();
            } catch (MessagingException e) {
                System.err.println("[GmailClient] Error during disconnect: " + e.getMessage());
            } finally {
                connected = false;
                store = null;
                session = null;
            }
        } finally {
            connectionLock.writeLock().unlock();
        }
    }

    /**
     * Checks if the client is currently connected.
     *
     * <p>Acquires the read lock for a consistent view of connection state.</p>
     *
     * @return true if connected to Gmail IMAP
     */
    public boolean isConnected() {
        connectionLock.readLock().lock();
        try {
            return connected && store != null && store.isConnected();
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    // ==================== Background Poller (Shared Instances) ====================

    /**
     * Registers an extractor's monitoring start time with the background poller.
     *
     * <p>The poller uses the earliest registered time as its IMAP "since"
     * parameter, ensuring all extractors' time ranges are covered. If this
     * is the first registration, the background poller is started.</p>
     *
     * @param key   the extractor identity (typically {@code this} from the caller)
     * @param since the extractor's monitoring start time
     * @throws IllegalStateException if called on a standalone instance
     */
    public void registerSinceTime(Object key, Instant since) {
        if (!isShared) {
            throw new IllegalStateException("registerSinceTime() is only for shared instances");
        }

        registeredSinceTimes.put(key, since);

        synchronized (pollerLifecycleLock) {
            if (pollerExecutor == null || pollerExecutor.isShutdown()) {
                startPoller();
            }
        }
    }

    /**
     * Unregisters an extractor's monitoring start time.
     *
     * <p>If no extractors remain registered, the background poller is stopped
     * to avoid unnecessary IMAP traffic.</p>
     *
     * @param key the extractor identity previously passed to {@link #registerSinceTime}
     */
    public void unregisterSinceTime(Object key) {
        if (!isShared) return;

        registeredSinceTimes.remove(key);

        synchronized (pollerLifecycleLock) {
            if (registeredSinceTimes.isEmpty() && pollerExecutor != null
                    && !pollerExecutor.isShutdown()) {
                stopPoller();
            }
        }
    }

    /**
     * Starts the background IMAP poller thread.
     *
     * <p>Must be called while holding {@link #pollerLifecycleLock}.</p>
     */
    private void startPoller() {
        pollerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GmailPoller-" + catchallEmail);
            t.setDaemon(true);
            return t;
        });

        pollerExecutor.scheduleAtFixedRate(this::pollInbox,
                0, POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);

        System.err.println("[GmailPoller] Started for " + catchallEmail);
    }

    /**
     * Stops the background IMAP poller thread.
     *
     * <p>Must be called while holding {@link #pollerLifecycleLock}.</p>
     */
    private void stopPoller() {
        if (pollerExecutor != null && !pollerExecutor.isShutdown()) {
            pollerExecutor.shutdownNow();
            System.err.println("[GmailPoller] Stopped for " + catchallEmail);
        }
        pollerExecutor = null;
        polledMessages = List.of();
    }

    /**
     * Single poller iteration. Called every {@link #POLL_INTERVAL} by the scheduler.
     *
     * <p>Fetches all recent emails from INBOX using the earliest registered "since"
     * time, then publishes the results to the volatile {@link #polledMessages} field.
     * All exceptions are caught to prevent the scheduler from dying.</p>
     */
    private void pollInbox() {
        try {
            Instant broadSince = registeredSinceTimes.values().stream()
                    .min(Instant::compareTo)
                    .orElse(null);

            if (broadSince == null) return;

            EmailSearchCriteria broadCriteria = EmailSearchCriteria.builder()
                    .since(broadSince)
                    .build();

            boolean needsReconnect = false;

            connectionLock.readLock().lock();
            try {
                if (!connected || store == null || !store.isConnected()) {
                    System.err.println("[GmailPoller] Connection lost, will attempt reconnect");
                    needsReconnect = true;
                } else {
                    List<EmailMessage> messages = executeFetch("INBOX", broadCriteria);
                    this.polledMessages = List.copyOf(messages);
                    System.err.println("[GmailPoller] Polled INBOX: " + messages.size()
                            + " messages (since=" + broadSince + ")");
                }
            } catch (GmailClientException e) {
                System.err.println("[GmailPoller] Fetch failed: " + e.getMessage());
                needsReconnect = true;
            } finally {
                connectionLock.readLock().unlock();
            }

            if (needsReconnect) {
                tryReconnect();
            }
        } catch (Exception e) {
            System.err.println("[GmailPoller] Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Attempts to re-establish the IMAP connection after a failure.
     *
     * <p>Closes the stale store and reconnects. Called by the poller thread
     * when a disconnection is detected.</p>
     */
    private void tryReconnect() {
        connectionLock.writeLock().lock();
        try {
            if (store != null) {
                try {
                    store.close();
                } catch (MessagingException ignored) {}
            }
            connected = false;
            store = null;
            session = null;

            connectInternal();
            System.err.println("[GmailPoller] Reconnected successfully");

        } catch (GmailClientException e) {
            System.err.println("[GmailPoller] Reconnect failed: " + e.getMessage());
        } finally {
            connectionLock.writeLock().unlock();
        }
    }

    // ==================== Message Fetching ====================

    /**
     * Fetches messages matching the specified criteria.
     *
     * <p><strong>Shared instances:</strong> Returns an in-memory filtered view of
     * the latest background poll results. No IMAP operation is performed — the
     * result is instant. The {@code folderName} parameter is ignored (the poller
     * always fetches from INBOX).</p>
     *
     * <p><strong>Standalone instances:</strong> Executes a direct IMAP fetch against
     * the specified folder. Acquires the read lock.</p>
     *
     * @param folderName the folder to search (ignored for shared instances)
     * @param criteria   the search criteria (can be null for all messages)
     * @return list of matching messages, newest first
     * @throws GmailClientException  if not connected or fetch fails
     * @throws IllegalStateException if not connected
     */
    public List<EmailMessage> fetchMessages(String folderName, EmailSearchCriteria criteria)
            throws GmailClientException {

        if (isShared) {
            // In-memory filter on the latest polled snapshot (instant, no IMAP, no locks)
            List<EmailMessage> snapshot = this.polledMessages;
            List<EmailMessage> filtered = filterMessages(snapshot, criteria);
            System.err.println("[GmailClient] fetchMessages (shared): snapshot=" + snapshot.size()
                    + ", afterFilter=" + filtered.size()
                    + ", criteria=" + criteria);
            return filtered;
        }

        // Standalone: direct IMAP fetch against the requested folder
        connectionLock.readLock().lock();
        try {
            ensureConnected();

            if (folderName == null || folderName.isBlank()) {
                folderName = "INBOX";
            }

            return executeFetch(folderName, criteria);

        } finally {
            connectionLock.readLock().unlock();
        }
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

    // ==================== In-Memory Filtering ====================

    /**
     * Filters a message list using the caller's full criteria.
     *
     * <p>Applies all filters in-memory: subject (substring), recipient (exact),
     * sender (exact/domain/domain-contains), and timestamp. Returns a new list
     * containing only matching messages, sorted newest first.</p>
     *
     * @param messages the messages to filter
     * @param criteria the caller's criteria (may be null for no filtering)
     * @return a new list containing only matching messages, newest first
     */
    private List<EmailMessage> filterMessages(List<EmailMessage> messages, EmailSearchCriteria criteria) {
        if (criteria == null) {
            List<EmailMessage> copy = new ArrayList<>(messages);
            copy.sort((a, b) -> b.receivedDate().compareTo(a.receivedDate()));
            return copy;
        }

        List<EmailMessage> results = new ArrayList<>();

        for (EmailMessage message : messages) {
            if (!matchesSubjectCriteria(message, criteria)) continue;
            if (!matchesSenderCriteria(message, criteria)) continue;
            if (!matchesRecipientCriteria(message, criteria)) continue;
            if (!matchesTimestampCriteria(message, criteria)) continue;
            results.add(message);
        }

        results.sort((a, b) -> b.receivedDate().compareTo(a.receivedDate()));
        return results;
    }

    // ==================== Direct IMAP Fetch ====================

    /**
     * Executes the actual IMAP folder open/search/close cycle using a two-phase
     * approach to minimize body downloads.
     *
     * <p><strong>Phase 1 (envelope):</strong> Runs the IMAP SEARCH, then prefetches
     * only envelope data (subject, sender, recipient, date) for all matching messages
     * in a single batch round-trip. Filters on header data to produce a small
     * candidate set.</p>
     *
     * <p><strong>Phase 2 (body):</strong> Downloads full message content only for
     * candidates that passed the header filter.</p>
     *
     * <p>This is critical for the background poller, whose broad criteria (only a
     * {@code since} date at day granularity) may match dozens of today's emails.
     * Without envelope pre-filtering, every one of those bodies would be downloaded
     * over IMAP — potentially blocking the poller thread for longer than its 2-second
     * cycle.</p>
     *
     * <p>Must be called while holding the connection read lock.</p>
     *
     * @param folderName the folder to search
     * @param criteria   the search criteria (may be null)
     * @return list of matching messages, newest first
     * @throws GmailClientException if the IMAP operation fails
     */
    private List<EmailMessage> executeFetch(String folderName, EmailSearchCriteria criteria)
            throws GmailClientException {

        List<EmailMessage> results = new ArrayList<>();

        try (Folder folder = store.getFolder(folderName)) {
            folder.open(Folder.READ_ONLY);

            SearchTerm searchTerm = buildSearchTerm(criteria);

            Message[] messages;
            if (searchTerm != null) {
                messages = folder.search(searchTerm);
            } else {
                messages = folder.getMessages();
            }

            if (messages.length == 0) {
                return results;
            }

            // ── Phase 1: Envelope prefetch + header-only filter ──
            // Batch-fetch envelope data (subject, from, to, date) in one IMAP
            // round-trip. Without this, each getSubject()/getFrom() call triggers
            // an individual IMAP FETCH command.
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            folder.fetch(messages, fp);

            // Filter using only envelope data — no body download.
            // This narrows the candidate set before committing to expensive body
            // downloads. The key filter is timestamp: IMAP's ReceivedDateTerm
            // only works at day granularity, but our criteria.since is second-
            // precise. For a user who received 50 emails today, this typically
            // narrows to just 1-3 recent ones.
            List<Message> candidates = new ArrayList<>();
            for (Message message : messages) {
                try {
                    EmailMessage headerOnly = convertHeaders(message);
                    if (matchesSubjectCriteria(headerOnly, criteria)
                            && matchesSenderCriteria(headerOnly, criteria)
                            && matchesRecipientCriteria(headerOnly, criteria)
                            && matchesTimestampCriteria(headerOnly, criteria)) {
                        candidates.add(message);
                    }
                } catch (MessagingException e) {
                    System.err.println("[GmailClient] Failed to read message headers: "
                            + e.getMessage());
                }
            }

            System.err.println("[GmailClient] executeFetch: " + messages.length
                    + " from IMAP search, " + candidates.size()
                    + " passed header filter");

            // ── Phase 2: Body download for candidates only ──
            for (Message message : candidates) {
                try {
                    results.add(convertMessage(message));
                } catch (Exception e) {
                    System.err.println("[GmailClient] Failed to parse message body: "
                            + e.getMessage());
                }
            }

            results.sort((a, b) -> b.receivedDate().compareTo(a.receivedDate()));

        } catch (MessagingException e) {
            throw new GmailClientException("Failed to fetch messages from folder: " + folderName, e);
        }

        return results;
    }

    /**
     * Lists available folders in the mailbox.
     *
     * @return list of folder names
     * @throws GmailClientException if listing fails
     */
    public List<String> listFolders() throws GmailClientException {
        connectionLock.readLock().lock();
        try {
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

        } finally {
            connectionLock.readLock().unlock();
        }
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

        if (criteria.subject != null && !criteria.subject.isBlank()) {
            terms.add(new SubjectTerm(criteria.subject));
        }

        if (criteria.recipient != null && !criteria.recipient.isBlank()) {
            terms.add(new RecipientStringTerm(Message.RecipientType.TO, criteria.recipient));
        }

        if (criteria.senderExact != null && !criteria.senderExact.isBlank()) {
            terms.add(new FromStringTerm(criteria.senderExact));
        }

        if (criteria.since != null) {
            Date sinceDate = Date.from(criteria.since);
            terms.add(new ReceivedDateTerm(ComparisonTerm.GE, sinceDate));
        }

        if (terms.isEmpty()) {
            return null;
        }

        if (terms.size() == 1) {
            return terms.getFirst();
        }

        return new AndTerm(terms.toArray(new SearchTerm[0]));
    }

    // ==================== Post-Fetch Filtering ====================

    /**
     * Checks if an email matches the subject criteria (case-insensitive substring match).
     *
     * <p>Mirrors the behavior of IMAP's {@link SubjectTerm}. Used by
     * {@link #filterMessages} where the broad fetch did not apply a subject filter.</p>
     */
    private boolean matchesSubjectCriteria(EmailMessage message, EmailSearchCriteria criteria) {
        if (criteria == null || criteria.subject == null || criteria.subject.isBlank()) {
            return true;
        }
        String subject = message.subject();
        if (subject == null) {
            return false;
        }
        return subject.toLowerCase().contains(criteria.subject.toLowerCase());
    }

    /**
     * Checks if an email matches the recipient criteria (case-insensitive exact match).
     */
    private boolean matchesRecipientCriteria(EmailMessage message, EmailSearchCriteria criteria) {
        if (criteria == null || criteria.recipient == null || criteria.recipient.isBlank()) {
            return true;
        }

        String recipient = message.recipient();
        if (recipient == null) {
            return false;
        }

        return recipient.equalsIgnoreCase(criteria.recipient);
    }

    /**
     * Checks if an email matches the sender criteria.
     * Handles exact, domain, and domain-contains matching.
     */
    private boolean matchesSenderCriteria(EmailMessage message, EmailSearchCriteria criteria) {
        if (criteria == null) {
            return true;
        }

        // If criteria has no sender requirements, any sender (including null) matches
        boolean hasSenderCriteria = (criteria.senderExact != null && !criteria.senderExact.isBlank())
                || (criteria.senderDomain != null && !criteria.senderDomain.isBlank())
                || (criteria.senderDomainContains != null && !criteria.senderDomainContains.isBlank());
        if (!hasSenderCriteria) {
            return true;
        }

        String sender = message.sender();
        if (sender == null) {
            return false;
        }

        sender = sender.toLowerCase();

        if (criteria.senderExact != null && !criteria.senderExact.isBlank()) {
            if (!sender.equals(criteria.senderExact.toLowerCase())) {
                return false;
            }
        }

        if (criteria.senderDomain != null && !criteria.senderDomain.isBlank()) {
            String domain = criteria.senderDomain.toLowerCase();
            if (!domain.startsWith("@")) {
                domain = "@" + domain;
            }
            if (!sender.endsWith(domain)) {
                return false;
            }
        }

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

    /**
     * Checks if an email's received date is at or after the criteria's since instant.
     */
    private boolean matchesTimestampCriteria(EmailMessage message, EmailSearchCriteria criteria) {
        if (criteria == null || criteria.since == null) {
            return true;
        }
        return !message.receivedDate().isBefore(criteria.since);
    }

    // ==================== Message Conversion ====================

    /**
     * Extracts only envelope (header) data from a JavaMail Message — no body download.
     *
     * <p>Returns an {@link EmailMessage} with null {@code htmlBody} and {@code textBody}.
     * All header fields ({@code subject}, {@code recipient}, {@code sender},
     * {@code receivedDate}) are populated from the IMAP envelope, which is available
     * without downloading the message body when a {@link FetchProfile} with
     * {@link FetchProfile.Item#ENVELOPE} has been applied.</p>
     *
     * <p>Used by {@link #executeFetch} to pre-filter messages on header data before
     * committing to the expensive body download.</p>
     *
     * @param message the raw IMAP message (envelope must be prefetched)
     * @return an EmailMessage with header fields only (null bodies)
     * @throws MessagingException if header extraction fails
     */
    private EmailMessage convertHeaders(Message message) throws MessagingException {
        String subject = message.getSubject();

        Address[] toAddress = message.getRecipients(Message.RecipientType.TO);
        String recipient = (toAddress != null && toAddress.length > 0
                && toAddress[0] instanceof InternetAddress ia) ? ia.getAddress() : null;

        Address[] fromAddresses = message.getFrom();
        String sender = (fromAddresses != null && fromAddresses.length > 0
                && fromAddresses[0] instanceof InternetAddress ia) ? ia.getAddress() : null;

        Date receivedDate = message.getReceivedDate();
        Instant receivedInstant = (receivedDate != null) ? receivedDate.toInstant() : Instant.now();

        return new EmailMessage(subject, recipient, sender, null, null, receivedInstant);
    }

    /**
     * Converts a JavaMail Message to our EmailMessage record, including the full body.
     *
     * <p>Uses {@link InternetAddress#getAddress()} to extract clean email addresses
     * from recipient and sender fields, handling all RFC 2822 formats including
     * display names with angle brackets (e.g., "Hide My Email &lt;user@icloud.com&gt;").</p>
     *
     * <p><strong>Performance note:</strong> This method calls {@link Message#getContent()},
     * which triggers a full body download over IMAP. Callers should pre-filter using
     * {@link #convertHeaders} to avoid downloading bodies for non-matching messages.</p>
     */
    private EmailMessage convertMessage(Message message) throws MessagingException, IOException {
        String subject = message.getSubject();

        Address[] toAddress = message.getRecipients(Message.RecipientType.TO);
        String recipient = (toAddress != null && toAddress.length > 0
                && toAddress[0] instanceof InternetAddress ia) ? ia.getAddress() : null;

        Address[] fromAddresses = message.getFrom();
        String sender = (fromAddresses != null && fromAddresses.length > 0
                && fromAddresses[0] instanceof InternetAddress ia) ? ia.getAddress() : null;

        Date receivedDate = message.getReceivedDate();
        Instant receivedInstant = (receivedDate != null) ? receivedDate.toInstant() : Instant.now();

        String htmlBody = null;
        String textBody = null;

        Object content = message.getContent();

        if (content instanceof String stringContent) {
            if (message.isMimeType("text/html")) {
                htmlBody = stringContent;
            } else {
                textBody = stringContent;
            }
        } else if (content instanceof MimeMultipart multipart) {
            BodyContent bodyContent = extractBodyFromMultipart(multipart);
            htmlBody = bodyContent.html;
            textBody = bodyContent.text;
        }

        return new EmailMessage(subject, recipient, sender, htmlBody, textBody, receivedInstant);
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
            } else if (part.getContent() instanceof MimeMultipart nestedMultipart) {
                BodyContent nested = extractBodyFromMultipart(nestedMultipart);
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

    /**
     * Ensures the client is connected, throwing if not.
     *
     * <p>Must be called while holding at least the read lock.</p>
     *
     * @throws IllegalStateException if not connected
     */
    private void ensureConnected() {
        if (!connected || store == null || !store.isConnected()) {
            throw new IllegalStateException("GmailClient is not connected. Call connect() first.");
        }
    }

    // ==================== Accessors ====================

    /**
     * Returns the profile email address this client is associated with.
     *
     * <p>Returns {@code null} for shared instances, which serve multiple
     * profiles and have no single profile email.</p>
     *
     * @return the email address, or null for shared instances
     */
    public String email() {
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

    /**
     * Returns whether this is a shared (pooled) instance.
     *
     * @return true if created via {@link #shared}, false if standalone
     */
    public boolean isShared() {
        return isShared;
    }

    /**
     * Returns the current reference count (shared instances only).
     *
     * @return the number of active consumers, or 0 for standalone instances
     */
    public int refCount() {
        return refCount.get();
    }

    /**
     * Closes this client.
     *
     * <p>For shared instances, this calls {@link #release()} to decrement the
     * reference count. For standalone instances, this calls {@link #disconnect()}
     * directly.</p>
     */
    @Override
    public void close() {
        if (isShared) {
            release();
        } else {
            disconnect();
        }
    }

    @Override
    public String toString() {
        if (isShared) {
            return String.format("GmailClient{shared=true, catchall=%s, refs=%d, connected=%s}",
                    catchallEmail, refCount.get(), connected);
        }
        return String.format("GmailClient{email=%s, catchall=%s, connected=%s}",
                email, catchallEmail, connected);
    }

    // ==================== Inner Classes ====================

    /**
     * Immutable container for email message data.
     *
     * @param subject      the email subject line
     * @param recipient    the recipient address
     * @param sender       the sender address
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

            /**
             * Filters by recipient address (exact match).
             *
             * @param recipient the recipient email address
             * @return this builder
             */
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
