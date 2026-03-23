# nodriver4j --- Details for admissions officer

A browser automation framework built from scratch in Java, paired with a custom-patched Chromium build designed to be undetectable by modern anti-bot fingerprinting services. These are some highlights of the project that I think demostrate technical ability and knowledge of the domain.

---

## 1. Canvas Fingerprint Noise --- Anti-Aliased Edge Pixel Targeting

Note (3/5/26): This implementation is based on my fundemental misunderstanding of how rounding errors occur in the rendering pipeline. As of now this noise injection is easily detectable as artificial but I'm studying up and working on a fix.

Note (3/16/26): I fixed this a couple weeks ago. Now it applies similar deterministic noise to canvas methods like arc(), which is effective at changing the hashed output in a natural way, but doesn't change the hashed output of all getImageData() calls.

Modern fingerprinting services (BrowserScan, CreepJS, FingerprintJS) hash the pixel output of canvas draw operations to identify browsers. This section covers both the original approach (since removed) and the current implementation.

Previous Approach: Post-Render Pixel Noise (Removed)
The original implementation scanned rendered pixel data for anti-aliased transition zones, pixels where neighboring color values differed by a small amount, then applied ±1 sub-channel noise to a seed-deterministic subset of those edge pixels. This was hooked into all four canvas pixel extraction surfaces that I'm aware of: toDataURL(), toBlob(), getImageData(), and WebGL readPixels().

The upside was complete coverage: regardless of what was drawn or how pixels were read back, the fingerprint hash changed. The flaw was that it modified pixels after rendering, which was detectable. I'm not sure of the specific methods used to detect the modified pixels (if I knew I'd be able to circumvent detection), but it's likely done by statistically analyzing noise patterns and comparing them to realistic patterns. This approach was becoming a waste of time so I decided to try something else.

Current Approach: Pre-Render Geometry Shifts
Rather than modifying pixel output, the current implementation shifts input geometry before the browser's own rasterizer runs. A pair of deterministic sub-pixel offsets (ranging from 0.01 to 0.09 pixels) are derived from the --canvas-fingerprint seed and cached at startup:


```cpp
size_t hash_x = std::hash<std::string>{}(seed_str + "_cx");
size_t hash_y = std::hash<std::string>{}(seed_str + "_cy");
float dx = 0.01f + static_cast<float>(hash_x % 80) * 0.001f;
float dy = 0.01f + static_cast<float>(hash_y % 80) * 0.001f;
```

These offsets are applied to three CanvasPath methods in Blink's rendering engine:

- arc() - center point shifted by (dx, dy)
- quadraticCurveTo() - control point and endpoint shifted
- bezierCurveTo() - both control points and endpoint shifted

When an arc center moves by 0.04 pixels, the rasterizer produces naturally different anti-aliasing gradients at every curved edge. I'm not entirely happy with this implementation because it doesn't touch nearly as many outputs as the previous methods, so any fingerprinting script that doesn't include one of these three functions in their test would be unnaffected. In order to introduce a bit more coverage, font-based canvas fingerprints are handled separately by ApplyFontSpacingNoise() in harfbuzz_shaper.cc, which adjusts glyph spacing during text shaping to vary fillText()/strokeText() output.

### Summary
The tradeoff is that this approach only affects drawing operations that pass through the three modified path methods. If a fingerprinting test draws only straight lines (lineTo), fills rectangles, or uses any canvas operation that doesn't involve arc(), quadraticCurveTo(), or bezierCurveTo(), the geometry shifts have no effect and the fingerprint hash will be unchanged. The old pixel-noise approach covered all extraction surfaces regardless of what was drawn; the new approach trades that breadth for undetectability on the surfaces it does cover.

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

## 4. IMAP Connection Pooling with Background Polling

When dozens of automation tasks run concurrently, each polling for its own email (OTP codes, verification links), opening one IMAP connection per task quickly hits Gmail's rate limits. This implementation solves the problem with a shared connection pool and a background poller that collapses all email checking into a single IMAP operation every 2 seconds, regardless of how many tasks are active.

The GmailClient.shared() factory returns a reference-counted instance cached by catchall email + app password. Multiple extractors using the same catchall account all receive the same GmailClient and share a single IMAP connection. An atomic counter tracks how many consumers are active, and the connection is only closed when the last one releases:

```java
String key = catchallEmail.toLowerCase() + ":" + appPassword;

synchronized (SHARED_LOCK) {
    client = sharedInstances.computeIfAbsent(key,
            k -> new GmailClient(null, catchallEmail, appPassword, true, k));
    client.refCount.incrementAndGet();
}

// connect() is called outside the lock to avoid blocking the pool during network I/O
client.connect();
```

Instead of each extractor hitting IMAP on its own schedule, shared instances use a single background thread that fetches from INBOX every 2 seconds. Each extractor registers the time it started monitoring, and the poller uses the earliest registered time to make sure its fetch window covers everyone:

```java
private void pollInbox() {
    Instant broadSince = registeredSinceTimes.values().stream()
            .min(Instant::compareTo)
            .orElse(null);

    EmailSearchCriteria broadCriteria = EmailSearchCriteria.builder()
            .since(broadSince)
            .build();

    List<EmailMessage> messages = executeFetch("INBOX", broadCriteria);
    this.polledMessages = List.copyOf(messages);
}
```

The results are published to a volatile field. When any extractor calls fetchMessages(), it just reads that snapshot and filters it in memory --- no IMAP call, which speeds up the process significantly:

```java
if (isShared) {
    List<EmailMessage> snapshot = this.polledMessages;
    return filterMessages(snapshot, criteria);
}
```
The poller starts when the first extractor registers and shuts down when the last one unregisters, so there's no background IMAP traffic when nothing is actively waiting for an email.

IMAP's date filtering only works at day granularity, searching for "emails since 2:35 PM" actually returns everything from today. For a catchall account receiving dozens of emails a day, downloading every message body every 2 seconds would be wasteful.
The fetch uses a two-phase approach to avoid this. Phase 1 batch-fetches only the lightweight envelope data (subject, sender, recipient, date) for all matches, then filters on that metadata to narrow down candidates. Phase 2 downloads the full body only for the messages that actually passed the filter:

```java
// Phase 1: batch-fetch envelopes (one IMAP round-trip)
FetchProfile fp = new FetchProfile();
fp.add(FetchProfile.Item.ENVELOPE);
folder.fetch(messages, fp);

// Filter on headers only — no body download
List<Message> candidates = new ArrayList<>();
for (Message message : messages) {
    EmailMessage headerOnly = convertHeaders(message);
    if (matchesSubjectCriteria(headerOnly, criteria)
            && matchesSenderCriteria(headerOnly, criteria)
            && matchesRecipientCriteria(headerOnly, criteria)
            && matchesTimestampCriteria(headerOnly, criteria)) {
        candidates.add(message);
    }
}

// Phase 2: download bodies for candidates only
for (Message message : candidates) {
    results.add(convertMessage(message));
}
```

If 40 emails matched the broad date filter but only 2 were received in the last minute, only those 2 bodies get downloaded.

On the consumer side, EmailPollingBase<T> is an abstract class that handles the polling loop so individual extractors only need to define three things: what to search for (buildCriteria), how to extract a result from a matching email (extractFromEmail), and a name for logging (extractorName). The base class takes care of timeout enforcement, cooperative cancellation (checking Thread.interrupted() so tasks can be stopped cleanly), and managing the shared connection lifecycle through registerSinceTime / unregisterSinceTime:

```java
try (MyOtpExtractor extractor = new MyOtpExtractor("user@gmail.com", "catchall@gmail.com", "app-pass")) {
    String otp = extractor.poll(); // blocks until found or timeout
}
```
This system has worked pretty well in testing and emails are never outright missed.

---


(styled and formatted with AI in case it matters)
