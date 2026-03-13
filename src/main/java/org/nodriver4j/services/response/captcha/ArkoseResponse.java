package org.nodriver4j.services.response.captcha;

import org.nodriver4j.services.captcha.twocaptcha.TwoCaptchaService;

/**
 * Service-agnostic response from an Arkose Labs (FunCaptcha) solve.
 *
 * <p>This record represents the result of solving an Arkose Labs challenge
 * through any solving service (e.g., 2Captcha). It contains the session token
 * needed for form submission and optional metadata returned by the service.</p>
 *
 * <h2>Arkose Token Format</h2>
 * <p>Arkose session tokens are pipe-delimited strings containing metadata:</p>
 * <pre>{@code
 * 14160cdbe84b28cd5.8020398501|r=us-east-1|metabgclr=#ffffff|pk=PUBLIC_KEY|...
 * }</pre>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * ArkoseResponse response = twoCaptchaService.solveFunCaptcha(
 *     "https://example.com", publicKey, null, null
 * );
 *
 * if (response.success()) {
 *     String token = response.token();
 *     // Inject token into page via onCompleted callback
 * } else {
 *     System.err.println("Solve failed: " + response.errorMessage());
 * }
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Hold the Arkose session token and solve metadata</li>
 *   <li>Provide convenience methods for token validation</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>API communication (delegated to service classes like TwoCaptchaService)</li>
 *   <li>Token injection into pages (delegated to ArkoseSolver)</li>
 *   <li>Service-specific fields (task IDs, cost, solve count)</li>
 * </ul>
 *
 * @param token        the Arkose session token for submission, null if solve failed
 * @param userAgent    the user agent string recommended by the solving service, may be null
 * @param success      whether the solve request completed successfully
 * @param errorMessage description of the error if unsuccessful, null otherwise
 *
 * @see TwoCaptchaService
 */
public record ArkoseResponse(
        String token,
        String userAgent,
        boolean success,
        String errorMessage
) {

    /**
     * Creates a successful response with a token.
     *
     * @param token     the Arkose session token
     * @param userAgent the user agent string from the solving service, may be null
     * @return a successful response
     * @throws IllegalArgumentException if token is null or blank
     */
    public static ArkoseResponse success(String token, String userAgent) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token cannot be null or blank for a successful response");
        }
        return new ArkoseResponse(token, userAgent, true, null);
    }

    /**
     * Creates a failed response with an error message.
     *
     * @param errorMessage description of the failure
     * @return a failed response
     */
    public static ArkoseResponse failure(String errorMessage) {
        return new ArkoseResponse(null, null, false, errorMessage);
    }

    /**
     * Checks whether the response contains a usable token.
     *
     * @return true if the solve succeeded and the token is non-blank
     */
    public boolean hasToken() {
        return success && token != null && !token.isBlank();
    }

    /**
     * Checks whether the solving service provided a user agent recommendation.
     *
     * @return true if a non-blank user agent string is present
     */
    public boolean hasUserAgent() {
        return userAgent != null && !userAgent.isBlank();
    }

    @Override
    public String toString() {
        if (success) {
            return String.format("ArkoseResponse{success=true, tokenLength=%d, hasUserAgent=%s}",
                    token != null ? token.length() : 0, hasUserAgent());
        }
        return String.format("ArkoseResponse{success=false, error=%s}", errorMessage);
    }
}