package org.nodriver4j.ui.settings;

import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.nodriver4j.persistence.Settings;
import org.nodriver4j.ui.controllers.MainController;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Controller for the Settings page.
 *
 * <p>Provides a UI for viewing and editing application settings that are
 * persisted via {@link Settings}. Editable fields are populated from the
 * current settings on initialization and written back on save.</p>
 *
 * <h2>SMS Provider Section</h2>
 * <p>The SMS provider section uses a ComboBox dropdown to select between
 * TextVerified, SMS-Man, and DaisySMS. The input fields change dynamically
 * based on the selection:</p>
 * <ul>
 *   <li><b>TextVerified:</b> Shows an email field and an API key field
 *       (two credentials required for bearer token auth)</li>
 *   <li><b>SMS-Man:</b> Shows a single API key field</li>
 *   <li><b>DaisySMS:</b> Shows a single API key field</li>
 * </ul>
 * <p>All provider credentials are saved simultaneously regardless of which
 * provider is currently selected in the dropdown — switching providers does
 * not discard previously entered credentials.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Load current settings into form fields</li>
 *   <li>Write form field values back to Settings on save</li>
 *   <li>Dynamically show/hide SMS provider fields based on dropdown</li>
 *   <li>Display read-only path information</li>
 *   <li>Show save confirmation feedback</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Settings file I/O (delegated to {@link Settings})</li>
 *   <li>Validation of setting values</li>
 *   <li>Navigation (handled by {@link MainController})</li>
 * </ul>
 *
 * @see Settings
 */
public class SettingsController implements Initializable {

    private static final double SAVE_FEEDBACK_SECONDS = 3.0;

    // ==================== SMS Provider Enum ====================

    /**
     * Enum for the SMS provider dropdown. Determines which fields are
     * visible when a provider is selected.
     */
    private enum SmsProviderOption {
        TEXT_VERIFIED("TextVerified"),
        SMS_MAN("SMS-Man"),
        DAISY_SMS("DaisySMS");

        private final String displayName;

        SmsProviderOption(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    // ==================== FXML Injected Fields ====================

    @FXML
    private TextField chromePathField;

    @FXML
    private CheckBox fingerprintMonitoringCheckBox;

    @FXML
    private TextField autoSolveApiKeyField;

    @FXML
    private TextField capsolverApiKeyField;

    @FXML
    private TextField twoCaptchaApiKeyField;

    @FXML
    private ComboBox<SmsProviderOption> smsProviderComboBox;

    @FXML
    private VBox smsFieldsContainer;

    @FXML
    private Label fingerprintsPathLabel;

    @FXML
    private Label userdataPathLabel;

    @FXML
    private Label saveStatusLabel;

    // ==================== Dynamic SMS Fields ====================

    /**
     * Email field shown only for TextVerified (which requires email + API key).
     */
    private TextField smsEmailField;

    /**
     * API key field shown for all SMS providers. The prompt text changes
     * based on the selected provider.
     */
    private TextField smsApiKeyField;

    /**
     * Container for the email field — shown/hidden based on provider selection.
     */
    private VBox smsEmailGroup;

    /**
     * Container for the API key field — always visible when a provider is selected.
     */
    private VBox smsApiKeyGroup;

    // ==================== Internal State ====================

    /**
     * Timer used to clear the save status label after a brief delay.
     */
    private PauseTransition saveStatusTimer;

    /**
     * Tracks the currently visible provider so field values can be
     * stashed before switching.
     */
    private SmsProviderOption currentProvider;

    // ==================== Stashed Credentials ====================

    /*
     * Each provider's credentials are stashed in memory so switching
     * the dropdown doesn't lose previously entered values. On save,
     * the stashed values for ALL providers are written to Settings
     * (not just the currently visible one).
     */
    private String stashedTextVerifiedEmail = "";
    private String stashedTextVerifiedApiKey = "";
    private String stashedSmsManApiKey = "";
    private String stashedDaisySmsApiKey = "";

    // ==================== Initialization ====================

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        System.out.println("[SettingsController] Initializing...");

        saveStatusTimer = new PauseTransition(Duration.seconds(SAVE_FEEDBACK_SECONDS));
        saveStatusTimer.setOnFinished(e -> saveStatusLabel.setText(""));

        buildSmsFields();
        setupSmsProviderComboBox();
        loadSettings();

        System.out.println("[SettingsController] Initialized successfully");
    }

    // ==================== SMS Provider UI ====================

    /**
     * Creates the dynamic SMS input fields programmatically.
     *
     * <p>Two form groups are created: one for the email (TextVerified only)
     * and one for the API key (all providers). Both are added to the
     * {@code smsFieldsContainer} VBox defined in FXML.</p>
     */
    private void buildSmsFields() {
        // Email group (TextVerified only)
        Label emailLabel = new Label("Account Email");
        emailLabel.getStyleClass().add("form-label");

        smsEmailField = new TextField();
        smsEmailField.setPromptText("Enter your TextVerified email");

        smsEmailGroup = new VBox(0);
        smsEmailGroup.getStyleClass().add("form-group");
        smsEmailGroup.getChildren().addAll(emailLabel, smsEmailField);

        // API key group (all providers)
        Label apiKeyLabel = new Label("API Key");
        apiKeyLabel.getStyleClass().add("form-label");

        smsApiKeyField = new TextField();
        smsApiKeyField.setPromptText("Enter your API key");

        smsApiKeyGroup = new VBox(0);
        smsApiKeyGroup.getStyleClass().add("form-group");
        smsApiKeyGroup.getChildren().addAll(apiKeyLabel, smsApiKeyField);

        // Add both to the container (visibility controlled by updateSmsFields)
        smsFieldsContainer.getChildren().addAll(smsEmailGroup, smsApiKeyGroup);
    }

    /**
     * Configures the SMS provider ComboBox with a StringConverter and
     * a change listener that swaps the visible fields.
     */
    private void setupSmsProviderComboBox() {
        smsProviderComboBox.getItems().addAll(SmsProviderOption.values());
        smsProviderComboBox.setPrefHeight(40);
        smsProviderComboBox.setMaxWidth(Double.MAX_VALUE);

        smsProviderComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(SmsProviderOption option) {
                return option != null ? option.toString() : "";
            }

            @Override
            public SmsProviderOption fromString(String string) {
                return null; // Not editable
            }
        });

        smsProviderComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (oldVal != null) {
                stashCurrentFields(oldVal);
            }
            currentProvider = newVal;
            updateSmsFields(newVal);
        });
    }

    /**
     * Updates the visible SMS fields based on the selected provider.
     *
     * <p>TextVerified shows both the email and API key fields. SMS-Man
     * and DaisySMS show only the API key field. The prompt text on the
     * API key field is updated to reflect the selected provider.</p>
     *
     * @param provider the selected provider
     */
    private void updateSmsFields(SmsProviderOption provider) {
        if (provider == null) {
            smsEmailGroup.setVisible(false);
            smsEmailGroup.setManaged(false);
            smsApiKeyGroup.setVisible(false);
            smsApiKeyGroup.setManaged(false);
            return;
        }

        switch (provider) {
            case TEXT_VERIFIED -> {
                smsEmailGroup.setVisible(true);
                smsEmailGroup.setManaged(true);
                smsApiKeyGroup.setVisible(true);
                smsApiKeyGroup.setManaged(true);
                smsApiKeyField.setPromptText("Enter your TextVerified API key");
                smsEmailField.setText(stashedTextVerifiedEmail);
                smsApiKeyField.setText(stashedTextVerifiedApiKey);
            }
            case SMS_MAN -> {
                smsEmailGroup.setVisible(false);
                smsEmailGroup.setManaged(false);
                smsApiKeyGroup.setVisible(true);
                smsApiKeyGroup.setManaged(true);
                smsApiKeyField.setPromptText("Enter your SMS-Man API token");
                smsApiKeyField.setText(stashedSmsManApiKey);
            }
            case DAISY_SMS -> {
                smsEmailGroup.setVisible(false);
                smsEmailGroup.setManaged(false);
                smsApiKeyGroup.setVisible(true);
                smsApiKeyGroup.setManaged(true);
                smsApiKeyField.setPromptText("Enter your DaisySMS API key");
                smsApiKeyField.setText(stashedDaisySmsApiKey);
            }
        }
    }

    /**
     * Stashes the current field values for the given provider before
     * switching to a different one.
     *
     * @param provider the provider whose fields should be stashed
     */
    private void stashCurrentFields(SmsProviderOption provider) {
        switch (provider) {
            case TEXT_VERIFIED -> {
                stashedTextVerifiedEmail = smsEmailField.getText().trim();
                stashedTextVerifiedApiKey = smsApiKeyField.getText().trim();
            }
            case SMS_MAN -> stashedSmsManApiKey = smsApiKeyField.getText().trim();
            case DAISY_SMS -> stashedDaisySmsApiKey = smsApiKeyField.getText().trim();
        }
    }

    // ==================== Actions ====================

    /**
     * Handles the Save button click.
     *
     * <p>Reads values from all editable fields, applies them to the
     * {@link Settings} singleton, and persists to disk. Shows a brief
     * confirmation message on success or an error message on failure.</p>
     *
     * <p>For SMS providers, stashes the currently visible fields first,
     * then saves all stashed credentials to Settings — ensuring all
     * provider keys are persisted regardless of which dropdown option
     * is currently selected.</p>
     */
    @FXML
    private void onSaveClicked() {
        try {
            Settings settings = Settings.get();

            // Browser
            settings.chromePath(chromePathField.getText().trim());
            settings.fingerprintMonitoringEnabled(fingerprintMonitoringCheckBox.isSelected());

            // Captcha API Keys
            settings.autoSolveApiKey(autoSolveApiKeyField.getText().trim());
            settings.capsolverApiKey(capsolverApiKeyField.getText().trim());
            settings.twoCaptchaApiKey(twoCaptchaApiKeyField.getText().trim());

            // SMS Provider Keys — stash current fields first, then save all
            if (currentProvider != null) {
                stashCurrentFields(currentProvider);
            }
            settings.textVerifiedApiKey(stashedTextVerifiedApiKey);
            settings.textVerifiedEmail(stashedTextVerifiedEmail);
            settings.smsManApiKey(stashedSmsManApiKey);
            settings.daisySmsApiKey(stashedDaisySmsApiKey);

            Settings.save();

            showSaveStatus("Saved", "-fx-text-fill: #4ade80;");
            System.out.println("[SettingsController] Settings saved");

        } catch (Exception e) {
            showSaveStatus("Save failed: " + e.getMessage(), "-fx-text-fill: #f87171;");
            System.err.println("[SettingsController] Failed to save settings: " + e.getMessage());
        }
    }

    // ==================== Internal Methods ====================

    /**
     * Populates all UI fields from the current {@link Settings}.
     */
    private void loadSettings() {
        Settings settings = Settings.get();

        // Browser
        chromePathField.setText(settings.chromePath() != null ? settings.chromePath() : "");
        fingerprintMonitoringCheckBox.setSelected(settings.fingerprintMonitoringEnabled());

        // Captcha API Keys
        autoSolveApiKeyField.setText(settings.autoSolveApiKey() != null ? settings.autoSolveApiKey() : "");
        capsolverApiKeyField.setText(settings.capsolverApiKey() != null ? settings.capsolverApiKey() : "");
        twoCaptchaApiKeyField.setText(settings.twoCaptchaApiKey() != null ? settings.twoCaptchaApiKey() : "");

        // SMS Provider Keys — load into stash
        stashedTextVerifiedApiKey = settings.textVerifiedApiKey() != null ? settings.textVerifiedApiKey() : "";
        stashedTextVerifiedEmail = settings.textVerifiedEmail() != null ? settings.textVerifiedEmail() : "";
        stashedSmsManApiKey = settings.smsManApiKey() != null ? settings.smsManApiKey() : "";
        stashedDaisySmsApiKey = settings.daisySmsApiKey() != null ? settings.daisySmsApiKey() : "";

        // Default to TextVerified in the dropdown
        smsProviderComboBox.getSelectionModel().select(SmsProviderOption.TEXT_VERIFIED);

        // Read-only labels
        fingerprintsPathLabel.setText(settings.fingerprintsPath() != null ? settings.fingerprintsPath() : "Not configured");
        userdataPathLabel.setText(settings.userdataBasePath() != null ? settings.userdataBasePath() : "Not configured");
    }

    /**
     * Shows a temporary status message next to the Save button.
     *
     * @param message the message to display
     * @param style   the CSS style for the label (e.g., green for success, red for error)
     */
    private void showSaveStatus(String message, String style) {
        saveStatusLabel.setText(message);
        saveStatusLabel.setStyle("-fx-font-size: 13px; -fx-padding: 0 0 0 12px; " + style);

        // Reset and restart the timer to clear the message
        saveStatusTimer.stop();
        saveStatusTimer.playFromStart();
    }
}