package org.nodriver4j.ui.dialogs;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.nodriver4j.scripts.ScriptRegistry;

import java.util.List;

/**
 * Dialog for creating a new task group.
 *
 * <p>Prompts the user to enter a group name and select a script.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display form for group name and script selection</li>
 *   <li>Validate input (name required, script required)</li>
 *   <li>Return TaskGroupData on success, empty on cancel</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Actually creating the task group (returns data only)</li>
 *   <li>Persisting data</li>
 *   <li>Managing scripts (uses hardcoded list)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Window owner = someNode.getScene().getWindow();
 * CreateTaskGroupDialog dialog = new CreateTaskGroupDialog(owner);
 * Optional<TaskGroupData> result = dialog.showAndWait();
 * result.ifPresent(data -> {
 *     System.out.println("Name: " + data.name());
 *     System.out.println("Script: " + data.script());
 * });
 * }</pre>
 */
public class CreateTaskGroupDialog extends Dialog<CreateTaskGroupDialog.TaskGroupData> {

    // ==================== UI Components ====================

    private final TextField nameField;
    private final ComboBox<String> scriptComboBox;
    private final Label errorLabel;
    private final Button createButton;

    // ==================== Constructor ====================

    /**
     * Creates a new CreateTaskGroupDialog with an owner window.
     *
     * @param owner the owner window (dialog will be centered on this and move with it)
     */
    public CreateTaskGroupDialog(Window owner) {
        // Set owner for proper positioning
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);

        // Configure dialog
        setTitle("Create Task Group");
        initStyle(StageStyle.TRANSPARENT);


        // Create UI components
        nameField = createNameField();
        scriptComboBox = createScriptComboBox();
        errorLabel = createErrorLabel();
        createButton = createCreateButton();

        // Build content
        DialogPane dialogPane = getDialogPane();
        dialogPane.setContent(buildContent());
        dialogPane.getStyleClass().add("dialog-pane");

        // Apply stylesheet
        dialogPane.getStylesheets().add(
                getClass().getResource("../css/dark-theme.css").toExternalForm()
        );

        // Add buttons
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType createButtonType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialogPane.getButtonTypes().addAll(cancelButtonType, createButtonType);

        // Style the buttons
        Button cancelBtn = (Button) dialogPane.lookupButton(cancelButtonType);
        cancelBtn.getStyleClass().add("secondary");

        Button createBtn = (Button) dialogPane.lookupButton(createButtonType);
        createBtn.getStyleClass().add("primary");

        // Disable create button initially and bind to validation
        createBtn.setDisable(true);

        // Add validation listeners
        nameField.textProperty().addListener((obs, oldVal, newVal) -> {
            validateAndUpdateButton(createBtn);
        });

        scriptComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            validateAndUpdateButton(createBtn);
        });

        // Set result converter
        setResultConverter(buttonType -> {
            if (buttonType == createButtonType) {
                return new TaskGroupData(
                        nameField.getText().trim(),
                        ScriptRegistry.internalName(scriptComboBox.getValue())
                );
            }
            return null;
        });

        // Add this near the end of the constructor, after configuring the DialogPane
        setOnShown(event -> {
            getDialogPane().getScene().setFill(Color.TRANSPARENT);
        });

        // Focus name field when dialog opens
        Platform.runLater(() -> nameField.requestFocus());
    }

    // ==================== UI Building ====================

    private VBox buildContent() {
        VBox content = new VBox();
        content.setSpacing(16);
        content.setPadding(new Insets(24));
        content.setPrefWidth(400);

        // Title
        Label title = new Label("Create Task Group");
        title.getStyleClass().add("dialog-title");

        // Name field group
        VBox nameGroup = createFormGroup("Group Name", nameField);

        // Script dropdown group
        VBox scriptGroup = createFormGroup("Script", scriptComboBox);

        content.getChildren().addAll(title, nameGroup, scriptGroup, errorLabel);

        return content;
    }

    private VBox createFormGroup(String labelText, Control control) {
        VBox group = new VBox();
        group.getStyleClass().add("form-group");
        group.setSpacing(6);

        Label label = new Label(labelText);
        label.getStyleClass().add("form-label");

        group.getChildren().addAll(label, control);

        return group;
    }

    private TextField createNameField() {
        TextField field = new TextField();
        field.setPromptText("Enter group name...");
        field.setPrefHeight(40);
        return field;
    }

    private ComboBox<String> createScriptComboBox() {
        ComboBox<String> comboBox = new ComboBox<>();
        comboBox.getItems().addAll(ScriptRegistry.displayNames());
        comboBox.setPromptText("Select a script...");
        comboBox.setPrefHeight(40);
        comboBox.setMaxWidth(Double.MAX_VALUE);
        return comboBox;
    }

    private Label createErrorLabel() {
        Label label = new Label();
        label.setStyle("-fx-text-fill: #f87171; -fx-font-size: 12px;");
        label.setVisible(false);
        label.setManaged(false);
        return label;
    }

    private Button createCreateButton() {
        Button button = new Button("Create");
        button.getStyleClass().add("primary");
        button.setDefaultButton(true);
        return button;
    }

    // ==================== Validation ====================

    /**
     * Validates the form and updates the create button state.
     *
     * @param createBtn the create button to enable/disable
     */
    private void validateAndUpdateButton(Button createBtn) {
        String name = nameField.getText().trim();
        String script = scriptComboBox.getValue();

        boolean isValid = !name.isEmpty() && script != null && !script.isEmpty();

        createBtn.setDisable(!isValid);

        // Update error message
        if (name.isEmpty() && !nameField.getText().isEmpty()) {
            showError("Group name cannot be blank");
        } else {
            hideError();
        }
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    // ==================== Result Data ====================

    /**
     * Data returned when a task group is created.
     *
     * @param name   the group name
     * @param script the selected script name
     */
    public record TaskGroupData(String name, String script) {

        /**
         * Creates TaskGroupData with validation.
         *
         * @param name   the group name (must not be blank)
         * @param script the script name (must not be null)
         */
        public TaskGroupData {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Name cannot be null or blank");
            }
            if (script == null || script.isBlank()) {
                throw new IllegalArgumentException("Script cannot be null or blank");
            }
        }
    }
}