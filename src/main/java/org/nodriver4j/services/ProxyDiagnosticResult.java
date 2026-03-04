package org.nodriver4j.services;

/**
 * Result of a proxy diagnostic operation performed before browser launch.
 *
 * <p>Holds the exit IP, IANA timezone, and geolocation data derived from
 * the proxy's exit node. Used by {@link TaskExecutionService} to apply
 * {@code --fingerprint-timezone} and {@code --fingerprint-geolocation}
 * Chrome switches.</p>
 *
 * <p>Use the static factory methods to construct instances:</p>
 * <pre>{@code
 * ProxyDiagnosticResult result = ProxyDiagnosticResult.success(
 *     "203.0.113.5", "America/New_York", 40.7128, -74.0060, 876.3);
 *
 * ProxyDiagnosticResult failed = ProxyDiagnosticResult.failure("Proxy did not route traffic");
 * }</pre>
 */
public record ProxyDiagnosticResult(
        String exitIp,
        String timezone,
        double latitude,
        double longitude,
        double accuracy,
        boolean success,
        String failureReason
) {

    /**
     * Creates a successful diagnostic result.
     *
     * @param exitIp   the public exit IP address detected through the proxy
     * @param timezone IANA timezone string (e.g. "America/New_York")
     * @param latitude latitude of the exit node
     * @param longitude longitude of the exit node
     * @param accuracy geolocation accuracy radius in meters
     * @return a successful ProxyDiagnosticResult
     */
    public static ProxyDiagnosticResult success(String exitIp, String timezone,
                                                double latitude, double longitude,
                                                double accuracy) {
        return new ProxyDiagnosticResult(exitIp, timezone, latitude, longitude,
                accuracy, true, null);
    }

    /**
     * Creates a failed diagnostic result.
     *
     * @param reason human-readable description of why the diagnostic failed
     * @return a failed ProxyDiagnosticResult
     */
    public static ProxyDiagnosticResult failure(String reason) {
        return new ProxyDiagnosticResult(null, null, 0.0, 0.0, 0.0, false, reason);
    }

    /**
     * Formats geolocation as a {@code --fingerprint-geolocation} switch value.
     *
     * <p>Format: {@code "latitude,longitude,accuracy"} where accuracy is
     * rounded to the nearest meter.</p>
     *
     * @return the formatted geolocation argument
     * @throws IllegalStateException if called on a failed result
     */
    public String toGeolocationArg() {
        if (!success) {
            throw new IllegalStateException(
                    "Cannot format a failed diagnostic result as a geolocation argument");
        }
        return latitude + "," + longitude + "," + Math.round(accuracy);
    }

    @Override
    public String toString() {
        if (!success) {
            return "ProxyDiagnosticResult{failed, reason=" + failureReason + "}";
        }
        return String.format(
                "ProxyDiagnosticResult{ip=%s, timezone=%s, lat=%.4f, lon=%.4f, accuracy=%.1f}",
                exitIp, timezone, latitude, longitude, accuracy);
    }
}