package org.nodriver4j.ui.profile.detail;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.ui.profile.CreateProfileGroupDialog;
import org.nodriver4j.ui.util.SmoothScrollHelper;

import java.util.Optional;

/**
 * Modal dialog displaying all fields of a profile with click-to-copy
 * functionality and an editable notes area.
 *
 * <p>Every non-empty field value is clickable — clicking copies the raw
 * value to the system clipboard and shows brief "Copied!" visual feedback.
 * The Notes field at the bottom is the only editable area; all other fields
 * are read-only display.</p>
 *
 * <p>Fields are organized into logical sections:</p>
 * <ol>
 *   <li><strong>Identity</strong> — profile name, email address</li>
 *   <li><strong>Payment</strong> — card details, checkout preference</li>
 *   <li><strong>Shipping</strong> — full shipping address</li>
 *   <li><strong>Billing</strong> — full billing address, or "Same as shipping" note</li>
 *   <li><strong>Email Access</strong> — catchall email, IMAP password</li>
 *   <li><strong>Notes</strong> — editable text area</li>
 * </ol>
 *
 * <p>Returns the notes text when the user clicks Done, or empty Optional
 * when the user cancels. The caller is responsible for persisting any
 * changes to the database.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Optional<String> updatedNotes = ProfileDetailDialog.show(ownerWindow, profileEntity);
 * updatedNotes.ifPresent(notes -> saveProfileNotes(profile, notes));
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display all profile fields organized in sections</li>
 *   <li>Click-to-copy with visual feedback for every non-empty value</li>
 *   <li>Editable Notes text area pre-populated with existing notes</li>
 *   <li>Return updated notes string on Done, empty on Cancel</li>
 *   <li>Dark theme styling consistent with other application dialogs</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Persisting notes to the database (caller handles)</li>
 *   <li>Loading the profile entity (caller provides)</li>
 *   <li>Profile card display (see {@link ProfileCard})</li>
 *   <li>Page navigation (see {@link org.nodriver4j.ui.controllers.MainController})</li>
 * </ul>
 *
 * @see ProfileGroupDetailController
 * @see ProfileCard
 * @see CreateProfileGroupDialog
 */
public final class ProfileDetailDialog extends Dialog<String> {

    // ==================== Constants ====================

    private static final double LABEL_MIN_WIDTH = 120;
    private static final double DIALOG_PREF_WIDTH = 520;
    private static final double SCROLL_PREF_HEIGHT = 500;
    private static final Duration COPIED_FEEDBACK_DURATION = Duration.seconds(1.5);

    // ==================== UI Components ====================

    private final TextArea notesArea;

    // ==================== Data ====================

    private final ProfileEntity profile;

    // ==================== Constructor ====================

    /**
     * Creates a new ProfileDetailDialog.
     *
     * @param owner   the owner window (dialog will be centered on this)
     * @param profile the profile entity to display
     */
    public ProfileDetailDialog(Window owner, ProfileEntity profile) {
        this.profile = profile;

        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);

        // TRANSPARENT for rounded corners without white artifacts
        initStyle(StageStyle.TRANSPARENT);

        // Create the notes area before building content
        notesArea = createNotesArea();

        // Build and configure the dialog pane
        DialogPane dialogPane = getDialogPane();
        dialogPane.setContent(buildContent());
        dialogPane.getStyleClass().add("dialog-pane");

        // Apply dark theme stylesheet
        dialogPane.getStylesheets().add(
                getClass().getResource("/org/nodriver4j/ui/css/dark-theme.css").toExternalForm()
        );

        // Add buttons
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType doneButtonType = new ButtonType("Done", ButtonBar.ButtonData.OK_DONE);
        dialogPane.getButtonTypes().addAll(cancelButtonType, doneButtonType);

        // Style the buttons
        Button cancelBtn = (Button) dialogPane.lookupButton(cancelButtonType);
        cancelBtn.getStyleClass().add("secondary");

        Button doneBtn = (Button) dialogPane.lookupButton(doneButtonType);
        doneBtn.getStyleClass().add("primary");

        // Result converter: Done → notes text, Cancel → null
        setResultConverter(buttonType -> {
            if (buttonType == doneButtonType) {
                return notesArea.getText();
            }
            return null;
        });

        // Transparent fill for rounded corners
        setOnShown(event ->
                getDialogPane().getScene().setFill(Color.TRANSPARENT));
    }

    // ==================== Static Show Method ====================

    /**
     * Shows the dialog and waits for the user to submit or cancel.
     *
     * @param owner   the owner window (dialog is centered on this window)
     * @param profile the profile entity to display
     * @return an Optional containing the updated notes text if the user
     *         clicked Done, or empty if the user cancelled
     */
    public static Optional<String> show(Window owner, ProfileEntity profile) {
        ProfileDetailDialog dialog = new ProfileDetailDialog(owner, profile);
        return dialog.showAndWait();
    }

    // ==================== Content Building ====================

    /**
     * Builds the main content layout with all profile sections.
     *
     * @return the content VBox
     */
    private VBox buildContent() {
        // Dialog title: profile's display name
        Label titleLabel = new Label(profile.displayName());
        titleLabel.getStyleClass().add("dialog-title");

        // Build all sections
        VBox sectionsContainer = new VBox(20);
        sectionsContainer.getChildren().addAll(
                buildIdentitySection(),
                buildPaymentSection(),
                buildShippingSection(),
                buildBillingSection(),
                buildEmailAccessSection(),
                buildNotesSection()
        );

        // Scrollable container for the sections
        ScrollPane scrollPane = new ScrollPane(sectionsContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPrefViewportHeight(SCROLL_PREF_HEIGHT);
        scrollPane.setMaxHeight(SCROLL_PREF_HEIGHT);
        scrollPane.setStyle("-fx-background-color: transparent;");
        SmoothScrollHelper.apply(scrollPane);

        // Root content
        VBox content = new VBox(8);
        content.setPadding(new Insets(24));
        content.setPrefWidth(DIALOG_PREF_WIDTH);
        content.getChildren().addAll(titleLabel, scrollPane);

        return content;
    }

    // ==================== Section Builders ====================

    /**
     * Builds the Identity section showing profile name and email.
     */
    private VBox buildIdentitySection() {
        return buildSection("Identity",
                buildFieldRow("Profile Name", profile.profileName()),
                buildFieldRow("Email Address", profile.emailAddress())
        );
    }

    /**
     * Builds the Payment section showing card and checkout details.
     */
    private VBox buildPaymentSection() {
        String expiration = formatExpiration(profile.expirationMonth(), profile.expirationYear());

        return buildSection("Payment",
                buildFieldRow("Only One Checkout", profile.onlyOneCheckout() ? "Yes" : "No"),
                buildFieldRow("Name on Card", profile.nameOnCard()),
                buildFieldRow("Card Type", profile.cardType()),
                buildFieldRow("Card Number",
                        formatCardNumber(profile.cardNumber()),
                        stripWhitespace(profile.cardNumber())),
                buildFieldRow("Expiration", expiration),
                buildFieldRow("CVV", profile.cvv())
        );
    }

    /**
     * Builds the Shipping section showing the full shipping address.
     */
    private VBox buildShippingSection() {
        return buildSection("Shipping",
                buildFieldRow("Name", profile.shippingName()),
                buildFieldRow("Phone", profile.shippingPhone()),
                buildFieldRow("Address", profile.shippingAddress()),
                buildFieldRow("Address 2", profile.shippingAddress2()),
                buildFieldRow("Address 3", profile.shippingAddress3()),
                buildFieldRow("Post Code", profile.shippingPostCode()),
                buildFieldRow("City", profile.shippingCity()),
                buildFieldRow("State", profile.shippingState()),
                buildFieldRow("Country", profile.shippingCountry())
        );
    }

    /**
     * Builds the Billing section.
     *
     * <p>If billing is the same as shipping, shows a note instead of
     * repeating all address fields. Otherwise, shows full billing address.</p>
     */
    private VBox buildBillingSection() {
        if (profile.sameBillingShipping()) {
            VBox section = new VBox(6);
            section.getStyleClass().add("profile-detail-section");

            Label header = new Label("Billing");
            header.getStyleClass().add("profile-detail-section-header");

            Label note = new Label("Same as shipping address");
            note.getStyleClass().addAll("profile-detail-value", "muted");

            section.getChildren().addAll(header, note);
            return section;
        }

        return buildSection("Billing",
                buildFieldRow("Name", profile.billingName()),
                buildFieldRow("Phone", profile.billingPhone()),
                buildFieldRow("Address", profile.billingAddress()),
                buildFieldRow("Address 2", profile.billingAddress2()),
                buildFieldRow("Address 3", profile.billingAddress3()),
                buildFieldRow("Post Code", profile.billingPostCode()),
                buildFieldRow("City", profile.billingCity()),
                buildFieldRow("State", profile.billingState()),
                buildFieldRow("Country", profile.billingCountry())
        );
    }

    /**
     * Builds the Email Access section showing catchall and IMAP details.
     */
    private VBox buildEmailAccessSection() {
        return buildSection("Email Access",
                buildFieldRow("Catchall Email", profile.catchallEmail()),
                buildFieldRow("IMAP Password", profile.imapPassword())
        );
    }

    /**
     * Builds the Notes section with an editable TextArea.
     */
    private VBox buildNotesSection() {
        VBox section = new VBox(6);
        section.getStyleClass().add("profile-detail-section");

        Label header = new Label("Notes");
        header.getStyleClass().add("profile-detail-section-header");

        section.getChildren().addAll(header, notesArea);
        return section;
    }

    // ==================== Component Builders ====================

    /**
     * Builds a labeled section containing field rows.
     *
     * @param title the section header text
     * @param rows  the field rows to include (nulls are silently skipped)
     * @return the assembled section VBox
     */
    private VBox buildSection(String title, HBox... rows) {
        VBox section = new VBox(6);
        section.getStyleClass().add("profile-detail-section");

        Label header = new Label(title);
        header.getStyleClass().add("profile-detail-section-header");
        section.getChildren().add(header);

        for (HBox row : rows) {
            if (row != null) {
                section.getChildren().add(row);
            }
        }

        return section;
    }

    /**
     * Builds a field row where the displayed value and the copy value are the same.
     *
     * @param fieldName the label displayed on the left
     * @param value     the value displayed on the right (also the clipboard value)
     * @return the assembled row HBox
     */
    private HBox buildFieldRow(String fieldName, String value) {
        return buildFieldRow(fieldName, value, value);
    }

    /**
     * Builds a field row with a separate display value and clipboard copy value.
     *
     * <p>Used when the display format differs from the raw value the user
     * would want copied (e.g., card numbers formatted with spaces vs raw digits).</p>
     *
     * @param fieldName    the label displayed on the left
     * @param displayValue the formatted value shown to the user
     * @param copyValue    the raw value copied to clipboard on click
     * @return the assembled row HBox
     */
    private HBox buildFieldRow(String fieldName, String displayValue, String copyValue) {
        // Field name label (left side)
        Label nameLabel = new Label(fieldName);
        nameLabel.getStyleClass().add("profile-detail-label");
        nameLabel.setMinWidth(LABEL_MIN_WIDTH);
        nameLabel.setPrefWidth(LABEL_MIN_WIDTH);

        // Determine display text and whether the value is copyable
        boolean hasValue = copyValue != null && !copyValue.isBlank();
        String display = hasValue ? displayValue : "—";

        // Value label (right side, clickable if non-empty)
        Label valueLabel = new Label(display);
        valueLabel.getStyleClass().add("profile-detail-value");
        valueLabel.setWrapText(true);
        HBox.setHgrow(valueLabel, Priority.ALWAYS);

        if (hasValue) {
            valueLabel.setCursor(Cursor.HAND);
            valueLabel.setOnMouseClicked(event -> {
                copyToClipboard(copyValue);
                showCopiedFeedback(valueLabel, display);
                event.consume();
            });
        } else {
            valueLabel.getStyleClass().add("muted");
        }

        // Assemble row
        HBox row = new HBox(12);
        row.getStyleClass().add("profile-detail-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(nameLabel, valueLabel);

        return row;
    }

    /**
     * Creates the editable notes TextArea, pre-populated with existing notes.
     *
     * @return the configured TextArea
     */
    private TextArea createNotesArea() {
        TextArea area = new TextArea();
        area.setPromptText("Add notes about this profile...");
        area.setPrefRowCount(4);
        area.setWrapText(true);

        String existingNotes = profile.notes();
        if (existingNotes != null && !existingNotes.isBlank()) {
            area.setText(existingNotes);
        }

        SmoothScrollHelper.apply(area, 0.4, 2.0);
        return area;
    }

    // ==================== Clipboard ====================

    /**
     * Copies a string to the system clipboard.
     *
     * @param text the text to copy
     */
    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * Shows brief "Copied!" feedback on a value label, then reverts.
     *
     * <p>Temporarily replaces the label text with "Copied!" and applies
     * a highlight style class. After {@link #COPIED_FEEDBACK_DURATION},
     * the original text and styling are restored.</p>
     *
     * @param label        the label to animate
     * @param originalText the text to restore after the feedback period
     */
    private void showCopiedFeedback(Label label, String originalText) {
        label.setText("Copied!");
        label.getStyleClass().add("profile-detail-copied");

        PauseTransition pause = new PauseTransition(COPIED_FEEDBACK_DURATION);
        pause.setOnFinished(event -> {
            label.setText(originalText);
            label.getStyleClass().remove("profile-detail-copied");
        });
        pause.play();
    }

    // ==================== Formatting ====================

    /**
     * Formats a card number with spaces every 4 digits for readability.
     *
     * @param cardNumber the raw card number
     * @return the formatted number (e.g., "4111 1111 1111 1111"),
     *         or "—" if blank
     */
    private String formatCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) {
            return "—";
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
     * Formats expiration month and year as MM/YYYY.
     *
     * @param month the expiration month
     * @param year  the expiration year
     * @return the formatted string (e.g., "02/2026")
     */
    private String formatExpiration(String month, String year) {
        boolean hasMonth = month != null && !month.isBlank();
        boolean hasYear = year != null && !year.isBlank();

        if (!hasMonth && !hasYear) {
            return "";
        }

        String m = hasMonth ? month : "••";
        String y = hasYear ? year : "••••";
        return m + "/" + y;
    }

    /**
     * Strips all whitespace and dashes from a string.
     *
     * <p>Used to produce a clean clipboard value for card numbers.</p>
     *
     * @param value the string to strip
     * @return the stripped string, or null if input is null
     */
    private String stripWhitespace(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[\\s\\-]", "");
    }
}