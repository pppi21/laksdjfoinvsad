package org.nodriver4j.ui.components;

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

/**
 * Base card component for displaying group information.
 *
 * <p>Provides a reusable card layout used by task groups, profile groups,
 * and proxy groups. The card displays:</p>
 * <ul>
 *   <li>Group name at the top</li>
 *   <li>Subclass-specific content in the middle (via {@link #contentBox()})</li>
 *   <li>Subclass-specific stats at bottom-left (via {@link #statsBox()})</li>
 *   <li>Delete button with inline confirmation at bottom-right</li>
 * </ul>
 *
 * <p>Subclasses extend this class and populate the content and stats
 * containers in their constructors after calling {@code super()}. This
 * avoids the anti-pattern of calling overridden methods from a constructor.</p>
 *
 * <h2>Layout Structure</h2>
 * <pre>
 * VBox (card)
 * ├── nameLabel
 * ├── contentBox (subclass populates)
 * ├── spacer
 * └── bottomRow (HBox)
 *     ├── statsBox (subclass populates)
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
 *   <li>Common card layout skeleton</li>
 *   <li>Delete button with inline confirmation flow</li>
 *   <li>Click handling delegation via callbacks</li>
 *   <li>Holding the group database ID</li>
 *   <li>Base card CSS styling</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Specific content or stats display (subclasses)</li>
 *   <li>Database operations (controllers handle persistence)</li>
 *   <li>Page navigation (controllers handle navigation)</li>
 *   <li>Task/profile/proxy data management</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyGroupCard extends GroupCard {
 *     private final Label itemCountLabel;
 *
 *     public MyGroupCard(long groupId, String name, int itemCount) {
 *         super(groupId, name);
 *
 *         Label subtitle = new Label("Some info");
 *         subtitle.getStyleClass().add("group-card-subtitle");
 *         contentBox().getChildren().add(subtitle);
 *
 *         itemCountLabel = new Label(itemCount + " items");
 *         itemCountLabel.getStyleClass().add("group-card-stat");
 *         statsBox().getChildren().add(itemCountLabel);
 *     }
 * }
 * }</pre>
 *
 * @see TaskGroupCard
 */
public class GroupCard extends VBox {

    // ==================== UI Components ====================

    private final Label nameLabel;

    /**
     * Container for subclass-specific content below the name label.
     * Subclasses access via {@link #contentBox()} and add their own nodes.
     */
    private final VBox contentBox;

    /**
     * Container for subclass-specific stats at the bottom-left.
     * Subclasses access via {@link #statsBox()} and add their own nodes.
     */
    private final VBox statsBox;

    // ==================== Delete Flow Components ====================

    private final StackPane deleteContainer;
    private final Button deleteButton;
    private final HBox confirmCancelContainer;

    // ==================== Data ====================

    private final long groupId;
    private String groupName;

    // ==================== State ====================

    private boolean isConfirmingDelete = false;

    // ==================== Callbacks ====================

    private Runnable onClick;
    private Runnable onDelete;

    // ==================== Constructor ====================

    /**
     * Creates a new GroupCard.
     *
     * <p>After calling this constructor, subclasses should populate
     * {@link #contentBox()} and {@link #statsBox()} with their
     * specific UI nodes.</p>
     *
     * @param groupId the database ID of the group
     * @param name    the display name of the group
     */
    protected GroupCard(long groupId, String name) {
        this.groupId = groupId;
        this.groupName = name;

        // Apply card styling
        getStyleClass().add("group-card");

        // Build UI components
        nameLabel = buildNameLabel();

        contentBox = new VBox();

        statsBox = new VBox();
        statsBox.getStyleClass().add("group-card-stats");
        statsBox.setSpacing(4);

        // Build delete flow components
        deleteButton = buildDeleteButton();
        confirmCancelContainer = buildConfirmCancelContainer();
        deleteContainer = buildDeleteContainer();

        // Build bottom row with stats on left, delete on right
        HBox bottomRow = buildBottomRow();

        // Spacer to push bottom row down
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        // Assemble card layout
        getChildren().addAll(nameLabel, contentBox, spacer, bottomRow);

        // Set up click handler (only fires when not confirming delete)
        setOnMouseClicked(event -> {
            if (event.getButton().name().equals("PRIMARY")
                    && !isConfirmingDelete
                    && onClick != null) {
                onClick.run();
            }
        });
    }

    // ==================== UI Building ====================

    private Label buildNameLabel() {
        Label label = new Label(groupName);
        label.getStyleClass().add("group-card-title");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private Button buildDeleteButton() {
        Button button = new Button();
        button.getStyleClass().add("delete-button");

        FontIcon trashIcon = new FontIcon(FontAwesomeSolid.TRASH_ALT);
        trashIcon.setIconSize(16);
        trashIcon.setIconColor(Color.web("#d15252"));
        button.setGraphic(trashIcon);

        button.setOnAction(event -> {
            event.consume();
            showDeleteConfirmation();
        });

        return button;
    }

    private HBox buildConfirmCancelContainer() {
        HBox container = new HBox();
        container.getStyleClass().add("delete-confirm-buttons");
        container.setAlignment(Pos.CENTER_RIGHT);
        container.setSpacing(6);

        // Confirm button first (left), then Cancel (right)
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

    private StackPane buildDeleteContainer() {
        StackPane container = new StackPane();
        container.setAlignment(Pos.BOTTOM_RIGHT);
        container.setMinWidth(140);
        container.setPrefWidth(140);
        container.setMaxWidth(140);
        container.getChildren().addAll(deleteButton, confirmCancelContainer);
        return container;
    }

    private HBox buildBottomRow() {
        // Spacer to push delete to the right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Bottom row: stats left, delete right
        HBox row = new HBox();
        row.setAlignment(Pos.BOTTOM_LEFT);
        row.getChildren().addAll(statsBox, spacer, deleteContainer);

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

    // ==================== Protected Hooks ====================

    /**
     * Gets the content container for subclass-specific content.
     *
     * <p>Subclasses should add their custom nodes (labels, icons, etc.)
     * to this container in their constructor. This container appears
     * between the name label and the bottom stats/delete row.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * Label scriptLabel = new Label("UberGen");
     * scriptLabel.getStyleClass().add("group-card-subtitle");
     * contentBox().getChildren().add(scriptLabel);
     * }</pre>
     *
     * @return the content VBox
     */
    protected VBox contentBox() {
        return contentBox;
    }

    /**
     * Gets the stats container for subclass-specific statistics.
     *
     * <p>Subclasses should add stat labels to this container in their
     * constructor. This container appears at the bottom-left of the card,
     * opposite the delete button.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * Label countLabel = new Label("12 tasks");
     * countLabel.getStyleClass().add("group-card-stat");
     * statsBox().getChildren().add(countLabel);
     * }</pre>
     *
     * @return the stats VBox
     */
    protected VBox statsBox() {
        return statsBox;
    }

    // ==================== Data Updates ====================

    /**
     * Updates the group name display.
     *
     * @param name the new name
     */
    public void setGroupName(String name) {
        this.groupName = name;
        nameLabel.setText(name);
    }

    // ==================== Getters ====================

    /**
     * Gets the database ID of the group.
     *
     * @return the group ID
     */
    public long groupId() {
        return groupId;
    }

    /**
     * Gets the group name.
     *
     * @return the group name
     */
    public String groupName() {
        return groupName;
    }

    /**
     * Checks if the card is currently showing delete confirmation.
     *
     * @return true if confirming delete
     */
    public boolean isConfirmingDelete() {
        return isConfirmingDelete;
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
}