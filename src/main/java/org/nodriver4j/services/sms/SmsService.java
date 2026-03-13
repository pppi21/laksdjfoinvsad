package org.nodriver4j.services.sms;

import org.nodriver4j.services.response.sms.SmsActivation;

/**
 * Enum of target services supported by the SMS verification system.
 *
 * <p>Each constant maps a logical service name to the provider-specific
 * identifiers used by TextVerified, SMS-Man, and DaisySMS. Adding support
 * for a new site requires only a new enum constant — no changes to the
 * service classes themselves.</p>
 *
 * <h2>Provider ID Formats</h2>
 * <ul>
 *   <li><b>TextVerified:</b> case-sensitive service name string
 *       (e.g., {@code "Uber"}). Found on their Service List page.</li>
 *   <li><b>SMS-Man:</b> integer {@code application_id}
 *       (e.g., {@code 31}). Found via their
 *       {@code /control/get-limits} endpoint.</li>
 *   <li><b>DaisySMS:</b> short string code in the sms-activate format
 *       (e.g., {@code "ub"}). Found on their Services page.</li>
 * </ul>
 *
 * <h2>Adding a New Service</h2>
 * <ol>
 *   <li>Look up the service ID on each provider's dashboard/API</li>
 *   <li>Add a new enum constant with the three IDs</li>
 *   <li>Use it in your script: {@code sms.requestNumber(SmsService.NEW_SERVICE)}</li>
 * </ol>
 *
 * @see SmsServiceBase
 * @see SmsActivation
 */
public enum SmsService {

    // ==================== Service Definitions ====================

    /**
     * Uber / Uber Eats.
     *
     * <p>Provider IDs:</p>
     * <ul>
     *   <li>TextVerified: {@code "Uber"}</li>
     *   <li>SMS-Man: {@code application_id=31} (verify via get-limits endpoint)</li>
     *   <li>DaisySMS: {@code "ub"}</li>
     * </ul>
     */
    UBER("Uber", 31, "ub", "Uber");

    // ==================== Fields ====================

    private final String textVerifiedName;
    private final int smsManAppId;
    private final String daisySmsCode;
    private final String displayName;

    // ==================== Constructor ====================

    /**
     * Creates a service mapping.
     *
     * @param textVerifiedName the service name for TextVerified API
     * @param smsManAppId      the application_id for SMS-Man API
     * @param daisySmsCode     the service code for DaisySMS API
     * @param displayName      human-readable name for logs
     */
    SmsService(String textVerifiedName, int smsManAppId, String daisySmsCode,
               String displayName) {
        this.textVerifiedName = textVerifiedName;
        this.smsManAppId = smsManAppId;
        this.daisySmsCode = daisySmsCode;
        this.displayName = displayName;
    }

    // ==================== Accessors ====================

    /**
     * Returns the TextVerified service name.
     *
     * @return the service name string (e.g., "Uber")
     */
    public String textVerifiedName() {
        return textVerifiedName;
    }

    /**
     * Returns the SMS-Man application ID.
     *
     * @return the integer application_id
     */
    public int smsManAppId() {
        return smsManAppId;
    }

    /**
     * Returns the DaisySMS service code (sms-activate format).
     *
     * @return the short service code (e.g., "ub")
     */
    public String daisySmsCode() {
        return daisySmsCode;
    }

    /**
     * Returns the human-readable display name for log messages.
     *
     * @return the display name
     */
    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}