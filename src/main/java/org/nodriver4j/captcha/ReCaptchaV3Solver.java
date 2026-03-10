package org.nodriver4j.captcha;

import org.nodriver4j.core.Page;
import org.nodriver4j.persistence.Settings;
import org.nodriver4j.services.CapSolverService;
import org.nodriver4j.services.ReCaptchaV3Response;
import org.nodriver4j.services.exceptions.CapSolverException;

import java.util.concurrent.TimeoutException;

/**
 * Static utility class for solving reCAPTCHA v3 challenges.
 *
 * <p>This solver orchestrates the complete reCAPTCHA v3 flow:</p>
 * <ol>
 *   <li>Extract the site key from the page</li>
 *   <li>Resolve proxy configuration from the browser</li>
 *   <li>Send to CapSolver for token generation</li>
 *   <li>Inject the token into the page's {@code g-recaptcha-response} textarea</li>
 *   <li>Attempt to invoke the reCAPTCHA callback if present</li>
 * </ol>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Basic usage with action string
 * ReCaptchaV3Solver.SolveResult result = ReCaptchaV3Solver.solve(page, "login");
 *
 * if (result.success()) {
 *     System.out.println("Token injected, length: " + result.token().length());
 *     // Proceed to submit the form
 * } else {
 *     System.out.println("Failed: " + result.failureReason());
 * }
 *
 * // With explicit site key (skips extraction)
 * ReCaptchaV3Solver.SolveResult result = ReCaptchaV3Solver.solve(page, "login", "6Lc...");
 * }</pre>
 *
 * <h2>reCAPTCHA v3 Structure</h2>
 * <p>Unlike v2, reCAPTCHA v3 is invisible. It loads via a script tag:</p>
 * <pre>{@code <script src="https://www.google.com/recaptcha/api.js?render=SITE_KEY">}</pre>
 * <p>Sites invoke it with:</p>
 * <pre>{@code grecaptcha.execute('SITE_KEY', {action: 'login'})}</pre>
 * <p>The token is typically placed in a hidden textarea with id {@code g-recaptcha-response}
 * or consumed via a JavaScript callback.</p>
 *
 * @see CapSolverService
 * @see ReCaptchaV3Response
 */
public final class ReCaptchaV3Solver {

    // ==================== Sitekey Extraction ====================

    /**
     * JavaScript to extract the reCAPTCHA v3 site key from the page.
     *
     * <p>Searches for the key in the following locations (in order):</p>
     * <ol>
     *   <li>Script tags with {@code src} containing {@code render=SITEKEY}</li>
     *   <li>The {@code ___grecaptcha_cfg.clients} config object</li>
     * </ol>
     */
    private static final String EXTRACT_SITEKEY_SCRIPT = """
            (function() {
                // Strategy 1: script src with render= parameter
                var scripts = document.querySelectorAll('script[src*="recaptcha"]');
                for (var i = 0; i < scripts.length; i++) {
                    var src = scripts[i].getAttribute('src');
                    if (src) {
                        var match = src.match(/[?&]render=([A-Za-z0-9_-]+)/);
                        if (match && match[1] !== 'explicit') {
                            return match[1];
                        }
                    }
                }
                // Strategy 2: grecaptcha config clients
                if (typeof ___grecaptcha_cfg !== 'undefined' && ___grecaptcha_cfg.clients) {
                    var clients = ___grecaptcha_cfg.clients;
                    for (var key in clients) {
                        if (clients.hasOwnProperty(key)) {
                            var client = clients[key];
                            // Walk the client object looking for sitekey
                            var queue = [client];
                            while (queue.length > 0) {
                                var obj = queue.shift();
                                if (obj && typeof obj === 'object') {
                                    if (obj.sitekey) return obj.sitekey;
                                    for (var k in obj) {
                                        if (obj.hasOwnProperty(k) && typeof obj[k] === 'object' && obj[k] !== null) {
                                            queue.push(obj[k]);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return null;
            })()
            """;

    // ==================== Token Injection ====================

    /**
     * JavaScript template to inject the token into the page.
     *
     * <p>Sets the value on the {@code g-recaptcha-response} textarea and
     * attempts to invoke the reCAPTCHA callback if one is registered.</p>
     *
     * <p>Placeholders: %s = token</p>
     */
    private static final String INJECT_TOKEN_SCRIPT = """
            (function() {
                var token = '%s';
                var injected = false;
                // Inject into all g-recaptcha-response textareas (some pages have multiple)
                var textareas = document.querySelectorAll('textarea[id*="g-recaptcha-response"]');
                for (var i = 0; i < textareas.length; i++) {
                    textareas[i].value = token;
                    textareas[i].innerHTML = token;
                    injected = true;
                }
                // Attempt to invoke the callback
                if (typeof ___grecaptcha_cfg !== 'undefined' && ___grecaptcha_cfg.clients) {
                    var clients = ___grecaptcha_cfg.clients;
                    for (var key in clients) {
                        if (clients.hasOwnProperty(key)) {
                            var queue = [clients[key]];
                            while (queue.length > 0) {
                                var obj = queue.shift();
                                if (obj && typeof obj === 'object') {
                                    if (typeof obj.callback === 'function') {
                                        try { obj.callback(token); } catch(e) {}
                                    }
                                    for (var k in obj) {
                                        if (obj.hasOwnProperty(k) && typeof obj[k] === 'object' && obj[k] !== null) {
                                            queue.push(obj[k]);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return injected ? 'true' : 'false';
            })()
            """;

    // ==================== Private Constructor ====================

    private ReCaptchaV3Solver() {
        // Static utility class - prevent instantiation
    }

    // ==================== Public API ====================

    /**
     * Solves a reCAPTCHA v3 challenge by extracting the site key from the page.
     *
     * @param page   the Page containing the reCAPTCHA v3
     * @param action the action string (e.g., "login", "submit", "homepage")
     * @return the solve result containing the token
     * @throws IllegalArgumentException if page or action is null
     */
    public static SolveResult solve(Page page, String action) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("Action cannot be null or blank");
        }

        System.out.println("[ReCaptchaV3Solver] Extracting site key from page...");

        String siteKey;
        try {
            siteKey = extractSiteKey(page);
        } catch (TimeoutException e) {
            return SolveResult.failure("Failed to extract site key: " + e.getMessage());
        }

        if (siteKey == null || siteKey.isBlank()) {
            return SolveResult.failure("Could not find reCAPTCHA v3 site key on the page");
        }

        System.out.println("[ReCaptchaV3Solver] Found site key: " + siteKey);

        return solve(page, action, siteKey);
    }

    /**
     * Solves a reCAPTCHA v3 challenge with an explicit site key.
     *
     * <p>Use this overload when the site key is already known, avoiding
     * the automatic extraction step.</p>
     *
     * @param page    the Page containing the reCAPTCHA v3
     * @param action  the action string (e.g., "login", "submit", "homepage")
     * @param siteKey the reCAPTCHA site key
     * @return the solve result containing the token
     * @throws IllegalArgumentException if any parameter is null/blank
     */
    public static SolveResult solve(Page page, String action, String siteKey) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("Action cannot be null or blank");
        }
        if (siteKey == null || siteKey.isBlank()) {
            throw new IllegalArgumentException("Site key cannot be null or blank");
        }

        String apiKey = Settings.get().capsolverApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return SolveResult.failure("CapSolver API key is not configured in Settings");
        }

        String websiteUrl = currentUrl(page);
        if (websiteUrl == null) {
            return SolveResult.failure("Could not determine current page URL");
        }

        String proxy = resolveProxy(page);

        System.out.println("[ReCaptchaV3Solver] Solving reCAPTCHA v3: action=" + action +
                ", siteKey=" + siteKey + ", proxy=" + (proxy != null ? "yes" : "none"));

        try (CapSolverService service = new CapSolverService(apiKey)) {
            ReCaptchaV3Response response = service.solveReCaptchaV3(websiteUrl, siteKey, action, proxy);

            if (!response.hasToken()) {
                return SolveResult.failure("CapSolver returned a response without a token");
            }

            System.out.println("[ReCaptchaV3Solver] Token received, length=" + response.token().length());

            // Inject the token into the page
            boolean injected = injectToken(page, response.token());

            if (injected) {
                System.out.println("[ReCaptchaV3Solver] ✓ Token injected into page");
            } else {
                System.out.println("[ReCaptchaV3Solver] Token obtained but injection into textarea failed " +
                        "(site may use callback-only approach)");
            }

            return SolveResult.success(response.token(), injected);

        } catch (CapSolverException e) {
            System.err.println("[ReCaptchaV3Solver] ✗ Solve failed: " + e.getMessage());
            return SolveResult.failure("CapSolver error: " + e.getMessage());
        }
    }

    /**
     * Checks if a reCAPTCHA v3 is present on the page.
     *
     * <p>Looks for the reCAPTCHA v3 script tag or the {@code grecaptcha} global object.</p>
     *
     * @param page the Page to check
     * @return true if reCAPTCHA v3 indicators are found
     */
    public static boolean isPresent(Page page) {
        try {
            String script = """
                    (function() {
                        var scripts = document.querySelectorAll('script[src*="recaptcha"][src*="render="]');
                        if (scripts.length > 0) return 'true';
                        if (typeof grecaptcha !== 'undefined' && typeof grecaptcha.execute === 'function') return 'true';
                        return 'false';
                    })()
                    """;
            String result = page.evaluate(script);
            return "true".equals(result);
        } catch (TimeoutException e) {
            return false;
        }
    }

    // ==================== Internal Methods ====================

    /**
     * Extracts the reCAPTCHA v3 site key from the page via JavaScript evaluation.
     *
     * @param page the Page to extract from
     * @return the site key, or null if not found
     * @throws TimeoutException if the script evaluation fails
     */
    private static String extractSiteKey(Page page) throws TimeoutException {
        String result = page.evaluate(EXTRACT_SITEKEY_SCRIPT);
        if (result == null || result.isBlank() || "null".equals(result)) {
            return null;
        }
        return result.trim();
    }

    /**
     * Injects the reCAPTCHA token into the page.
     *
     * @param page  the Page to inject into
     * @param token the token to inject
     * @return true if the token was injected into at least one textarea
     */
    private static boolean injectToken(Page page, String token) {
        try {
            // Escape single quotes in token to prevent JS injection
            String safeToken = token.replace("'", "\\'");
            String script = String.format(INJECT_TOKEN_SCRIPT, safeToken);
            String result = page.evaluate(script);
            return "true".equals(result);
        } catch (TimeoutException e) {
            System.err.println("[ReCaptchaV3Solver] Token injection failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Resolves the proxy configuration from the browser into CapSolver format.
     *
     * @param page the Page whose browser proxy to resolve
     * @return the formatted proxy string, or null if no proxy is configured
     */
    private static String resolveProxy(Page page) {
        try {
            var proxyConfig = page.browser().config().proxyConfig();
            if (proxyConfig == null) {
                return null;
            }

            String host = proxyConfig.host();
            String port = String.valueOf(proxyConfig.port());

            if (host == null || host.isBlank() || port.isBlank()) {
                return null;
            }

            return CapSolverService.formatProxy(
                    "http",
                    host,
                    port,
                    proxyConfig.username(),
                    proxyConfig.password()
            );
        } catch (Exception e) {
            System.err.println("[ReCaptchaV3Solver] Could not resolve proxy: " + e.getMessage());
            return null;
        }
    }

    /**
     * Gets the current page URL safely.
     *
     * @param page the Page
     * @return the current URL, or null if it cannot be determined
     */
    private static String currentUrl(Page page) {
        try {
            return page.currentUrl();
        } catch (TimeoutException e) {
            System.err.println("[ReCaptchaV3Solver] Could not get current URL: " + e.getMessage());
            return null;
        }
    }

    // ==================== Result Types ====================

    /**
     * Result of a reCAPTCHA v3 solve attempt.
     *
     * @param success          whether the token was successfully obtained
     * @param failureReason    reason for failure (null if success)
     * @param token            the reCAPTCHA token (null if failed)
     * @param injectedIntoPage whether the token was injected into the page's textarea
     */
    public record SolveResult(
            boolean success,
            String failureReason,
            String token,
            boolean injectedIntoPage
    ) {

        static SolveResult success(String token, boolean injected) {
            return new SolveResult(true, null, token, injected);
        }

        static SolveResult failure(String reason) {
            return new SolveResult(false, reason, null, false);
        }

        /**
         * Checks whether the token is available for manual use.
         *
         * <p>Even if injection into the page textarea failed, the token
         * itself may still be valid for use via custom injection logic.</p>
         *
         * @return true if a non-blank token is present
         */
        public boolean hasToken() {
            return token != null && !token.isBlank();
        }
    }
}