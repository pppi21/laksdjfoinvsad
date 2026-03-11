package org.nodriver4j.captcha;

import org.nodriver4j.core.Page;
import org.nodriver4j.persistence.Settings;
import org.nodriver4j.services.ArkoseResponse;
import org.nodriver4j.services.TwoCaptchaService;
import org.nodriver4j.services.exceptions.TwoCaptchaException;

import java.util.concurrent.TimeoutException;

/**
 * Static utility class for solving Arkose Labs (FunCaptcha) challenges.
 *
 * <p>This solver orchestrates the complete Arkose solving flow:</p>
 * <ol>
 *   <li>Extract the Arkose public key from the page</li>
 *   <li>Optionally extract the data blob and API subdomain</li>
 *   <li>Resolve proxy configuration from the browser</li>
 *   <li>Send to 2Captcha for token generation</li>
 *   <li>Inject the token into the page by invoking the enforcement callback</li>
 * </ol>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Auto-extract public key from page
 * ArkoseSolver.SolveResult result = ArkoseSolver.solve(page);
 *
 * // With explicit public key
 * ArkoseSolver.SolveResult result = ArkoseSolver.solve(page, "30000F36-CADF-490C-929A-C6A7DD8B33C4");
 *
 * if (result.success()) {
 *     System.out.println("Token injected: " + result.injectedIntoPage());
 *     // Proceed — the enforcement callback has been invoked
 * }
 * }</pre>
 *
 * <h2>Arkose Integration Patterns</h2>
 * <p>Sites integrate Arkose in various ways. This solver handles the most common patterns:</p>
 * <ul>
 *   <li>The enforcement instance stored on {@code window.__arkoseInstance}</li>
 *   <li>Hidden inputs with names like {@code fc-token} or {@code verification-token}</li>
 *   <li>Callbacks registered in the enforcement config's {@code onCompleted}</li>
 * </ul>
 *
 * @see TwoCaptchaService
 * @see ArkoseResponse
 */
public final class ArkoseSolver {

    // ==================== Public Key Extraction ====================

    /**
     * JavaScript to extract the Arkose public key from the page.
     *
     * <p>Searches in the following locations (in order):</p>
     * <ol>
     *   <li>Elements with {@code data-pkey} attribute</li>
     *   <li>Arkose script URL containing the public key in the path</li>
     *   <li>Hidden input with {@code fc-token} or {@code verification-token} name/id</li>
     *   <li>{@code window.__arkosePublicKey} if set by the page</li>
     * </ol>
     */
    private static final String EXTRACT_PUBLIC_KEY_SCRIPT = """
            (function() {
                // Strategy 1: data-pkey attribute
                var el = document.querySelector('[data-pkey]');
                if (el) return el.getAttribute('data-pkey');
                // Strategy 2: Arkose script URL — /v2/PUBLIC_KEY/api.js
                var scripts = document.querySelectorAll('script[src*="arkoselabs.com"]');
                for (var i = 0; i < scripts.length; i++) {
                    var match = scripts[i].src.match(/\\/v2\\/([A-F0-9-]{36})\\/api\\.js/i);
                    if (match) return match[1];
                }
                // Strategy 3: fc-token or verification-token hidden input
                var tokenEl = document.querySelector('#fc-token, #verification-token, input[name="fc-token"], input[name="verification-token"]');
                if (tokenEl && tokenEl.value) {
                    var pkMatch = tokenEl.value.match(/pk\\|([A-F0-9-]{36})/i);
                    if (pkMatch) return pkMatch[1];
                }
                // Strategy 4: window.__arkosePublicKey
                if (typeof window.__arkosePublicKey !== 'undefined') return window.__arkosePublicKey;
                return null;
            })()
            """;

    // ==================== Subdomain Extraction ====================

    /**
     * JavaScript to extract the Arkose API subdomain from the page.
     */
    private static final String EXTRACT_SUBDOMAIN_SCRIPT = """
            (function() {
                var scripts = document.querySelectorAll('script[src*="arkoselabs.com"]');
                for (var i = 0; i < scripts.length; i++) {
                    var match = scripts[i].src.match(/https?:\\/\\/([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.arkoselabs\\.com)/i);
                    if (match) return match[1];
                }
                return null;
            })()
            """;

    // ==================== Token Injection ====================

    /**
     * JavaScript template to inject the Arkose token into the page.
     *
     * <p>Performs multiple injection strategies:</p>
     * <ol>
     *   <li>Sets the token on hidden inputs ({@code fc-token}, {@code verification-token})</li>
     *   <li>Invokes the enforcement's {@code onCompleted} callback via {@code window.__arkoseInstance}</li>
     *   <li>Dispatches a custom event for frameworks that listen for Arkose completion</li>
     * </ol>
     *
     * <p>Placeholders: %s = token</p>
     */
    private static final String INJECT_TOKEN_SCRIPT = """
            (function() {
                var token = '%s';
                var injected = false;
                // Strategy 1: Set hidden input values
                var inputs = document.querySelectorAll('#fc-token, #verification-token, input[name="fc-token"], input[name="verification-token"], input[name="arkoseToken"]');
                for (var i = 0; i < inputs.length; i++) {
                    inputs[i].value = token;
                    injected = true;
                }
                // Strategy 2: Invoke the enforcement onCompleted callback
                if (typeof window.__arkoseInstance !== 'undefined' && window.__arkoseInstance) {
                    try {
                        // The enforcement config stores callbacks internally.
                        // Triggering setConfig with onCompleted should invoke the site's handler.
                        if (typeof window.__arkoseEnforcementCallback === 'function') {
                            window.__arkoseEnforcementCallback({token: token});
                            injected = true;
                        }
                    } catch(e) {}
                }
                // Strategy 3: Dispatch a custom event that frameworks may listen for
                try {
                    window.dispatchEvent(new CustomEvent('arkose-complete', {detail: {token: token}}));
                } catch(e) {}
                return injected ? 'true' : 'false';
            })()
            """;

    /**
     * JavaScript injected before navigation to hook the Arkose enforcement setup.
     *
     * <p>This intercepts the {@code setupEnforcement} callback to capture the
     * {@code onCompleted} function reference, which we later invoke with our token.</p>
     */
    private static final String HOOK_ENFORCEMENT_SCRIPT = """
            (function() {
                var originalSetup = window.setupEnforcement;
                window.setupEnforcement = function(enforcement) {
                    window.__arkoseInstance = enforcement;
                    var originalSetConfig = enforcement.setConfig.bind(enforcement);
                    enforcement.setConfig = function(config) {
                        if (config && typeof config.onCompleted === 'function') {
                            window.__arkoseEnforcementCallback = config.onCompleted;
                        }
                        return originalSetConfig(config);
                    };
                    if (typeof originalSetup === 'function') {
                        originalSetup(enforcement);
                    }
                };
            })()
            """;

    // ==================== Private Constructor ====================

    private ArkoseSolver() {
        // Static utility class - prevent instantiation
    }

    // ==================== Public API ====================

    /**
     * Solves an Arkose Labs challenge by extracting the public key from the page.
     *
     * @param page the Page containing the Arkose challenge
     * @return the solve result containing the token
     * @throws IllegalArgumentException if page is null
     */
    public static SolveResult solve(Page page) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }

        System.out.println("[ArkoseSolver] Extracting public key from page...");

        String publicKey;
        try {
            publicKey = extractPublicKey(page);
        } catch (TimeoutException e) {
            return SolveResult.failure("Failed to extract public key: " + e.getMessage());
        }

        if (publicKey == null || publicKey.isBlank()) {
            return SolveResult.failure("Could not find Arkose public key on the page");
        }

        System.out.println("[ArkoseSolver] Found public key: " + publicKey);

        return solve(page, publicKey);
    }

    /**
     * Solves an Arkose Labs challenge with an explicit public key.
     *
     * <p>Use this overload when the public key is already known, avoiding
     * the automatic extraction step.</p>
     *
     * @param page      the Page containing the Arkose challenge
     * @param publicKey the Arkose public key
     * @return the solve result containing the token
     * @throws IllegalArgumentException if any parameter is null/blank
     */
    public static SolveResult solve(Page page, String publicKey) {
        return solve(page, publicKey, null);
    }

    /**
     * Solves an Arkose Labs challenge with an explicit public key and optional data blob.
     *
     * @param page      the Page containing the Arkose challenge
     * @param publicKey the Arkose public key
     * @param dataBlob  the optional data blob value, or null
     * @return the solve result containing the token
     * @throws IllegalArgumentException if page or publicKey is null/blank
     */
    public static SolveResult solve(Page page, String publicKey, String dataBlob) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalArgumentException("Public key cannot be null or blank");
        }

        String apiKey = Settings.get().twoCaptchaApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return SolveResult.failure("2Captcha API key is not configured in Settings");
        }

        String websiteUrl = currentUrl(page);
        if (websiteUrl == null) {
            return SolveResult.failure("Could not determine current page URL");
        }

        String subdomain = extractSubdomain(page);
        String proxy = resolveProxy(page);

        System.out.println("[ArkoseSolver] Solving Arkose challenge: publicKey=" + publicKey +
                ", subdomain=" + (subdomain != null ? subdomain : "default") +
                ", blob=" + (dataBlob != null ? "yes" : "none") +
                ", proxy=" + (proxy != null ? "yes" : "none"));

        try (TwoCaptchaService service = new TwoCaptchaService(apiKey)) {
            ArkoseResponse response = service.solveFunCaptcha(websiteUrl, publicKey, subdomain, dataBlob, proxy);

            if (!response.hasToken()) {
                return SolveResult.failure("2Captcha returned a response without a token");
            }

            System.out.println("[ArkoseSolver] Token received, length=" + response.token().length());

            // Inject the token into the page
            boolean injected = injectToken(page, response.token());

            if (injected) {
                System.out.println("[ArkoseSolver] \u2713 Token injected into page");
            } else {
                System.out.println("[ArkoseSolver] Token obtained but page injection may not have succeeded " +
                        "(caller should use token from SolveResult directly)");
            }

            return SolveResult.success(response.token(), injected);

        } catch (TwoCaptchaException e) {
            System.err.println("[ArkoseSolver] \u2717 Solve failed: " + e.getMessage());
            return SolveResult.failure("2Captcha error: " + e.getMessage());
        }
    }

    /**
     * Injects the enforcement hook script into the page.
     *
     * <p>Call this <strong>before</strong> the page navigates to the Arkose-protected page.
     * The hook intercepts the {@code setupEnforcement} callback to capture the
     * {@code onCompleted} function reference for later token injection.</p>
     *
     * <p>This is optional — the solver will attempt injection via other strategies
     * if the hook is not installed. However, for sites like Uber where the enforcement
     * callback is the primary token delivery mechanism, installing the hook is recommended.</p>
     *
     * @param page the Page to hook
     */
    public static void installEnforcementHook(Page page) {
        try {
            page.evaluate(HOOK_ENFORCEMENT_SCRIPT);
            System.out.println("[ArkoseSolver] Enforcement hook installed");
        } catch (TimeoutException e) {
            System.err.println("[ArkoseSolver] Failed to install enforcement hook: " + e.getMessage());
        }
    }

    /**
     * Checks if Arkose Labs is present on the page.
     *
     * <p>Looks for the Arkose script tag, enforcement instance, or challenge elements.</p>
     *
     * @param page the Page to check
     * @return true if Arkose indicators are found
     */
    public static boolean isPresent(Page page) {
        try {
            String script = """
                    (function() {
                        if (document.querySelector('script[src*="arkoselabs.com"]')) return 'true';
                        if (typeof window.__arkoseInstance !== 'undefined') return 'true';
                        if (document.querySelector('#arkose-challenge')) return 'true';
                        if (document.querySelector('[data-pkey]')) return 'true';
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
     * Extracts the Arkose public key from the page via JavaScript evaluation.
     *
     * @param page the Page to extract from
     * @return the public key, or null if not found
     * @throws TimeoutException if the script evaluation fails
     */
    private static String extractPublicKey(Page page) throws TimeoutException {
        String result = page.evaluate(EXTRACT_PUBLIC_KEY_SCRIPT);
        if (result == null || result.isBlank() || "null".equals(result)) {
            return null;
        }
        return result.trim();
    }

    /**
     * Extracts the Arkose API subdomain from the page.
     *
     * @param page the Page to extract from
     * @return the subdomain, or null if not found or default
     */
    private static String extractSubdomain(Page page) {
        try {
            String result = page.evaluate(EXTRACT_SUBDOMAIN_SCRIPT);
            if (result == null || result.isBlank() || "null".equals(result)) {
                return null;
            }
            // Only return if it's non-default
            String subdomain = result.trim();
            if ("client-api.arkoselabs.com".equals(subdomain)) {
                return null;
            }
            return subdomain;
        } catch (TimeoutException e) {
            return null;
        }
    }

    /**
     * Injects the Arkose token into the page.
     *
     * @param page  the Page to inject into
     * @param token the Arkose session token to inject
     * @return true if the token was injected via at least one strategy
     */
    private static boolean injectToken(Page page, String token) {
        try {
            // Escape single quotes in token to prevent JS injection
            String safeToken = token.replace("\\", "\\\\").replace("'", "\\'");
            String script = String.format(INJECT_TOKEN_SCRIPT, safeToken);
            String result = page.evaluate(script);
            return "true".equals(result);
        } catch (TimeoutException e) {
            System.err.println("[ArkoseSolver] Token injection failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Resolves the proxy configuration from the browser into the format
     * expected by {@link TwoCaptchaService}.
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
            String port = proxyConfig.port();

            if (host == null || host.isBlank() || port == null || port.isBlank()) {
                return null;
            }

            return TwoCaptchaService.formatProxy(
                    "http",
                    host,
                    port,
                    proxyConfig.username(),
                    proxyConfig.password()
            );
        } catch (Exception e) {
            System.err.println("[ArkoseSolver] Could not resolve proxy: " + e.getMessage());
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
            System.err.println("[ArkoseSolver] Could not get current URL: " + e.getMessage());
            return null;
        }
    }

    // ==================== Result Types ====================

    /**
     * Result of an Arkose Labs solve attempt.
     *
     * @param success          whether the token was successfully obtained
     * @param failureReason    reason for failure (null if success)
     * @param token            the Arkose session token (null if failed)
     * @param injectedIntoPage whether the token was injected into the page
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
         * <p>Even if injection into the page failed, the token
         * itself may still be valid for use via custom injection logic.</p>
         *
         * @return true if a non-blank token is present
         */
        public boolean hasToken() {
            return token != null && !token.isBlank();
        }
    }
}
