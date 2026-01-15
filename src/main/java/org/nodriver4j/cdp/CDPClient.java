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
 * Supports both browser-level and page-level connections.
 */
public class CDPClient implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final WebSocket webSocket;
    private final AtomicInteger messageId = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final BlockingQueue<JsonObject> eventQueue = new LinkedBlockingQueue<>();

    /**
     * Event listeners mapped by event method name.
     * Uses CopyOnWriteArrayList for thread-safe iteration during dispatch.
     */
    private final ConcurrentHashMap<String, List<Consumer<JsonObject>>> eventListeners = new ConcurrentHashMap<>();

    private CDPClient(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    /**
     * Connects to the first available page target on the given port.
     * Use this for page-specific commands like Emulation, Page, DOM, etc.
     *
     * @param port the Chrome remote debugging port
     * @return a connected CDPClient targeting a page
     * @throws IOException if connection fails
     * @throws InterruptedException if interrupted while connecting
     */
    public static CDPClient connect(int port) throws IOException, InterruptedException {
        String wsUrl = discoverPageWebSocketUrl(port);
        return connectToWebSocket(wsUrl);
    }

    /**
     * Connects to the browser target on the given port.
     * Use this for browser-wide commands like Fetch (for proxy auth).
     * Commands sent via this connection apply to ALL pages/tabs.
     *
     * @param port the Chrome remote debugging port
     * @return a connected CDPClient targeting the browser
     * @throws IOException if connection fails
     * @throws InterruptedException if interrupted while connecting
     */
    public static CDPClient connectToBrowser(int port) throws IOException, InterruptedException {
        String wsUrl = discoverBrowserWebSocketUrl(port);
        return connectToWebSocket(wsUrl);
    }

    /**
     * Discovers a page-level WebSocket URL from Chrome's debug endpoint.
     *
     * @param port the Chrome remote debugging port
     * @return the page WebSocket debugger URL
     * @throws IOException if discovery fails
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
     * @throws IOException if discovery fails
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

    private void handleMessage(String message) {
        JsonObject json = GSON.fromJson(message, JsonObject.class);

        if (json.has("id")) {
            // Response to a command
            int id = json.get("id").getAsInt();
            CompletableFuture<JsonObject> future = pendingRequests.remove(id);
            if (future != null) {
                future.complete(json);
            }
        } else if (json.has("method")) {
            // Event - dispatch to listeners and queue
            String method = json.get("method").getAsString();
            JsonObject params = json.has("params") ? json.getAsJsonObject("params") : new JsonObject();

            // Dispatch to registered listeners
            dispatchEvent(method, params);

            // Also add to queue for waitForEvent() compatibility
            eventQueue.offer(json);
        }
    }

    /**
     * Dispatches an event to all registered listeners for that event type.
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
                System.err.println("[CDPClient] Error in event listener for '" + eventName + "': " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Registers an event listener for a specific CDP event.
     * The listener will be called each time the event is received.
     *
     * <p>Note: Listeners are called on the WebSocket thread. Avoid blocking
     * operations in listeners, or dispatch work to another thread.</p>
     *
     * @param eventName the CDP event method name (e.g., "Fetch.authRequired")
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

    /**
     * Sends a CDP command and waits for the response.
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
     * @param params  the parameters
     * @param timeout the timeout value
     * @param unit    the timeout unit
     * @return the result from the response
     * @throws TimeoutException if no response within timeout
     */
    public JsonObject send(String method, JsonObject params, long timeout, TimeUnit unit) throws TimeoutException {
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
     * Sends a CDP command without waiting for a response.
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
     * Waits for a specific CDP event.
     *
     * @param eventName the event method name (e.g., "Page.loadEventFired")
     * @param timeout   the timeout value
     * @param unit      the timeout unit
     * @return the event data
     * @throws TimeoutException if event not received within timeout
     */
    public JsonObject waitForEvent(String eventName, long timeout, TimeUnit unit) throws TimeoutException {
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

    @Override
    public void close() {
        // Clear all listeners on close
        eventListeners.clear();
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "closing");
    }

    // Helper class to hold client reference during async construction
    private static class CDPClientHolder {
        CDPClient client;
    }
}