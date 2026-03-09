package org.nodriver4j.services;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.nodriver4j.services.exceptions.AutoSolveAIException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client for the AutoSolve AI captcha solving service.
 *
 * <p>This service solves reCAPTCHA v2 image challenges by sending the captcha
 * grid image to the AutoSolve AI API and receiving a 3x3 boolean grid indicating
 * which tiles to select.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * AutoSolveAIService service = new AutoSolveAIService(apiKey);
 *
 * // Capture captcha image as base64 (no data URL prefix)
 * String imageBase64 = Base64.getEncoder().encodeToString(captchaImageBytes);
 *
 * // Solve the captcha
 * AutoSolveAIResponse response = service.solve(
 *     "Select all images with traffic lights",
 *     imageBase64
 * );
 *
 * if (response.success()) {
 *     boolean[][] grid = response.squares();
 *     // Click tiles where grid[row][col] is true
 * }
 * }</pre>
 *
 * <h2>API Details</h2>
 * <ul>
 *   <li>Endpoint: https://autosolve-ai-api.aycd.io/api/v1/solve</li>
 *   <li>Auth: Token-based (Authorization header)</li>
 *   <li>Supports: reCAPTCHA v2 (3x3 grid challenges only)</li>
 * </ul>
 *
 * @see AutoSolveAIResponse
 * @see AutoSolveAIException
 */
public class AutoSolveAIService implements AutoCloseable {

    private static final String API_URL = "https://autosolve-ai-api.aycd.io/api/v1/solve";
    private static final int RECAPTCHA_VERSION = 1;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private final String apiKey;
    private final AtomicBoolean closed = new AtomicBoolean(false);


    /**
     * Creates a new AutoSolveAIService with the specified API key.
     *
     * @param apiKey the AutoSolve AI API key
     * @throws IllegalArgumentException if apiKey is null or blank
     */
    public AutoSolveAIService(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }

        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();

        System.out.println("[AutoSolveAI] Service initialized");
    }

    /**
     * Solves a reCAPTCHA v2 image challenge.
     *
     * <p>Sends the captcha grid image to AutoSolve AI and returns a response
     * indicating which tiles should be selected.</p>
     *
     * @param description the challenge description (e.g., "Select all images with traffic lights")
     * @param imageBase64 the captcha grid image as base64 string (no data URL prefix)
     * @return the solve response containing the solution grid
     * @throws AutoSolveAIException if the request fails or API returns an error
     * @throws IllegalArgumentException if description or imageBase64 is null/blank
     */
    public AutoSolveAIResponse solve(String description, String imageBase64) throws AutoSolveAIException {
        ensureOpen();
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Description cannot be null or blank");
        }
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw new IllegalArgumentException("Image base64 cannot be null or blank");
        }

        String taskId = UUID.randomUUID().toString();

        System.out.println("[AutoSolveAI] Sending solve request for: " + description);

        try {
            String requestBody = buildRequestBody(taskId, description, imageBase64);
            HttpRequest request = buildHttpRequest(requestBody);

            long startTime = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - startTime;

            System.out.println("[AutoSolveAI] Response received in " + duration + "ms, status: " + response.statusCode());

            return parseResponse(response);

        } catch (IOException e) {
            throw new AutoSolveAIException("Network error during solve request: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AutoSolveAIException("Solve request interrupted", e);
        }
    }

    /**
     * Solves a batch of reCAPTCHA replacement tile images.
     *
     * <p>Used for fade-away captchas where multiple tiles need to be evaluated together.
     * Sends all images in a single API request.</p>
     *
     * @param description  the challenge description (e.g., "Select all images with crosswalks")
     * @param imagesBase64 list of base64-encoded images (no data URL prefix)
     * @return the raw JSON response body for logging/parsing
     * @throws AutoSolveAIException if the request fails
     * @throws IllegalArgumentException if description is null/blank or imagesBase64 is null/empty
     */
    public String solveBatch(String description, List<String> imagesBase64) throws AutoSolveAIException {
        ensureOpen();
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Description cannot be null or blank");
        }
        if (imagesBase64 == null || imagesBase64.isEmpty()) {
            throw new IllegalArgumentException("Images list cannot be null or empty");
        }

        String taskId = UUID.randomUUID().toString();

        System.out.println("[AutoSolveAI] Sending batch solve request for " + imagesBase64.size() +
                " images: " + description);

        try {
            String requestBody = buildBatchRequestBody(taskId, description, imagesBase64);
            HttpRequest request = buildHttpRequest(requestBody);

            long startTime = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - startTime;

            System.out.println("[AutoSolveAI] Batch response received in " + duration + "ms, status: " +
                    response.statusCode());

            // Handle HTTP errors
            int statusCode = response.statusCode();
            if (statusCode == 401 || statusCode == 403) {
                throw new AutoSolveAIException("Authentication failed: Invalid API key (HTTP " + statusCode + ")");
            }
            if (statusCode == 429) {
                throw new AutoSolveAIException("Rate limited: Too many requests (HTTP 429)");
            }
            if (statusCode < 200 || statusCode >= 300) {
                throw new AutoSolveAIException("API request failed with HTTP " + statusCode + ": " + response.body());
            }

            return response.body();

        } catch (IOException e) {
            throw new AutoSolveAIException("Network error during batch solve request: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AutoSolveAIException("Batch solve request interrupted", e);
        }
    }

    /**
     * Builds the JSON request body for a batch solve API call.
     */
    private String buildBatchRequestBody(String taskId, String description, List<String> imagesBase64) {
        JsonObject body = new JsonObject();
        body.addProperty("taskId", taskId);
        body.addProperty("version", RECAPTCHA_VERSION);
        body.addProperty("description", description);
        body.add("exampleImages", GSON.toJsonTree(new String[0]));
        body.add("imageData", GSON.toJsonTree(imagesBase64));

        return GSON.toJson(body);
    }

    /**
     * Builds the JSON request body for the solve API.
     */
    private String buildRequestBody(String taskId, String description, String imageBase64) {
        JsonObject body = new JsonObject();
        body.addProperty("taskId", taskId);
        body.addProperty("version", RECAPTCHA_VERSION);
        body.addProperty("description", description);

        body.add("exampleImages", GSON.toJsonTree(new String[0]));

        // image data
        body.add("imageData", GSON.toJsonTree(new String[]{imageBase64}));

        return GSON.toJson(body);
    }

    /**
     * Builds the HTTP request with proper headers.
     */
    private HttpRequest buildHttpRequest(String requestBody) {
        return HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Token " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    /**
     * Parses the HTTP response into an AutoSolveAIResponse.
     */
    private AutoSolveAIResponse parseResponse(HttpResponse<String> response) throws AutoSolveAIException {
        int statusCode = response.statusCode();
        String body = response.body();

        // Handle HTTP errors
        if (statusCode == 401 || statusCode == 403) {
            throw new AutoSolveAIException("Authentication failed: Invalid API key (HTTP " + statusCode + ")");
        }

        if (statusCode == 429) {
            throw new AutoSolveAIException("Rate limited: Too many requests (HTTP 429)");
        }

        if (statusCode < 200 || statusCode >= 300) {
            throw new AutoSolveAIException("API request failed with HTTP " + statusCode + ": " + body);
        }

        // Parse response body
        if (body == null || body.isBlank()) {
            throw new AutoSolveAIException("Empty response body from API");
        }

        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            String id = json.has("id") ? json.get("id").getAsString() : null;
            boolean success = json.has("success") && json.get("success").getAsBoolean();
            String message = json.has("message") && !json.get("message").isJsonNull()
                    ? json.get("message").getAsString()
                    : null;
            long remaining = json.has("remaining") ? json.get("remaining").getAsLong() : 0;

            // Parse squares grid
            boolean[][] squares = null;
            if (json.has("squares") && !json.get("squares").isJsonNull()) {
                squares = GSON.fromJson(json.get("squares"), boolean[][].class);
            }

            AutoSolveAIResponse aiResponse = new AutoSolveAIResponse(id, squares, success, message, remaining);

            if (success) {
                System.out.println("[AutoSolveAI] Solve successful: " + aiResponse.selectedTileCount() + " tiles to select");
            } else {
                System.out.println("[AutoSolveAI] Solve failed: " + message);
            }

            return aiResponse;

        } catch (Exception e) {
            throw new AutoSolveAIException("Failed to parse API response: " + body, e);
        }
    }

    // ==================== Lifecycle ====================

    /**
     * Shuts down the underlying HTTP client, cancelling any in-flight requests.
     *
     * <p>After this call, any blocked {@code solve()} or {@code solveBatch()}
     * call will fail immediately, and new calls will throw
     * {@link AutoSolveAIException}. This is the mechanism by which
     * {@link TaskContext#cancel()} terminates stuck captcha-solving requests.</p>
     *
     * <p>This method is idempotent — calling it multiple times is safe.</p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        httpClient.shutdownNow();
        System.out.println("[AutoSolveAI] Service shut down");
    }

    /**
     * Ensures the service has not been closed.
     *
     * @throws AutoSolveAIException if the service has been closed
     */
    private void ensureOpen() throws AutoSolveAIException {
        if (closed.get()) {
            throw new AutoSolveAIException("AutoSolveAIService has been closed");
        }
    }

    /**
     * Returns the API key used by this service (masked for security).
     *
     * @return masked API key string
     */
    public String maskedApiKey() {
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    @Override
    public String toString() {
        return "AutoSolveAIService{apiKey=" + maskedApiKey() + "}";
    }
}