package org.nodriver4j.services;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.nodriver4j.services.exceptions.CapSolverException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client for the CapSolver captcha solving service.
 *
 * <p>This service communicates with the CapSolver API using the asynchronous
 * task flow: create a task via {@code createTask}, then poll for results via
 * {@code getTaskResult}. The generic core methods are designed for reuse
 * across any CapSolver-supported task type.</p>
 *
 * <h2>Supported Task Types</h2>
 * <ul>
 *   <li>reCAPTCHA v3 — via {@link #solveReCaptchaV3}</li>
 * </ul>
 *
 * <h2>Adding New Task Types</h2>
 * <p>To add support for a new captcha type:</p>
 * <ol>
 *   <li>Create a convenience method that builds the task-specific {@link JsonObject}</li>
 *   <li>Call {@link #createTask} and {@link #pollForResult} for the create-poll cycle</li>
 *   <li>Map the raw solution JSON into the appropriate response record</li>
 * </ol>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * CapSolverService service = new CapSolverService(apiKey);
 *
 * // With proxy
 * String proxy = CapSolverService.formatProxy("http", "1.2.3.4", "8080", "user", "pass");
 * ReCaptchaV3Response response = service.solveReCaptchaV3(
 *     "https://example.com", siteKey, "login", proxy
 * );
 *
 * // Without proxy (uses CapSolver's built-in proxy)
 * ReCaptchaV3Response response = service.solveReCaptchaV3(
 *     "https://example.com", siteKey, "login", null
 * );
 *
 * if (response.hasToken()) {
 *     // inject token into page
 * }
 * }</pre>
 *
 * @see ReCaptchaV3Response
 * @see CapSolverException
 */
public class CapSolverService implements AutoCloseable {

    private static final String BASE_URL = "https://api.capsolver.com";
    private static final String CREATE_TASK_ENDPOINT = BASE_URL + "/createTask";
    private static final String GET_TASK_RESULT_ENDPOINT = BASE_URL + "/getTaskResult";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Maximum poll attempts per task — CapSolver enforces a 120-query limit. */
    private static final int MAX_POLL_ATTEMPTS = 60;

    /** Interval between poll requests. CapSolver docs recommend at least 3 seconds. */
    private static final long POLL_INTERVAL_MS = 3000;

    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private final String apiKey;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a new CapSolverService with the specified API key.
     *
     * @param apiKey the CapSolver API key (clientKey)
     * @throws IllegalArgumentException if apiKey is null or blank
     */
    public CapSolverService(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }

        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();

        System.out.println("[CapSolver] Service initialized");
    }

    // ==================== reCAPTCHA v3 ====================

    /**
     * Solves a reCAPTCHA v3 challenge by creating a task and polling for the token.
     *
     * <p>Uses {@code ReCaptchaV3Task} when a proxy is provided, or
     * {@code ReCaptchaV3TaskProxyLess} when proxy is null.</p>
     *
     * @param websiteUrl the URL of the page containing the reCAPTCHA
     * @param websiteKey the reCAPTCHA site key
     * @param pageAction the action string from {@code grecaptcha.execute(sitekey, {action: '...'})}
     * @param proxy      proxy in CapSolver format (e.g., "http:host:port:user:pass"), or null for proxy-less
     * @return the solve response containing the token
     * @throws CapSolverException       if the API request fails or returns an error
     * @throws IllegalArgumentException if websiteUrl or websiteKey is null/blank
     */
    public ReCaptchaV3Response solveReCaptchaV3(String websiteUrl, String websiteKey,
                                                String pageAction, String proxy)
            throws CapSolverException {
        if (websiteUrl == null || websiteUrl.isBlank()) {
            throw new IllegalArgumentException("Website URL cannot be null or blank");
        }
        if (websiteKey == null || websiteKey.isBlank()) {
            throw new IllegalArgumentException("Website key cannot be null or blank");
        }

        boolean useProxy = proxy != null && !proxy.isBlank();
        String taskType = useProxy ? "ReCaptchaV3Task" : "ReCaptchaV3TaskProxyLess";

        System.out.println("[CapSolver] Solving reCAPTCHA v3: type=" + taskType +
                ", action=" + pageAction + ", url=" + websiteUrl);

        JsonObject task = new JsonObject();
        task.addProperty("type", taskType);
        task.addProperty("websiteURL", websiteUrl);
        task.addProperty("websiteKey", websiteKey);

        if (pageAction != null && !pageAction.isBlank()) {
            task.addProperty("pageAction", pageAction);
        }

        if (useProxy) {
            task.addProperty("proxy", proxy);
        }

        try {
            String taskId = createTask(task);
            JsonObject solution = pollForResult(taskId);

            String token = solution.has("gRecaptchaResponse")
                    ? solution.get("gRecaptchaResponse").getAsString()
                    : null;
            String userAgent = solution.has("userAgent")
                    ? solution.get("userAgent").getAsString()
                    : null;

            if (token == null || token.isBlank()) {
                throw new CapSolverException("Solution missing gRecaptchaResponse token");
            }

            System.out.println("[CapSolver] reCAPTCHA v3 solved: tokenLength=" + token.length());
            return ReCaptchaV3Response.success(token, userAgent);

        } catch (CapSolverException e) {
            System.err.println("[CapSolver] reCAPTCHA v3 solve failed: " + e.getMessage());
            throw e;
        }
    }

    // ==================== Core API Methods ====================

    /**
     * Creates a task on the CapSolver API.
     *
     * <p>This is the generic entry point for any task type. Convenience methods
     * like {@link #solveReCaptchaV3} build the task JSON and delegate here.</p>
     *
     * @param task the task object containing type-specific parameters
     * @return the task ID for use with {@link #pollForResult}
     * @throws CapSolverException if the API returns an error or the request fails
     */
    String createTask(JsonObject task) throws CapSolverException {
        ensureOpen();

        JsonObject body = new JsonObject();
        body.addProperty("clientKey", apiKey);
        body.add("task", task);

        JsonObject response = post(CREATE_TASK_ENDPOINT, body);

        validateResponse(response);

        if (!response.has("taskId")) {
            throw new CapSolverException("createTask response missing taskId");
        }

        String taskId = response.get("taskId").getAsString();
        System.out.println("[CapSolver] Task created: " + taskId);

        return taskId;
    }

    /**
     * Polls the CapSolver API for a task result until it is ready.
     *
     * <p>This method blocks the calling thread, polling at {@link #POLL_INTERVAL_MS}
     * intervals until the task status is {@code "ready"}, an error occurs, or
     * the maximum number of poll attempts is reached.</p>
     *
     * @param taskId the task ID from {@link #createTask}
     * @return the solution JSON object from the response
     * @throws CapSolverException if the task fails, times out, or exceeds max poll attempts
     */
    JsonObject pollForResult(String taskId) throws CapSolverException {
        ensureOpen();

        JsonObject body = new JsonObject();
        body.addProperty("clientKey", apiKey);
        body.addProperty("taskId", taskId);

        for (int attempt = 1; attempt <= MAX_POLL_ATTEMPTS; attempt++) {
            sleep(POLL_INTERVAL_MS);

            JsonObject response = post(GET_TASK_RESULT_ENDPOINT, body);

            // Check for API-level errors
            int errorId = response.has("errorId") ? response.get("errorId").getAsInt() : 0;
            if (errorId > 0) {
                String errorCode = response.has("errorCode")
                        ? response.get("errorCode").getAsString() : "UNKNOWN";
                String errorDesc = response.has("errorDescription")
                        ? response.get("errorDescription").getAsString() : "No description";
                throw new CapSolverException("Task " + taskId + " failed: " + errorCode + " - " + errorDesc);
            }

            String status = response.has("status") ? response.get("status").getAsString() : "";

            if ("ready".equals(status)) {
                if (!response.has("solution") || response.get("solution").isJsonNull()) {
                    throw new CapSolverException("Task " + taskId + " ready but solution is missing");
                }

                System.out.println("[CapSolver] Task " + taskId + " completed after " + attempt + " polls");
                return response.getAsJsonObject("solution");
            }

            // "idle" or "processing" — keep polling
            if (attempt % 5 == 0) {
                System.out.println("[CapSolver] Task " + taskId + " still processing (poll " + attempt + "/" + MAX_POLL_ATTEMPTS + ")");
            }
        }

        throw new CapSolverException("Task " + taskId + " timed out after " + MAX_POLL_ATTEMPTS + " poll attempts");
    }

    // ==================== HTTP Layer ====================

    /**
     * Sends a POST request to the CapSolver API and parses the JSON response.
     *
     * @param url  the full endpoint URL
     * @param body the JSON request body
     * @return the parsed JSON response
     * @throws CapSolverException if the request fails at the HTTP level
     */
    private JsonObject post(String url, JsonObject body) throws CapSolverException {
        ensureOpen();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String responseBody = response.body();
            int statusCode = response.statusCode();

            // Attempt to parse JSON even from error responses — CapSolver
            // returns structured errorCode/errorDescription in the body
            if (responseBody != null && !responseBody.isBlank()) {
                try {
                    JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

                    // For non-2xx responses, extract the structured error before throwing
                    if (statusCode < 200 || statusCode >= 300) {
                        String errorCode = json.has("errorCode")
                                ? json.get("errorCode").getAsString() : "HTTP_" + statusCode;
                        String errorDesc = json.has("errorDescription")
                                ? json.get("errorDescription").getAsString() : "No description";
                        throw new CapSolverException(errorCode + ": " + errorDesc);
                    }

                    return json;

                } catch (com.google.gson.JsonSyntaxException e) {
                    // Response body isn't valid JSON
                    if (statusCode < 200 || statusCode >= 300) {
                        throw new CapSolverException("HTTP " + statusCode + ": " + responseBody);
                    }
                    throw new CapSolverException("Invalid JSON response from " + url);
                }
            }

            // No body at all
            if (statusCode < 200 || statusCode >= 300) {
                throw new CapSolverException("HTTP " + statusCode + " with empty response body");
            }
            throw new CapSolverException("Empty response body from " + url);

        } catch (IOException e) {
            throw new CapSolverException("Network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CapSolverException("Request interrupted", e);
        }
    }

    /**
     * Validates a CapSolver API response for errors.
     *
     * @param response the parsed JSON response
     * @throws CapSolverException if errorId indicates an error
     */
    private void validateResponse(JsonObject response) throws CapSolverException {
        int errorId = response.has("errorId") ? response.get("errorId").getAsInt() : 0;
        if (errorId > 0) {
            String errorCode = response.has("errorCode")
                    ? response.get("errorCode").getAsString() : "UNKNOWN";
            String errorDesc = response.has("errorDescription")
                    ? response.get("errorDescription").getAsString() : "No description";
            throw new CapSolverException(errorCode + ": " + errorDesc);
        }
    }

    // ==================== Proxy Formatting ====================

    /**
     * Formats proxy components into the CapSolver proxy string format.
     *
     * <p>Produces the format: {@code scheme:host:port:username:password}</p>
     *
     * <p>Use this to convert from your proxy configuration to the string
     * expected by CapSolver task parameters.</p>
     *
     * @param scheme   the proxy scheme (e.g., "http", "socks5")
     * @param host     the proxy hostname or IP
     * @param port     the proxy port
     * @param username the proxy username, may be null for IP-whitelisted proxies
     * @param password the proxy password, may be null for IP-whitelisted proxies
     * @return the formatted proxy string
     * @throws IllegalArgumentException if host or port is null/blank
     */
    public static String formatProxy(String scheme, String host, String port,
                                     String username, String password) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Proxy host cannot be null or blank");
        }
        if (port == null || port.isBlank()) {
            throw new IllegalArgumentException("Proxy port cannot be null or blank");
        }

        String effectiveScheme = (scheme != null && !scheme.isBlank()) ? scheme : "http";

        StringBuilder sb = new StringBuilder();
        sb.append(effectiveScheme).append(":").append(host).append(":").append(port);

        if (username != null && !username.isBlank()) {
            sb.append(":").append(username);
            if (password != null && !password.isBlank()) {
                sb.append(":").append(password);
            }
        }

        return sb.toString();
    }

    // ==================== Lifecycle ====================

    /**
     * Shuts down the underlying HTTP client, cancelling any in-flight requests.
     *
     * <p>After this call, any blocked poll loop will fail on the next iteration,
     * and new calls will throw {@link CapSolverException}.</p>
     *
     * <p>This method is idempotent.</p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        httpClient.shutdownNow();
        System.out.println("[CapSolver] Service shut down");
    }

    /**
     * Ensures the service has not been closed.
     *
     * @throws CapSolverException if the service has been closed
     */
    private void ensureOpen() throws CapSolverException {
        if (closed.get()) {
            throw new CapSolverException("CapSolverService has been closed");
        }
    }

    // ==================== Utilities ====================

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        return "CapSolverService{apiKey=" + maskedApiKey() + "}";
    }
}