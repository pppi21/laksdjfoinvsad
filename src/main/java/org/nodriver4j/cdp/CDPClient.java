package org.nodriver4j.cdp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Chrome DevTools Protocol client.
 * Supports both browser-level and page-level connections, with session
 * multiplexing for multi-tab automation.
 *
 * <h2>Architecture</h2>
 * <p>A single CDPClient connects to the browser-level WebSocket endpoint.
 * Individual tabs are controlled through {@link CDPSession} instances, each
 * bound to a CDP session ID obtained via {@code Target.attachToTarget}.
 * The client demultiplexes incoming messages by session ID, routing them
 * to the correct CDPSession.</p>
 *
 * <h2>Message Routing</h2>
 * <p>Incoming WebSocket messages are handled as follows:</p>
 * <ol>
 *   <li><b>Response with session ID</b>: Check CDPClient's own pending requests
 *       first (for OOPIF delegated calls), then route to the matching CDPSession.</li>
 *   <li><b>Response without session ID</b>: Complete the matching browser-level
 *       pending request.</li>
 *   <li><b>Event with session ID</b>: Dispatch to the matching CDPSession's
 *       event listeners and queue.</li>
 *   <li><b>Event without session ID</b>: Dispatch to browser-level event
 *       listeners and queue.</li>
 * </ol>
 *
 * <h2>Session Lifecycle</h2>
 * <p>Sessions are created via {@link #createSession(String)} after a successful
 * {@code Target.attachToTarget} call. They are unregistered when
 * {@link CDPSession#close()} is called, typically when a tab is closed or
 * the browser is shutting down.</p>
 */
public class CDPClient implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final WebSocket webSocket;
    private final AtomicInteger messageId = new AtomicInteger(0);

    /**
     * Pending requests for browser-level commands (no session ID) and
     * OOPIF delegated calls via {@link #sendWithSession}.
     */
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonObject>> pendingRequests =
            new ConcurrentHashMap<>();

    /**
     * Browser-level event queue for {@link #waitForEvent}.
     * Only receives events that have no session ID.
     */
    private final BlockingQueue<JsonObject> eventQueue = new LinkedBlockingQueue<>();

    /**
     * Browser-level event listeners mapped by event method name.
     * Only receives events that have no session ID.
     * Uses CopyOnWriteArrayList for thread-safe iteration during dispatch.
     */
    private final ConcurrentHashMap<String, List<Consumer<JsonObject>>> eventListeners =
            new ConcurrentHashMap<>();

    /**
     * Registered sessions keyed by CDP session ID.
     * Each session handles events and responses for a specific target (tab/frame).
     */
    private final ConcurrentHashMap<String, CDPSession> sessions = new ConcurrentHashMap<>();

    private CDPClient(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    // ==================== Connection Factory Methods ====================

    /**
     * Connects to the first available page target on the given port.
     * Use this for page-specific commands like Emulation, Page, DOM, etc.
     *
     * <p><b>Note:</b> For the session-based multi-tab architecture, prefer
     * {@link #connectToBrowser(int)} and use {@link CDPSession} instances
     * for page-level commands. This method is retained for backward
     * compatibility and standalone single-tab usage.</p>
     *
     * @param port the Chrome remote debugging port
     * @return a connected CDPClient targeting a page
     * @throws IOException          if connection fails
     * @throws InterruptedException if interrupted while connecting
     */
    public static CDPClient connect(int port) throws IOException, InterruptedException {
        String wsUrl = discoverPageWebSocketUrl(port);
        return connectToWebSocket(wsUrl);
    }

    /**
     * Connects to the browser target on the given port.
     * Use this for browser-wide commands (Target, Fetch) and as the
     * parent connection for session-based tab control.
     *
     * @param port the Chrome remote debugging port
     * @return a connected CDPClient targeting the browser
     * @throws IOException          if connection fails
     * @throws InterruptedException if interrupted while connecting
     */
    public static CDPClient connectToBrowser(int port) throws IOException, InterruptedException {
        String wsUrl = discoverBrowserWebSocketUrl(port);
        return connectToWebSocket(wsUrl);
    }

    // ==================== Session Management ====================

    /**
     * Creates and registers a new CDPSession for the given session ID.
     *
     * <p>Call this after a successful {@code Target.attachToTarget} response
     * to begin routing messages to the session.</p>
     *
     * @param sessionId the CDP session ID from Target.attachToTarget
     * @return the new CDPSession
     * @throws IllegalArgumentException if sessionId is null or blank
     * @throws IllegalStateException    if a session with this ID is already registered
     */
    public CDPSession createSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID cannot be null or blank");
        }

        CDPSession session = new CDPSession(sessionId, this);
        CDPSession existing = sessions.putIfAbsent(sessionId, session);

        if (existing != null) {
            throw new IllegalStateException("Session already registered: " + sessionId);
        }

        return session;
    }

    /**
     * Gets a registered session by ID.
     *
     * @param sessionId the CDP session ID
     * @return the CDPSession, or null if not registered
     */
    public CDPSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Checks if a session is registered.
     *
     * @param sessionId the CDP session ID
     * @return true if the session is registered
     */
    public boolean hasSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * Unregisters a session. Called by {@link CDPSession#close()}.
     *
     * @param sessionId the CDP session ID to unregister
     */
    void unregisterSession(String sessionId) {
        sessions.remove(sessionId);
    }

    // ==================== Package-Private Hooks for CDPSession ====================

    /**
     * Generates the next globally unique message ID.
     *
     * <p>Message IDs are shared across all sessions to ensure uniqueness.
     * This allows {@link #handleMessage} to route responses unambiguously
     * even when OOPIF delegated calls are mixed with session commands.</p>
     *
     * @return the next message ID
     */
    int nextMessageId() {
        return messageId.incrementAndGet();
    }

    /**
     * Sends raw text over the WebSocket.
     *
     * <p>Used by {@link CDPSession} to send session-scoped commands over
     * the shared browser WebSocket connection.</p>
     *
     * @param text the JSON message text to send
     */
    void sendText(String text) {
        webSocket.sendText(text, true);
    }

    // ==================== Discovery ====================

    /**
     * Discovers a page-level WebSocket URL from Chrome's debug endpoint.
     *
     * @param port the Chrome remote debugging port
     * @return the page WebSocket debugger URL
     * @throws IOException          if discovery fails
     * @throws InterruptedException if interrupted
     */
    private static String discoverPageWebSocketUrl(int port) throws IOException, InterruptedException {
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/json"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch targets: HTTP " + response.statusCode());
        }

        JsonArray targets = GSON.fromJson(response.body(), JsonArray.class);

        for (JsonElement element : targets) {
            JsonObject target = element.getAsJsonObject();
            if ("page".equals(target.get("type").getAsString())) {
                return target.get("webSocketDebuggerUrl").getAsString();
            }
        }

        throw new IOException("No page target found");
    }

    /**
     * Discovers the browser-level WebSocket URL from Chrome's debug endpoint.
     *
     * @param port the Chrome remote debugging port
     * @return the browser WebSocket debugger URL
     * @throws IOException          if discovery fails
     * @throws InterruptedException if interrupted
     */
    private static String discoverBrowserWebSocketUrl(int port) throws IOException, InterruptedException {
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/json/version"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch browser version info: HTTP " + response.statusCode());
        }

        JsonObject versionInfo = GSON.fromJson(response.body(), JsonObject.class);

        if (!versionInfo.has("webSocketDebuggerUrl")) {
            throw new IOException("Browser WebSocket URL not found in version info");
        }

        return versionInfo.get("webSocketDebuggerUrl").getAsString();
    }

    // ==================== WebSocket Connection ====================

    private static CDPClient connectToWebSocket(String wsUrl) throws InterruptedException {
        CompletableFuture<CDPClient> clientFuture = new CompletableFuture<>();

        HttpClient httpClient = HttpClient.newHttpClient();
        CDPClientHolder holder = new CDPClientHolder();

        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    private final StringBuilder messageBuffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        CDPClient client = new CDPClient(webSocket);
                        holder.client = client;
                        clientFuture.complete(client);
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        messageBuffer.append(data);
                        if (last) {
                            String message = messageBuffer.toString();
                            messageBuffer.setLength(0);
                            holder.client.handleMessage(message);
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        if (!clientFuture.isDone()) {
                            clientFuture.completeExceptionally(error);
                        }
                    }
                });

        try {
            return clientFuture.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to connect to WebSocket", e.getCause());
        } catch (TimeoutException e) {
            throw new RuntimeException("Timeout connecting to WebSocket");
        }
    }

    // ==================== Message Handling ====================

    /**
     * Routes an incoming WebSocket message to the appropriate handler.
     *
     * <p>Routing logic:</p>
     * <ol>
     *   <li><b>Response (has "id")</b>:
     *     <ol>
     *       <li>Check CDPClient's own pendingRequests (browser-level commands
     *           and OOPIF delegated calls via sendWithSession)</li>
     *       <li>If not found and message has a sessionId, route to the
     *           matching CDPSession</li>
     *     </ol>
     *   </li>
     *   <li><b>Event (has "method", no "id")</b>:
     *     <ol>
     *       <li>If message has a sessionId, route to the matching CDPSession</li>
     *       <li>Otherwise, dispatch as a browser-level event</li>
     *     </ol>
     *   </li>
     * </ol>
     */
    private void handleMessage(String message) {
        JsonObject json = GSON.fromJson(message, JsonObject.class);

        String sessionId = json.has("sessionId") ? json.get("sessionId").getAsString() : null;

        if (json.has("id")) {
            // Response to a command
            handleResponse(json, sessionId);
        } else if (json.has("method")) {
            // Event
            String method = json.get("method").getAsString();
            JsonObject params = json.has("params") ? json.getAsJsonObject("params") : new JsonObject();
            handleEvent(method, params, sessionId, json);
        }
    }

    /**
     * Routes a command response to the correct pending request.
     *
     * <p>Browser-level pending requests are checked first. This handles both
     * browser-level commands (no session ID) and OOPIF delegated calls
     * (which have a session ID but were tracked in CDPClient's own map by
     * {@link #sendWithSession}). If not found and a session ID is present,
     * the response is routed to the matching CDPSession.</p>
     */
    private void handleResponse(JsonObject json, String sessionId) {
        int id = json.get("id").getAsInt();

        // Check browser-level pending requests first (covers both browser
        // commands and OOPIF delegated calls)
        CompletableFuture<JsonObject> future = pendingRequests.remove(id);
        if (future != null) {
            future.complete(json);
            return;
        }

        // Route to session if present
        if (sessionId != null) {
            CDPSession session = sessions.get(sessionId);
            if (session != null) {
                session.handleResponse(json);
            }
        }
    }

    /**
     * Routes an event to the correct handler.
     *
     * <p>Session-scoped events go to the matching CDPSession. Browser-level
     * events (no session ID) are dispatched to browser-level listeners and
     * queued for {@link #waitForEvent}.</p>
     */
    private void handleEvent(String method, JsonObject params, String sessionId, JsonObject rawJson) {
        if (sessionId != null) {
            // Session-scoped event
            CDPSession session = sessions.get(sessionId);
            if (session != null) {
                session.handleEvent(method, params);
            }
        } else {
            // Browser-level event
            dispatchEvent(method, params);
            eventQueue.offer(rawJson);
        }
    }

    /**
     * Dispatches a browser-level event to all registered listeners for that event type.
     * Listeners are called synchronously on the WebSocket thread.
     *
     * @param eventName the CDP event method name
     * @param params    the event parameters
     */
    private void dispatchEvent(String eventName, JsonObject params) {
        List<Consumer<JsonObject>> listeners = eventListeners.get(eventName);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }

        for (Consumer<JsonObject> listener : listeners) {
            try {
                listener.accept(params);
            } catch (Exception e) {
                System.err.println("[CDPClient] Error in event listener for '" + eventName + "': "
                        + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // ==================== Browser-Level Event Listeners ====================

    /**
     * Registers an event listener for a specific browser-level CDP event.
     * The listener will be called each time the event is received without
     * a session ID.
     *
     * <p>For session-scoped events (e.g., Page.loadEventFired on a specific
     * tab), use {@link CDPSession#addEventListener} instead.</p>
     *
     * <p>Note: Listeners are called on the WebSocket thread. Avoid blocking
     * operations in listeners, or dispatch work to another thread.</p>
     *
     * @param eventName the CDP event method name (e.g., "Target.targetCreated")
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
     * Removes a previously registered browser-level event listener.
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
     * Removes all browser-level event listeners for a specific event.
     *
     * @param eventName the CDP event method name
     */
    public void removeAllEventListeners(String eventName) {
        eventListeners.remove(eventName);
    }

    /**
     * Removes all browser-level event listeners for all events.
     */
    public void removeAllEventListeners() {
        eventListeners.clear();
    }

    /**
     * Checks if there are any browser-level listeners registered for an event.
     *
     * @param eventName the CDP event method name
     * @return true if at least one listener is registered
     */
    public boolean hasEventListeners(String eventName) {
        List<Consumer<JsonObject>> listeners = eventListeners.get(eventName);
        return listeners != null && !listeners.isEmpty();
    }

    // ==================== Browser-Level Commands ====================

    /**
     * Sends a browser-level CDP command and waits for the response.
     *
     * <p>Use this for commands that target the browser itself (e.g.,
     * Target.getTargets, Fetch.enable). For page-level commands,
     * use {@link CDPSession#send} instead.</p>
     *
     * @param method the CDP method name (e.g., "Target.getTargets")
     * @param params the parameters as a JsonObject (can be null)
     * @return the result from the response
     * @throws TimeoutException if no response within timeout
     */
    public JsonObject send(String method, JsonObject params) throws TimeoutException {
        return send(method, params, 30, TimeUnit.SECONDS);
    }

    /**
     * Sends a browser-level CDP command and waits for the response with custom timeout.
     *
     * @param method  the CDP method name
     * @param params  the parameters
     * @param timeout the timeout value
     * @param unit    the timeout unit
     * @return the result from the response
     * @throws TimeoutException if no response within timeout
     */
    public JsonObject send(String method, JsonObject params, long timeout, TimeUnit unit)
            throws TimeoutException {
        int id = messageId.incrementAndGet();

        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("method", method);
        if (params != null) {
            message.add("params", params);
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        webSocket.sendText(GSON.toJson(message), true);

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
     * Sends a browser-level CDP command without waiting for a response.
     * Useful for fire-and-forget commands.
     *
     * @param method the CDP method name
     * @param params the parameters as a JsonObject (can be null)
     */
    public void sendAsync(String method, JsonObject params) {
        int id = messageId.incrementAndGet();

        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("method", method);
        if (params != null) {
            message.add("params", params);
        }

        webSocket.sendText(GSON.toJson(message), true);
    }

    /**
     * Waits for a specific browser-level CDP event.
     *
     * <p>For session-scoped events, use {@link CDPSession#waitForEvent} instead.</p>
     *
     * @param eventName the event method name (e.g., "Target.targetCreated")
     * @param timeout   the timeout value
     * @param unit      the timeout unit
     * @return the event data
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
     * Sends a CDP command to a specific session (for OOPIFs).
     *
     * <p>The response future is tracked in CDPClient's own pending requests map,
     * not in a CDPSession. This allows ephemeral OOPIF interactions without
     * needing to create a full CDPSession for each cross-origin iframe.</p>
     *
     * @param method    the CDP method name
     * @param params    the parameters
     * @param sessionId the target session ID
     * @return the result from the response
     * @throws TimeoutException if no response within timeout
     */
    public JsonObject sendWithSession(String method, JsonObject params, String sessionId)
            throws TimeoutException {
        return sendWithSession(method, params, sessionId, 30, TimeUnit.SECONDS);
    }

    /**
     * Sends a CDP command to a specific session with custom timeout.
     *
     * @param method    the CDP method name
     * @param params    the parameters
     * @param sessionId the target session ID
     * @param timeout   the timeout value
     * @param unit      the timeout unit
     * @return the result from the response
     * @throws TimeoutException if no response within timeout
     */
    public JsonObject sendWithSession(String method, JsonObject params, String sessionId,
                                      long timeout, TimeUnit unit) throws TimeoutException {
        int id = messageId.incrementAndGet();

        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("method", method);
        if (sessionId != null) {
            message.addProperty("sessionId", sessionId);
        }
        if (params != null) {
            message.add("params", params);
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        webSocket.sendText(GSON.toJson(message), true);

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
     * Clears any pending browser-level events from the queue.
     */
    public void clearEvents() {
        eventQueue.clear();
    }

    // ==================== Lifecycle ====================

    /**
     * Closes all sessions and the WebSocket connection.
     *
     * <p>All registered CDPSessions are closed first (completing their pending
     * futures exceptionally), then browser-level listeners are cleared, and
     * finally the WebSocket is closed.</p>
     */
    @Override
    public void close() {
        // Close all sessions
        for (CDPSession session : sessions.values()) {
            try {
                session.close();
            } catch (Exception e) {
                System.err.println("[CDPClient] Error closing session " + session.sessionId()
                        + ": " + e.getMessage());
            }
        }
        sessions.clear();

        // Clear browser-level listeners
        eventListeners.clear();

        // Complete any remaining browser-level pending requests
        pendingRequests.forEach((id, future) ->
                future.completeExceptionally(new RuntimeException("CDPClient closed")));
        pendingRequests.clear();

        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "closing");
    }

    // Helper class to hold client reference during async construction
    private static class CDPClientHolder {
        CDPClient client;
    }
}