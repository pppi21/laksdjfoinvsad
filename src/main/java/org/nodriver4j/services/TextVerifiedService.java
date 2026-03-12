package org.nodriver4j.services;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.nodriver4j.services.exceptions.SmsProviderException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * SMS verification service backed by the TextVerified API (v2).
 *
 * <p>TextVerified uses a REST/JSON API with bearer token authentication.
 * Unlike the other providers, TextVerified requires <strong>two</strong>
 * credentials: an API key and a username (email address). These are
 * exchanged for a short-lived bearer token via the Simple Authentication
 * endpoint.</p>
 *
 * <h2>Authentication</h2>
 * <p>Bearer tokens are obtained by calling
 * {@code POST /api/pub/v2/simple/authentication} with the API key and
 * username in headers. Tokens are cached and automatically refreshed
 * when they expire or approach expiry (30s buffer).</p>
 *
 * <h2>Verification Lifecycle</h2>
 * <ol>
 *   <li>{@code POST /api/pub/v2/verifications} — creates a new verification,
 *       returns a {@code Location} header with the verification URL</li>
 *   <li>{@code GET /api/pub/v2/verifications/{id}} — retrieves details including
 *       {@code state}, {@code number}, and HATEOAS links ({@code sms.href},
 *       {@code cancel.link.href})</li>
 *   <li>When {@code state} becomes {@code verificationCompleted}, the
 *       {@code sms.href} link is followed to retrieve the OTP code</li>
 *   <li>Cancellation: {@code POST /api/pub/v2/verifications/{id}/cancel}</li>
 * </ol>
 *
 * <h2>Verification States</h2>
 * <ul>
 *   <li>{@code verificationPending} — waiting for SMS (keep polling)</li>
 *   <li>{@code verificationCompleted} — SMS received (extract code)</li>
 *   <li>{@code verificationCanceled} — cancelled (terminal)</li>
 *   <li>{@code verificationTimedOut} — timed out (terminal)</li>
 *   <li>{@code verificationReported} — reported as failed (terminal)</li>
 *   <li>{@code verificationRefunded} — refunded (terminal)</li>
 * </ul>
 *
 * <h2>API Reference</h2>
 * <p>Base URL: {@code https://www.textverified.com}<br>
 * Auth: Bearer token (obtained via Simple Authentication)<br>
 * Docs: <a href="https://www.textverified.com/docs/api/v2">
 * textverified.com/docs/api/v2</a></p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (TextVerifiedService sms = context.register(
 *         new TextVerifiedService(apiKey, email, context))) {
 *
 *     SmsActivation activation = sms.requestNumber(SmsService.UBER);
 *     page.fillFormField(PHONE_TEXT, activation.phoneNumber(), true);
 *
 *     String code = sms.pollForCode(activation);
 *     page.fillFormField(OTP_TEXT, code, false);
 *
 *     sms.completeActivation(activation.activationId());
 * }
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Bearer token acquisition, caching, and refresh</li>
 *   <li>Create verifications via POST endpoint</li>
 *   <li>Poll verification state and follow HATEOAS sms link</li>
 *   <li>Cancel verifications via POST cancel endpoint</li>
 *   <li>Parse JSON responses from all endpoints</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Polling loop and timeout logic ({@link SmsServiceBase})</li>
 *   <li>Phone number normalization ({@link SmsServiceBase})</li>
 *   <li>API key / email storage ({@link org.nodriver4j.persistence.Settings})</li>
 *   <li>Task lifecycle ({@link TaskExecutionService})</li>
 * </ul>
 *
 * @see SmsServiceBase
 * @see SmsService
 * @see SmsActivation
 */
public class TextVerifiedService extends SmsServiceBase {

    // ==================== Constants ====================

    private static final String BASE_URL = "https://www.textverified.com";
    private static final String AUTH_ENDPOINT = "/api/pub/v2/simple/authentication";
    private static final String VERIFICATIONS_ENDPOINT = "/api/pub/v2/verifications";

    // --- Auth Headers ---
    private static final String HEADER_API_KEY = "X-SIMPLE-API-ACCESS-TOKEN";
    private static final String HEADER_AUTH = "Authorization";

    // --- Verification States ---
    private static final String STATE_PENDING = "verificationPending";
    private static final String STATE_COMPLETED = "verificationCompleted";
    private static final String STATE_CANCELED = "verificationCanceled";
    private static final String STATE_TIMED_OUT = "verificationTimedOut";
    private static final String STATE_REPORTED = "verificationReported";
    private static final String STATE_REFUNDED = "verificationRefunded";

    /**
     * Buffer before actual token expiry to trigger a refresh. Prevents
     * race conditions where a request is sent with a token that expires
     * mid-flight.
     */
    private static final Duration TOKEN_EXPIRY_BUFFER = Duration.ofSeconds(30);

    private static final Gson GSON = new Gson();

    // ==================== Fields ====================

    private final String apiKey;
    private final String email;

    /** Cached bearer token. Guarded by {@code synchronized(tokenLock)}. */
    private String bearerToken;

    /** When the cached bearer token expires. Guarded by {@code synchronized(tokenLock)}. */
    private Instant tokenExpiry;

    private final Object tokenLock = new Object();

    // ==================== Constructors ====================

    /**
     * Creates a TextVerified service with the default poll interval.
     *
     * @param apiKey  the TextVerified API key
     * @param email   the TextVerified account email (username)
     * @param context the task context for cancellation (may be null for testing)
     * @throws IllegalArgumentException if apiKey or email is null or blank
     */
    public TextVerifiedService(String apiKey, String email, TaskContext context) {
        super(context);
        validateCredentials(apiKey, email);
        this.apiKey = apiKey;
        this.email = email;
    }

    /**
     * Creates a TextVerified service with a custom poll interval.
     *
     * @param apiKey       the TextVerified API key
     * @param email        the TextVerified account email (username)
     * @param context      the task context for cancellation (may be null for testing)
     * @param pollInterval how often to poll for OTP codes
     * @throws IllegalArgumentException if apiKey or email is null or blank,
     *                                  or pollInterval is invalid
     */
    public TextVerifiedService(String apiKey, String email, TaskContext context,
                               Duration pollInterval) {
        super(context, pollInterval);
        validateCredentials(apiKey, email);
        this.apiKey = apiKey;
        this.email = email;
    }

    // ==================== SmsServiceBase Implementation ====================

    /**
     * Creates a new verification on TextVerified for the specified service.
     *
     * <p>Sends a POST to {@code /api/pub/v2/verifications} with the service
     * name and {@code "sms"} capability. On success (HTTP 201), extracts
     * the verification ID from the {@code Location} header, then fetches
     * the full verification details to obtain the phone number.</p>
     *
     * @param service the target service
     * @return an activation containing the phone number and verification ID
     * @throws SmsProviderException if the request fails
     */
    @Override
    public SmsActivation requestNumber(SmsService service) throws SmsProviderException {
        ensureAuthenticated();

        JsonObject body = new JsonObject();
        body.addProperty("serviceName", service.textVerifiedName());
        body.addProperty("capability", "sms");

        HttpResponse<String> response = executePost(
                BASE_URL + VERIFICATIONS_ENDPOINT,
                body.toString()
        );

        // 201 Created — extract verification ID from Location header or response
        if (response.statusCode() != 201) {
            throw new SmsProviderException(
                    "TextVerified create verification failed (HTTP " + response.statusCode() +
                            "): " + truncateBody(response.body()));
        }

        String verificationId = extractVerificationId(response);

        // Fetch full details to get the phone number
        JsonObject details = getVerificationDetails(verificationId);

        String phoneNumber = normalizePhoneNumber(
                details.has("number") ? details.get("number").getAsString() : null);

        if (phoneNumber.isBlank()) {
            throw new SmsProviderException(
                    "TextVerified verification created but no phone number returned");
        }

        System.out.println("[TextVerified] Created verification " + verificationId +
                " — number " + phoneNumber + " for " + service.displayName());

        return new SmsActivation(verificationId, phoneNumber, providerName());
    }

    /**
     * Checks TextVerified for an OTP code on the given verification.
     *
     * <p>Fetches the verification details and inspects the {@code state}
     * field:</p>
     * <ul>
     *   <li>{@code verificationPending} → returns {@code null} (keep polling)</li>
     *   <li>{@code verificationCompleted} → follows the {@code sms.href}
     *       link to retrieve the SMS content and extract the code</li>
     *   <li>Any terminal error state → throws {@link SmsProviderException}</li>
     * </ul>
     *
     * @param activationId the verification ID
     * @return the OTP code, or null if not yet received
     * @throws SmsProviderException if the verification is in a terminal error state
     */
    @Override
    protected String checkForCode(String activationId) throws SmsProviderException {
        ensureAuthenticated();

        JsonObject details = getVerificationDetails(activationId);

        String state = details.has("state")
                ? details.get("state").getAsString()
                : "unknown";

        return switch (state) {
            case STATE_PENDING -> null; // Still waiting — polling continues

            case STATE_COMPLETED -> fetchSmsCode(details, activationId);

            case STATE_CANCELED ->
                    throw new SmsProviderException("TextVerified verification was cancelled");

            case STATE_TIMED_OUT ->
                    throw new SmsProviderException("TextVerified verification timed out");

            case STATE_REPORTED ->
                    throw new SmsProviderException("TextVerified verification was reported");

            case STATE_REFUNDED ->
                    throw new SmsProviderException("TextVerified verification was refunded");

            default ->
                    throw new SmsProviderException(
                            "TextVerified verification in unexpected state: " + state);
        };
    }

    /**
     * Marks a TextVerified verification as completed.
     *
     * <p>TextVerified verifications auto-complete when the SMS is received.
     * This method is a no-op — included for API symmetry with other providers.
     * The verification will transition to {@code verificationCompleted}
     * automatically.</p>
     *
     * @param activationId the verification ID
     */
    @Override
    public void completeActivation(String activationId) throws SmsProviderException {
        // TextVerified auto-completes verifications when the SMS arrives.
        // No explicit complete action is needed or available.
        System.out.println("[TextVerified] Verification " + activationId +
                " — auto-completed by provider");
    }

    /**
     * Cancels a TextVerified verification.
     *
     * <p>Sends a POST to {@code /api/pub/v2/verifications/{id}/cancel}.
     * Returns 200 on success. If the verification does not allow
     * cancellation, the server returns 400.</p>
     *
     * @param activationId the verification ID
     * @throws SmsProviderException if the cancellation request fails
     */
    @Override
    public void cancelActivation(String activationId) throws SmsProviderException {
        ensureAuthenticated();

        String url = BASE_URL + VERIFICATIONS_ENDPOINT + "/" + activationId + "/cancel";

        try {
            HttpResponse<String> response = executePost(url, "");

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("[TextVerified] Verification " + activationId + " cancelled");
                return;
            }

            // Non-fatal — the verification may already be in a terminal state
            System.err.println("[TextVerified] Cancel returned HTTP " +
                    response.statusCode() + " for verification " + activationId +
                    ": " + truncateBody(response.body()));

        } catch (SmsProviderException e) {
            // Non-fatal for cancel — log but don't propagate
            System.err.println("[TextVerified] Cancel failed for verification " +
                    activationId + ": " + e.getMessage());
        }
    }

    @Override
    public String providerName() {
        return "TextVerified";
    }

    // ==================== Authentication ====================

    /**
     * Ensures a valid bearer token is available, refreshing if necessary.
     *
     * <p>Thread-safe: multiple threads can call this concurrently. Only
     * one will perform the actual HTTP auth call; others will wait and
     * then use the refreshed token.</p>
     *
     * @throws SmsProviderException if authentication fails
     */
    private void ensureAuthenticated() throws SmsProviderException {
        synchronized (tokenLock) {
            if (bearerToken != null && tokenExpiry != null
                    && Instant.now().isBefore(tokenExpiry.minus(TOKEN_EXPIRY_BUFFER))) {
                return; // Token is still valid
            }

            authenticate();
        }
    }

    /**
     * Exchanges the API key and email for a bearer token.
     *
     * <p>Sends a POST to the Simple Authentication endpoint with
     * credentials in headers. The response contains the bearer token
     * and its expiration timestamp.</p>
     *
     * <p>Must be called while holding {@code tokenLock}.</p>
     *
     * @throws SmsProviderException if authentication fails
     */
    private void authenticate() throws SmsProviderException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + AUTH_ENDPOINT))
                    .header(HEADER_API_KEY, apiKey)
                    .header("X-SIMPLE-API-ACCESS-USERNAME", email)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new SmsProviderException(
                        "TextVerified authentication failed — check API key and email");
            }

            ensureSuccess(response, "authentication");

            JsonObject json = GSON.fromJson(response.body().trim(), JsonObject.class);

            // Extract bearer token — field name may be "bearer_token" or "token"
            bearerToken = extractString(json, "bearer_token", "token");
            if (bearerToken == null || bearerToken.isBlank()) {
                throw new SmsProviderException(
                        "TextVerified auth response missing bearer token: " + json);
            }

            // Extract expiration — field may be "expiration" or "expires_at"
            String expiryStr = extractString(json, "expiration", "expires_at");
            if (expiryStr != null && !expiryStr.isBlank()) {
                try {
                    tokenExpiry = Instant.parse(expiryStr);
                } catch (Exception e) {
                    // Fallback: assume 30 minutes from now
                    tokenExpiry = Instant.now().plusSeconds(1800);
                    System.err.println("[TextVerified] Could not parse token expiry '" +
                            expiryStr + "', defaulting to 30 minutes");
                }
            } else {
                // No expiry provided — assume 30 minutes
                tokenExpiry = Instant.now().plusSeconds(1800);
            }

            System.out.println("[TextVerified] Authenticated — token expires at " + tokenExpiry);

        } catch (SmsProviderException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsProviderException("TextVerified authentication interrupted", e);
        } catch (Exception e) {
            throw new SmsProviderException(
                    "TextVerified authentication failed: " + e.getMessage(), e);
        }
    }

    // ==================== Verification Details ====================

    /**
     * Fetches the full details of a verification by ID.
     *
     * @param verificationId the verification ID
     * @return the parsed JSON response
     * @throws SmsProviderException if the request fails
     */
    private JsonObject getVerificationDetails(String verificationId) throws SmsProviderException {
        String url = BASE_URL + VERIFICATIONS_ENDPOINT + "/" + verificationId;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header(HEADER_AUTH, "Bearer " + bearerToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            ensureSuccess(response, "get verification details");

            return GSON.fromJson(response.body().trim(), JsonObject.class);

        } catch (SmsProviderException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsProviderException("TextVerified request interrupted", e);
        } catch (Exception e) {
            throw new SmsProviderException(
                    "TextVerified get verification failed: " + e.getMessage(), e);
        }
    }

    // ==================== SMS Retrieval ====================

    /**
     * Follows the HATEOAS {@code sms.href} link from the verification
     * details to retrieve the SMS content and extract the OTP code.
     *
     * <p>The {@code sms} object in the verification details contains a
     * {@code method} and {@code href} field. The href is an absolute or
     * relative URL that returns a list of SMS messages for this
     * verification.</p>
     *
     * @param details        the verification details JSON
     * @param verificationId the verification ID (for error messages)
     * @return the extracted OTP code
     * @throws SmsProviderException if the SMS link is missing or retrieval fails
     */
    private String fetchSmsCode(JsonObject details, String verificationId)
            throws SmsProviderException {

        // Extract the sms.href link
        String smsHref = extractHref(details, "sms");
        if (smsHref == null || smsHref.isBlank()) {
            throw new SmsProviderException(
                    "TextVerified verification " + verificationId +
                            " completed but no sms.href link found");
        }

        // Resolve relative URLs
        String smsUrl = smsHref.startsWith("http") ? smsHref : BASE_URL + smsHref;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(smsUrl))
                    .header(HEADER_AUTH, "Bearer " + bearerToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            ensureSuccess(response, "get SMS messages");

            return extractCodeFromSmsResponse(response.body().trim(), verificationId);

        } catch (SmsProviderException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsProviderException("TextVerified SMS retrieval interrupted", e);
        } catch (Exception e) {
            throw new SmsProviderException(
                    "TextVerified SMS retrieval failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the OTP code from the SMS endpoint response.
     *
     * <p>Handles two response formats:</p>
     * <ul>
     *   <li>Array format: {@code [{"sms_content": "...", "parsedCode": "1234"}]}</li>
     *   <li>Wrapper format: {@code {"data": [{"smsContent": "...", "parsedCode": "1234"}]}}</li>
     * </ul>
     *
     * <p>Looks for a parsed code field first, then falls back to extracting
     * digits from the raw SMS content.</p>
     *
     * @param responseBody   the raw response body
     * @param verificationId the verification ID (for error messages)
     * @return the extracted OTP code
     * @throws SmsProviderException if no code can be extracted
     */
    private String extractCodeFromSmsResponse(String responseBody, String verificationId)
            throws SmsProviderException {

        JsonElement root = GSON.fromJson(responseBody, JsonElement.class);

        JsonArray messages;
        if (root.isJsonArray()) {
            messages = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("data")) {
            messages = root.getAsJsonObject().getAsJsonArray("data");
        } else {
            throw new SmsProviderException(
                    "TextVerified SMS response has unexpected format for verification " +
                            verificationId);
        }

        if (messages == null || messages.isEmpty()) {
            throw new SmsProviderException(
                    "TextVerified verification " + verificationId +
                            " completed but no SMS messages found");
        }

        // Check the most recent message (last in array)
        JsonObject message = messages.get(messages.size() - 1).getAsJsonObject();

        // Try parsed code fields first
        String code = extractString(message, "parsedCode", "parsed_code", "code");
        if (code != null && !code.isBlank()) {
            return code.trim();
        }

        // Fall back to raw SMS content — extract the first numeric sequence
        String content = extractString(message, "smsContent", "sms_content", "text");
        if (content != null && !content.isBlank()) {
            String extracted = extractDigitsFromText(content);
            if (extracted != null) {
                return extracted;
            }
        }

        throw new SmsProviderException(
                "TextVerified verification " + verificationId +
                        " — could not extract OTP code from SMS response");
    }

    // ==================== HTTP Helpers ====================

    /**
     * Executes an authenticated POST request.
     *
     * @param url  the full URL
     * @param body the JSON request body (may be empty)
     * @return the HTTP response
     * @throws SmsProviderException if the request fails
     */
    private HttpResponse<String> executePost(String url, String body)
            throws SmsProviderException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header(HEADER_AUTH, "Bearer " + bearerToken)
                    .header("Content-Type", "application/json");

            if (body != null && !body.isBlank()) {
                builder.POST(HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            }

            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsProviderException("TextVerified request interrupted", e);
        } catch (Exception e) {
            throw new SmsProviderException("TextVerified request failed: " + e.getMessage(), e);
        }
    }

    // ==================== Response Parsing ====================

    /**
     * Extracts the verification ID from a create verification response.
     *
     * <p>Tries the {@code Location} header first (standard REST pattern),
     * then falls back to parsing the response body for an {@code id} or
     * {@code href} field.</p>
     *
     * @param response the HTTP response from the create verification call
     * @return the extracted verification ID
     * @throws SmsProviderException if the ID cannot be determined
     */
    private String extractVerificationId(HttpResponse<String> response)
            throws SmsProviderException {

        // Try Location header: /api/pub/v2/verifications/{id}
        String location = response.headers()
                .firstValue("Location")
                .or(() -> response.headers().firstValue("location"))
                .orElse(null);

        if (location != null && !location.isBlank()) {
            // Extract the last path segment as the ID
            String[] segments = location.split("/");
            String lastSegment = segments[segments.length - 1];
            if (!lastSegment.isBlank()) {
                return lastSegment;
            }
        }

        // Fallback: parse response body
        String body = response.body();
        if (body != null && !body.isBlank()) {
            JsonObject json = GSON.fromJson(body.trim(), JsonObject.class);

            // Try direct id field
            if (json.has("id")) {
                return json.get("id").getAsString();
            }

            // Try href in link object: {"method": "GET", "href": "/api/.../verifications/abc123"}
            String href = extractString(json, "href");
            if (href != null) {
                String[] segments = href.split("/");
                return segments[segments.length - 1];
            }
        }

        throw new SmsProviderException(
                "TextVerified create verification succeeded but could not determine verification ID");
    }

    /**
     * Extracts the {@code href} value from a HATEOAS link object.
     *
     * <p>Given a verification details JSON, extracts the href from a
     * nested link structure like {@code {"sms": {"method": "GET", "href": "..."}}}.</p>
     *
     * @param parent  the parent JSON object
     * @param linkName the name of the link object (e.g., "sms", "cancel")
     * @return the href string, or null if not found
     */
    private static String extractHref(JsonObject parent, String linkName) {
        if (!parent.has(linkName)) {
            return null;
        }

        JsonElement element = parent.get(linkName);
        if (!element.isJsonObject()) {
            return null;
        }

        JsonObject link = element.getAsJsonObject();
        if (link.has("href") && !link.get("href").isJsonNull()) {
            return link.get("href").getAsString();
        }

        // Also check nested link structure: {"sms": {"link": {"href": "..."}}}
        if (link.has("link") && link.get("link").isJsonObject()) {
            JsonObject innerLink = link.getAsJsonObject("link");
            if (innerLink.has("href") && !innerLink.get("href").isJsonNull()) {
                return innerLink.get("href").getAsString();
            }
        }

        return null;
    }

    /**
     * Extracts the first non-null string value from a JSON object,
     * trying multiple possible field names.
     *
     * @param json       the JSON object
     * @param fieldNames the candidate field names (tried in order)
     * @return the first non-null value found, or null
     */
    private static String extractString(JsonObject json, String... fieldNames) {
        for (String name : fieldNames) {
            if (json.has(name) && !json.get(name).isJsonNull()) {
                return json.get(name).getAsString();
            }
        }
        return null;
    }

    /**
     * Extracts the first sequence of 4–8 consecutive digits from text.
     *
     * <p>OTP codes are typically 4–8 digits. This method finds the first
     * such sequence in the SMS body text.</p>
     *
     * @param text the SMS body text
     * @return the digit sequence, or null if none found
     */
    private static String extractDigitsFromText(String text) {
        if (text == null) {
            return null;
        }

        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\b(\\d{4,8})\\b").matcher(text);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    /**
     * Truncates a response body to a reasonable length for error messages.
     *
     * @param body the response body
     * @return the truncated body (max 200 chars)
     */
    private static String truncateBody(String body) {
        if (body == null) {
            return "<empty>";
        }
        if (body.length() <= 200) {
            return body;
        }
        return body.substring(0, 200) + "...";
    }

    // ==================== Validation ====================

    /**
     * Validates that the API key and email are non-null and non-blank.
     *
     * @param apiKey the API key to validate
     * @param email  the email to validate
     * @throws IllegalArgumentException if either is null or blank
     */
    private static void validateCredentials(String apiKey, String email) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email cannot be null or blank");
        }
    }
}