package org.nodriver4j.ui.dialogs;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Modal dialog for creating a new profile group by importing a CSV file.
 *
 * <p>Collects the following from the user:</p>
 * <ol>
 *   <li><strong>CSV file</strong> — selected via a native file chooser,
 *       filtered to {@code .csv} files</li>
 *   <li><strong>Group name</strong> — auto-populated from the filename
 *       (without extension) but editable by the user</li>
 * </ol>
 *
 * <p>The dialog validates that both a file is selected and the group name
 * is non-blank before enabling the Create button.</p>
 *
 * <p>This dialog extends {@link Dialog} and uses {@link DialogPane} to
 * ensure consistent styling with other dialogs in the application
 * (e.g., {@link CreateTaskGroupDialog}). The dark theme CSS selectors
 * for {@code .dialog-pane}, {@code .dialog-pane > .button-bar}, and
 * {@code .dialog-pane > .content} all apply correctly.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Optional<CreateProfileGroupDialog.Result> result =
 *     CreateProfileGroupDialog.show(ownerWindow);
 *
 * result.ifPresent(r -> {
 *     System.out.println("Name: " + r.groupName());
 *     System.out.println("CSV: " + r.csvPath());
 * });
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display a styled modal dialog for profile group creation</li>
 *   <li>Collect group name and CSV file path</li>
 *   <li>Auto-populate group name from selected filename</li>
 *   <li>Validate inputs before submission</li>
 *   <li>Return result as an immutable record</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Parsing the CSV file (use {@link org.nodriver4j.persistence.importer.ProfileImporter})</li>
 *   <li>Database persistence (controller handles)</li>
 *   <li>Profile group card creation (controller handles)</li>
 * </ul>
 *
 * @see CreateTaskGroupDialog
 * @see org.nodriver4j.persistence.importer.ProfileImporter
 */
public final class CreateProfileGroupDialog extends Dialog<CreateProfileGroupDialog.Result> {

    // ==================== Result Record ====================

    /**
     * Immutable result of a successful profile group creation dialog.
     *
     * @param groupName the user-specified group name
     * @param csvPath   the path to the selected CSV file
     */
    public record Result(String groupName, Path csvPath) {}

    // ==================== UI Components ====================

    private final TextField nameField;
    private final Label fileNameLabel;

    // ==================== State ====================

    /**
     * The selected CSV file, or null if no file has been chosen.
     */
    private File selectedFile;

    // ==================== Constructor ====================

    /**
     * Creates a new CreateProfileGroupDialog with an owner window.
     *
     * @param owner the owner window (dialog will be centered on this)
     */
    public CreateProfileGroupDialog(Window owner) {
        // Set owner for proper positioning
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);

        // TRANSPARENT for rounded corners without white artifacts
        initStyle(StageStyle.TRANSPARENT);

        // Create UI components
        nameField = createNameField();
        fileNameLabel = createFileNameLabel();

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

        // Disable create button initially
        createBtn.setDisable(true);

        // Add validation listener
        nameField.textProperty().addListener((obs, oldVal, newVal) ->
                validateAndUpdateButton(createBtn));

        // Set result converter
        setResultConverter(buttonType -> {
            if (buttonType == createButtonType && selectedFile != null) {
                return new Result(
                        nameField.getText().trim(),
                        selectedFile.toPath()
                );
            }
            return null;
        });

        setOnShown(event -> {
            getDialogPane().getScene().setFill(Color.TRANSPARENT);
        });


        // Focus name field when dialog opens
        Platform.runLater(() -> nameField.requestFocus());
    }

    // ==================== Static Show Method ====================

    /**
     * Shows the dialog and waits for the user to submit or cancel.
     *
     * <p>Convenience method that creates the dialog, shows it modally,
     * and returns the result. Matches the calling convention used by
     * other dialogs in the application.</p>
     *
     * @param owner the owner window (dialog is centered on this window)
     * @return an Optional containing the result if the user clicked Create,
     *         or empty if the user cancelled
     */
    public static Optional<Result> show(Window owner) {
        CreateProfileGroupDialog dialog = new CreateProfileGroupDialog(owner);
        return dialog.showAndWait();
    }

    // ==================== UI Building ====================

    /**
     * Builds the main content layout.
     *
     * @return the content VBox
     */
    private VBox buildContent() {
        // Title
        Label titleLabel = new Label("Create Profile Group");
        titleLabel.getStyleClass().add("dialog-title");

        // File selection group
        VBox fileGroup = buildFileGroup();

        // Name group
        VBox nameGroup = buildNameGroup();

        // Assemble
        VBox content = new VBox(4);
        content.setPadding(new Insets(24));
        content.setPrefWidth(420);
        content.getChildren().addAll(titleLabel, fileGroup, nameGroup);

        return content;
    }

    /**
     * Builds the CSV file selection form group.
     *
     * @return the file selection VBox
     */
    private VBox buildFileGroup() {
        Label fileLabel = new Label("Profiles CSV");
        fileLabel.getStyleClass().add("form-label");

        fileNameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fileNameLabel, Priority.ALWAYS);

        Button browseButton = new Button("Browse...");
        browseButton.getStyleClass().addAll("button", "secondary");
        browseButton.setStyle("-fx-padding: 6px 14px; -fx-font-size: 13px;");
        browseButton.setOnAction(event -> onBrowseClicked());

        HBox fileRow = new HBox(10);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        fileRow.getChildren().addAll(fileNameLabel, browseButton);

        VBox fileGroup = new VBox();
        fileGroup.getStyleClass().add("form-group");
        fileGroup.getChildren().addAll(fileLabel, fileRow);

        return fileGroup;
    }

    /**
     * Builds the group name form group.
     *
     * @return the name VBox
     */
    private VBox buildNameGroup() {
        Label nameLabel = new Label("Group Name");
        nameLabel.getStyleClass().add("form-label");

        VBox nameGroup = new VBox();
        nameGroup.getStyleClass().add("form-group");
        nameGroup.getChildren().addAll(nameLabel, nameField);

        return nameGroup;
    }

    /**
     * Creates the group name text field.
     *
     * @return the configured TextField
     */
    private TextField createNameField() {
        TextField field = new TextField();
        field.setPromptText("e.g. Uber Accounts");
        return field;
    }

    /**
     * Creates the file name display label.
     *
     * @return the configured Label
     */
    private Label createFileNameLabel() {
        Label label = new Label("No file selected");
        label.getStyleClass().addAll("label", "muted");
        return label;
    }

    // ==================== Actions ====================

    /**
     * Handles the Browse button click.
     *
     * <p>Opens a native file chooser filtered to CSV files. On selection,
     * updates the file name display and auto-populates the group name
     * field if it is currently empty.</p>
     */
    private void onBrowseClicked() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Profiles CSV");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV Files", "*.csv")
        );

        File file = fileChooser.showOpenDialog(getOwner());
        if (file != null) {
            selectedFile = file;
            fileNameLabel.setText(file.getName());
            fileNameLabel.getStyleClass().remove("muted");

            // Auto-populate group name if empty
            if (nameField.getText().isBlank()) {
                String filename = file.getName();
                String nameWithoutExtension = filename.contains(".")
                        ? filename.substring(0, filename.lastIndexOf('.'))
                        : filename;
                nameField.setText(nameWithoutExtension);
            }

            // Re-validate since file selection changed
            Button createBtn = (Button) getDialogPane().lookupButton(
                    getDialogPane().getButtonTypes().stream()
                            .filter(bt -> bt.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                            .findFirst()
                            .orElse(null)
            );
            if (createBtn != null) {
                validateAndUpdateButton(createBtn);
            }
        }
    }

    // ==================== Validation ====================

    /**
     * Validates the form and enables/disables the Create button.
     *
     * <p>The form is valid when both a CSV file has been selected
     * and the group name is non-blank.</p>
     *
     * @param createButton the Create button to enable/disable
     */
    private void validateAndUpdateButton(Button createButton) {
        boolean valid = selectedFile != null
                && !nameField.getText().isBlank();
        createButton.setDisable(!valid);
    }
}