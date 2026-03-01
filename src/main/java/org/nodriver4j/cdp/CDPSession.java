package org.nodriver4j.cdp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * A session-scoped CDP connection targeting a specific browser tab or frame.
 *
 * <p>CDPSession multiplexes commands and events over a single browser-level
 * WebSocket managed by the parent {@link CDPClient}. Every command sent
 * through this session automatically includes the session's {@code sessionId},
 * and only events bearing that same session ID are delivered to this session's
 * listeners and event queue.</p>
 *
 * <h2>How It Works</h2>
 * <p>When Chrome's {@code Target.attachToTarget} returns a {@code sessionId},
 * a CDPSession is created and registered with the parent {@link CDPClient}.
 * From that point:</p>
 * <ol>
 *   <li>Commands sent via {@link #send} or {@link #sendAsync} include the
 *       session ID in the outgoing JSON and register futures in this session's
 *       own {@code pendingRequests} map.</li>
 *   <li>{@link CDPClient#handleMessage} inspects incoming messages for a
 *       {@code sessionId} field and routes responses and events to the
 *       matching CDPSession.</li>
 *   <li>Responses complete the appropriate future in {@link #pendingRequests}.</li>
 *   <li>Events are dispatched to session-scoped {@link #eventListeners} and
 *       queued in {@link #eventQueue} for {@link #waitForEvent}.</li>
 * </ol>
 *
 * <h2>OOPIF Support</h2>
 * <p>{@link #sendWithSession} allows sending commands with a <em>different</em>
 * session ID (e.g., for out-of-process iframes). These calls delegate to
 * {@link CDPClient#sendWithSession}, which tracks the future in the client's
 * own pending-requests map. This avoids the need to create a full CDPSession
 * for ephemeral OOPIF interactions.</p>
 *
 * <h2>Threading</h2>
 * <p>Event listeners are invoked on the WebSocket reader thread. Avoid
 * blocking operations in listeners, or dispatch work to another thread.
 * All public methods are thread-safe.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>CDPSession instances are created by {@link CDPClient#createSession} and
 * should be closed via {@link #close()} when the associated target is detached
 * or destroyed. Closing a session:</p>
 * <ul>
 *   <li>Clears all event listeners and the event queue</li>
 *   <li>Completes all pending request futures exceptionally</li>
 *   <li>Unregisters from the parent CDPClient</li>
 * </ul>
 *
 * @see CDPClient
 * @see CDPClient#createSession(String)
 */
public class CDPSession implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final String sessionId;
    private final CDPClient parentClient;

    /**
     * Futures awaiting responses to commands sent through this session.
     * Keyed by message ID (globally unique across all sessions via
     * {@link CDPClient#nextMessageId()}).
     */
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonObject>> pendingRequests =
            new ConcurrentHashMap<>();

    /**
     * Queue of raw event messages for {@link #waitForEvent}.
     * Each entry contains {@code "method"} and {@code "params"} fields.
     */
    private final BlockingQueue<JsonObject> eventQueue = new LinkedBlockingQueue<>();

    /**
     * Event listeners mapped by event method name.
     * Uses CopyOnWriteArrayList for thread-safe iteration during dispatch.
     */
    private final ConcurrentHashMap<String, List<Consumer<JsonObject>>> eventListeners =
            new ConcurrentHashMap<>();

    // ==================== Constructor ====================

    /**
     * Creates a new CDPSession. Package-private — instances are created
     * by {@link CDPClient#createSession(String)}.
     *
     * @param sessionId    the CDP session ID from Target.attachToTarget
     * @param parentClient the browser-level CDPClient that owns the WebSocket
     */
    CDPSession(String sessionId, CDPClient parentClient) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID cannot be null or blank");
        }
        if (parentClient == null) {
            throw new IllegalArgumentException("Parent CDPClient cannot be null");
        }
        this.sessionId = sessionId;
        this.parentClient = parentClient;
    }

    // ==================== Accessors ====================

    /**
     * Gets the CDP session ID.
     *
     * @return the session ID
     */
    public String sessionId() {
        return sessionId;
    }

    // ==================== Sending Commands ====================

    /**
     * Sends a CDP command and waits for the response.
     *
     * <p>The command is automatically scoped to this session's target.
     * Uses a default timeout of 30 seconds.</p>
     *
     * @param method the CDP method name (e.g., "Page.navigate")
     * @param params the parameters as a JsonObject (can be null)
     * @return the result from the response
     * @throws TimeoutException if no response within timeout
     */
    public JsonObject send(String method, JsonObject params) throws TimeoutException {
        return send(method, params, 30, TimeUnit.SECONDS);
    }

    /**
     * Sends a CDP command and waits for the response with custom timeout.
     *
     * @param method  the CDP method name
     * @param params  the parameters (can be null)
     * @param timeout the timeout value
     * @param unit    the timeout unit
     * @return the result from the response
     * @throws TimeoutException if no response within timeout
     */
    public JsonObject send(String method, JsonObject params, long timeout, TimeUnit unit)
            throws TimeoutException {
        int id = parentClient.nextMessageId();

        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("method", method);
        message.addProperty("sessionId", sessionId);
        if (params != null) {
            message.add("params", params);
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        parentClient.sendText(GSON.toJson(message));

        try {
            JsonObject response = future.get(timeout, unit);
            if (response.has("error")) {
                JsonObject error = response.getAsJsonObject("error");
                throw new RuntimeException("CDP error: " + error.get("message").getAsString());
            }
            return response.has("result") ? response.getAsJsonObject("result") : new JsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for response", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Error receiving response", e.getCause());
        }
    }

    /**
     * Sends a CDP command without waiting for a response.
     * Useful for fire-and-forget commands like event acknowledgments.
     *
     * @param method the CDP method name
     * @param params the parameters (can be null)
     */
    public void sendAsync(String method, JsonObject params) {
        int id = parentClient.nextMessageId();

        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("method", method);
        message.addProperty("sessionId", sessionId);
        if (params != null) {
            message.add("params", params);
        }

        parentClient.sendText(GSON.toJson(message));
    }

    /**
     * Sends a CDP command to a different session (e.g., an OOPIF target).
     *
     * <p>This delegates to {@link CDPClient#sendWithSession}, which tracks
     * the pending future in the client's own request map. Use this when a
     * Page needs to interact with a cross-origin iframe that has its own
     * session ID.</p>
     *
     * @param method          the CDP method name
     * @param params          the parameters
     * @param targetSessionId the session ID of the target (e.g., OOPIF)
     * @return the result from the response
     * @throws TimeoutException if no response within timeout
     */
    public JsonObject sendWithSession(String method, JsonObject params, String targetSessionId)
            throws TimeoutException {
        return parentClient.sendWithSession(method, params, targetSessionId);
    }

    /**
     * Sends a CDP command to a different session with custom timeout.
     *
     * @param method          the CDP method name
     * @param params          the parameters
     * @param targetSessionId the session ID of the target
     * @param timeout         the timeout value
     * @param unit            the timeout unit
     * @return the result from the response
     * @throws TimeoutException if no response within timeout
     */
    public JsonObject sendWithSession(String method, JsonObject params, String targetSessionId,
                                      long timeout, TimeUnit unit) throws TimeoutException {
        return parentClient.sendWithSession(method, params, targetSessionId, timeout, unit);
    }

    // ==================== Event Listeners ====================

    /**
     * Registers an event listener for a specific CDP event.
     * The listener will be called each time the event is received on this session.
     *
     * <p>Note: Listeners are called on the WebSocket thread. Avoid blocking
     * operations in listeners, or dispatch work to another thread.</p>
     *
     * @param eventName the CDP event method name (e.g., "Page.loadEventFired")
     * @param listener  the callback to invoke when the event is received
     */
    public void addEventListener(String eventName, Consumer<JsonObject> listener) {
        if (eventName == null || eventName.isBlank()) {
            throw new IllegalArgumentException("Event name cannot be null or blank");
        }
        if (listener == null) {
            throw new IllegalArgumentException("Listener cannot be null");
        }

        eventListeners.computeIfAbsent(eventName, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Removes a previously registered event listener.
     *
     * @param eventName the CDP event method name
     * @param listener  the listener to remove
     * @return true if the listener was found and removed
     */
    public boolean removeEventListener(String eventName, Consumer<JsonObject> listener) {
        List<Consumer<JsonObject>> listeners = eventListeners.get(eventName);
        if (listeners == null) {
            return false;
        }
        return listeners.remove(listener);
    }

    /**
     * Removes all event listeners for a specific event.
     *
     * @param eventName the CDP event method name
     */
    public void removeAllEventListeners(String eventName) {
        eventListeners.remove(eventName);
    }

    /**
     * Removes all event listeners for all events.
     */
    public void removeAllEventListeners() {
        eventListeners.clear();
    }

    /**
     * Checks if there are any listeners registered for an event.
     *
     * @param eventName the CDP event method name
     * @return true if at least one listener is registered
     */
    public boolean hasEventListeners(String eventName) {
        List<Consumer<JsonObject>> listeners = eventListeners.get(eventName);
        return listeners != null && !listeners.isEmpty();
    }

    // ==================== Event Waiting ====================

    /**
     * Waits for a specific CDP event on this session.
     *
     * @param eventName the event method name (e.g., "Page.loadEventFired")
     * @param timeout   the timeout value
     * @param unit      the timeout unit
     * @return the event parameters
     * @throws TimeoutException if event not received within timeout
     */
    public JsonObject waitForEvent(String eventName, long timeout, TimeUnit unit)
            throws TimeoutException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);

        while (System.currentTimeMillis() < deadline) {
            try {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;

                JsonObject event = eventQueue.poll(remaining, TimeUnit.MILLISECONDS);
                if (event != null && eventName.equals(event.get("method").getAsString())) {
                    return event.has("params") ? event.getAsJsonObject("params") : new JsonObject();
                }
                // If not the event we're looking for, continue waiting
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for event", e);
            }
        }

        throw new TimeoutException("Timeout waiting for event: " + eventName);
    }

    /**
     * Clears any pending events from the queue.
     */
    public void clearEvents() {
        eventQueue.clear();
    }

    // ==================== Internal Routing (Package-Private) ====================

    /**
     * Routes an incoming response to the matching pending request future.
     *
     * <p>Called by {@link CDPClient#handleMessage} when a response arrives
     * with this session's ID.</p>
     *
     * @param json the full response JSON (must contain an "id" field)
     */
    void handleResponse(JsonObject json) {
        if (!json.has("id")) {
            return;
        }

        int id = json.get("id").getAsInt();
        CompletableFuture<JsonObject> future = pendingRequests.remove(id);
        if (future != null) {
            future.complete(json);
        }
    }

    /**
     * Dispatches an incoming event to listeners and queues it for waitForEvent.
     *
     * <p>Called by {@link CDPClient#handleMessage} when an event arrives
     * with this session's ID.</p>
     *
     * @param method the CDP event method name
     * @param params the event parameters
     */
    void handleEvent(String method, JsonObject params) {
        // Dispatch to registered listeners
        List<Consumer<JsonObject>> listeners = eventListeners.get(method);
        if (listeners != null && !listeners.isEmpty()) {
            for (Consumer<JsonObject> listener : listeners) {
                try {
                    listener.accept(params);
                } catch (Exception e) {
                    System.err.println("[CDPSession:" + sessionId + "] Error in event listener for '"
                            + method + "': " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        // Queue for waitForEvent
        JsonObject event = new JsonObject();
        event.addProperty("method", method);
        event.add("params", params != null ? params : new JsonObject());
        eventQueue.offer(event);
    }

    // ==================== Lifecycle ====================

    /**
     * Closes this session and releases all resources.
     *
     * <p>Clears all event listeners, empties the event queue, completes
     * all pending request futures exceptionally, and unregisters this
     * session from the parent {@link CDPClient}.</p>
     *
     * <p>This does <em>not</em> send {@code Target.detachFromTarget} — target
     * lifecycle is managed by {@link org.nodriver4j.core.Browser}.</p>
     */
    @Override
    public void close() {
        eventListeners.clear();
        eventQueue.clear();

        // Complete any pending requests so callers don't hang
        pendingRequests.forEach((id, future) ->
                future.completeExceptionally(new RuntimeException("CDPSession closed")));
        pendingRequests.clear();

        parentClient.unregisterSession(sessionId);
    }

    @Override
    public String toString() {
        return String.format("CDPSession{sessionId=%s, pendingRequests=%d, listeners=%d}",
                sessionId, pendingRequests.size(), eventListeners.size());
    }
}