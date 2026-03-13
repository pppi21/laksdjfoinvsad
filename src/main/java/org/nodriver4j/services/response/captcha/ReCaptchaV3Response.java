package org.nodriver4j.services.response.captcha;

import org.nodriver4j.services.captcha.capsolver.CapSolverService;

/**
 * Service-agnostic response from a reCAPTCHA v3 token solve.
 *
 * <p>This record represents the result of solving a reCAPTCHA v3 challenge
 * through any solving service (e.g., CapSolver). It contains the token
 * needed for form submission and optional metadata returned by the service.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * ReCaptchaV3Response response = capSolverService.solveReCaptchaV3(
 *     "https://example.com", siteKey, "login", proxy
 * );
 *
 * if (response.success()) {
 *     String token = response.token();
 *     // Inject token into page or submit with form
 * } else {
 *     System.err.println("Solve failed: " + response.errorMessage());
 * }
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Hold the reCAPTCHA v3 token and solve metadata</li>
 *   <li>Provide convenience methods for token validation</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>API communication (delegated to service classes like CapSolverService)</li>
 *   <li>Token injection into pages (delegated to ReCaptchaV3Solver)</li>
 *   <li>Service-specific fields (task IDs, billing, session cookies)</li>
 * </ul>
 *
 * @param token        the gRecaptchaResponse token for form submission, null if solve failed
 * @param userAgent    the user agent string recommended by the solving service, may be null
 * @param success      whether the solve request completed successfully
 * @param errorMessage description of the error if unsuccessful, null otherwise
 *
 * @see CapSolverService
 */
public record ReCaptchaV3Response(
        String token,
        String userAgent,
        boolean success,
        String errorMessage
) {

    /**
     * Creates a successful response with a token.
     *
     * @param token     the gRecaptchaResponse token
     * @param userAgent the user agent string from the solving service, may be null
     * @return a successful response
     * @throws IllegalArgumentException if token is null or blank
     */
    public static ReCaptchaV3Response success(String token, String userAgent) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token cannot be null or blank for a successful response");
        }
        return new ReCaptchaV3Response(token, userAgent, true, null);
    }

    /**
     * Creates a failed response with an error message.
     *
     * @param errorMessage description of the failure
     * @return a failed response
     */
    public static ReCaptchaV3Response failure(String errorMessage) {
        return new ReCaptchaV3Response(null, null, false, errorMessage);
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
            return String.format("ReCaptchaV3Response{success=true, tokenLength=%d, hasUserAgent=%s}",
                    token != null ? token.length() : 0, hasUserAgent());
        }
        return String.format("ReCaptchaV3Response{success=false, error=%s}", errorMessage);
    }
}