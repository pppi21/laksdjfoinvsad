package org.nodriver4j.services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.nodriver4j.core.Proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Stateless utility that diagnoses a proxy connection before browser launch.
 *
 * <p>Performs two operations without a browser:</p>
 * <ol>
 *   <li>Detects the proxy's real exit IP by routing an HTTP request through it</li>
 *   <li>Queries a geolocation provider for the exit IP's IANA timezone and coordinates</li>
 * </ol>
 *
 * <p>Results are used by {@link TaskExecutionService} to apply
 * {@code --fingerprint-timezone} and {@code --fingerprint-geolocation}
 * Chrome switches, keeping the browser's reported location consistent
 * with the proxy's actual exit region.</p>
 *
 * <p>Primary geolocation provider: ip-api.com (no key, 45 req/min per machine IP)</p>
 * <p>Fallback provider: ipwhois.io (no key, 1 req/sec)</p>
 *
 * <p>All methods are static. This class should not be instantiated.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProxyDiagnosticResult result = ProxyDiagnosticService.diagnose(proxy);
 * if (result.success()) {
 *     // apply result.timezone() and result.toGeolocationArg() as Chrome switches
 * }
 * }</pre>
 */
public class ProxyDiagnosticService {

    private static final String IPIFY_URL = "https://api.ipify.org?format=json";
    private static final String IP_API_URL =
            "http://ip-api.com/json/%s?fields=status,message,lat,lon,timezone,query";
    private static final String IPWHOIS_URL = "https://ipwho.is/%s";

    private static final int TIMEOUT_SECONDS = 10;

    /**
     * Shared direct (non-proxied) HTTP client for geolocation API calls.
     * Geolocation providers are queried by IP address — the request does not
     * need to route through the proxy.
     */
    private static final OkHttpClient DIRECT_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

    private ProxyDiagnosticService() {}

    // ==================== Public API ====================

    /**
     * Diagnoses a proxy: detects its exit IP, validates it, then resolves
     * the IANA timezone and geolocation for that IP.
     *
     * <p>If the primary geolocation provider (ip-api.com) fails or is rate limited,
     * the call automatically falls back to ipwhois.io. If both fail, a failed
     * {@link ProxyDiagnosticResult} is returned — the browser will still launch,
     * just without timezone or geolocation overrides.</p>
     *
     * <p>This method is safe to call concurrently from multiple threads.</p>
     *
     * @param proxy the proxy to diagnose
     * @return a {@link ProxyDiagnosticResult} — always non-null, check {@code success()} before use
     */
    public static ProxyDiagnosticResult diagnose(Proxy proxy) {
        OkHttpClient proxiedClient = buildProxiedClient(proxy);

        // Step 1: Detect exit IP through the proxy
        String exitIp;
        try {
            exitIp = detectExitIp(proxiedClient);
        } catch (IOException e) {
            return ProxyDiagnosticResult.failure("Failed to detect exit IP: " + e.getMessage());
        }

        // Step 2: Validate it is a real public address
        if (!isPublicIp(exitIp)) {
            return ProxyDiagnosticResult.failure(
                    "Exit IP is in a reserved range: " + exitIp +
                            ". Proxy may not be routing traffic correctly.");
        }

        System.out.println("[ProxyDiagnosticService] Exit IP detected: " + exitIp);

        // Step 3: Query primary geolocation provider
        ProxyDiagnosticResult result = queryIpApi(exitIp);

        // Step 4: Fall back to secondary provider if primary failed
        if (!result.success()) {
            System.out.println("[ProxyDiagnosticService] ip-api.com failed (" +
                    result.failureReason() + "), falling back to ipwhois.io");
            result = queryIpWhois(exitIp);
        }

        if (result.success()) {
            System.out.println("[ProxyDiagnosticService] Diagnostic complete: " + result);
        } else {
            System.err.println("[ProxyDiagnosticService] All providers failed for IP " +
                    exitIp + ": " + result.failureReason());
        }

        return result;
    }

    // ==================== Exit IP Detection ====================

    /**
     * Detects the proxy's exit IP by hitting ipify through the proxied client.
     *
     * @param proxiedClient OkHttpClient configured to route through the proxy
     * @return the detected public IP address string
     * @throws IOException if the request fails or returns an unexpected response
     */
    private static String detectExitIp(OkHttpClient proxiedClient) throws IOException {
        Request request = new Request.Builder()
                .url(IPIFY_URL)
                .build();

        try (Response response = proxiedClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Unexpected response from ipify: HTTP " + response.code());
            }
            JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();
            return json.get("ip").getAsString().trim();
        }
    }

    // ==================== Geolocation Providers ====================

    /**
     * Queries ip-api.com for timezone and coordinates.
     *
     * <p>Returns a failed result (not an exception) on HTTP errors, rate limiting,
     * or API-level failures so the caller can fall back cleanly.</p>
     *
     * @param exitIp the public IP to look up
     * @return a ProxyDiagnosticResult — check {@code success()} before use
     */
    private static ProxyDiagnosticResult queryIpApi(String exitIp) {
        String url = String.format(IP_API_URL, exitIp);
        Request request = new Request.Builder().url(url).build();

        try (Response response = DIRECT_CLIENT.newCall(request).execute()) {
            if (response.code() == 429) {
                return ProxyDiagnosticResult.failure("ip-api.com rate limited (HTTP 429)");
            }
            if (!response.isSuccessful() || response.body() == null) {
                return ProxyDiagnosticResult.failure(
                        "ip-api.com returned HTTP " + response.code());
            }

            JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();

            if (!"success".equals(json.get("status").getAsString())) {
                String message = json.has("message")
                        ? json.get("message").getAsString() : "unknown";
                return ProxyDiagnosticResult.failure("ip-api.com status failed: " + message);
            }

            double lat = json.get("lat").getAsDouble();
            double lon = json.get("lon").getAsDouble();
            String timezone = json.get("timezone").getAsString();
            double accuracy = calculateAccuracy(lat, lon);

            return ProxyDiagnosticResult.success(exitIp, timezone, lat, lon, accuracy);

        } catch (IOException e) {
            return ProxyDiagnosticResult.failure(
                    "ip-api.com request failed: " + e.getMessage());
        }
    }

    /**
     * Queries ipwhois.io for timezone and coordinates.
     *
     * <p>Used as fallback when ip-api.com is unavailable or rate limited.</p>
     *
     * @param exitIp the public IP to look up
     * @return a ProxyDiagnosticResult — check {@code success()} before use
     */
    private static ProxyDiagnosticResult queryIpWhois(String exitIp) {
        String url = String.format(IPWHOIS_URL, exitIp);
        Request request = new Request.Builder().url(url).build();

        try (Response response = DIRECT_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return ProxyDiagnosticResult.failure(
                        "ipwhois.io returned HTTP " + response.code());
            }

            JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();

            if (!json.get("success").getAsBoolean()) {
                String message = json.has("message")
                        ? json.get("message").getAsString() : "unknown";
                return ProxyDiagnosticResult.failure("ipwhois.io query failed: " + message);
            }

            double lat = json.get("latitude").getAsDouble();
            double lon = json.get("longitude").getAsDouble();
            String timezone = json.getAsJsonObject("timezone").get("id").getAsString();
            double accuracy = calculateAccuracy(lat, lon);

            return ProxyDiagnosticResult.success(exitIp, timezone, lat, lon, accuracy);

        } catch (IOException e) {
            return ProxyDiagnosticResult.failure(
                    "ipwhois.io request failed: " + e.getMessage());
        }
    }

    // ==================== HTTP Client ====================

    /**
     * Builds an OkHttpClient that routes requests through the given proxy.
     *
     * <p>A new client is created per diagnostic call since each proxy has
     * different credentials. The client is short-lived and used for the
     * single ipify request only.</p>
     *
     * @param proxy the proxy to configure
     * @return a configured OkHttpClient
     */
    private static OkHttpClient buildProxiedClient(Proxy proxy) {
        java.net.Proxy okProxy = new java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                new InetSocketAddress(proxy.host(), proxy.port())
        );

        Authenticator proxyAuthenticator = (route, response) -> {
            // Prevent infinite auth retry loops
            if (response.request().header("Proxy-Authorization") != null) {
                return null;
            }
            String credential = Credentials.basic(proxy.username(), proxy.password());
            return response.request().newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build();
        };

        return new OkHttpClient.Builder()
                .proxy(okProxy)
                .proxyAuthenticator(proxyAuthenticator)
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    // ==================== Validation & Utilities ====================

    /**
     * Returns true if the IP address is a routable public address.
     *
     * <p>Rejects loopback, private (RFC 1918), link-local, multicast,
     * unspecified, broadcast/Class E (240+), and CGNAT (100.64.0.0/10) ranges.</p>
     *
     * @param ipStr the IP address string to validate
     * @return true if the IP is a valid public address
     */
    private static boolean isPublicIp(String ipStr) {
        try {
            InetAddress addr = InetAddress.getByName(ipStr);

            if (addr.isLoopbackAddress()
                    || addr.isSiteLocalAddress()    // 10.x, 172.16-31.x, 192.168.x
                    || addr.isLinkLocalAddress()    // 169.254.x
                    || addr.isMulticastAddress()    // 224.x - 239.x
                    || addr.isAnyLocalAddress()) {  // 0.0.0.0
                return false;
            }

            // Additional ranges not covered by InetAddress methods
            byte[] bytes = addr.getAddress();
            if (bytes.length == 4) {
                int first = bytes[0] & 0xFF;
                int second = bytes[1] & 0xFF;

                // Class E + broadcast: 240.0.0.0 - 255.255.255.255
                if (first >= 240) return false;

                // CGNAT shared address space: 100.64.0.0/10
                if (first == 100 && second >= 64 && second <= 127) return false;
            }

            return true;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Calculates geolocation accuracy in meters from coordinate decimal precision.
     *
     * <p>Uses the same formula as Camoufox:</p>
     * <pre>accuracy = (111320 * cos(lat * π / 180)) / 10^precision</pre>
     *
     * <p>Where precision is the number of meaningful decimal places in the
     * coordinate returned by the geolocation provider. For example, a latitude
     * of 37.7749 (4 decimal places) yields approximately 8.8 meters accuracy.</p>
     *
     * @param lat latitude
     * @param lon longitude
     * @return accuracy radius in meters
     */
    private static double calculateAccuracy(double lat, double lon) {
        int latPrecision = countDecimalPlaces(lat);
        int lonPrecision = countDecimalPlaces(lon);
        int precision = Math.min(latPrecision, lonPrecision);
        return (111320.0 * Math.cos(lat * Math.PI / 180.0)) / Math.pow(10, precision);
    }

    /**
     * Counts the number of meaningful decimal places in a double value.
     * Strips trailing zeros that may be introduced by {@link Double#toString}.
     *
     * @param value the value to inspect
     * @return the number of significant decimal places
     */
    private static int countDecimalPlaces(double value) {
        String str = Double.toString(Math.abs(value));
        int dotIndex = str.indexOf('.');
        if (dotIndex < 0) return 0;
        String decimals = str.substring(dotIndex + 1).replaceAll("0+$", "");
        return decimals.length();
    }
}