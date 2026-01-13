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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal Chrome DevTools Protocol client.
 * Connects to a browser page via WebSocket and allows sending CDP commands.
 */
public class CDPClient implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final WebSocket webSocket;
    private final AtomicInteger messageId = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final BlockingQueue<JsonObject> eventQueue = new LinkedBlockingQueue<>();

    private CDPClient(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    /**
     * Connects to the first available page target on the given port.
     *
     * @param port the Chrome remote debugging port
     * @return a connected CDPClient
     * @throws IOException if connection fails
     * @throws InterruptedException if interrupted while connecting
     */
    public static CDPClient connect(int port) throws IOException, InterruptedException {
        String wsUrl = discoverWebSocketUrl(port);
        return connectToWebSocket(wsUrl);
    }

    private static String discoverWebSocketUrl(int port) throws IOException, InterruptedException {
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
            // Event
            eventQueue.offer(json);
        }
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
     * @param method the CDP method name
     * @param params the parameters
     * @param timeout the timeout value
     * @param unit the timeout unit
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
     * Waits for a specific CDP event.
     *
     * @param eventName the event method name (e.g., "Page.loadEventFired")
     * @param timeout the timeout value
     * @param unit the timeout unit
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
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "closing");
    }

    // Helper class to hold client reference during async construction
    private static class CDPClientHolder {
        CDPClient client;
    }
}