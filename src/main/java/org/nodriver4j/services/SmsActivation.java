package org.nodriver4j.services;

/**
 * Immutable snapshot of an active SMS verification session.
 *
 * <p>Returned by {@link SmsServiceBase#requestNumber(SmsService)} after
 * successfully renting a phone number from a provider. Passed into
 * {@link SmsServiceBase#pollForCode(SmsActivation)} to poll for the
 * incoming OTP code.</p>
 *
 * <p>The {@link #phoneNumber()} is always normalized — US country code
 * stripped, bare 10-digit format (e.g., {@code "2125551234"}) — ready
 * for direct entry into web forms.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SmsActivation activation = smsService.requestNumber(SmsService.UBER);
 *
 * // Enter the number into the site
 * page.fillFormField(PHONE_TEXT, activation.phoneNumber(), true);
 *
 * // Poll for the OTP
 * String code = smsService.pollForCode(activation);
 *
 * // Complete the activation
 * smsService.completeActivation(activation.activationId());
 * }</pre>
 *
 * @param activationId the provider-specific activation/request ID
 * @param phoneNumber  the rented phone number (normalized, no country code)
 * @param providerName the name of the provider (e.g., "SMS-Man", "DaisySMS")
 * @see SmsServiceBase
 * @see SmsService
 */
public record SmsActivation(
        String activationId,
        String phoneNumber,
        String providerName
) {

    /**
     * Compact constructor with validation.
     */
    public SmsActivation {
        if (activationId == null || activationId.isBlank()) {
            throw new IllegalArgumentException("Activation ID cannot be null or blank");
        }
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("Phone number cannot be null or blank");
        }
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("Provider name cannot be null or blank");
        }
    }
}