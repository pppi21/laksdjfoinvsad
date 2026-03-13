package org.nodriver4j.ui.controllers;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.nodriver4j.ui.components.GroupCard;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Abstract base controller for group manager pages.
 *
 * <p>Provides the common infrastructure shared by Task Manager, Profile Manager,
 * and Proxy Manager pages. Each of these pages follows the same pattern:</p>
 * <ol>
 *   <li>A page header with title, subtitle, and an add button</li>
 *   <li>An empty state shown when no groups exist</li>
 *   <li>A scrollable grid of group cards</li>
 * </ol>
 *
 * <p>This controller is paired with {@code group-manager.fxml}, which has no
 * {@code fx:controller} attribute. Concrete subclasses are set as the controller
 * programmatically by {@link MainController} via
 * {@code loader.setController(new MyConcreteController())} before loading.</p>
 *
 * <h2>Subclass Contract</h2>
 * <p>Concrete subclasses must implement:</p>
 * <ul>
 *   <li>{@link #pageTitle()} — page header title text</li>
 *   <li>{@link #pageSubtitle()} — page header subtitle text</li>
 *   <li>{@link #emptyStateIcon()} — emoji or text for the empty state icon</li>
 *   <li>{@link #emptyStateTitle()} — empty state heading</li>
 *   <li>{@link #emptyStateDescription()} — empty state help text</li>
 *   <li>{@link #loadGroups()} — load persisted groups from the database</li>
 *   <li>{@link #onAddClicked()} — handle the add button click</li>
 * </ul>
 *
 * <h2>Card Management</h2>
 * <p>Subclasses manage cards through the protected API:</p>
 * <ul>
 *   <li>{@link #addCard(GroupCard)} — appends a card to the grid</li>
 *   <li>{@link #addCardFirst(GroupCard)} — inserts a card at the beginning</li>
 *   <li>{@link #removeCard(GroupCard)} — removes a card from the grid</li>
 *   <li>{@link #clearCards()} — removes all cards</li>
 *   <li>{@link #cards()} — read-only snapshot of current cards</li>
 * </ul>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>FXML field injection for the shared layout</li>
 *   <li>Configure page labels from subclass-provided text</li>
 *   <li>Card list management and grid synchronization</li>
 *   <li>Empty state vs grid visibility toggle</li>
 *   <li>Shared error alert display</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Page-specific text content (subclasses)</li>
 *   <li>Data loading logic (subclasses)</li>
 *   <li>Add button behavior (subclasses)</li>
 *   <li>Card construction (subclasses)</li>
 *   <li>Database operations (repositories via subclasses)</li>
 *   <li>Page caching or FXML loading (MainController)</li>
 * </ul>
 *
 * @param <C> the concrete card type (must extend {@link GroupCard})
 * @see GroupCard
 * @see MainController
 */
public abstract class GroupManagerController<C extends GroupCard> implements Initializable {

    // ==================== FXML Injected Fields ====================

    @FXML
    private Label pageTitleLabel;

    @FXML
    private Label pageSubtitleLabel;

    @FXML
    private Label emptyStateIconLabel;

    @FXML
    private Label emptyStateTitleLabel;

    @FXML
    private Label emptyStateDescLabel;

    @FXML
    private Button addButton;

    @FXML
    private VBox emptyState;

    @FXML
    private ScrollPane scrollPane;

    @FXML
    private FlowPane cardGrid;

    // ==================== Internal State ====================

    /**
     * List of cards currently displayed in the grid.
     * Maintained in sync with {@code cardGrid.getChildren()}.
     */
    private final List<C> cards = new ArrayList<>();

    // ==================== Initialization ====================

    /**
     * Initializes the page by configuring labels, loading data, and
     * updating the view state.
     *
     * <p>Subclasses should NOT override this method. Instead, implement
     * {@link #loadGroups()} for data loading and the label methods for
     * page-specific text. If additional initialization is needed, override
     * {@link #onInitialized()} which is called at the end of this method.</p>
     */
    @Override
    public final void initialize(URL location, ResourceBundle resources) {
        System.out.println("[" + getClass().getSimpleName() + "] Initializing...");

        configurePageLabels();
        loadGroups();
        updateViewState();
        onInitialized();

        System.out.println("[" + getClass().getSimpleName() + "] Initialized successfully");
    }

    /**
     * Configures all page labels from subclass-provided text.
     */
    private void configurePageLabels() {
        pageTitleLabel.setText(pageTitle());
        pageSubtitleLabel.setText(pageSubtitle());
        emptyStateIconLabel.setText(emptyStateIcon());
        emptyStateTitleLabel.setText(emptyStateTitle());
        emptyStateDescLabel.setText(emptyStateDescription());
    }

    // ==================== Abstract Methods — Page Text ====================

    /**
     * Returns the page title displayed in the header.
     *
     * @return the title text (e.g., "Task Manager", "Profiles")
     */
    protected abstract String pageTitle();

    /**
     * Returns the page subtitle displayed below the title.
     *
     * @return the subtitle text
     */
    protected abstract String pageSubtitle();

    /**
     * Returns the emoji or text for the empty state icon.
     *
     * @return the icon text (e.g., "📋", "👤")
     */
    protected abstract String emptyStateIcon();

    /**
     * Returns the empty state heading text.
     *
     * @return the title text (e.g., "No Task Groups")
     */
    protected abstract String emptyStateTitle();

    /**
     * Returns the empty state description text.
     *
     * @return the description text
     */
    protected abstract String emptyStateDescription();

    // ==================== Abstract Methods — Behavior ====================

    /**
     * Loads persisted groups from the database and populates the card grid.
     *
     * <p>Called once during initialization. Subclasses should query their
     * repository, build cards, and call {@link #addCard(GroupCard)} for each.</p>
     */
    protected abstract void loadGroups();

    /**
     * Handles the add button click.
     *
     * <p>Bound to the FXML {@code onAction="#onAddClicked"} attribute.
     * Subclasses typically open a creation dialog and call
     * {@link #addCardFirst(GroupCard)} on success.</p>
     */
    @FXML
    protected abstract void onAddClicked();

    // ==================== Lifecycle Hook ====================

    /**
     * Called after initialization is complete.
     *
     * <p>Override this method for any additional setup that needs to happen
     * after labels are configured, groups are loaded, and the view state
     * is updated. Default implementation does nothing.</p>
     */
    protected void onInitialized() {
        // Default: no-op. Subclasses can override.
    }

    // ==================== Card Management ====================

    /**
     * Adds a card to the end of the grid.
     *
     * <p>Updates both the internal list and the FXML grid, then
     * refreshes the view state.</p>
     *
     * @param card the card to add
     */
    protected void addCard(C card) {
        cards.add(card);
        cardGrid.getChildren().add(card);
        updateViewState();
    }

    /**
     * Adds a card to the beginning of the grid.
     *
     * <p>Used when adding newly created groups so they appear first
     * (newest-first display order).</p>
     *
     * @param card the card to add
     */
    protected void addCardFirst(C card) {
        cards.addFirst(card);
        cardGrid.getChildren().addFirst(card);
        updateViewState();
    }

    /**
     * Removes a card from the grid.
     *
     * <p>Updates both the internal list and the FXML grid, then
     * refreshes the view state.</p>
     *
     * @param card the card to remove
     */
    protected void removeCard(C card) {
        cards.remove(card);
        cardGrid.getChildren().remove(card);
        updateViewState();
    }

    /**
     * Removes all cards from the grid.
     *
     * <p>Updates both the internal list and the FXML grid, then
     * refreshes the view state.</p>
     */
    protected void clearCards() {
        cards.clear();
        cardGrid.getChildren().clear();
        updateViewState();
    }

    /**
     * Gets a read-only snapshot of all current cards.
     *
     * @return unmodifiable list of cards
     */
    protected List<C> cards() {
        return List.copyOf(cards);
    }

    /**
     * Gets the number of cards currently displayed.
     *
     * @return the card count
     */
    protected int cardCount() {
        return cards.size();
    }

    // ==================== View State ====================

    /**
     * Updates the view to show either the empty state or the card grid.
     *
     * <p>Called automatically by card management methods. Subclasses
     * should not normally need to call this directly.</p>
     */
    protected void updateViewState() {
        boolean hasCards = !cards.isEmpty();

        emptyState.setVisible(!hasCards);
        emptyState.setManaged(!hasCards);

        scrollPane.setVisible(hasCards);
        scrollPane.setManaged(hasCards);
    }

    // ==================== FXML Component Access ====================

    /**
     * Gets the add button for subclass use (e.g., to obtain the owner window).
     *
     * @return the add button
     */
    protected Button addButton() {
        return addButton;
    }

    /**
     * Gets the card grid for subclass use.
     *
     * <p>Prefer using {@link #addCard(GroupCard)}, {@link #removeCard(GroupCard)},
     * etc. instead of manipulating the grid directly.</p>
     *
     * @return the FlowPane card grid
     */
    protected FlowPane cardGrid() {
        return cardGrid;
    }

    /**
     * Gets the owner window from the add button's scene.
     *
     * <p>Convenience method for subclasses that need to show dialogs
     * centered on the main window.</p>
     *
     * @return the owner window
     */
    protected Window ownerWindow() {
        return addButton.getScene().getWindow();
    }

    // ==================== Error Handling ====================

    /**
     * Shows an error alert dialog styled with the dark theme.
     *
     * @param title   the alert title
     * @param header  the header text describing what went wrong
     * @param content the detailed error message
     */
    protected void showErrorAlert(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);

        // Style the alert with our dark theme if possible
        try {
            alert.getDialogPane().getStylesheets().add(
                    getClass().getResource("/org/nodriver4j/ui/css/dark-theme.css").toExternalForm()
            );
            alert.getDialogPane().getStyleClass().add("dialog-pane");
        } catch (Exception e) {
            System.err.println("[" + getClass().getSimpleName()
                    + "] Could not apply dark theme to alert: " + e.getMessage());
        }

        alert.showAndWait();
    }
}