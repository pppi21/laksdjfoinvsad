# nodriver4j --- Details for admissions officer

A browser automation framework built from scratch in Java, paired with a custom-patched Chromium build designed to be undetectable by modern anti-bot fingerprinting services. These are some highlights of the project that I think demostrate technical ability and knowledge of the domain.

---

## 1. Canvas Fingerprint Noise --- Anti-Aliased Edge Pixel Targeting

Note (3/5/26): This implementation is based on my fundemental misunderstanding of how rounding errors occur in the rendering pipeline. As of now this noise injection is easily detectable as artificial but I'm studying up and working on a fix.

Note (3/16/26): I fixed this a couple weeks ago, now it applies similar deterministic noise to canvas methods like arc(), which is effective at changing the hashed output in a natural way, but doesn't change the hashed output of all getImageData() calls.

Modern fingerprinting services (BrowserScan, CreepJS, FingerprintJS) hash the pixel output of canvas draw operations to identify browsers. The typical approach, adding random noise to every pixel, is trivially detected because it disrupts the visual coherence of the image.

This implementation takes a fundamentally different approach: it identifies anti-aliased transition pixels (where neighboring pixels differ by a small amount) and applies ±1 sub-channel noise only to those edge regions. This makes the modifications visually imperceptible and statistically indistinguishable from natural rendering variance across GPUs:

```cpp
// Pass 1: Identify anti-aliased edge pixels (small neighbor difference)
for (int y = 0; y < h; y++) {
    for (int x = 0; x < w; x++) {
        auto* current = (uint32_t*)((const char*)addr + y * fRowBytes + x * sizeof(uint32_t));
        // ...extract r, g, b channels...

        // Check right neighbor
        if (x < w - 1) {
            auto* right = (uint32_t*)((const char*)addr + y * fRowBytes + (x + 1) * sizeof(uint32_t));
            if (*current != *right) {
                int diff = std::abs(cr - rr) + std::abs(cg - rg) + std::abs(cb - rb);
                // Small difference = anti-aliasing zone (1-80 total across RGB)
                // Large difference = hard edge boundary --- skip
                if (diff >= 1 && diff <= 80) {
                    is_transition = true;
                }
            }
        }
        if (is_transition) {
            edge_indices.push_back(y * w + x);
        }
    }
}
```

A seed-deterministic subset of these edge pixels is then selected via a Fisher-Yates partial shuffle, and each pixel gets per-channel ±1 adjustments based on a per-pixel hash:

```cpp
// Per-pixel hash determines which channels to modify and direction
size_t pixel_hash = std::hash<std::string>{}(seed_str + "_p" + std::to_string(idx));

bool mod_r = (pixel_hash & 0x7) >= 3;        // ~60% chance per channel
bool mod_g = ((pixel_hash >> 3) & 0x7) >= 3;
bool mod_b = ((pixel_hash >> 6) & 0x7) >= 3;

int dr = ((pixel_hash >> 9) & 1) ? 1 : -1;   // ±1 only
int dg = ((pixel_hash >> 10) & 1) ? 1 : -1;
int db = ((pixel_hash >> 11) & 1) ? 1 : -1;
```

This covers all four canvas pixel extraction surfaces: `toDataURL()`, `toBlob()`, `getImageData()`, and WebGL `readPixels()` --- the same seed produces the same fingerprint every time, but different seeds produce different fingerprints, enabling consistent profiles between browser sessions.

---

## 2. Custom CDP Command --- `DOM.getShadowRoot`

Closed Shadow DOMs are intentionally inaccessible from page JavaScript (`element.shadowRoot` returns `null`). Rather than adding a non-standard property like `fakeShadowRoot` to `Element.prototype` (which would be detected by scripts that hash prototype property names), this patch adds an entirely new CDP command that operates at the DevTools protocol layer --- completely invisible to page JavaScript:

```
// DOM.pdl --- Protocol Definition Language
experimental command getShadowRoot
  parameters
    optional NodeId nodeId
    optional BackendNodeId backendNodeId
    optional Runtime.RemoteObjectId objectId
  returns
    optional Node shadowRoot
```

The C++ implementation uses Blink's internal `GetShadowRoot()` which bypasses the open/closed access check:

```cpp
protocol::Response InspectorDOMAgent::getShadowRoot(
    std::optional<int> node_id,
    std::optional<int> backend_node_id,
    std::optional<String> object_id,
    std::unique_ptr<protocol::DOM::Node>* shadow_root) {
    Node* node = nullptr;
    protocol::Response response =
        AssertNode(node_id, backend_node_id, object_id, node);
    if (!response.IsSuccess())
        return response;

    auto* element = DynamicTo<Element>(node);
    if (!element)
        return protocol::Response::ServerError("Node is not an Element");

    ShadowRoot* root = element->GetShadowRoot();
    if (root) {
        *shadow_root = BuildObjectForNode(root, 0, false,
                                          document_node_to_id_map_.Get());
    }
    return protocol::Response::Success();
}
```

This means the Java automation app can access any closed Shadow DOM via a simple `CDP.send("DOM.getShadowRoot", ...)` call, with zero page-side footprint. This is used in practice to solve PerimeterX captchas, where the challenge lives inside a closed shadow root containing multiple iframes --- only one of which contains a valid captcha:

```java
// Java side --- piercing the closed shadow root via the custom CDP command
JsonObject shadowParams = new JsonObject();
shadowParams.addProperty("nodeId", captchaNodeId);

JsonObject shadowResult = cdp.send("DOM.getShadowRoot", shadowParams);
int shadowRootNodeId = shadowResult
    .getAsJsonObject("shadowRoot").get("nodeId").getAsInt();

// Then inspect children to find the visible iframe (display: block)
JsonObject describeParams = new JsonObject();
describeParams.addProperty("nodeId", shadowRootNodeId);
describeParams.addProperty("depth", 1);
describeParams.addProperty("pierce", true);

JsonObject describeResult = cdp.send("DOM.describeNode", describeParams);
```

---

## 3. Fitts's Law & Bezier Curve Human Simulation

Mouse movement in automation is a solved problem in the trivial sense --- move from A to B. Making that movement indistinguishable from a human is not. This implementation applies **Fitts's Law**, a well-established model from human-computer interaction research, to calculate how many steps a movement path should contain based on distance and target size. Larger, closer targets get fewer steps (faster movement); smaller, distant targets get more (slower, more careful movement):

**Refactor of https://github.com/Xetera/ghost-cursor by the way. This was not an original idea, just a semi-unique implementation.**

```java
/**
 * Fitts's Law: ID = log2(D/W + 1)
 * Models the time required to move to a target as a function
 * of distance and target width.
 */
public static double fitts(double distance, double width) {
    double indexOfDifficulty = Math.log(distance / width + 1) / Math.log(2);
    return 2 * indexOfDifficulty;
}
```

The path itself is a **cubic Bezier curve** with randomized control points. Both control points are placed on the **same side** of the start-to-end line, producing a smooth arc rather than an unnatural S-curve. The control point placement uses perpendicular vectors offset from random positions along the line:

```java
private static Vector calculateControlPoint(Vector start, Vector end,
                                            double spread, int side) {
    // Random point on the line between start and end
    Vector randomMid = Vector.randomOnLine(start, end);

    // Perpendicular vector with magnitude = spread
    Vector direction = start.directionTo(randomMid);
    Vector perpendicular = direction.perpendicular().withMagnitude(spread);

    // Apply side multiplier (both CPs use same side → smooth arc, not S-curve)
    Vector offset = perpendicular.multiply(side);

    // Random point between midpoint and the offset → control point
    return Vector.randomOnLine(randomMid, randomMid.add(offset));
}
```

The spread (how far control points deviate from a straight line) is clamped between 2px and 200px based on the distance --- short movements are nearly straight, long movements have wide arcs. The final step count combines Fitts's Law with a speed factor:

```java
double baseTime = speed * MIN_STEPS;            // speed ∈ [0, 1]
double fittsValue = fitts(length, width);        // index of difficulty
int steps = (int) Math.ceil(
    (Math.log(fittsValue + 1) / Math.log(2) + baseTime) * 3
);
steps = Math.max(steps, MIN_STEPS);             // floor of 25 points
```

For distant targets (>500px), the system also simulates **overshoot and correction** --- the mouse moves past the target to a random point within 120px, then makes a second, tighter Bezier movement back. This mirrors a well-documented pattern in human motor control.

Typing simulation uses a similar depth of modeling. Keystroke delays follow a **Gaussian distribution** centered in the configured range, and timing adjusts based on character context --- common English bigrams (`th`, `he`, `in`, `er`) are typed 30% faster, repeated characters 20% faster, and characters after punctuation 20% slower:

```java
public static int keystrokeDelay(char currentChar, Character previousChar,
                                 int minMs, int maxMs) {
    double multiplier = 1.0;

    if (previousChar != null) {
        String bigram = "" + Character.toLowerCase(previousChar)
                           + Character.toLowerCase(currentChar);

        if (isCommonBigram(bigram)) {
            multiplier = 0.7;   // 30% faster --- trained muscle memory
        } else if (previousChar == currentChar) {
            multiplier = 0.8;   // 20% faster --- finger already in position
        } else if (previousChar == ' ' || isPunctuation(previousChar)) {
            multiplier = 1.2;   // 20% slower --- cognitive pause after word boundary
        }
    }

    return randomDelayGaussian(
        (int) (minMs * multiplier),
        (int) (maxMs * multiplier));
}
```

---

## 4. IMAP Connection Pooling with Batch-Cached Fetching

When dozens of automation tasks run concurrently --- each polling for its own email (OTP codes, verification links) --- the approach of one IMAP connection per task quickly hits Gmail's rate limits. This implementation solves the problem with a reference-counted shared connection pool and a broad fetch-then-filter-in-memory caching strategy that collapses N IMAP operations per poll cycle into 1.

The shared instance factory uses a `ConcurrentHashMap` keyed by `catchallEmail:appPassword`. Multiple extractors sharing the same catchall account receive the same `GmailClient` instance. An `AtomicInteger` tracks consumers, and the IMAP connection is closed only when the last one releases:

```java
public static GmailClient shared(String catchallEmail, String appPassword)
        throws GmailClientException {

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

public void release() {
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
```

Note that `connect()` and `disconnect()` are deliberately called **outside** the pool lock --- network I/O under a global lock would serialize all pool operations across all accounts.

The batch caching layer is where the real throughput gain comes from. Instead of each extractor running its own IMAP folder-open/search/close cycle, one thread performs a broad fetch against `[Gmail]/All Mail` (no subject or sender filter --- just a `since` timestamp) and caches the results with a 3-second TTL. Every other extractor that polls within that window filters the cached results in-memory using its own criteria:

```java
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
        // Double-check after acquiring lock
        cached = this.cachedFetch;
        if (cached != null && cached.isFresh() && cached.covers(callerSince)) {
            return filterCached(cached.messages, criteria);
        }

        // Preserve the broadest 'since' timestamp for maximum cache coverage
        Instant broadSince = callerSince;
        if (cached != null && cached.since != null
                && broadSince != null && cached.since.isBefore(broadSince)) {
            broadSince = cached.since;
        }

        // One broad IMAP fetch --- all other threads will read from this cache
        fetchSemaphore.acquire();
        try {
            EmailSearchCriteria broadCriteria = broadSince != null
                    ? EmailSearchCriteria.builder().since(broadSince).build()
                    : null;

            List<EmailMessage> allMessages = executeFetch(ALL_MAIL_FOLDER, broadCriteria);
            this.cachedFetch = new CachedFetch(
                    List.copyOf(allMessages), broadSince, Instant.now());
        } finally {
            fetchSemaphore.release();
        }

        return filterCached(this.cachedFetch.messages, criteria);

    } finally {
        cacheRefreshLock.unlock();
    }
}
```

The cache coverage check ensures correctness: a cache fetched with `since=T1` can serve a request for `since=T2` only if `T1 ≤ T2` (the cache looked at least as far back). When refreshing, the system preserves the **broadest** (earliest) `since` from the stale cache so that extractors with older time windows still get cache hits:

```java
private record CachedFetch(List<EmailMessage> messages, Instant since, Instant fetchedAt) {

    boolean isFresh() {
        return Duration.between(fetchedAt, Instant.now()).compareTo(CACHE_TTL) < 0;
    }

    boolean covers(Instant callerSince) {
        if (callerSince == null) return since == null;
        if (since == null) return true;
        return !since.isAfter(callerSince);
    }
}
```

The full system layers **four synchronization primitives**, each solving a distinct problem:

| Primitive | Purpose |
|---|---|
| `ReentrantReadWriteLock` | Connection lifecycle (write lock) vs concurrent fetches (read lock) --- multiple threads can fetch simultaneously, but `connect()`/`disconnect()` get exclusive access |
| `Semaphore(7)` | Caps concurrent IMAP folder operations below Gmail's 15-connection limit, preventing undocumented command-rate throttling |
| `ReentrantLock` | Serializes cache refreshes --- only one thread performs the broad IMAP fetch while others wait, then all read from the fresh cache |
| `volatile` field | Enables lock-free fast-path cache reads via double-checked locking --- the common case (cache hit) requires zero synchronization overhead |

The in-memory filter applies the caller's full criteria (subject substring, recipient exact match, sender domain/exact/contains, timestamp) against the cached message list, mirroring the same logic used for direct IMAP search term construction:

```java
private List<EmailMessage> filterCached(List<EmailMessage> messages,
                                        EmailSearchCriteria criteria) {
    List<EmailMessage> results = new ArrayList<>();

    for (EmailMessage message : messages) {
        if (!matchesSubjectCriteria(message, criteria))  continue;
        if (!matchesSenderCriteria(message, criteria))   continue;
        if (!matchesRecipientCriteria(message, criteria)) continue;
        if (!matchesTimestampCriteria(message, criteria)) continue;
        results.add(message);
    }

    results.sort((a, b) -> b.receivedDate().compareTo(a.receivedDate()));
    return results;
}
```

The result: dozens of extractors polling every 5 seconds against the same catchall account produce at most one IMAP operation every 3 seconds, regardless of how many extractors are active. The shared connection, reference counting, and cache coverage tracking ensure this works correctly across varying extractor lifecycles without leaking connections or serving stale data.

---


(styled and formatted with AI in case it matters)
