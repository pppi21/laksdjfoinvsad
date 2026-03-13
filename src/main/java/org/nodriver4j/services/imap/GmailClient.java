package org.nodriver4j.services.imap;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
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
 * acquire an exclusive write lock, while {@link #fetchMessages} acquires a shared
 * read lock — allowing multiple concurrent fetches against the same IMAP Store.
 * A {@link Semaphore} additionally caps the number of concurrent folder
 * open/search/close cycles to avoid triggering Gmail's IMAP rate limits.</p>
 *
 * <h2>Shared Instances (Connection Pooling)</h2>
 * <p>Use {@link #shared(String, String)} to obtain a reference-counted instance
 * that is cached by catchall email + app password. Multiple consumers sharing the
 * same catchall account receive the same {@code GmailClient} instance and the same
 * underlying IMAP connection. Call {@link #release()} (or {@link #close()}) when
 * finished — the connection is closed automatically when the last consumer releases.</p>
 *
 * <h2>Batch Fetching (Shared Instances)</h2>
 * <p>Shared instances automatically batch IMAP operations. Instead of each caller
 * executing its own folder open/search/close cycle, one thread performs a broad
 * fetch against {@code [Gmail]/All Mail} and caches the results with a short TTL.
 * Subsequent callers within the TTL window filter the cached results in-memory
 * using their own criteria — collapsing N IMAP operations per poll cycle into 1.</p>
 *
 * <h2>Standalone Instances</h2>
 * <p>The public constructor creates a standalone instance that is not pooled and
 * does not use batch caching. Each {@link #fetchMessages} call executes a direct
 * IMAP operation against the requested folder. Suitable for testing and one-off use.</p>
 *
 * <h2>Usage Example (Shared)</h2>
 * <pre>{@code
 * GmailClient client = GmailClient.shared("catchall@gmail.com", "app-password");
 * try {
 *     EmailSearchCriteria criteria = EmailSearchCriteria.builder()
 *         .subject("Welcome")
 *         .recipient("user@gmail.com")
 *         .since(Instant.now().minusSeconds(60))
 *         .build();
 *
 *     List<EmailMessage> messages = client.fetchMessages("INBOX", criteria);
 * } finally {
 *     client.release();
 * }
 * }</pre>
 *
 * <h2>Usage Example (Standalone)</h2>
 * <pre>{@code
 * try (GmailClient client = new GmailClient("user@gmail.com", "catchall@gmail.com", "app-password")) {
 *     client.connect();
 *     List<EmailMessage> messages = client.fetchMessages("INBOX", criteria);
 * }
 * }</pre>
 *
 * <h2>Sender Filtering Modes</h2>
 * <ul>
 *   <li><strong>Exact match:</strong> {@code senderExact("noreply@uber.com")} — matches exactly</li>
 *   <li><strong>Domain match:</strong> {@code senderDomain("icloud.com")} — matches *@icloud.com</li>
 *   <li><strong>Domain contains:</strong> {@code senderDomainContains("icloud")} — matches *@*.icloud.* (subdomains)</li>
 * </ul>
 *
 * @see EmailSearchCriteria
 * @see EmailMessage
 */
public class GmailClient implements AutoCloseable {

    // ==================== Constants ====================

    private static final String GMAIL_IMAP_HOST = "imap.gmail.com";
    private static final int GMAIL_IMAP_PORT = 993;

    /**
     * Maximum number of concurrent folder open/search/close cycles allowed
     * per GmailClient instance. Gmail caps IMAP connections at 15 per account;
     * this limits concurrent folder operations well below that threshold to
     * avoid triggering undocumented command-rate throttling.
     */
    private static final int MAX_CONCURRENT_FETCHES = 7;

    /**
     * The Gmail folder that contains all messages across all labels.
     * Used by shared instances for broad cache fetches.
     */
    private static final String ALL_MAIL_FOLDER = "[Gmail]/All Mail";

    /**
     * How long cached fetch results remain valid before a refresh is needed.
     * Kept short to ensure near-real-time email detection while still
     * collapsing concurrent IMAP operations into one.
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(3);

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

    /** Whether this instance was created via {@link #shared} (pooled + cached). */
    private final boolean isShared;

    /** Cache key used for {@link #sharedInstances} lookup. Null for standalone instances. */
    private final String cacheKey;

    /** Number of active consumers. Only meaningful for shared instances. */
    private final AtomicInteger refCount = new AtomicInteger(0);

    /**
     * Guards all access to {@link #session}, {@link #store}, and {@link #connected}.
     *
     * <p>Read lock: held during {@link #fetchMessages} and {@link #isConnected()} —
     * multiple threads can fetch concurrently.</p>
     * <p>Write lock: held during {@link #connect()} and {@link #disconnect()} —
     * exclusive access while mutating connection state.</p>
     */
    private final ReentrantReadWriteLock connectionLock = new ReentrantReadWriteLock();

    /**
     * Limits the number of threads that can execute an IMAP folder
     * open/search/close cycle at the same time. Layered on top of the
     * read lock — a thread must hold both the read lock AND a semaphore
     * permit before touching a folder.
     */
    private final Semaphore fetchSemaphore = new Semaphore(MAX_CONCURRENT_FETCHES, true);

    // --- Batch fetch cache (shared instances only) ---

    /**
     * Most recent broad fetch result. Read via volatile for fast-path cache
     * hits without locking. Written under {@link #cacheRefreshLock}.
     */
    private volatile CachedFetch cachedFetch;

    /**
     * Serializes cache refresh operations so that only one thread performs
     * the broad IMAP fetch while others wait and then read the fresh cache.
     */
    private final ReentrantLock cacheRefreshLock = new ReentrantLock();

    // --- IMAP connection state ---

    private Session session;
    private Store store;
    private boolean connected = false;

    // ==================== Constructors ====================

    /**
     * Creates a new standalone GmailClient with the specified credentials.
     *
     * <p>Standalone instances are not pooled and do not use batch caching.
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
     * <p>Shared instances use batch caching: {@link #fetchMessages} fetches broadly
     * from {@code [Gmail]/All Mail} and caches results with a short TTL. Concurrent
     * callers filter the cached results in-memory, collapsing N IMAP operations into 1.</p>
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
            if (connected) {
                return;
            }

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
        } finally {
            connectionLock.writeLock().unlock();
        }
    }

    /**
     * Disconnects from the Gmail IMAP server.
     *
     * <p>Acquires the write lock to ensure no fetches are in progress
     * when the store is closed. Clears the batch cache. Safe to call multiple times.</p>
     */
    public void disconnect() {
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
                cachedFetch = null;
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

    // ==================== Message Fetching ====================

    /**
     * Fetches messages matching the specified criteria.
     *
     * <p><strong>Shared instances:</strong> Uses batch caching. A broad fetch against
     * {@code [Gmail]/All Mail} is performed once and cached with a short TTL
     * ({@value #CACHE_TTL}). Concurrent callers within the TTL window filter the
     * cached results in-memory using their own criteria — no IMAP operation is
     * executed. The {@code folderName} parameter is ignored (all mail is searched).</p>
     *
     * <p><strong>Standalone instances:</strong> Executes a direct IMAP fetch against
     * the specified folder. Acquires the read lock and a semaphore permit.</p>
     *
     * @param folderName the folder to search (ignored for shared instances)
     * @param criteria   the search criteria (can be null for all messages)
     * @return list of matching messages, newest first
     * @throws GmailClientException  if not connected or fetch fails
     * @throws IllegalStateException if not connected
     */
    public List<EmailMessage> fetchMessages(String folderName, EmailSearchCriteria criteria)
            throws GmailClientException {

        connectionLock.readLock().lock();
        try {
            ensureConnected();

            if (isShared) {
                return fetchFromCacheOrRefresh(criteria);
            }

            // Standalone: direct IMAP fetch against the requested folder
            if (folderName == null || folderName.isBlank()) {
                folderName = "INBOX";
            }

            try {
                fetchSemaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GmailClientException("Interrupted while waiting for fetch permit", e);
            }

            try {
                return executeFetch(folderName, criteria);
            } finally {
                fetchSemaphore.release();
            }

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

    // ==================== Batch Cache (Shared Instances) ====================

    /**
     * Attempts to serve results from the batch cache, refreshing if stale.
     *
     * <p>Uses double-checked locking: the fast path reads the volatile
     * {@link #cachedFetch} without any lock. On a cache miss, the
     * {@link #cacheRefreshLock} serializes refresh attempts so only one
     * thread performs the broad IMAP fetch while others wait and then
     * read the fresh cache.</p>
     *
     * <p>Must be called while holding the connection read lock.</p>
     *
     * @param criteria the caller's search criteria
     * @return filtered list of matching messages, newest first
     * @throws GmailClientException if the IMAP refresh fails
     */
    private List<EmailMessage> fetchFromCacheOrRefresh(EmailSearchCriteria criteria)
            throws GmailClientException {

        Instant callerSince = (criteria != null) ? criteria.since : null;

        // Fast path: volatile read, no locking
        CachedFetch cached = this.cachedFetch;
        if (cached != null && cached.isFresh() && cached.covers(callerSince)) {
            return filterCached(cached.messages, criteria);
        }

        // Slow path: serialize cache refreshes
        cacheRefreshLock.lock();
        try {
            // Double-check after acquiring lock — another thread may have refreshed
            cached = this.cachedFetch;
            if (cached != null && cached.isFresh() && cached.covers(callerSince)) {
                return filterCached(cached.messages, criteria);
            }

            // Determine the broadest since timestamp to maximize cache coverage.
            // If the stale cache had a broader (earlier) since, preserve it so
            // callers with older since values still get cache hits.
            Instant broadSince = callerSince;
            if (cached != null && cached.since != null
                    && broadSince != null && cached.since.isBefore(broadSince)) {
                broadSince = cached.since;
            }

            // Execute the broad IMAP fetch (requires semaphore permit)
            try {
                fetchSemaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GmailClientException("Interrupted while waiting for fetch permit", e);
            }

            try {
                EmailSearchCriteria broadCriteria = broadSince != null
                        ? EmailSearchCriteria.builder().since(broadSince).build()
                        : null;

                List<EmailMessage> allMessages = executeFetch(ALL_MAIL_FOLDER, broadCriteria);
                System.err.println("[GmailClient] Broad cache fetch from " + ALL_MAIL_FOLDER
                        + ": " + allMessages.size() + " messages (since=" + broadSince + ")");
                this.cachedFetch = new CachedFetch(List.copyOf(allMessages), broadSince, Instant.now());
            } finally {
                fetchSemaphore.release();
            }

            return filterCached(this.cachedFetch.messages, criteria);

        } finally {
            cacheRefreshLock.unlock();
        }
    }

    /**
     * Filters a cached message list using the caller's full criteria.
     *
     * <p>Applies all filters in-memory: subject (substring), recipient (exact),
     * sender (exact/domain/domain-contains), and timestamp. This mirrors the
     * combined behavior of IMAP search terms and post-fetch filtering.</p>
     *
     * @param messages the cached messages to filter
     * @param criteria the caller's criteria (may be null for no filtering)
     * @return a new list containing only matching messages, newest first
     */
    private List<EmailMessage> filterCached(List<EmailMessage> messages, EmailSearchCriteria criteria) {
        if (criteria == null) {
            List<EmailMessage> copy = new ArrayList<>(messages);
            copy.sort((a, b) -> b.receivedDate().compareTo(a.receivedDate()));
            return copy;
        }

        List<EmailMessage> results = new ArrayList<>();
        int subjectFail = 0, senderFail = 0, recipientFail = 0, timestampFail = 0;

        for (EmailMessage message : messages) {
            if (!matchesSubjectCriteria(message, criteria)) { subjectFail++; continue; }
            if (!matchesSenderCriteria(message, criteria))  { senderFail++;  continue; }
            if (!matchesRecipientCriteria(message, criteria)){ recipientFail++; continue; }
            if (!matchesTimestampCriteria(message, criteria)){ timestampFail++; continue; }
            results.add(message);
        }

        if (!messages.isEmpty() && results.isEmpty()) {
            System.err.println("[GmailClient] filterCached: " + messages.size()
                    + " messages → 0 results. Eliminated by — subject: " + subjectFail
                    + ", sender: " + senderFail + ", recipient: " + recipientFail
                    + ", timestamp: " + timestampFail);
            System.err.println("[GmailClient] Criteria: " + criteria);
            EmailMessage sample = messages.getFirst();
            System.err.println("[GmailClient] Sample msg — subject=\"" + sample.subject()
                    + "\", sender=\"" + sample.sender()
                    + "\", recipient=\"" + sample.recipient()
                    + "\", received=" + sample.receivedDate());
        }

        results.sort((a, b) -> b.receivedDate().compareTo(a.receivedDate()));
        return results;
    }

    /**
     * Snapshot of a broad fetch result with a time-to-live.
     *
     * @param messages  the fetched messages (immutable copy)
     * @param since     the since timestamp used for the broad fetch (may be null)
     * @param fetchedAt when this cache entry was created
     */
    private record CachedFetch(List<EmailMessage> messages, Instant since, Instant fetchedAt) {

        /**
         * Returns true if this cache entry is still within the TTL window.
         */
        boolean isFresh() {
            return Duration.between(fetchedAt, Instant.now()).compareTo(CACHE_TTL) < 0;
        }

        /**
         * Returns true if this cache entry covers the requested time range.
         *
         * <p>A cache with {@code since=T1} covers a request for {@code since=T2}
         * if {@code T1 ≤ T2} (the cache fetched at least as far back). A null
         * caller since (no time restriction) is only covered if the cache also
         * has no time restriction.</p>
         *
         * @param callerSince the caller's since timestamp, or null
         */
        boolean covers(Instant callerSince) {
            if (callerSince == null) {
                return since == null;
            }
            if (since == null) {
                return true;
            }
            return !since.isAfter(callerSince);
        }
    }

    // ==================== Direct IMAP Fetch ====================

    /**
     * Executes the actual IMAP folder open/search/close cycle.
     *
     * <p>Called only while holding both the read lock and a semaphore permit.</p>
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

            for (Message message : messages) {
                try {
                    EmailMessage emailMessage = convertMessage(message);

                    if (matchesSenderCriteria(emailMessage, criteria)
                            && matchesRecipientCriteria(emailMessage, criteria)
                            && matchesTimestampCriteria(emailMessage, criteria)) {
                        results.add(emailMessage);
                    }
                } catch (Exception e) {
                    System.err.println("[GmailClient] Failed to parse message: " + e.getMessage());
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
     * {@link #filterCached} where the broad fetch did not apply a subject filter.</p>
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
        System.out.println(recipient);
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
     * Converts a JavaMail Message to our EmailMessage record.
     */
    private EmailMessage convertMessage(Message message) throws MessagingException, IOException {
        String subject = message.getSubject();

        Address[] toAddress = message.getRecipients(Message.RecipientType.TO);
        String recipient = extractBetweenBrackets(
                (toAddress != null && toAddress.length > 0) ? toAddress[0].toString() : null);

        Address[] fromAddresses = message.getFrom();
        String sender = (fromAddresses != null && fromAddresses.length > 0)
                ? extractBetweenBrackets(fromAddresses[0].toString())
                : null;

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
     * Extracts the email address from between angle brackets.
     *
     * <p>Given {@code "John Doe <john@example.com>"}, returns {@code "john@example.com"}.
     * If no brackets are found, returns an empty string.</p>
     */
    private static String extractBetweenBrackets(String input) {
        if (input == null) {
            return "";
        }

        int start = input.indexOf('<');
        int end = input.indexOf('>');

        if (start != -1 && end != -1 && start < end) {
            return input.substring(start + 1, end);
        }

        return "";
    }

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