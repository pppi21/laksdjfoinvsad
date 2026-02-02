package org.nodriver4j.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * An ID-card-shaped component that displays a profile's key information.
 *
 * <p>Each card shows:</p>
 * <ul>
 *   <li>Profile name (bold, primary text)</li>
 *   <li>Email address</li>
 *   <li>Phone number</li>
 *   <li>Card number (full)</li>
 *   <li>Expiration date + CVV</li>
 * </ul>
 *
 * <p>Clicking the card fires the {@code onClick} callback. The controller
 * uses this to open a {@code ProfileDetailDialog} — the card itself has
 * no knowledge of the dialog or copy-to-clipboard behavior.</p>
 *
 * <p>Cards are displayed in a FlowPane grid on the Profile Group Detail
 * page, similar to how {@link GroupCard} appears on manager pages.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display profile summary fields in an ID-card layout</li>
 *   <li>Hold the database profile ID for controller lookups</li>
 *   <li>Delegate click events via callback</li>
 *   <li>Apply profile-card CSS styling</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Copy-to-clipboard functionality (handled by ProfileDetailDialog)</li>
 *   <li>Database operations (controller handles persistence)</li>
 *   <li>Opening the detail popup (controller handles via callback)</li>
 *   <li>Notes editing (handled by ProfileDetailDialog)</li>
 *   <li>Profile data resolution (controller resolves from ProfileEntity)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProfileCard card = new ProfileCard(
 *     42L,
 *     "John Doe",
 *     "johndoe@gmail.com",
 *     "5551234567",
 *     "4111111111111111",
 *     "02",
 *     "2026",
 *     "123"
 * );
 * card.setOnClick(() -> openDetailDialog(42L));
 * flowPane.getChildren().add(card);
 * }</pre>
 */
public class ProfileCard extends VBox {

    // ==================== UI Components ====================

    private final Label nameLabel;
    private final Label emailLabel;
    private final Label phoneLabel;
    private final Label cardNumberLabel;
    private final Label cardDetailsLabel;

    // ==================== Data ====================

    private final long profileId;

    // ==================== Callbacks ====================

    private Runnable onClick;

    // ==================== Constructor ====================

    /**
     * Creates a new ProfileCard.
     *
     * @param profileId       the database ID of the profile
     * @param name            the display name (e.g., shipping name or profile name)
     * @param email           the email address
     * @param phone           the phone number
     * @param cardNumber      the full card number
     * @param expirationMonth the expiration month (e.g., "02")
     * @param expirationYear  the expiration year (e.g., "2026")
     * @param cvv             the CVV
     */
    public ProfileCard(long profileId, String name, String email, String phone,
                       String cardNumber, String expirationMonth,
                       String expirationYear, String cvv) {
        this.profileId = profileId;

        // Apply card styling
        getStyleClass().add("profile-card");

        // ---- Identity section ----
        nameLabel = new Label(fallback(name, "Unknown"));
        nameLabel.getStyleClass().add("profile-card-name");
        nameLabel.setWrapText(true);

        emailLabel = new Label(fallback(email, "No email"));
        emailLabel.getStyleClass().add("profile-card-email");
        emailLabel.setWrapText(true);

        phoneLabel = new Label(fallback(phone, "No phone"));
        phoneLabel.getStyleClass().add("profile-card-phone");

        VBox identitySection = new VBox(2);
        identitySection.getChildren().addAll(nameLabel, emailLabel, phoneLabel);

        // ---- Payment section ----
        cardNumberLabel = new Label(formatCardNumber(cardNumber));
        cardNumberLabel.getStyleClass().add("profile-card-number");

        String expText = formatExpiration(expirationMonth, expirationYear);
        String cvvText = formatCvv(cvv);

        cardDetailsLabel = new Label(expText + "    CVV: " + cvvText);
        cardDetailsLabel.getStyleClass().add("profile-card-details");

        VBox paymentSection = new VBox(2);
        paymentSection.getStyleClass().add("profile-card-payment");
        paymentSection.getChildren().addAll(cardNumberLabel, cardDetailsLabel);

        // ---- Assemble card ----
        setSpacing(8);
        setAlignment(Pos.TOP_LEFT);
        getChildren().addAll(identitySection, paymentSection);

        // Click handler
        setOnMouseClicked(event -> {
            if (event.getButton().name().equals("PRIMARY") && onClick != null) {
                onClick.run();
            }
        });
    }

    // ==================== Formatting ====================

    /**
     * Formats a card number with spaces every 4 digits for readability.
     *
     * @param cardNumber the raw card number
     * @return the formatted number (e.g., "4111 1111 1111 1111")
     */
    private String formatCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) {
            return "•••• •••• •••• ••••";
        }

        // Remove any existing spaces or dashes
        String digits = cardNumber.replaceAll("[\\s\\-]", "");

        // Insert space every 4 characters
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                formatted.append(' ');
            }
            formatted.append(digits.charAt(i));
        }

        return formatted.toString();
    }

    /**
     * Formats expiration month and year as MM/YY or MM/YYYY.
     */
    private String formatExpiration(String month, String year) {
        String m = (month != null && !month.isBlank()) ? month : "••";
        String y = (year != null && !year.isBlank()) ? year : "••••";
        return m + "/" + y;
    }

    /**
     * Formats CVV, masking if empty.
     */
    private String formatCvv(String cvv) {
        return (cvv != null && !cvv.isBlank()) ? cvv : "•••";
    }

    /**
     * Returns the value if non-blank, otherwise the fallback.
     */
    private String fallback(String value, String fallbackText) {
        return (value != null && !value.isBlank()) ? value : fallbackText;
    }

    // ==================== Callbacks ====================

    /**
     * Sets the callback for when the card is clicked.
     *
     * @param onClick the callback
     */
    public void setOnClick(Runnable onClick) {
        this.onClick = onClick;
    }

    // ==================== Getters ====================

    /**
     * Gets the database ID of the profile.
     *
     * @return the profile ID
     */
    public long profileId() {
        return profileId;
    }
}