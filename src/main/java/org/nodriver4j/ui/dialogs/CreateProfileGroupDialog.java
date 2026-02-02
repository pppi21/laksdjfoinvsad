package org.nodriver4j.ui.dialogs;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.File;
import java.net.URL;
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
 * @see org.nodriver4j.persistence.importer.ProfileImporter
 */
public final class CreateProfileGroupDialog {

    private static final double DIALOG_WIDTH = 420;

    private CreateProfileGroupDialog() {
        // Static utility class — prevent instantiation
    }

    // ==================== Result Record ====================

    /**
     * Immutable result of a successful profile group creation dialog.
     *
     * @param groupName the user-specified group name
     * @param csvPath   the path to the selected CSV file
     */
    public record Result(String groupName, Path csvPath) {}

    // ==================== Public API ====================

    /**
     * Shows the dialog and waits for the user to submit or cancel.
     *
     * @param owner the owner window (dialog is centered on this window)
     * @return an Optional containing the result if the user clicked Create,
     *         or empty if the user cancelled
     */
    public static Optional<Result> show(Window owner) {
        // Result holder (effectively final for use in lambdas)
        final Result[] result = {null};

        // ---- Build the stage ----
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(owner);
        dialog.initStyle(StageStyle.UNDECORATED);
        dialog.setResizable(false);

        // ---- Title ----
        Label titleLabel = new Label("Create Profile Group");
        titleLabel.getStyleClass().add("dialog-title");

        // ---- CSV File Selection ----
        Label fileLabel = new Label("Profiles CSV");
        fileLabel.getStyleClass().add("form-label");

        Label fileNameLabel = new Label("No file selected");
        fileNameLabel.getStyleClass().addAll("label", "muted");
        fileNameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fileNameLabel, Priority.ALWAYS);

        // Track selected file
        final File[] selectedFile = {null};

        Button browseButton = new Button("Browse...");
        browseButton.getStyleClass().addAll("button", "secondary");
        browseButton.setStyle("-fx-padding: 6px 14px; -fx-font-size: 13px;");

        HBox fileRow = new HBox(10);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        fileRow.getChildren().addAll(fileNameLabel, browseButton);

        VBox fileGroup = new VBox();
        fileGroup.getStyleClass().add("form-group");
        fileGroup.getChildren().addAll(fileLabel, fileRow);

        // ---- Group Name ----
        Label nameLabel = new Label("Group Name");
        nameLabel.getStyleClass().add("form-label");

        TextField nameField = new TextField();
        nameField.setPromptText("e.g. Uber Accounts");

        VBox nameGroup = new VBox();
        nameGroup.getStyleClass().add("form-group");
        nameGroup.getChildren().addAll(nameLabel, nameField);

        // ---- Buttons ----
        Button cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().addAll("button", "secondary");

        Button createButton = new Button("Create");
        createButton.getStyleClass().addAll("button", "primary");
        createButton.setDisable(true);

        Region buttonSpacer = new Region();
        HBox.setHgrow(buttonSpacer, Priority.ALWAYS);

        HBox buttonRow = new HBox(12);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);
        buttonRow.setPadding(new Insets(8, 0, 0, 0));
        buttonRow.getChildren().addAll(buttonSpacer, cancelButton, createButton);

        // ---- Layout ----
        VBox content = new VBox(4);
        content.setPadding(new Insets(24));
        content.setPrefWidth(DIALOG_WIDTH);
        content.getStyleClass().add("dialog-pane");
        content.getChildren().addAll(titleLabel, fileGroup, nameGroup, buttonRow);

        // ---- Validation ----
        Runnable validateForm = () -> {
            boolean valid = selectedFile[0] != null
                    && !nameField.getText().isBlank();
            createButton.setDisable(!valid);
        };

        nameField.textProperty().addListener((obs, oldVal, newVal) -> validateForm.run());

        // ---- Browse action ----
        browseButton.setOnAction(event -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select Profiles CSV");
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("CSV Files", "*.csv")
            );

            File file = fileChooser.showOpenDialog(dialog);
            if (file != null) {
                selectedFile[0] = file;
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

                validateForm.run();
            }
        });

        // ---- Button actions ----
        cancelButton.setOnAction(event -> dialog.close());

        createButton.setOnAction(event -> {
            String groupName = nameField.getText().trim();
            Path csvPath = selectedFile[0].toPath();

            result[0] = new Result(groupName, csvPath);
            dialog.close();
        });

        // ---- Scene ----
        Scene scene = new Scene(content);

        // Apply dark theme
        URL cssUrl = CreateProfileGroupDialog.class.getResource(
                "/org/nodriver4j/ui/css/dark-theme.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        // ---- Keyboard shortcuts ----
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                dialog.close();
            } else if (event.getCode() == KeyCode.ENTER && !createButton.isDisabled()) {
                createButton.fire();
            }
        });

        dialog.setScene(scene);

        // Center on owner window
        if (owner != null) {
            dialog.setOnShown(event -> {
                dialog.setX(owner.getX() + (owner.getWidth() - dialog.getWidth()) / 2);
                dialog.setY(owner.getY() + (owner.getHeight() - dialog.getHeight()) / 2);
            });
        }

        dialog.showAndWait();

        return Optional.ofNullable(result[0]);
    }
}