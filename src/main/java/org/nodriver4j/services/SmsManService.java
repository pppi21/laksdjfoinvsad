package org.nodriver4j.services;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.nodriver4j.services.exceptions.SmsProviderException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * SMS verification service backed by the SMS-Man API (v2.0).
 *
 * <p>SMS-Man uses a JSON-based REST API with {@code token} query parameter
 * authentication. All requests are GET calls to
 * {@code https://api.sms-man.com/control/} with action-specific paths.</p>
 *
 * <h2>API Endpoints</h2>
 * <ul>
 *   <li>{@code get-number} — rents a phone number, returns JSON with
 *       {@code request_id} and {@code number}</li>
 *   <li>{@code get-sms} — checks for an incoming code, returns JSON with
 *       {@code sms_code} on success or {@code error_code: "wait_sms"}
 *       while waiting</li>
 *   <li>{@code set-status} — updates the activation status
 *       ({@code close} = complete, {@code reject} = cancel)</li>
 * </ul>
 *
 * <h2>API Reference</h2>
 * <p>Base URL: {@code https://api.sms-man.com/control/}<br>
 * Auth: {@code token} query parameter<br>
 * Docs: <a href="https://sms-man.com/api">sms-man.com/api</a></p>
 *
 * <h2>Country ID</h2>
 * <p>SMS-Man uses integer country IDs. The US country ID is stored in
 * {@link #US_COUNTRY_ID}. If this value is incorrect, verify it via the
 * {@code get-limits} endpoint:
 * {@code https://api.sms-man.com/control/limits?token=$token}</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (SmsManService sms = context.register(new SmsManService(apiKey, context))) {
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
 *   <li>Rent numbers via {@code get-number} endpoint</li>
 *   <li>Check for OTP codes via {@code get-sms} endpoint</li>
 *   <li>Complete activations via {@code set-status} (status=close)</li>
 *   <li>Cancel activations via {@code set-status} (status=reject)</li>
 *   <li>Parse JSON responses and error objects</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Polling loop and timeout logic ({@link SmsServiceBase})</li>
 *   <li>Phone number normalization ({@link SmsServiceBase})</li>
 *   <li>API key storage ({@link org.nodriver4j.persistence.Settings})</li>
 *   <li>Task lifecycle ({@link TaskExecutionService})</li>
 * </ul>
 *
 * @see SmsServiceBase
 * @see SmsService
 * @see SmsActivation
 */
public class SmsManService extends SmsServiceBase {

    // ==================== Constants ====================

    private static final String BASE_URL = "https://api.sms-man.com/control/";

    /**
     * SMS-Man country ID for the United States.
     *
     * <p>Verify via: {@code GET https://api.sms-man.com/control/limits?token=$token}
     * and look for the US entry. Update this constant if incorrect.</p>
     */
    private static final int US_COUNTRY_ID = 1;

    // --- JSON Field Names ---
    private static final String FIELD_REQUEST_ID = "request_id";
    private static final String FIELD_NUMBER = "number";
    private static final String FIELD_SMS_CODE = "sms_code";
    private static final String FIELD_ERROR_CODE = "error_code";
    private static final String FIELD_ERROR_MSG = "error_msg";
    private static final String FIELD_SUCCESS = "success";

    // --- Error Codes ---
    private static final String ERROR_WAIT_SMS = "wait_sms";
    private static final String ERROR_WRONG_TOKEN = "wrong_token";
    private static final String ERROR_NO_NUMBERS = "no_numbers";
    private static final String ERROR_WRONG_STATUS = "wrong_status";

    // --- setStatus Values ---
    private static final String STATUS_CLOSE = "close";
    private static final String STATUS_REJECT = "reject";

    private static final Gson GSON = new Gson();

    // ==================== Fields ====================

    private final String apiToken;

    // ==================== Constructors ====================

    /**
     * Creates an SMS-Man service with the default poll interval.
     *
     * @param apiToken the SMS-Man API token
     * @param context  the task context for cancellation (may be null for testing)
     * @throws IllegalArgumentException if apiToken is null or blank
     */
    public SmsManService(String apiToken, TaskContext context) {
        super(context);
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalArgumentException("API token cannot be null or blank");
        }
        this.apiToken = apiToken;
    }

    /**
     * Creates an SMS-Man service with a custom poll interval.
     *
     * @param apiToken     the SMS-Man API token
     * @param context      the task context for cancellation (may be null for testing)
     * @param pollInterval how often to poll for OTP codes
     * @throws IllegalArgumentException if apiToken is null or blank, or pollInterval is invalid
     */
    public SmsManService(String apiToken, TaskContext context, Duration pollInterval) {
        super(context, pollInterval);
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalArgumentException("API token cannot be null or blank");
        }
        this.apiToken = apiToken;
    }

    // ==================== SmsServiceBase Implementation ====================

    /**
     * Rents a phone number from SMS-Man for the specified service.
     *
     * <p>Calls the {@code get-number} endpoint with {@code country_id}
     * and {@code application_id}. On success, the JSON response contains
     * {@code request_id} and {@code number}.</p>
     *
     * <p>Example success response:</p>
     * <pre>{@code {"request_id":1,"country_id":1,"application_id":31,"number":"12125551234"}}</pre>
     *
     * @param service the target service
     * @return an activation containing the rented number and request ID
     * @throws SmsProviderException if the request fails
     */
    @Override
    public SmsActivation requestNumber(SmsService service) throws SmsProviderException {
        String url = BASE_URL + "get-number" +
                "?token=" + apiToken +
                "&country_id=" + US_COUNTRY_ID +
                "&application_id=" + service.smsManAppId();

        JsonObject json = executeGetJson(url);

        if (json.has(FIELD_ERROR_CODE)) {
            throw new SmsProviderException(
                    "SMS-Man getNumber failed: " + extractErrorMessage(json));
        }

        if (!json.has(FIELD_REQUEST_ID) || !json.has(FIELD_NUMBER)) {
            throw new SmsProviderException(
                    "SMS-Man getNumber returned incomplete response: " + json);
        }

        String requestId = json.get(FIELD_REQUEST_ID).getAsString();
        String phoneNumber = normalizePhoneNumber(json.get(FIELD_NUMBER).getAsString());

        System.out.println("[SMS-Man] Rented number " + phoneNumber +
                " (requestId=" + requestId + ") for " + service.displayName());

        return new SmsActivation(requestId, phoneNumber, providerName());
    }

    /**
     * Checks SMS-Man for an OTP code on the given activation.
     *
     * <p>Calls the {@code get-sms} endpoint. Returns the code if
     * {@code sms_code} is present, {@code null} if the error code is
     * {@code wait_sms}, or throws for other errors.</p>
     *
     * <p>Example success response:</p>
     * <pre>{@code {"request_id":1,"country_id":1,"application_id":31,"number":"12125551234","sms_code":"1243"}}</pre>
     *
     * <p>Example waiting response:</p>
     * <pre>{@code {"request_id":1,...,"error_code":"wait_sms","error_msg":"Still waiting..."}}</pre>
     *
     * @param activationId the request ID from the getNumber response
     * @return the OTP code, or null if not yet received
     * @throws SmsProviderException if the activation is in a terminal error state
     */
    @Override
    protected String checkForCode(String activationId) throws SmsProviderException {
        String url = BASE_URL + "get-sms" +
                "?token=" + apiToken +
                "&request_id=" + activationId;

        JsonObject json = executeGetJson(url);

        // Success — code is present
        if (json.has(FIELD_SMS_CODE)) {
            String code = json.get(FIELD_SMS_CODE).getAsString();
            if (code != null && !code.isBlank()) {
                return code.trim();
            }
        }

        // Check for error_code
        if (json.has(FIELD_ERROR_CODE)) {
            String errorCode = json.get(FIELD_ERROR_CODE).getAsString();

            if (ERROR_WAIT_SMS.equals(errorCode)) {
                return null; // Still waiting — polling continues
            }

            // Any other error code is a permanent failure
            throw new SmsProviderException(
                    "SMS-Man getSms failed: " + extractErrorMessage(json));
        }

        // No sms_code and no error_code — unexpected format
        throw new SmsProviderException(
                "SMS-Man getSms returned unexpected response: " + json);
    }

    /**
     * Marks an SMS-Man activation as successfully completed.
     *
     * <p>Calls {@code set-status} with {@code status=close}.</p>
     *
     * @param activationId the request ID
     * @throws SmsProviderException if the completion request fails
     */
    @Override
    public void completeActivation(String activationId) throws SmsProviderException {
        setStatus(activationId, STATUS_CLOSE, "complete");
    }

    /**
     * Cancels an SMS-Man activation and requests a refund.
     *
     * <p>Calls {@code set-status} with {@code status=reject}.</p>
     *
     * @param activationId the request ID
     * @throws SmsProviderException if the cancellation request fails
     */
    @Override
    public void cancelActivation(String activationId) throws SmsProviderException {
        setStatus(activationId, STATUS_REJECT, "cancel");
    }

    @Override
    public String providerName() {
        return "SMS-Man";
    }

    // ==================== Internal ====================

    /**
     * Calls the {@code set-status} endpoint for the given activation.
     *
     * <p>Expected success response: {@code {"request_id":1,"success":true}}</p>
     *
     * @param requestId the request ID
     * @param status    the status value ("close" or "reject")
     * @param action    human-readable action name for log messages
     * @throws SmsProviderException if the request fails
     */
    private void setStatus(String requestId, String status, String action)
            throws SmsProviderException {

        String url = BASE_URL + "set-status" +
                "?token=" + apiToken +
                "&request_id=" + requestId +
                "&status=" + status;

        JsonObject json = executeGetJson(url);

        if (json.has(FIELD_SUCCESS) && json.get(FIELD_SUCCESS).getAsBoolean()) {
            System.out.println("[SMS-Man] Activation " + requestId + " " + action + "d");
            return;
        }

        // Non-fatal — log but don't throw. The activation may already be
        // in a terminal state (e.g., user cancelled then script tries to complete).
        System.err.println("[SMS-Man] setStatus(" + action + ") unexpected response " +
                "for request " + requestId + ": " + json);
    }

    /**
     * Executes a GET request and parses the response as a JSON object.
     *
     * @param url the full URL with query parameters
     * @return the parsed JSON object
     * @throws SmsProviderException if the HTTP request or JSON parsing fails
     */
    private JsonObject executeGetJson(String url) throws SmsProviderException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            ensureSuccess(response, "request");

            String body = response.body().trim();

            return GSON.fromJson(body, JsonObject.class);

        } catch (SmsProviderException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsProviderException("SMS-Man request interrupted", e);
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new SmsProviderException("SMS-Man returned invalid JSON: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new SmsProviderException("SMS-Man request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts a human-readable error message from an SMS-Man error response.
     *
     * <p>SMS-Man error responses contain {@code error_code} and optionally
     * {@code error_msg}. This method combines them into a single string,
     * mapping known error codes to friendlier descriptions.</p>
     *
     * @param json the error response JSON object
     * @return a descriptive error message
     */
    private static String extractErrorMessage(JsonObject json) {
        String errorCode = json.has(FIELD_ERROR_CODE)
                ? json.get(FIELD_ERROR_CODE).getAsString()
                : "unknown";

        String errorMsg = json.has(FIELD_ERROR_MSG)
                ? json.get(FIELD_ERROR_MSG).getAsString()
                : null;

        String friendly = switch (errorCode) {
            case ERROR_WRONG_TOKEN -> "Invalid SMS-Man API token";
            case ERROR_NO_NUMBERS -> "No phone numbers available for this service";
            case ERROR_WRONG_STATUS -> "Invalid status transition";
            default -> null;
        };

        if (friendly != null) {
            return friendly;
        }

        if (errorMsg != null && !errorMsg.isBlank()) {
            return errorCode + ": " + errorMsg;
        }

        return errorCode;
    }
}