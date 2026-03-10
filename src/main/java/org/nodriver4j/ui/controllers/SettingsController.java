package org.nodriver4j.ui.controllers;

import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.util.Duration;
import org.nodriver4j.persistence.Settings;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Controller for the Settings page.
 *
 * <p>Provides a UI for viewing and editing application settings that are
 * persisted via {@link Settings}. Editable fields are populated from the
 * current settings on initialization and written back on save.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Load current settings into form fields</li>
 *   <li>Write form field values back to Settings on save</li>
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

    // ==================== FXML Injected Fields ====================

    @FXML
    private TextField chromePathField;

    @FXML
    private TextField autoSolveApiKeyField;

    @FXML
    private TextField capsolverApiKeyField;

    @FXML
    private Label fingerprintsPathLabel;

    @FXML
    private Label userdataPathLabel;

    @FXML
    private Label saveStatusLabel;

    // ==================== Internal State ====================

    /**
     * Timer used to clear the save status label after a brief delay.
     */
    private PauseTransition saveStatusTimer;

    // ==================== Initialization ====================

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        System.out.println("[SettingsController] Initializing...");

        saveStatusTimer = new PauseTransition(Duration.seconds(SAVE_FEEDBACK_SECONDS));
        saveStatusTimer.setOnFinished(e -> saveStatusLabel.setText(""));

        loadSettings();

        System.out.println("[SettingsController] Initialized successfully");
    }

    // ==================== Actions ====================

    /**
     * Handles the Save button click.
     *
     * <p>Reads values from all editable fields, applies them to the
     * {@link Settings} singleton, and persists to disk. Shows a brief
     * confirmation message on success or an error message on failure.</p>
     */
    @FXML
    private void onSaveClicked() {
        try {
            Settings settings = Settings.get();

            settings.chromePath(chromePathField.getText().trim());
            settings.autoSolveApiKey(autoSolveApiKeyField.getText().trim());
            settings.capsolverApiKey(capsolverApiKeyField.getText().trim());

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

        // Editable fields
        chromePathField.setText(settings.chromePath() != null ? settings.chromePath() : "");
        autoSolveApiKeyField.setText(settings.autoSolveApiKey() != null ? settings.autoSolveApiKey() : "");
        capsolverApiKeyField.setText(settings.capsolverApiKey() != null ? settings.capsolverApiKey() : "");

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