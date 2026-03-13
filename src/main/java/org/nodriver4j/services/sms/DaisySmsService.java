package org.nodriver4j.services.sms;

import org.nodriver4j.services.*;
import org.nodriver4j.services.response.sms.SmsActivation;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * SMS verification service backed by the DaisySMS API.
 *
 * <p><strong>DEPRECATION NOTICE:</strong> DaisySMS is shutting down on
 * <b>March 26, 2026</b>. This class will stop functioning after that date.
 * Migrate to {@link SmsManService} or {@link TextVerifiedService} before
 * the shutdown.</p>
 *
 * <p>DaisySMS implements the <em>sms-activate compatible</em> text-based
 * protocol. All requests are GET calls to a single endpoint with an
 * {@code action} parameter, and responses are plain-text strings with
 * colon-separated fields:</p>
 * <ul>
 *   <li>{@code ACCESS_NUMBER:$id:$phone} — number rented successfully</li>
 *   <li>{@code STATUS_OK:$code} — OTP code received</li>
 *   <li>{@code STATUS_WAIT_CODE} — still waiting for SMS</li>
 *   <li>{@code STATUS_CANCEL} — activation was cancelled</li>
 * </ul>
 *
 * <h2>API Reference</h2>
 * <p>Base URL: {@code https://daisysms.com/stubs/handler_api.php}<br>
 * Auth: {@code api_key} query parameter<br>
 * Docs: <a href="https://daisysms.com/docs/api">daisysms.com/docs/api</a></p>
 *
 * <h2>Rate Limiting</h2>
 * <p>DaisySMS requires polling intervals of 3 seconds or more. The default
 * poll interval inherited from {@link SmsServiceBase} (4s) satisfies this.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (DaisySmsService sms = context.register(new DaisySmsService(apiKey, context))) {
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
 *   <li>Rent numbers via {@code getNumber} action</li>
 *   <li>Check for OTP codes via {@code getStatus} action</li>
 *   <li>Complete activations via {@code setStatus} (status=6)</li>
 *   <li>Cancel activations via {@code setStatus} (status=8)</li>
 *   <li>Parse sms-activate text responses</li>
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
 * @deprecated DaisySMS is shutting down on March 26, 2026.
 * @see SmsServiceBase
 * @see SmsService
 * @see SmsActivation
 */
@Deprecated(since = "2026-03-26", forRemoval = true)
public class DaisySmsService extends SmsServiceBase {

    // ==================== Constants ====================

    private static final String BASE_URL = "https://daisysms.com/stubs/handler_api.php";

    // --- Response Prefixes ---
    private static final String ACCESS_NUMBER = "ACCESS_NUMBER";
    private static final String STATUS_OK = "STATUS_OK";
    private static final String STATUS_WAIT_CODE = "STATUS_WAIT_CODE";
    private static final String STATUS_CANCEL = "STATUS_CANCEL";
    private static final String ACCESS_ACTIVATION = "ACCESS_ACTIVATION";
    private static final String ACCESS_CANCEL = "ACCESS_CANCEL";

    // --- Error Responses ---
    private static final String NO_NUMBERS = "NO_NUMBERS";
    private static final String NO_MONEY = "NO_MONEY";
    private static final String NO_ACTIVATION = "NO_ACTIVATION";
    private static final String TOO_MANY_ACTIVE_RENTALS = "TOO_MANY_ACTIVE_RENTALS";
    private static final String MAX_PRICE_EXCEEDED = "MAX_PRICE_EXCEEDED";
    private static final String BAD_KEY = "BAD_KEY";

    // --- setStatus Codes ---
    private static final String SET_STATUS_COMPLETE = "6";
    private static final String SET_STATUS_CANCEL = "8";

    // ==================== Fields ====================

    private final String apiKey;

    // ==================== Constructors ====================

    /**
     * Creates a DaisySMS service with the default poll interval.
     *
     * @param apiKey  the DaisySMS API key
     * @param context the task context for cancellation (may be null for testing)
     * @throws IllegalArgumentException if apiKey is null or blank
     */
    public DaisySmsService(String apiKey, TaskContext context) {
        super(context);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }
        this.apiKey = apiKey;
    }

    /**
     * Creates a DaisySMS service with a custom poll interval.
     *
     * @param apiKey       the DaisySMS API key
     * @param context      the task context for cancellation (may be null for testing)
     * @param pollInterval how often to poll for OTP codes (must be ≥3s for DaisySMS)
     * @throws IllegalArgumentException if apiKey is null or blank, or pollInterval is invalid
     */
    public DaisySmsService(String apiKey, TaskContext context, Duration pollInterval) {
        super(context, pollInterval);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }
        this.apiKey = apiKey;
    }

    // ==================== SmsServiceBase Implementation ====================

    /**
     * Rents a phone number from DaisySMS for the specified service.
     *
     * <p>Calls the {@code getNumber} action. On success, the response is
     * {@code ACCESS_NUMBER:$id:$phone}. The phone number is normalized
     * (US +1 prefix stripped) before constructing the activation.</p>
     *
     * @param service the target service
     * @return an activation containing the rented number and activation ID
     * @throws SmsProviderException if the request fails
     */
    @Override
    public SmsActivation requestNumber(SmsService service) throws SmsProviderException {
        String url = BASE_URL +
                "?api_key=" + apiKey +
                "&action=getNumber" +
                "&service=" + service.daisySmsCode();

        String body = executeGet(url);

        if (body.startsWith(ACCESS_NUMBER)) {
            // ACCESS_NUMBER:$id:$phone
            String[] parts = body.split(":");
            if (parts.length < 3) {
                throw new SmsProviderException(
                        "DaisySMS returned malformed ACCESS_NUMBER response: " + body);
            }
            String activationId = parts[1];
            String phoneNumber = normalizePhoneNumber(parts[2]);

            System.out.println("[DaisySMS] Rented number " + phoneNumber +
                    " (activation=" + activationId + ") for " + service.displayName());

            return new SmsActivation(activationId, phoneNumber, providerName());
        }

        throw new SmsProviderException("DaisySMS getNumber failed: " + mapErrorMessage(body));
    }

    /**
     * Checks DaisySMS for an OTP code on the given activation.
     *
     * <p>Calls the {@code getStatus} action. Returns the code if
     * {@code STATUS_OK:$code} is received, {@code null} if still waiting,
     * or throws if the activation is in a terminal state.</p>
     *
     * @param activationId the activation ID
     * @return the OTP code, or null if not yet received
     * @throws SmsProviderException if the activation was cancelled or is invalid
     */
    @Override
    protected String checkForCode(String activationId) throws SmsProviderException {
        String url = BASE_URL +
                "?api_key=" + apiKey +
                "&action=getStatus" +
                "&id=" + activationId;

        String body = executeGet(url);

        if (body.startsWith(STATUS_OK)) {
            // STATUS_OK:$code
            String[] parts = body.split(":");
            if (parts.length < 2) {
                throw new SmsProviderException(
                        "DaisySMS returned malformed STATUS_OK response: " + body);
            }
            return parts[1].trim();
        }

        if (STATUS_WAIT_CODE.equals(body.trim())) {
            return null; // Still waiting — polling continues
        }

        if (STATUS_CANCEL.equals(body.trim())) {
            throw new SmsProviderException("DaisySMS activation was cancelled server-side");
        }

        if (NO_ACTIVATION.equals(body.trim())) {
            throw new SmsProviderException("DaisySMS activation not found: " + activationId);
        }

        throw new SmsProviderException("DaisySMS getStatus unexpected response: " + body);
    }

    /**
     * Marks a DaisySMS activation as successfully completed.
     *
     * <p>Calls {@code setStatus} with {@code status=6}. Expected response
     * is {@code ACCESS_ACTIVATION}.</p>
     *
     * @param activationId the activation ID
     * @throws SmsProviderException if the completion request fails
     */
    @Override
    public void completeActivation(String activationId) throws SmsProviderException {
        setStatus(activationId, SET_STATUS_COMPLETE, "complete");
    }

    /**
     * Cancels a DaisySMS activation and requests a refund.
     *
     * <p>Calls {@code setStatus} with {@code status=8}. Expected response
     * is {@code ACCESS_CANCEL}.</p>
     *
     * @param activationId the activation ID
     * @throws SmsProviderException if the cancellation request fails
     */
    @Override
    public void cancelActivation(String activationId) throws SmsProviderException {
        setStatus(activationId, SET_STATUS_CANCEL, "cancel");
    }

    @Override
    public String providerName() {
        return "DaisySMS";
    }

    // ==================== Internal ====================

    /**
     * Calls the {@code setStatus} action for the given activation.
     *
     * @param activationId the activation ID
     * @param status       the status code ("6" for complete, "8" for cancel)
     * @param action       human-readable action name for error messages
     * @throws SmsProviderException if the request fails or returns an error
     */
    private void setStatus(String activationId, String status, String action)
            throws SmsProviderException {

        String url = BASE_URL +
                "?api_key=" + apiKey +
                "&action=setStatus" +
                "&id=" + activationId +
                "&status=" + status;

        String body = executeGet(url);

        if (ACCESS_ACTIVATION.equals(body.trim()) || ACCESS_CANCEL.equals(body.trim())) {
            System.out.println("[DaisySMS] Activation " + activationId + " " + action + "d");
            return;
        }

        // Non-fatal — log but don't throw. The activation may already be
        // in a terminal state (e.g., user cancelled then script tries to complete).
        System.err.println("[DaisySMS] setStatus(" + action + ") unexpected response " +
                "for activation " + activationId + ": " + body);
    }

    /**
     * Executes a GET request and returns the trimmed response body.
     *
     * @param url the full URL with query parameters
     * @return the response body
     * @throws SmsProviderException if the HTTP request fails
     */
    private String executeGet(String url) throws SmsProviderException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            ensureSuccess(response, "request");

            return response.body().trim();

        } catch (SmsProviderException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsProviderException("DaisySMS request interrupted", e);
        } catch (Exception e) {
            throw new SmsProviderException("DaisySMS request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Maps known DaisySMS error response strings to human-readable messages.
     *
     * @param response the raw error response
     * @return a descriptive error message
     */
    private static String mapErrorMessage(String response) {
        if (response == null) {
            return "empty response";
        }

        String trimmed = response.trim();

        return switch (trimmed) {
            case NO_NUMBERS -> "No phone numbers available for this service";
            case NO_MONEY -> "Insufficient DaisySMS balance";
            case TOO_MANY_ACTIVE_RENTALS -> "Too many active rentals — complete or cancel existing ones";
            case MAX_PRICE_EXCEEDED -> "Current price exceeds the configured maximum";
            case BAD_KEY -> "Invalid DaisySMS API key";
            case NO_ACTIVATION -> "Activation not found";
            default -> trimmed;
        };
    }
}