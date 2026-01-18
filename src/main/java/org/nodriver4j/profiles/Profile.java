package org.nodriver4j.profiles;

import java.util.*;

/**
 * Immutable container for profile data matching the standard CSV format.
 *
 * <p>Profile represents a complete user profile with personal information,
 * payment details, and shipping/billing addresses. It supports both input
 * (loading from CSV) and output (writing to CSV with additional fields).</p>
 *
 * <p>Instances are created via the {@link Builder} pattern:</p>
 * <pre>{@code
 * Profile profile = Profile.builder()
 *     .emailAddress("user@example.com")
 *     .shippingName("John Doe")
 *     .shippingPhone("5551234567")
 *     // ... other fields
 *     .build();
 * }</pre>
 *
 * <p>Use {@link #toBuilder()} to create modified copies:</p>
 * <pre>{@code
 * Profile completed = profile.toBuilder()
 *     .accountLoginInfo("user@example.com:password123")
 *     .build();
 * }</pre>
 *
 * @see ProfilePool
 */
public final class Profile {

    /**
     * CSV column headers in the expected order.
     * Used for parsing and serialization.
     */
    public static final String[] CSV_HEADERS = {
            "Email Address",
            "Profile Name",
            "Only One Checkout",
            "Name on Card",
            "Card Type",
            "Card Number",
            "Expiration Month",
            "Expiration Year",
            "CVV",
            "Same Billing/Shipping",
            "Shipping Name",
            "Shipping Phone",
            "Shipping Address",
            "Shipping Address 2",
            "Shipping Address 3",
            "Shipping Post Code",
            "Shipping City",
            "Shipping State",
            "Shipping Country",
            "Billing Name",
            "Billing Phone",
            "Billing Address",
            "Billing Address 2",
            "Billing Address 3",
            "Billing Post Code",
            "Billing City",
            "Billing State",
            "Billing Country",
            "Account Login Info",
            "IMAP Password"
    };

    // ==================== Core Fields ====================

    private final String emailAddress;
    private final String profileName;
    private final boolean onlyOneCheckout;

    // ==================== Payment Fields ====================

    private final String nameOnCard;
    private final String cardType;
    private final String cardNumber;
    private final String expirationMonth;
    private final String expirationYear;
    private final String cvv;

    // ==================== Address Flag ====================

    private final boolean sameBillingShipping;

    // ==================== Shipping Fields ====================

    private final String shippingName;
    private final String shippingPhone;
    private final String shippingAddress;
    private final String shippingAddress2;
    private final String shippingAddress3;
    private final String shippingPostCode;
    private final String shippingCity;
    private final String shippingState;
    private final String shippingCountry;

    // ==================== Billing Fields ====================

    private final String billingName;
    private final String billingPhone;
    private final String billingAddress;
    private final String billingAddress2;
    private final String billingAddress3;
    private final String billingPostCode;
    private final String billingCity;
    private final String billingState;
    private final String billingCountry;

    // ==================== Output Fields ====================

    private final String accountLoginInfo;
    private final String imapPassword;

    // ==================== Extra Fields ====================

    /**
     * Additional script-specific fields to be appended to CSV output.
     * These are not part of the standard 30-column format.
     */
    private final Map<String, String> extraFields;

    private Profile(Builder builder) {
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
        this.accountLoginInfo = builder.accountLoginInfo;
        this.imapPassword = builder.imapPassword;
        this.extraFields = Collections.unmodifiableMap(new LinkedHashMap<>(builder.extraFields));
    }

    // ==================== Static Factory Methods ====================

    /**
     * Creates a new builder for Profile construction.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Parses a CSV row into a Profile instance.
     *
     * <p>The row must contain at least 30 comma-separated values matching
     * the standard CSV format defined by {@link #CSV_HEADERS}.</p>
     *
     * @param csvRow the comma-separated row string
     * @return a new Profile instance
     * @throws IllegalArgumentException if the row has fewer than 30 columns
     */
    public static Profile fromCSVRow(String csvRow) {
        if (csvRow == null || csvRow.isBlank()) {
            throw new IllegalArgumentException("CSV row cannot be null or blank");
        }

        String[] values = parseCSVRow(csvRow);

        if (values.length < 30) {
            throw new IllegalArgumentException(
                    "CSV row must have at least 30 columns, found: " + values.length);
        }

        return builder()
                .emailAddress(values[0])
                .profileName(values[1])
                .onlyOneCheckout(parseBoolean(values[2]))
                .nameOnCard(values[3])
                .cardType(values[4])
                .cardNumber(values[5])
                .expirationMonth(values[6])
                .expirationYear(values[7])
                .cvv(values[8])
                .sameBillingShipping(parseBoolean(values[9]))
                .shippingName(values[10])
                .shippingPhone(values[11])
                .shippingAddress(values[12])
                .shippingAddress2(values[13])
                .shippingAddress3(values[14])
                .shippingPostCode(values[15])
                .shippingCity(values[16])
                .shippingState(values[17])
                .shippingCountry(values[18])
                .billingName(values[19])
                .billingPhone(values[20])
                .billingAddress(values[21])
                .billingAddress2(values[22])
                .billingAddress3(values[23])
                .billingPostCode(values[24])
                .billingCity(values[25])
                .billingState(values[26])
                .billingCountry(values[27])
                .accountLoginInfo(values[28])
                .imapPassword(values[29])
                .build();
    }

    /**
     * Creates a builder initialized with this profile's values.
     * Useful for creating modified copies.
     *
     * @return a Builder with current values
     */
    public Builder toBuilder() {
        Builder builder = new Builder()
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
                .accountLoginInfo(accountLoginInfo)
                .imapPassword(imapPassword);

        // Copy extra fields
        for (Map.Entry<String, String> entry : extraFields.entrySet()) {
            builder.extraField(entry.getKey(), entry.getValue());
        }

        return builder;
    }

    // ==================== Serialization ====================

    /**
     * Converts this profile to a CSV row string.
     *
     * <p>The output includes all 30 standard columns followed by any extra fields
     * in the order they were added.</p>
     *
     * @return comma-separated row string
     */
    public String toCSVRow() {
        StringBuilder sb = new StringBuilder();

        // Standard 30 columns
        appendCSVValue(sb, emailAddress);
        appendCSVValue(sb, profileName);
        appendCSVValue(sb, String.valueOf(onlyOneCheckout).toUpperCase());
        appendCSVValue(sb, nameOnCard);
        appendCSVValue(sb, cardType);
        appendCSVValue(sb, cardNumber);
        appendCSVValue(sb, expirationMonth);
        appendCSVValue(sb, expirationYear);
        appendCSVValue(sb, cvv);
        appendCSVValue(sb, String.valueOf(sameBillingShipping).toUpperCase());
        appendCSVValue(sb, shippingName);
        appendCSVValue(sb, shippingPhone);
        appendCSVValue(sb, shippingAddress);
        appendCSVValue(sb, shippingAddress2);
        appendCSVValue(sb, shippingAddress3);
        appendCSVValue(sb, shippingPostCode);
        appendCSVValue(sb, shippingCity);
        appendCSVValue(sb, shippingState);
        appendCSVValue(sb, shippingCountry);
        appendCSVValue(sb, billingName);
        appendCSVValue(sb, billingPhone);
        appendCSVValue(sb, billingAddress);
        appendCSVValue(sb, billingAddress2);
        appendCSVValue(sb, billingAddress3);
        appendCSVValue(sb, billingPostCode);
        appendCSVValue(sb, billingCity);
        appendCSVValue(sb, billingState);
        appendCSVValue(sb, billingCountry);
        appendCSVValue(sb, accountLoginInfo);
        appendCSVValue(sb, imapPassword);

        // Extra fields (appended at end)
        for (String value : extraFields.values()) {
            appendCSVValue(sb, value);
        }

        // Remove trailing comma
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }

        return sb.toString();
    }

    /**
     * Returns the CSV header row including any extra field headers.
     *
     * @return comma-separated header string
     */
    public String toCSVHeaderRow() {
        StringBuilder sb = new StringBuilder();

        // Standard headers
        for (String header : CSV_HEADERS) {
            sb.append(header).append(",");
        }

        // Extra field headers
        for (String key : extraFields.keySet()) {
            sb.append(key).append(",");
        }

        // Remove trailing comma
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }

        return sb.toString();
    }

    // ==================== CSV Parsing Helpers ====================

    /**
     * Parses a CSV row, handling quoted values that may contain commas.
     */
    private static String[] parseCSVRow(String row) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);

            if (c == '"') {
                if (inQuotes && i + 1 < row.length() && row.charAt(i + 1) == '"') {
                    // Escaped quote
                    current.append('"');
                    i++;
                } else {
                    // Toggle quote state
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        // Add last value
        values.add(current.toString().trim());

        return values.toArray(new String[0]);
    }

    /**
     * Parses a boolean from CSV value (TRUE/FALSE/empty).
     */
    private static boolean parseBoolean(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return "TRUE".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }

    /**
     * Appends a value to CSV output, quoting if necessary.
     */
    private void appendCSVValue(StringBuilder sb, String value) {
        if (value == null) {
            sb.append(",");
            return;
        }

        // Quote if contains comma, quote, or newline
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            sb.append("\"");
            sb.append(value.replace("\"", "\"\""));
            sb.append("\"");
        } else {
            sb.append(value);
        }
        sb.append(",");
    }

    // ==================== Getters ====================

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

    public String accountLoginInfo() {
        return accountLoginInfo;
    }

    public String imapPassword() {
        return imapPassword;
    }

    /**
     * Returns the extra fields map.
     *
     * @return unmodifiable map of extra field names to values
     */
    public Map<String, String> extraFields() {
        return extraFields;
    }

    /**
     * Returns a specific extra field value.
     *
     * @param key the extra field name
     * @return the value, or null if not present
     */
    public String extraField(String key) {
        return extraFields.get(key);
    }

    // ==================== Convenience Methods ====================

    /**
     * Extracts the first name from the shipping name.
     * Assumes format "FirstName LastName".
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
     * Assumes format "FirstName LastName".
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

    @Override
    public String toString() {
        return String.format("Profile{email=%s, name=%s, city=%s}",
                emailAddress, shippingName, shippingCity);
    }

    // ==================== Builder ====================

    /**
     * Builder for creating Profile instances.
     */
    public static class Builder {

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
        private String accountLoginInfo = "";
        private String imapPassword = "";
        private final Map<String, String> extraFields = new LinkedHashMap<>();

        private Builder() {}

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

        public Builder accountLoginInfo(String accountLoginInfo) {
            this.accountLoginInfo = accountLoginInfo != null ? accountLoginInfo : "";
            return this;
        }

        public Builder imapPassword(String imapPassword) {
            this.imapPassword = imapPassword != null ? imapPassword : "";
            return this;
        }

        /**
         * Adds an extra field to be appended to CSV output.
         *
         * @param key   the field name (used as column header)
         * @param value the field value
         * @return this builder
         */
        public Builder extraField(String key, String value) {
            if (key != null && !key.isBlank()) {
                this.extraFields.put(key, value != null ? value : "");
            }
            return this;
        }

        /**
         * Builds the Profile instance.
         *
         * @return a new Profile
         */
        public Profile build() {
            return new Profile(this);
        }
    }
}