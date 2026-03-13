package org.nodriver4j.ui.profile.detail;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.nodriver4j.ui.components.GroupCard;

/**
 * An ID-card-shaped component that displays a profile's key information.
 *
 * <p>Each card shows:</p>
 * <ul>
 *   <li>Profile name (bold, primary text)</li>
 *   <li>Email address</li>
 *   <li>Phone number</li>
 *   <li>Card number (formatted with spaces)</li>
 *   <li>Expiration date + CVV</li>
 *   <li>Delete button with inline confirmation (bottom-right)</li>
 * </ul>
 *
 * <p>Clicking the card fires the {@code onClick} callback (unless delete
 * confirmation is showing). The controller uses this to open a
 * {@code ProfileDetailDialog}. The delete button follows the same
 * inline confirmation pattern as {@link GroupCard}: clicking the trash
 * icon reveals "Delete" / "Cancel" buttons in the same slot.</p>
 *
 * <p>Cards are displayed in a FlowPane grid on the Profile Group Detail
 * page, similar to how {@link GroupCard} appears on manager pages.</p>
 *
 * <h2>Layout Structure</h2>
 * <pre>
 * VBox (card)
 * ├── identitySection (name, email, phone)
 * ├── paymentSection (card number, exp + cvv)
 * ├── spacer
 * └── bottomRow (HBox)
 *     ├── spacer
 *     └── deleteContainer (StackPane)
 *         ├── deleteButton (trash icon)
 *         └── confirmCancelContainer (hidden by default)
 *             ├── confirmButton ("Delete")
 *             └── cancelButton ("Cancel")
 * </pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display profile summary fields in an ID-card layout</li>
 *   <li>Hold the database profile ID for controller lookups</li>
 *   <li>Delegate click events via callback</li>
 *   <li>Delete button with inline confirmation flow</li>
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
 * card.setOnDelete(() -> deleteProfile(42L));
 * flowPane.getChildren().add(card);
 * }</pre>
 *
 * @see GroupCard
 * @see ProfileDetailDialog
 */
public class ProfileCard extends VBox {

    // ==================== UI Components ====================

    private final Label nameLabel;
    private final Label emailLabel;
    private final Label phoneLabel;
    private final Label cardNumberLabel;
    private final Label cardDetailsLabel;

    // ==================== Delete Flow Components ====================

    private final StackPane deleteContainer;
    private final Button deleteButton;
    private final HBox confirmCancelContainer;

    // ==================== Data ====================

    private final long profileId;

    // ==================== State ====================

    private boolean isConfirmingDelete = false;

    // ==================== Callbacks ====================

    private Runnable onClick;
    private Runnable onDelete;

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

        // ---- Delete flow components ----
        deleteButton = buildDeleteButton();
        confirmCancelContainer = buildConfirmCancelContainer();
        deleteContainer = buildDeleteContainer();

        // ---- Bottom row ----
        HBox bottomRow = buildBottomRow();

        // ---- Spacer to push bottom row down ----
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        // ---- Assemble card ----
        setSpacing(8);
        setAlignment(Pos.TOP_LEFT);
        getChildren().addAll(identitySection, paymentSection, spacer, bottomRow);

        // Click handler (only fires when not confirming delete)
        setOnMouseClicked(event -> {
            if (event.getButton().name().equals("PRIMARY")
                    && !isConfirmingDelete
                    && onClick != null) {
                onClick.run();
            }
        });
    }

    // ==================== Delete Flow UI Building ====================

    /**
     * Builds the trash icon delete button.
     */
    private Button buildDeleteButton() {
        Button button = new Button();
        button.getStyleClass().add("delete-button");

        FontIcon trashIcon = new FontIcon(FontAwesomeSolid.TRASH_ALT);
        trashIcon.setIconSize(14);
        trashIcon.setIconColor(Color.web("#d15252"));
        button.setGraphic(trashIcon);

        button.setOnAction(event -> {
            event.consume();
            showDeleteConfirmation();
        });

        return button;
    }

    /**
     * Builds the confirm/cancel button container for delete confirmation.
     */
    private HBox buildConfirmCancelContainer() {
        HBox container = new HBox();
        container.getStyleClass().add("delete-confirm-buttons");
        container.setAlignment(Pos.CENTER_RIGHT);
        container.setSpacing(6);

        Button confirmButton = new Button("Delete");
        confirmButton.getStyleClass().add("confirm-delete-button");
        confirmButton.setOnAction(event -> {
            event.consume();
            confirmDelete();
        });

        Button cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().add("cancel-delete-button");
        cancelButton.setOnAction(event -> {
            event.consume();
            hideDeleteConfirmation();
        });

        container.getChildren().addAll(confirmButton, cancelButton);
        container.setVisible(false);
        container.setManaged(false);

        return container;
    }

    /**
     * Builds the StackPane that holds the delete button and confirmation buttons.
     */
    private StackPane buildDeleteContainer() {
        StackPane container = new StackPane();
        container.setAlignment(Pos.BOTTOM_RIGHT);
        container.setMinWidth(120);
        container.setPrefWidth(120);
        container.setMaxWidth(120);
        container.setMinHeight(28);
        container.setPrefHeight(28);
        container.setMaxHeight(28);
        container.getChildren().addAll(deleteButton, confirmCancelContainer);
        return container;
    }

    /**
     * Builds the bottom row containing a spacer and the delete container.
     */
    private HBox buildBottomRow() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox();
        row.setAlignment(Pos.BOTTOM_RIGHT);
        row.getChildren().addAll(spacer, deleteContainer);

        return row;
    }

    // ==================== Delete Flow ====================

    /**
     * Shows the delete confirmation buttons, hiding the trash icon.
     */
    private void showDeleteConfirmation() {
        isConfirmingDelete = true;

        deleteButton.setVisible(false);
        deleteButton.setManaged(false);

        confirmCancelContainer.setVisible(true);
        confirmCancelContainer.setManaged(true);
    }

    /**
     * Hides the delete confirmation buttons, restoring the trash icon.
     */
    private void hideDeleteConfirmation() {
        isConfirmingDelete = false;

        deleteButton.setVisible(true);
        deleteButton.setManaged(true);

        confirmCancelContainer.setVisible(false);
        confirmCancelContainer.setManaged(false);
    }

    /**
     * Confirms the delete action and invokes the onDelete callback.
     */
    private void confirmDelete() {
        isConfirmingDelete = false;

        if (onDelete != null) {
            onDelete.run();
        }
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

        String digits = cardNumber.replaceAll("[\\s\\-]", "");

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
     * <p>The callback is only invoked on primary mouse button clicks
     * when the delete confirmation is not showing.</p>
     *
     * @param onClick the callback
     */
    public void setOnClick(Runnable onClick) {
        this.onClick = onClick;
    }

    /**
     * Sets the callback for when delete is confirmed.
     *
     * <p>The callback is invoked after the user clicks the trash icon
     * and then confirms with the "Delete" button.</p>
     *
     * @param onDelete the callback
     */
    public void setOnDelete(Runnable onDelete) {
        this.onDelete = onDelete;
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

    /**
     * Checks if the card is currently showing delete confirmation.
     *
     * @return true if confirming delete
     */
    public boolean isConfirmingDelete() {
        return isConfirmingDelete;
    }
}