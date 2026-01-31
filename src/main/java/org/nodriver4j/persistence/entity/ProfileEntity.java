package org.nodriver4j.persistence.entity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Entity representing a profile in the database.
 *
 * <p>A profile contains user information including personal details,
 * payment information, and shipping/billing addresses. Profiles are
 * used by automation scripts to fill forms and complete registrations.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProfileEntity profile = ProfileEntity.builder()
 *     .groupId(1)
 *     .emailAddress("user@example.com")
 *     .shippingName("John Doe")
 *     .build();
 *
 * profile = repository.save(profile);
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Hold all profile data fields</li>
 *   <li>Represent a row in the profiles table</li>
 *   <li>Provide convenience methods (firstName, lastName)</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Database operations (use ProfileRepository)</li>
 *   <li>CSV parsing (use ProfileImporter)</li>
 * </ul>
 *
 * @see ProfileGroupEntity
 */
public class ProfileEntity {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ==================== Identity ====================

    private long id;
    private long groupId;

    // ==================== Core Fields ====================

    private String emailAddress;
    private String profileName;
    private boolean onlyOneCheckout;

    // ==================== Payment Fields ====================

    private String nameOnCard;
    private String cardType;
    private String cardNumber;
    private String expirationMonth;
    private String expirationYear;
    private String cvv;

    // ==================== Address Flag ====================

    private boolean sameBillingShipping;

    // ==================== Shipping Fields ====================

    private String shippingName;
    private String shippingPhone;
    private String shippingAddress;
    private String shippingAddress2;
    private String shippingAddress3;
    private String shippingPostCode;
    private String shippingCity;
    private String shippingState;
    private String shippingCountry;

    // ==================== Billing Fields ====================

    private String billingName;
    private String billingPhone;
    private String billingAddress;
    private String billingAddress2;
    private String billingAddress3;
    private String billingPostCode;
    private String billingCity;
    private String billingState;
    private String billingCountry;

    // ==================== Email Access ====================

    private String catchallEmail;
    private String imapPassword;

    // ==================== Metadata ====================

    private String notes;
    private LocalDateTime createdAt;

    // ==================== Constructors ====================

    /**
     * Default constructor for repository mapping.
     */
    public ProfileEntity() {
        this.createdAt = LocalDateTime.now();
        this.sameBillingShipping = true;
    }

    /**
     * Private constructor for builder.
     */
    private ProfileEntity(Builder builder) {
        this.id = builder.id;
        this.groupId = builder.groupId;
        this.emailAddress = builder.emailAddress;
        this.profileName = builder.profileName;
        this.onlyOneCheckout = builder.onlyOneCheckout;
        this.nameOnCard = builder.nameOnCard;
        this.cardType = builder.cardType;
        this.cardNumber = builder.cardNumber;
        this.expirationMonth = builder.expirationMonth;
        this.expirationYear = builder.expirationYear;
        this.cvv = builder.cvv;
        this.sameBillingShipping = builder.sameBillingShipping;
        this.shippingName = builder.shippingName;
        this.shippingPhone = builder.shippingPhone;
        this.shippingAddress = builder.shippingAddress;
        this.shippingAddress2 = builder.shippingAddress2;
        this.shippingAddress3 = builder.shippingAddress3;
        this.shippingPostCode = builder.shippingPostCode;
        this.shippingCity = builder.shippingCity;
        this.shippingState = builder.shippingState;
        this.shippingCountry = builder.shippingCountry;
        this.billingName = builder.billingName;
        this.billingPhone = builder.billingPhone;
        this.billingAddress = builder.billingAddress;
        this.billingAddress2 = builder.billingAddress2;
        this.billingAddress3 = builder.billingAddress3;
        this.billingPostCode = builder.billingPostCode;
        this.billingCity = builder.billingCity;
        this.billingState = builder.billingState;
        this.billingCountry = builder.billingCountry;
        this.catchallEmail = builder.catchallEmail;
        this.imapPassword = builder.imapPassword;
        this.notes = builder.notes;
        this.createdAt = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
    }

    // ==================== Builder Factory ====================

    /**
     * Creates a new builder for ProfileEntity.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder initialized with this entity's values.
     *
     * @return a Builder with current values
     */
    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .groupId(groupId)
                .emailAddress(emailAddress)
                .profileName(profileName)
                .onlyOneCheckout(onlyOneCheckout)
                .nameOnCard(nameOnCard)
                .cardType(cardType)
                .cardNumber(cardNumber)
                .expirationMonth(expirationMonth)
                .expirationYear(expirationYear)
                .cvv(cvv)
                .sameBillingShipping(sameBillingShipping)
                .shippingName(shippingName)
                .shippingPhone(shippingPhone)
                .shippingAddress(shippingAddress)
                .shippingAddress2(shippingAddress2)
                .shippingAddress3(shippingAddress3)
                .shippingPostCode(shippingPostCode)
                .shippingCity(shippingCity)
                .shippingState(shippingState)
                .shippingCountry(shippingCountry)
                .billingName(billingName)
                .billingPhone(billingPhone)
                .billingAddress(billingAddress)
                .billingAddress2(billingAddress2)
                .billingAddress3(billingAddress3)
                .billingPostCode(billingPostCode)
                .billingCity(billingCity)
                .billingState(billingState)
                .billingCountry(billingCountry)
                .catchallEmail(catchallEmail)
                .imapPassword(imapPassword)
                .notes(notes)
                .createdAt(createdAt);
    }

    // ==================== Getters ====================

    public long id() {
        return id;
    }

    public long groupId() {
        return groupId;
    }

    public String emailAddress() {
        return emailAddress;
    }

    public String profileName() {
        return profileName;
    }

    public boolean onlyOneCheckout() {
        return onlyOneCheckout;
    }

    public String nameOnCard() {
        return nameOnCard;
    }

    public String cardType() {
        return cardType;
    }

    public String cardNumber() {
        return cardNumber;
    }

    public String expirationMonth() {
        return expirationMonth;
    }

    public String expirationYear() {
        return expirationYear;
    }

    public String cvv() {
        return cvv;
    }

    public boolean sameBillingShipping() {
        return sameBillingShipping;
    }

    public String shippingName() {
        return shippingName;
    }

    public String shippingPhone() {
        return shippingPhone;
    }

    public String shippingAddress() {
        return shippingAddress;
    }

    public String shippingAddress2() {
        return shippingAddress2;
    }

    public String shippingAddress3() {
        return shippingAddress3;
    }

    public String shippingPostCode() {
        return shippingPostCode;
    }

    public String shippingCity() {
        return shippingCity;
    }

    public String shippingState() {
        return shippingState;
    }

    public String shippingCountry() {
        return shippingCountry;
    }

    public String billingName() {
        return billingName;
    }

    public String billingPhone() {
        return billingPhone;
    }

    public String billingAddress() {
        return billingAddress;
    }

    public String billingAddress2() {
        return billingAddress2;
    }

    public String billingAddress3() {
        return billingAddress3;
    }

    public String billingPostCode() {
        return billingPostCode;
    }

    public String billingCity() {
        return billingCity;
    }

    public String billingState() {
        return billingState;
    }

    public String billingCountry() {
        return billingCountry;
    }

    public String catchallEmail() {
        return catchallEmail;
    }

    public String imapPassword() {
        return imapPassword;
    }

    public String notes() {
        return notes;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public String createdAtString() {
        return createdAt != null ? createdAt.format(FORMATTER) : null;
    }

    // ==================== Setters (for repository mapping) ====================

    public ProfileEntity id(long id) {
        this.id = id;
        return this;
    }

    public ProfileEntity groupId(long groupId) {
        this.groupId = groupId;
        return this;
    }

    public ProfileEntity emailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
        return this;
    }

    public ProfileEntity profileName(String profileName) {
        this.profileName = profileName;
        return this;
    }

    public ProfileEntity onlyOneCheckout(boolean onlyOneCheckout) {
        this.onlyOneCheckout = onlyOneCheckout;
        return this;
    }

    public ProfileEntity nameOnCard(String nameOnCard) {
        this.nameOnCard = nameOnCard;
        return this;
    }

    public ProfileEntity cardType(String cardType) {
        this.cardType = cardType;
        return this;
    }

    public ProfileEntity cardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
        return this;
    }

    public ProfileEntity expirationMonth(String expirationMonth) {
        this.expirationMonth = expirationMonth;
        return this;
    }

    public ProfileEntity expirationYear(String expirationYear) {
        this.expirationYear = expirationYear;
        return this;
    }

    public ProfileEntity cvv(String cvv) {
        this.cvv = cvv;
        return this;
    }

    public ProfileEntity sameBillingShipping(boolean sameBillingShipping) {
        this.sameBillingShipping = sameBillingShipping;
        return this;
    }

    public ProfileEntity shippingName(String shippingName) {
        this.shippingName = shippingName;
        return this;
    }

    public ProfileEntity shippingPhone(String shippingPhone) {
        this.shippingPhone = shippingPhone;
        return this;
    }

    public ProfileEntity shippingAddress(String shippingAddress) {
        this.shippingAddress = shippingAddress;
        return this;
    }

    public ProfileEntity shippingAddress2(String shippingAddress2) {
        this.shippingAddress2 = shippingAddress2;
        return this;
    }

    public ProfileEntity shippingAddress3(String shippingAddress3) {
        this.shippingAddress3 = shippingAddress3;
        return this;
    }

    public ProfileEntity shippingPostCode(String shippingPostCode) {
        this.shippingPostCode = shippingPostCode;
        return this;
    }

    public ProfileEntity shippingCity(String shippingCity) {
        this.shippingCity = shippingCity;
        return this;
    }

    public ProfileEntity shippingState(String shippingState) {
        this.shippingState = shippingState;
        return this;
    }

    public ProfileEntity shippingCountry(String shippingCountry) {
        this.shippingCountry = shippingCountry;
        return this;
    }

    public ProfileEntity billingName(String billingName) {
        this.billingName = billingName;
        return this;
    }

    public ProfileEntity billingPhone(String billingPhone) {
        this.billingPhone = billingPhone;
        return this;
    }

    public ProfileEntity billingAddress(String billingAddress) {
        this.billingAddress = billingAddress;
        return this;
    }

    public ProfileEntity billingAddress2(String billingAddress2) {
        this.billingAddress2 = billingAddress2;
        return this;
    }

    public ProfileEntity billingAddress3(String billingAddress3) {
        this.billingAddress3 = billingAddress3;
        return this;
    }

    public ProfileEntity billingPostCode(String billingPostCode) {
        this.billingPostCode = billingPostCode;
        return this;
    }

    public ProfileEntity billingCity(String billingCity) {
        this.billingCity = billingCity;
        return this;
    }

    public ProfileEntity billingState(String billingState) {
        this.billingState = billingState;
        return this;
    }

    public ProfileEntity billingCountry(String billingCountry) {
        this.billingCountry = billingCountry;
        return this;
    }

    public ProfileEntity catchallEmail(String catchallEmail) {
        this.catchallEmail = catchallEmail;
        return this;
    }

    public ProfileEntity imapPassword(String imapPassword) {
        this.imapPassword = imapPassword;
        return this;
    }

    public ProfileEntity notes(String notes) {
        this.notes = notes;
        return this;
    }

    public ProfileEntity createdAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    public ProfileEntity createdAtString(String createdAt) {
        this.createdAt = createdAt != null ? LocalDateTime.parse(createdAt, FORMATTER) : null;
        return this;
    }

    // ==================== Convenience Methods ====================

    /**
     * Extracts the first name from the shipping name.
     *
     * @return the first name, or full name if no space found
     */
    public String firstName() {
        if (shippingName == null || shippingName.isBlank()) {
            return "";
        }
        int spaceIndex = shippingName.indexOf(' ');
        return spaceIndex > 0 ? shippingName.substring(0, spaceIndex) : shippingName;
    }

    /**
     * Extracts the last name from the shipping name.
     *
     * @return the last name, or empty string if no space found
     */
    public String lastName() {
        if (shippingName == null || shippingName.isBlank()) {
            return "";
        }
        int spaceIndex = shippingName.indexOf(' ');
        return spaceIndex > 0 ? shippingName.substring(spaceIndex + 1) : "";
    }

    /**
     * Gets a display name for this profile.
     *
     * <p>Returns profile name if set, otherwise shipping name, otherwise email.</p>
     *
     * @return a human-readable name for display
     */
    public String displayName() {
        if (profileName != null && !profileName.isBlank()) {
            return profileName;
        }
        if (shippingName != null && !shippingName.isBlank()) {
            return shippingName;
        }
        return emailAddress != null ? emailAddress : "Unknown";
    }

    /**
     * Checks if this entity has been persisted.
     *
     * @return true if ID is set (greater than 0)
     */
    public boolean isPersisted() {
        return id > 0;
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format("ProfileEntity{id=%d, groupId=%d, email=%s, name=%s}",
                id, groupId, emailAddress, displayName());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProfileEntity that = (ProfileEntity) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    // ==================== Builder ====================

    /**
     * Builder for creating ProfileEntity instances.
     */
    public static class Builder {

        private long id;
        private long groupId;
        private String emailAddress = "";
        private String profileName = "";
        private boolean onlyOneCheckout = false;
        private String nameOnCard = "";
        private String cardType = "";
        private String cardNumber = "";
        private String expirationMonth = "";
        private String expirationYear = "";
        private String cvv = "";
        private boolean sameBillingShipping = true;
        private String shippingName = "";
        private String shippingPhone = "";
        private String shippingAddress = "";
        private String shippingAddress2 = "";
        private String shippingAddress3 = "";
        private String shippingPostCode = "";
        private String shippingCity = "";
        private String shippingState = "";
        private String shippingCountry = "";
        private String billingName = "";
        private String billingPhone = "";
        private String billingAddress = "";
        private String billingAddress2 = "";
        private String billingAddress3 = "";
        private String billingPostCode = "";
        private String billingCity = "";
        private String billingState = "";
        private String billingCountry = "";
        private String catchallEmail = "";
        private String imapPassword = "";
        private String notes = "";
        private LocalDateTime createdAt;

        private Builder() {}

        public Builder id(long id) {
            this.id = id;
            return this;
        }

        public Builder groupId(long groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder emailAddress(String emailAddress) {
            this.emailAddress = emailAddress != null ? emailAddress : "";
            return this;
        }

        public Builder profileName(String profileName) {
            this.profileName = profileName != null ? profileName : "";
            return this;
        }

        public Builder onlyOneCheckout(boolean onlyOneCheckout) {
            this.onlyOneCheckout = onlyOneCheckout;
            return this;
        }

        public Builder nameOnCard(String nameOnCard) {
            this.nameOnCard = nameOnCard != null ? nameOnCard : "";
            return this;
        }

        public Builder cardType(String cardType) {
            this.cardType = cardType != null ? cardType : "";
            return this;
        }

        public Builder cardNumber(String cardNumber) {
            this.cardNumber = cardNumber != null ? cardNumber : "";
            return this;
        }

        public Builder expirationMonth(String expirationMonth) {
            this.expirationMonth = expirationMonth != null ? expirationMonth : "";
            return this;
        }

        public Builder expirationYear(String expirationYear) {
            this.expirationYear = expirationYear != null ? expirationYear : "";
            return this;
        }

        public Builder cvv(String cvv) {
            this.cvv = cvv != null ? cvv : "";
            return this;
        }

        public Builder sameBillingShipping(boolean sameBillingShipping) {
            this.sameBillingShipping = sameBillingShipping;
            return this;
        }

        public Builder shippingName(String shippingName) {
            this.shippingName = shippingName != null ? shippingName : "";
            return this;
        }

        public Builder shippingPhone(String shippingPhone) {
            this.shippingPhone = shippingPhone != null ? shippingPhone : "";
            return this;
        }

        public Builder shippingAddress(String shippingAddress) {
            this.shippingAddress = shippingAddress != null ? shippingAddress : "";
            return this;
        }

        public Builder shippingAddress2(String shippingAddress2) {
            this.shippingAddress2 = shippingAddress2 != null ? shippingAddress2 : "";
            return this;
        }

        public Builder shippingAddress3(String shippingAddress3) {
            this.shippingAddress3 = shippingAddress3 != null ? shippingAddress3 : "";
            return this;
        }

        public Builder shippingPostCode(String shippingPostCode) {
            this.shippingPostCode = shippingPostCode != null ? shippingPostCode : "";
            return this;
        }

        public Builder shippingCity(String shippingCity) {
            this.shippingCity = shippingCity != null ? shippingCity : "";
            return this;
        }

        public Builder shippingState(String shippingState) {
            this.shippingState = shippingState != null ? shippingState : "";
            return this;
        }

        public Builder shippingCountry(String shippingCountry) {
            this.shippingCountry = shippingCountry != null ? shippingCountry : "";
            return this;
        }

        public Builder billingName(String billingName) {
            this.billingName = billingName != null ? billingName : "";
            return this;
        }

        public Builder billingPhone(String billingPhone) {
            this.billingPhone = billingPhone != null ? billingPhone : "";
            return this;
        }

        public Builder billingAddress(String billingAddress) {
            this.billingAddress = billingAddress != null ? billingAddress : "";
            return this;
        }

        public Builder billingAddress2(String billingAddress2) {
            this.billingAddress2 = billingAddress2 != null ? billingAddress2 : "";
            return this;
        }

        public Builder billingAddress3(String billingAddress3) {
            this.billingAddress3 = billingAddress3 != null ? billingAddress3 : "";
            return this;
        }

        public Builder billingPostCode(String billingPostCode) {
            this.billingPostCode = billingPostCode != null ? billingPostCode : "";
            return this;
        }

        public Builder billingCity(String billingCity) {
            this.billingCity = billingCity != null ? billingCity : "";
            return this;
        }

        public Builder billingState(String billingState) {
            this.billingState = billingState != null ? billingState : "";
            return this;
        }

        public Builder billingCountry(String billingCountry) {
            this.billingCountry = billingCountry != null ? billingCountry : "";
            return this;
        }

        public Builder catchallEmail(String catchallEmail) {
            this.catchallEmail = catchallEmail != null ? catchallEmail : "";
            return this;
        }

        public Builder imapPassword(String imapPassword) {
            this.imapPassword = imapPassword != null ? imapPassword : "";
            return this;
        }

        public Builder notes(String notes) {
            this.notes = notes != null ? notes : "";
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public ProfileEntity build() {
            return new ProfileEntity(this);
        }
    }
}