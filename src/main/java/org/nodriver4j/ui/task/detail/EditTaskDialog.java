package org.nodriver4j.ui.task.detail;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Modal dialog for editing an existing task's proxy and custom status.
 *
 * <p>Provides two editable fields:</p>
 * <ul>
 *   <li><strong>Proxy string</strong> — raw proxy in {@code host:port:username:password}
 *       format. An empty field means no proxy. Validated on submit.</li>
 *   <li><strong>Custom status</strong> — a short label displayed beside the task name
 *       in the UI (max 20 characters). An empty field clears the custom status.</li>
 * </ul>
 *
 * <p>Both fields are pre-populated with the task's current values when the
 * dialog opens. The dialog returns a {@link Result} record containing the
 * raw field values; the controller is responsible for parsing the proxy
 * string, creating/replacing proxy entities, and persisting changes.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * EditTaskDialog dialog = new EditTaskDialog(
 *     ownerWindow,
 *     "proxy.example.com:8080:user:pass",  // current proxy string or null
 *     "Waiting for OTP"                      // current custom status or null
 * );
 *
 * Optional<EditTaskDialog.Result> result = dialog.showAndWait();
 * result.ifPresent(r -> {
 *     // r.proxyString()  — new proxy string (null/blank = no proxy)
 *     // r.customStatus() — new custom status (null/blank = no custom status)
 * });
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display form with pre-populated proxy and custom status fields</li>
 *   <li>Validate proxy format when non-empty (host:port:user:pass, valid port)</li>
 *   <li>Enforce 20-character limit on custom status</li>
 *   <li>Show inline validation errors</li>
 *   <li>Return result as immutable record</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>ProxyEntity creation or cleanup (controller handles)</li>
 *   <li>Database persistence (controller handles)</li>
 *   <li>TaskEntity updates (controller handles)</li>
 *   <li>TaskRow UI refresh (controller handles)</li>
 * </ul>
 *
 * @see CreateTaskDialog
 */
public class EditTaskDialog extends Dialog<EditTaskDialog.Result> {

    // ==================== Result Record ====================

    /**
     * Immutable result of a successful dialog submission.
     *
     * @param proxyString  the raw proxy string (null or blank means no proxy)
     * @param customStatus the custom status text (null or blank means no custom status)
     */
    public record Result(
            String proxyString,
            String customStatus
    ) {}

    // ==================== Constants ====================

    /** Maximum allowed length for the custom status field. */
    private static final int MAX_CUSTOM_STATUS_LENGTH = 20;

    // ==================== UI Components ====================

    private final TextField proxyField;
    private final TextField customStatusField;
    private final Label proxyErrorLabel;
    private final Label statusCharCount;
    private Button saveButton;

    // ==================== Constructor ====================

    /**
     * Creates a new EditTaskDialog.
     *
     * @param owner              the owner window (dialog will be centered on this)
     * @param currentProxyString the task's current proxy string, or null if none
     * @param currentCustomStatus the task's current custom status, or null if none
     */
    public EditTaskDialog(Window owner, String currentProxyString, String currentCustomStatus) {
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        initStyle(StageStyle.TRANSPARENT);

        // Create fields
        proxyField = createProxyField(currentProxyString);
        customStatusField = createCustomStatusField(currentCustomStatus);
        proxyErrorLabel = createErrorLabel();
        statusCharCount = createCharCountLabel(currentCustomStatus);

        // Build content
        DialogPane dialogPane = getDialogPane();
        dialogPane.setContent(buildContent());
        dialogPane.getStyleClass().add("dialog-pane");

        // Apply stylesheet
        dialogPane.getStylesheets().add(
                getClass().getResource("/org/nodriver4j/ui/css/dark-theme.css").toExternalForm()
        );

        // Add buttons
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType saveButtonType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialogPane.getButtonTypes().addAll(cancelButtonType, saveButtonType);

        // Style buttons
        Button cancelBtn = (Button) dialogPane.lookupButton(cancelButtonType);
        cancelBtn.getStyleClass().add("secondary");

        saveButton = (Button) dialogPane.lookupButton(saveButtonType);
        saveButton.getStyleClass().add("primary");

        // Intercept save to validate before closing
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!validate()) {
                event.consume();
            }
        });

        // Set result converter
        setResultConverter(buttonType -> {
            if (buttonType == saveButtonType) {
                return buildResult();
            }
            return null;
        });

        // Transparent scene for rounded corners
        setOnShown(event -> getDialogPane().getScene().setFill(Color.TRANSPARENT));
    }

    // ==================== UI Building ====================

    /**
     * Builds the main content layout.
     *
     * @return the content VBox
     */
    private VBox buildContent() {
        VBox content = new VBox(4);
        content.setPadding(new Insets(24));
        content.setPrefWidth(420);

        // Title
        Label titleLabel = new Label("Edit Task");
        titleLabel.getStyleClass().add("dialog-title");

        content.getChildren().addAll(
                titleLabel,
                buildProxySection(),
                buildCustomStatusSection()
        );

        return content;
    }

    /**
     * Builds the proxy string input section.
     *
     * @return the proxy section VBox
     */
    private VBox buildProxySection() {
        Label label = new Label("Proxy");
        label.getStyleClass().add("form-label");

        Label hintLabel = new Label("Format: host:port:username:password");
        hintLabel.getStyleClass().addAll("label", "muted");
        hintLabel.setStyle("-fx-font-size: 11px;");

        VBox section = new VBox(6);
        section.getStyleClass().add("form-group");
        section.getChildren().addAll(label, proxyField, hintLabel, proxyErrorLabel);

        return section;
    }

    /**
     * Builds the custom status input section with character counter.
     *
     * @return the custom status section VBox
     */
    private VBox buildCustomStatusSection() {
        Label label = new Label("Custom Status");
        label.getStyleClass().add("form-label");

        Label hintLabel = new Label("Displayed beside the task name in the UI");
        hintLabel.getStyleClass().addAll("label", "muted");
        hintLabel.setStyle("-fx-font-size: 11px;");

        // Align character count to the right
        statusCharCount.setAlignment(Pos.CENTER_RIGHT);
        statusCharCount.setMaxWidth(Double.MAX_VALUE);

        VBox section = new VBox(6);
        section.getStyleClass().add("form-group");
        section.getChildren().addAll(label, customStatusField, hintLabel, statusCharCount);

        return section;
    }

    // ==================== Component Creation ====================

    /**
     * Creates the proxy input field, pre-populated with the current value.
     *
     * @param currentProxy the current proxy string, or null
     * @return the configured TextField
     */
    private TextField createProxyField(String currentProxy) {
        TextField field = new TextField();
        field.setPromptText("host:port:username:password");
        field.setPrefHeight(40);

        if (currentProxy != null && !currentProxy.isBlank()) {
            field.setText(currentProxy);
        }

        // Clear error on edit
        field.textProperty().addListener((obs, oldVal, newVal) -> clearProxyError());

        return field;
    }

    /**
     * Creates the custom status input field with a max-length enforcer.
     *
     * @param currentStatus the current custom status, or null
     * @return the configured TextField
     */
    private TextField createCustomStatusField(String currentStatus) {
        TextField field = new TextField();
        field.setPromptText("e.g. Entering OTP...");
        field.setPrefHeight(40);

        if (currentStatus != null && !currentStatus.isBlank()) {
            field.setText(currentStatus);
        }

        // Enforce max length and update character counter
        field.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.length() > MAX_CUSTOM_STATUS_LENGTH) {
                field.setText(oldVal);
            } else {
                updateCharCount(newVal);
            }
        });

        return field;
    }

    /**
     * Creates the error label for proxy validation messages.
     *
     * @return the configured Label (starts hidden)
     */
    private Label createErrorLabel() {
        Label label = new Label();
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: -fx-error;");
        label.setVisible(false);
        label.setManaged(false);
        return label;
    }

    /**
     * Creates the character count label for the custom status field.
     *
     * @param currentStatus the initial status text to count
     * @return the configured Label
     */
    private Label createCharCountLabel(String currentStatus) {
        int len = (currentStatus != null) ? currentStatus.length() : 0;
        Label label = new Label(len + " / " + MAX_CUSTOM_STATUS_LENGTH);
        label.getStyleClass().addAll("label", "muted");
        label.setStyle("-fx-font-size: 11px;");
        return label;
    }

    // ==================== Validation ====================

    /**
     * Validates the form fields before submission.
     *
     * <p>The proxy field is only validated if non-empty. An empty proxy
     * is valid and means "no proxy". The custom status field is already
     * length-enforced by the text listener.</p>
     *
     * @return true if the form is valid
     */
    private boolean validate() {
        String proxyText = proxyField.getText();

        if (proxyText == null || proxyText.isBlank()) {
            return true; // Empty proxy is valid (means no proxy)
        }

        return validateProxyString(proxyText.trim());
    }

    /**
     * Validates a proxy string in {@code host:port:username:password} format.
     *
     * @param proxyString the proxy string to validate
     * @return true if valid
     */
    private boolean validateProxyString(String proxyString) {
        String[] parts = proxyString.split(":", 4);

        if (parts.length != 4) {
            showProxyError("Expected format: host:port:username:password");
            return false;
        }

        // Validate host
        if (parts[0].isBlank()) {
            showProxyError("Host cannot be empty");
            return false;
        }

        // Validate port
        try {
            int port = Integer.parseInt(parts[1].trim());
            if (port < 1 || port > 65535) {
                showProxyError("Port must be between 1 and 65535");
                return false;
            }
        } catch (NumberFormatException e) {
            showProxyError("Invalid port number: " + parts[1]);
            return false;
        }

        // Validate username
        if (parts[2].isBlank()) {
            showProxyError("Username cannot be empty");
            return false;
        }

        // Validate password
        if (parts[3].isBlank()) {
            showProxyError("Password cannot be empty");
            return false;
        }

        return true;
    }

    // ==================== Error Display ====================

    /**
     * Shows a proxy validation error message.
     *
     * @param message the error message to display
     */
    private void showProxyError(String message) {
        proxyErrorLabel.setText(message);
        proxyErrorLabel.setVisible(true);
        proxyErrorLabel.setManaged(true);
    }

    /**
     * Clears any displayed proxy validation error.
     */
    private void clearProxyError() {
        proxyErrorLabel.setVisible(false);
        proxyErrorLabel.setManaged(false);
    }

    /**
     * Updates the character count label for the custom status field.
     *
     * @param text the current field text
     */
    private void updateCharCount(String text) {
        int len = (text != null) ? text.length() : 0;
        statusCharCount.setText(len + " / " + MAX_CUSTOM_STATUS_LENGTH);

        if (len >= MAX_CUSTOM_STATUS_LENGTH) {
            statusCharCount.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-warning;");
        } else {
            statusCharCount.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-muted;");
        }
    }

    // ==================== Result Building ====================

    /**
     * Builds the result from the current form state.
     *
     * <p>Trims both fields. Blank values are normalized to null.</p>
     *
     * @return the Result record
     */
    private Result buildResult() {
        String proxyText = proxyField.getText();
        String proxyString = (proxyText != null && !proxyText.isBlank())
                ? proxyText.trim()
                : null;

        String statusText = customStatusField.getText();
        String customStatus = (statusText != null && !statusText.isBlank())
                ? statusText.trim()
                : null;

        return new Result(proxyString, customStatus);
    }
}