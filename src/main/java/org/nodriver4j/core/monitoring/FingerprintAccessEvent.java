package org.nodriver4j.core.monitoring;

/**
 * A single fingerprint API access event captured by the monitoring script.
 *
 * @param api        the API that was accessed (e.g. "navigator.userAgent", "HTMLCanvasElement.toDataURL")
 * @param timestamp  epoch milliseconds when the access occurred
 * @param stackTrace the JavaScript stack trace at the time of access, or null if stack capture is disabled
 */
public record FingerprintAccessEvent(
        String api,
        long timestamp,
        String stackTrace
) {}
