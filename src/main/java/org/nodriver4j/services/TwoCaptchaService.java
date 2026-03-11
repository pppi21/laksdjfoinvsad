package org.nodriver4j.services;

import com.twocaptcha.TwoCaptcha;
import com.twocaptcha.captcha.FunCaptcha;
import org.nodriver4j.services.exceptions.TwoCaptchaException;

/**
 * Client for the 2Captcha captcha solving service.
 *
 * <p>This service wraps the official 2Captcha Java SDK to solve captcha challenges.
 * The SDK handles the createTask → poll → getResult cycle internally.</p>
 *
 * <h2>Supported Task Types</h2>
 * <ul>
 *   <li>Arkose Labs / FunCaptcha — via {@link #solveFunCaptcha}</li>
 * </ul>
 *
 * <h2>Adding New Task Types</h2>
 * <p>To add support for a new captcha type:</p>
 * <ol>
 *   <li>Create a convenience method that builds the SDK's captcha object</li>
 *   <li>Call {@code solver.solve(captcha)} and map the result</li>
 *   <li>Map SDK exceptions into {@link TwoCaptchaException}</li>
 * </ol>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * try (TwoCaptchaService service = new TwoCaptchaService(apiKey)) {
 *     ArkoseResponse response = service.solveFunCaptcha(
 *         "https://example.com",
 *         "30000F36-CADF-490C-929A-C6A7DD8B33C4",
 *         null, null, null
 *     );
 *
 *     if (response.hasToken()) {
 *         // inject token into page
 *     }
 * }
 * }</pre>
 *
 * @see ArkoseResponse
 * @see TwoCaptchaException
 */
public class TwoCaptchaService implements AutoCloseable {

    private static final int DEFAULT_TIMEOUT = 120;
    private static final int POLLING_INTERVAL = 5;

    private final TwoCaptcha solver;
    private final String apiKey;

    /**
     * Creates a new TwoCaptchaService with the specified API key.
     *
     * @param apiKey the 2Captcha API key
     * @throws IllegalArgumentException if apiKey is null or blank
     */
    public TwoCaptchaService(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }

        this.apiKey = apiKey;
        this.solver = new TwoCaptcha(apiKey);
        this.solver.setDefaultTimeout(DEFAULT_TIMEOUT);
        this.solver.setPollingInterval(POLLING_INTERVAL);

        System.out.println("[2Captcha] Service initialized");
    }

    // ==================== FunCaptcha (Arkose Labs) ====================

    /**
     * Solves an Arkose Labs (FunCaptcha) challenge.
     *
     * <p>Uses {@code FunCaptchaTask} when a proxy is provided, or
     * {@code FunCaptchaTaskProxyless} when proxy is null.</p>
     *
     * @param websiteUrl the URL of the page containing the Arkose challenge
     * @param publicKey  the Arkose public key (found in {@code data-pkey} or Arkose script URL)
     * @param subdomain  the Arkose API subdomain (e.g., "client-api.arkoselabs.com"), or null for default
     * @param dataBlob   the optional data blob as a JSON string (e.g., {@code "{\"blob\":\"...\"}" }), or null
     * @param proxy      proxy in "type:host:port:user:pass" format, or null for proxy-less
     * @return the solve response containing the Arkose session token
     * @throws TwoCaptchaException      if the solve fails
     * @throws IllegalArgumentException if websiteUrl or publicKey is null/blank
     */
    public ArkoseResponse solveFunCaptcha(String websiteUrl, String publicKey,
                                          String subdomain, String dataBlob,
                                          String proxy) throws TwoCaptchaException {
        if (websiteUrl == null || websiteUrl.isBlank()) {
            throw new IllegalArgumentException("Website URL cannot be null or blank");
        }
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalArgumentException("Public key cannot be null or blank");
        }

        System.out.println("[2Captcha] Solving FunCaptcha: publicKey=" + publicKey +
                ", url=" + websiteUrl + ", proxy=" + (proxy != null ? "yes" : "none"));

        FunCaptcha captcha = new FunCaptcha();
        captcha.setSiteKey(publicKey);
        captcha.setUrl(websiteUrl);

        if (subdomain != null && !subdomain.isBlank()) {
            captcha.setSUrl(subdomain);
        }

        if (dataBlob != null && !dataBlob.isBlank()) {
            captcha.setData("blob", dataBlob);
        }

        if (proxy != null && !proxy.isBlank()) {
            applyProxy(captcha, proxy);
        }

        try {
            long startTime = System.currentTimeMillis();
            solver.solve(captcha);
            long duration = System.currentTimeMillis() - startTime;

            String token = captcha.getCode();
            System.out.println("[2Captcha] FunCaptcha solved in " + duration + "ms, tokenLength=" +
                    (token != null ? token.length() : 0));

            if (token == null || token.isBlank()) {
                throw new TwoCaptchaException("Solve completed but no token returned");
            }

            return ArkoseResponse.success(token, null);

        } catch (com.twocaptcha.exceptions.ValidationException e) {
            throw new TwoCaptchaException("Invalid parameters: " + e.getMessage(), e);
        } catch (com.twocaptcha.exceptions.NetworkException e) {
            throw new TwoCaptchaException("Network error: " + e.getMessage(), e);
        } catch (com.twocaptcha.exceptions.ApiException e) {
            throw new TwoCaptchaException("API error: " + e.getMessage(), e);
        } catch (com.twocaptcha.exceptions.TimeoutException e) {
            throw new TwoCaptchaException("Solve timed out after " + DEFAULT_TIMEOUT + "s: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new TwoCaptchaException("Unexpected error: " + e.getMessage(), e);
        }
    }

    // ==================== Proxy Formatting ====================

    /**
     * Applies proxy settings to a FunCaptcha captcha object.
     *
     * <p>Expects the proxy in the format: {@code type:host:port:user:pass}
     * where type is "http", "socks4", or "socks5".</p>
     *
     * @param captcha the FunCaptcha captcha to configure
     * @param proxy   the proxy string
     */
    private void applyProxy(FunCaptcha captcha, String proxy) {
        String[] parts = proxy.split(":", 5);
        if (parts.length < 3) {
            System.err.println("[2Captcha] Invalid proxy format, expected type:host:port[:user:pass]: " + proxy);
            return;
        }

        String type = parts[0].toUpperCase();
        String hostPort = parts[1] + ":" + parts[2];

        if (parts.length >= 5) {
            String credentials = parts[3] + ":" + parts[4];
            captcha.setProxy(type, credentials + "@" + hostPort);
        } else {
            captcha.setProxy(type, hostPort);
        }
    }

    /**
     * Formats proxy components into the format expected by this service.
     *
     * <p>Produces the format: {@code scheme:host:port:username:password}</p>
     *
     * @param scheme   the proxy scheme (e.g., "http", "socks5")
     * @param host     the proxy hostname or IP
     * @param port     the proxy port
     * @param username the proxy username, may be null
     * @param password the proxy password, may be null
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
     * Closes the service.
     *
     * <p>The 2Captcha SDK does not hold persistent connections,
     * so this is a no-op included for consistency with the
     * {@link AutoCloseable} contract used by other services.</p>
     */
    @Override
    public void close() {
        System.out.println("[2Captcha] Service closed");
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
        return "TwoCaptchaService{apiKey=" + maskedApiKey() + "}";
    }
}