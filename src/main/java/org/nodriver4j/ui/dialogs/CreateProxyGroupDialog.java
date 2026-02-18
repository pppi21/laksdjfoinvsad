package org.nodriver4j.ui.dialogs;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.nodriver4j.ui.util.SmoothScrollHelper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Modal dialog for creating a new proxy group or adding proxies to an existing group.
 *
 * <p>Provides two input modes for proxy data:</p>
 * <ul>
 *   <li><strong>Upload File</strong> — select a {@code .txt} file via native file chooser</li>
 *   <li><strong>Paste Text</strong> — paste proxy lines directly into a text area</li>
 * </ul>
 *
 * <p>The dialog operates in two modes controlled by {@link Mode}:</p>
 * <ul>
 *   <li>{@link Mode#CREATE} — shows group name field, button says "Create"</li>
 *   <li>{@link Mode#ADD} — hides group name field, button says "Add Proxies"</li>
 * </ul>
 *
 * <p>All proxies must be in {@code host:port:username:password} format,
 * one per line. Empty lines and lines starting with {@code #} are skipped
 * by the importer.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // For creating a new proxy group
 * Optional<CreateProxyGroupDialog.Result> result =
 *     CreateProxyGroupDialog.showCreate(ownerWindow);
 *
 * result.ifPresent(r -> {
 *     System.out.println("Name: " + r.groupName());
 *     System.out.println("Content: " + r.proxyContent());
 * });
 *
 * // For adding proxies to an existing group
 * Optional<CreateProxyGroupDialog.Result> result =
 *     CreateProxyGroupDialog.showAdd(ownerWindow);
 *
 * result.ifPresent(r -> {
 *     // r.groupName() is null in ADD mode
 *     System.out.println("Content: " + r.proxyContent());
 * });
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display a styled modal dialog for proxy input</li>
 *   <li>Collect group name (create mode) and proxy content</li>
 *   <li>Provide dual input modes: file upload or paste</li>
 *   <li>Auto-populate group name from selected filename</li>
 *   <li>Validate inputs before submission</li>
 *   <li>Return result as an immutable record</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Parsing proxy lines (use {@link org.nodriver4j.persistence.importer.ProxyImporter})</li>
 *   <li>Database persistence (controller handles)</li>
 *   <li>Proxy group card creation (controller handles)</li>
 * </ul>
 *
 * @see CreateProfileGroupDialog
 * @see org.nodriver4j.persistence.importer.ProxyImporter
 */
public final class CreateProxyGroupDialog extends Dialog<CreateProxyGroupDialog.Result> {

    // ==================== Mode Enum ====================

    /**
     * Dialog operating mode.
     */
    public enum Mode {
        /** Creating a new proxy group — shows group name field */
        CREATE,
        /** Adding proxies to an existing group — hides group name field */
        ADD
    }

    // ==================== Result Record ====================

    /**
     * Immutable result of a successful dialog submission.
     *
     * @param groupName    the user-specified group name (null in ADD mode)
     * @param proxyContent the raw proxy content (from file or pasted text)
     */
    public record Result(String groupName, String proxyContent) {}

    // ==================== Input Mode Enum ====================

    /**
     * Input source for proxy data.
     */
    private enum InputMode {
        FILE,
        PASTE
    }

    // ==================== UI Components ====================

    private final Mode dialogMode;

    // Group name (only visible in CREATE mode)
    private final VBox nameGroup;
    private final TextField nameField;

    // Input mode selection
    private final RadioButton fileRadio;
    private final RadioButton pasteRadio;

    // File upload components
    private final VBox fileInputContainer;
    private final Label fileNameLabel;

    // Paste text components
    private final VBox pasteInputContainer;
    private final TextArea pasteArea;

    // ==================== State ====================

    private File selectedFile;
    private InputMode currentInputMode = InputMode.FILE;

    // ==================== Constructor ====================

    /**
     * Creates a new CreateProxyGroupDialog.
     *
     * @param owner the owner window (dialog will be centered on this)
     * @param mode  the dialog mode (CREATE or ADD)
     */
    public CreateProxyGroupDialog(Window owner, Mode mode) {
        this.dialogMode = mode;

        // Set owner for proper positioning
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        initStyle(StageStyle.TRANSPARENT);

        // Create UI components
        nameField = createNameField();
        nameGroup = buildNameGroup();
        fileNameLabel = createFileNameLabel();
        fileRadio = new RadioButton("Upload File");
        pasteRadio = new RadioButton("Paste Text");
        pasteArea = createPasteArea(); // Must be created before buildPasteInputContainer
        fileInputContainer = buildFileInputContainer();
        pasteInputContainer = buildPasteInputContainer();

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
        String submitText = (mode == Mode.CREATE) ? "Create" : "Add Proxies";
        ButtonType submitButtonType = new ButtonType(submitText, ButtonBar.ButtonData.OK_DONE);
        dialogPane.getButtonTypes().addAll(cancelButtonType, submitButtonType);

        // Style the buttons
        Button cancelBtn = (Button) dialogPane.lookupButton(cancelButtonType);
        cancelBtn.getStyleClass().add("secondary");

        Button submitBtn = (Button) dialogPane.lookupButton(submitButtonType);
        submitBtn.getStyleClass().add("primary");

        // Disable submit button initially
        submitBtn.setDisable(true);

        // Add validation listeners
        nameField.textProperty().addListener((obs, oldVal, newVal) ->
                validateAndUpdateButton(submitBtn));
        pasteArea.textProperty().addListener((obs, oldVal, newVal) ->
                validateAndUpdateButton(submitBtn));

        // Set result converter
        setResultConverter(buttonType -> {
            if (buttonType == submitButtonType) {
                String content = getProxyContent();
                if (content != null && !content.isBlank()) {
                    String groupName = (dialogMode == Mode.CREATE)
                            ? nameField.getText().trim()
                            : null;
                    return new Result(groupName, content);
                }
            }
            return null;
        });

        // Transparent scene for rounded corners
        setOnShown(event -> getDialogPane().getScene().setFill(Color.TRANSPARENT));

        // Focus appropriate field when dialog opens
        Platform.runLater(() -> {
            if (dialogMode == Mode.CREATE) {
                nameField.requestFocus();
            } else {
                fileRadio.requestFocus();
            }
        });
    }

    // ==================== Static Show Methods ====================

    /**
     * Shows the dialog in CREATE mode for creating a new proxy group.
     *
     * @param owner the owner window
     * @return an Optional containing the result if submitted, or empty if cancelled
     */
    public static Optional<Result> showCreate(Window owner) {
        CreateProxyGroupDialog dialog = new CreateProxyGroupDialog(owner, Mode.CREATE);
        return dialog.showAndWait();
    }

    /**
     * Shows the dialog in ADD mode for adding proxies to an existing group.
     *
     * @param owner the owner window
     * @return an Optional containing the result if submitted, or empty if cancelled
     */
    public static Optional<Result> showAdd(Window owner) {
        CreateProxyGroupDialog dialog = new CreateProxyGroupDialog(owner, Mode.ADD);
        return dialog.showAndWait();
    }

    // ==================== UI Building ====================

    /**
     * Builds the main content layout wrapped in a ScrollPane.
     *
     * @return the scrollable content container
     */
    private ScrollPane buildContent() {
        // Title
        String titleText = (dialogMode == Mode.CREATE) ? "Create Proxy Group" : "Add Proxies";
        Label titleLabel = new Label(titleText);
        titleLabel.getStyleClass().add("dialog-title");

        // Input mode selector
        VBox inputModeGroup = buildInputModeGroup();

        // Input containers (stacked, visibility toggled)
        StackPane inputStack = new StackPane();
        inputStack.getChildren().addAll(fileInputContainer, pasteInputContainer);

        // Format hint
        Label formatHint = new Label("One proxy per line: host:port:username:password");
        formatHint.getStyleClass().addAll("label", "muted");
        formatHint.setStyle("-fx-font-size: 12px;");

        // Assemble content
        VBox content = new VBox(4);
        content.setPadding(new Insets(24));
        content.setPrefWidth(450);

        if (dialogMode == Mode.CREATE) {
            content.getChildren().addAll(titleLabel, nameGroup, inputModeGroup, inputStack, formatHint);
        } else {
            content.getChildren().addAll(titleLabel, inputModeGroup, inputStack, formatHint);
        }

        // Wrap in ScrollPane
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPrefViewportWidth(470);
        scrollPane.setMaxHeight(400);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        SmoothScrollHelper.apply(scrollPane, 0.4, 0.8);
        return scrollPane;
    }

    /**
     * Builds the group name form group (only shown in CREATE mode).
     *
     * @return the name VBox
     */
    private VBox buildNameGroup() {
        Label nameLabel = new Label("Group Name");
        nameLabel.getStyleClass().add("form-label");

        VBox group = new VBox();
        group.getStyleClass().add("form-group");
        group.getChildren().addAll(nameLabel, nameField);

        return group;
    }

    /**
     * Builds the input mode selector (radio buttons).
     *
     * @return the input mode VBox
     */
    private VBox buildInputModeGroup() {
        Label label = new Label("Proxy Source");
        label.getStyleClass().add("form-label");

        // Radio button styling
        fileRadio.getStyleClass().add("radio-button");
        pasteRadio.getStyleClass().add("radio-button");

        // Group the radio buttons
        ToggleGroup toggleGroup = new ToggleGroup();
        fileRadio.setToggleGroup(toggleGroup);
        pasteRadio.setToggleGroup(toggleGroup);
        fileRadio.setSelected(true);

        // Handle toggle changes
        toggleGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == fileRadio) {
                switchToFileMode();
            } else if (newVal == pasteRadio) {
                switchToPasteMode();
            }
        });

        HBox radioRow = new HBox(16);
        radioRow.setAlignment(Pos.CENTER_LEFT);
        radioRow.getChildren().addAll(fileRadio, pasteRadio);

        VBox group = new VBox();
        group.getStyleClass().add("form-group");
        group.getChildren().addAll(label, radioRow);

        return group;
    }

    /**
     * Builds the file upload input container.
     *
     * @return the file input VBox
     */
    private VBox buildFileInputContainer() {
        fileNameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fileNameLabel, Priority.ALWAYS);

        Button browseButton = new Button("Browse...");
        browseButton.getStyleClass().addAll("button", "secondary");
        browseButton.setStyle("-fx-padding: 6px 14px; -fx-font-size: 13px;");
        browseButton.setOnAction(event -> onBrowseClicked());

        HBox fileRow = new HBox(10);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        fileRow.getChildren().addAll(fileNameLabel, browseButton);

        VBox container = new VBox();
        container.getStyleClass().add("form-group");
        container.getChildren().add(fileRow);

        return container;
    }

    /**
     * Builds the paste text input container.
     *
     * @return the paste input VBox
     */
    private VBox buildPasteInputContainer() {
        VBox container = new VBox();
        container.getStyleClass().add("form-group");
        container.getChildren().add(pasteArea);

        // Initially hidden
        container.setVisible(false);
        container.setManaged(false);

        return container;
    }

    /**
     * Creates the group name text field.
     *
     * @return the configured TextField
     */
    private TextField createNameField() {
        TextField field = new TextField();
        field.setPromptText("e.g. Lightning Proxies");
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

    /**
     * Creates the paste text area.
     *
     * @return the configured TextArea
     */
    private TextArea createPasteArea() {
        TextArea area = new TextArea();
        area.setPromptText("Paste proxies here (one per line)...");
        area.setPrefRowCount(6);
        area.setWrapText(false);
        SmoothScrollHelper.apply(area, 0.4, 2.0);
        return area;
    }

    // ==================== Input Mode Switching ====================

    /**
     * Switches to file upload mode.
     */
    private void switchToFileMode() {
        currentInputMode = InputMode.FILE;

        fileInputContainer.setVisible(true);
        fileInputContainer.setManaged(true);

        pasteInputContainer.setVisible(false);
        pasteInputContainer.setManaged(false);

        revalidate();
    }

    /**
     * Switches to paste text mode.
     */
    private void switchToPasteMode() {
        currentInputMode = InputMode.PASTE;

        fileInputContainer.setVisible(false);
        fileInputContainer.setManaged(false);

        pasteInputContainer.setVisible(true);
        pasteInputContainer.setManaged(true);

        // Focus the text area
        Platform.runLater(() -> pasteArea.requestFocus());

        revalidate();
    }

    // ==================== Actions ====================

    /**
     * Handles the Browse button click.
     *
     * <p>Opens a native file chooser filtered to .txt files. On selection,
     * updates the file name display and auto-populates the group name
     * field if it is currently empty (CREATE mode only).</p>
     */
    private void onBrowseClicked() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Proxy File");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Text Files", "*.txt")
        );

        File file = fileChooser.showOpenDialog(getOwner());
        if (file != null) {
            selectedFile = file;
            fileNameLabel.setText(file.getName());
            fileNameLabel.getStyleClass().remove("muted");

            // Auto-populate group name if empty (CREATE mode only)
            if (dialogMode == Mode.CREATE && nameField.getText().isBlank()) {
                String filename = file.getName();
                String nameWithoutExtension = filename.contains(".")
                        ? filename.substring(0, filename.lastIndexOf('.'))
                        : filename;
                nameField.setText(nameWithoutExtension);
            }

            revalidate();
        }
    }

    // ==================== Validation ====================

    /**
     * Triggers revalidation of the submit button state.
     */
    private void revalidate() {
        Button submitBtn = (Button) getDialogPane().lookupButton(
                getDialogPane().getButtonTypes().stream()
                        .filter(bt -> bt.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                        .findFirst()
                        .orElse(null)
        );
        if (submitBtn != null) {
            validateAndUpdateButton(submitBtn);
        }
    }

    /**
     * Validates the form and enables/disables the submit button.
     *
     * <p>Validation rules:</p>
     * <ul>
     *   <li>CREATE mode: group name must be non-blank</li>
     *   <li>FILE mode: a file must be selected</li>
     *   <li>PASTE mode: text area must have content</li>
     * </ul>
     *
     * @param submitButton the submit button to enable/disable
     */
    private void validateAndUpdateButton(Button submitButton) {
        boolean valid = true;

        // In CREATE mode, name must be non-blank
        if (dialogMode == Mode.CREATE && nameField.getText().isBlank()) {
            valid = false;
        }

        // Check input based on current mode
        if (currentInputMode == InputMode.FILE) {
            if (selectedFile == null) {
                valid = false;
            }
        } else { // PASTE mode
            if (pasteArea.getText().isBlank()) {
                valid = false;
            }
        }

        submitButton.setDisable(!valid);
    }

    // ==================== Content Retrieval ====================

    /**
     * Gets the proxy content from the current input source.
     *
     * <p>Reads file content if in FILE mode, or returns text area content
     * if in PASTE mode.</p>
     *
     * @return the proxy content string, or null if reading fails
     */
    private String getProxyContent() {
        if (currentInputMode == InputMode.FILE) {
            if (selectedFile == null) {
                return null;
            }
            try {
                return Files.readString(selectedFile.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[CreateProxyGroupDialog] Failed to read file: " + e.getMessage());
                return null;
            }
        } else {
            return pasteArea.getText();
        }
    }
}