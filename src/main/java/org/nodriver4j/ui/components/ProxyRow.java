package org.nodriver4j.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * A full-width row component that displays a single proxy's information.
 *
 * <p>Each row shows:</p>
 * <ul>
 *   <li>Proxy host as the primary identifier (left side)</li>
 *   <li>Full proxy string below: {@code host:port:username:password}</li>
 *   <li>Copy button (copies full proxy string to clipboard)</li>
 *   <li>Delete button (no confirmation required)</li>
 * </ul>
 *
 * <p>The row receives only display strings — it has no knowledge of
 * entities, repositories, or the database. The controller is responsible
 * for handling delete actions and updating the database.</p>
 *
 * <h2>Layout Structure</h2>
 * <pre>
 * HBox (row)
 * ├── VBox (info - left side)
 * │   ├── hostLabel (primary text)
 * │   └── proxyStringLabel (secondary text, full proxy string)
 * ├── Region (spacer)
 * └── HBox (buttons - right side)
 *     ├── copyButton
 *     └── deleteButton
 * </pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display proxy host and full proxy string</li>
 *   <li>Hold the database proxy ID for controller lookups</li>
 *   <li>Copy proxy string to clipboard on copy button click</li>
 *   <li>Invoke delete callback on delete button click</li>
 *   <li>Apply consistent CSS styling with TaskRow</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Database operations (controller handles persistence)</li>
 *   <li>Delete confirmation (not required per spec)</li>
 *   <li>Proxy parsing or validation</li>
 *   <li>Group management</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProxyRow row = new ProxyRow(42L, "proxy.example.com", 8080, "user", "pass");
 * row.setOnDelete(() -> {
 *     proxyRepository.deleteById(row.proxyId());
 *     proxyList.getChildren().remove(row);
 * });
 * proxyList.getChildren().add(row);
 * }</pre>
 *
 * @see TaskRow
 */
public class ProxyRow extends HBox {

    // ==================== UI Components ====================

    private final Label hostLabel;
    private final Label proxyStringLabel;
    private final Button copyButton;
    private final Button deleteButton;

    // ==================== Data ====================

    private final long proxyId;
    private final String host;
    private final int port;
    private final String username;
    private final String password;

    // ==================== Callbacks ====================

    private Runnable onDelete;

    // ==================== Constructor ====================

    /**
     * Creates a new ProxyRow.
     *
     * @param proxyId  the database ID of the proxy
     * @param host     the proxy hostname
     * @param port     the proxy port
     * @param username the proxy username
     * @param password the proxy password
     */
    public ProxyRow(long proxyId, String host, int port, String username, String password) {
        this.proxyId = proxyId;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;

        // Apply row styling (same as TaskRow)
        getStyleClass().add("proxy-row");
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(Double.MAX_VALUE);

        // ---- Left side: info ----
        hostLabel = createHostLabel();
        proxyStringLabel = createProxyStringLabel();

        VBox infoBox = new VBox(4);
        infoBox.setAlignment(Pos.CENTER_LEFT);
        infoBox.getChildren().addAll(hostLabel, proxyStringLabel);
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        // ---- Spacer ----
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // ---- Right side: buttons ----
        copyButton = createCopyButton();
        deleteButton = createDeleteButton();

        HBox buttonBox = new HBox(8);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.getChildren().addAll(copyButton, deleteButton);

        // Assemble row
        getChildren().addAll(infoBox, spacer, buttonBox);
    }

    // ==================== UI Building ====================

    /**
     * Creates the host label (primary identifier).
     *
     * @return the configured Label
     */
    private Label createHostLabel() {
        String displayText = host + ":" + port;
        Label label = new Label(displayText);
        label.getStyleClass().add("proxy-row-host");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /**
     * Creates the full proxy string label.
     *
     * @return the configured Label
     */
    private Label createProxyStringLabel() {
        Label label = new Label(toProxyString());
        label.getStyleClass().add("proxy-row-string");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /**
     * Creates the copy button with clipboard icon.
     *
     * @return the configured Button
     */
    private Button createCopyButton() {
        Button button = new Button();
        button.getStyleClass().add("proxy-row-button");

        FontIcon copyIcon = new FontIcon(FontAwesomeSolid.COPY);
        copyIcon.setIconSize(14);
        copyIcon.setIconColor(Color.web("#a3a3a3"));
        button.setGraphic(copyIcon);

        button.setTooltip(new Tooltip("Copy to clipboard"));

        button.setOnAction(event -> {
            event.consume();
            copyToClipboard();
        });

        return button;
    }

    /**
     * Creates the delete button with trash icon.
     *
     * @return the configured Button
     */
    private Button createDeleteButton() {
        Button button = new Button();
        button.getStyleClass().add("proxy-row-button");

        FontIcon trashIcon = new FontIcon(FontAwesomeSolid.TRASH_ALT);
        trashIcon.setIconSize(14);
        trashIcon.setIconColor(Color.web("#d15252"));
        button.setGraphic(trashIcon);

        button.setTooltip(new Tooltip("Delete proxy"));

        button.setOnAction(event -> {
            event.consume();
            if (onDelete != null) {
                onDelete.run();
            }
        });

        return button;
    }

    // ==================== Actions ====================

    /**
     * Copies the full proxy string to the system clipboard.
     *
     * <p>Shows brief visual feedback by temporarily changing the
     * copy button icon color.</p>
     */
    private void copyToClipboard() {
        String proxyString = toProxyString();

        ClipboardContent content = new ClipboardContent();
        content.putString(proxyString);
        Clipboard.getSystemClipboard().setContent(content);

        // Visual feedback: change icon color briefly
        FontIcon icon = (FontIcon) copyButton.getGraphic();
        Color originalColor = Color.web("#a3a3a3");
        Color successColor = Color.web("#4ade80");

        icon.setIconColor(successColor);

        // Reset after delay
        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}
            javafx.application.Platform.runLater(() -> icon.setIconColor(originalColor));
        }).start();

        System.out.println("[ProxyRow] Copied to clipboard: " + host + ":" + port + ":***:***");
    }

    // ==================== Formatting ====================

    /**
     * Returns the proxy in standard string format.
     *
     * <p>Format: {@code host:port:username:password}</p>
     *
     * @return the full proxy string
     */
    public String toProxyString() {
        return String.format("%s:%d:%s:%s", host, port, username, password);
    }

    // ==================== Getters ====================

    /**
     * Gets the database ID of the proxy.
     *
     * @return the proxy ID
     */
    public long proxyId() {
        return proxyId;
    }

    /**
     * Gets the proxy hostname.
     *
     * @return the host
     */
    public String host() {
        return host;
    }

    /**
     * Gets the proxy port.
     *
     * @return the port
     */
    public int port() {
        return port;
    }

    /**
     * Gets the proxy username.
     *
     * @return the username
     */
    public String username() {
        return username;
    }

    /**
     * Gets the proxy password.
     *
     * @return the password
     */
    public String password() {
        return password;
    }

    // ==================== Callbacks ====================

    /**
     * Sets the callback for when delete is clicked.
     *
     * <p>No confirmation is required — the callback is invoked immediately
     * when the delete button is clicked.</p>
     *
     * @param onDelete the callback
     */
    public void setOnDelete(Runnable onDelete) {
        this.onDelete = onDelete;
    }
}