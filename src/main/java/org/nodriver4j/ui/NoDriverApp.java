package org.nodriver4j.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.nodriver4j.ui.components.WindowTitleBar;
import org.nodriver4j.ui.util.WindowResizeHelper;

import java.io.IOException;
import java.net.URL;

/**
 * Main entry point for the NoDriver4j desktop application.
 *
 * <p>This class initializes the JavaFX application with a custom undecorated
 * window that includes:</p>
 * <ul>
 *   <li>Custom dark title bar with minimize, maximize, close buttons</li>
 *   <li>Full window resize functionality (drag edges and corners)</li>
 *   <li>Dark theme styling</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // From IDE or command line
 * NoDriverApp.main(args);
 * }</pre>
 */
public class NoDriverApp extends Application {

    private static final String APP_TITLE = "NoDriver4j";
    private static final int DEFAULT_WIDTH = 1200;
    private static final int DEFAULT_HEIGHT = 800;
    private static final int MIN_WIDTH = 900;
    private static final int MIN_HEIGHT = 600;
    private static final int RESIZE_BORDER = 6;

    /**
     * Application entry point.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws IOException {
        // Load the main layout (sidebar + content area)
        Parent content = loadFXML("fxml/main.fxml");

        // Create custom title bar
        WindowTitleBar titleBar = new WindowTitleBar(primaryStage, APP_TITLE);

        // Create root container: title bar on top, content below
        BorderPane root = new BorderPane();
        root.setTop(titleBar);
        root.setCenter(content);
        root.setStyle("-fx-background-color: #292929;");

        // Create the scene
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);

        // Apply the dark theme
        applyStylesheet(scene, "css/dark-theme.css");

        // Configure the stage (undecorated = no native title bar)
        primaryStage.initStyle(StageStyle.UNDECORATED);
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(MIN_WIDTH);
        primaryStage.setMinHeight(MIN_HEIGHT);

        // Attach resize functionality
        WindowResizeHelper.attach(scene, primaryStage, RESIZE_BORDER, MIN_WIDTH, MIN_HEIGHT);

        // Center on screen and show
        primaryStage.centerOnScreen();
        primaryStage.show();

        System.out.println("[NoDriverApp] Application started successfully");
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        System.out.println("[NoDriverApp] Application shutting down...");
        // Future: Clean up resources, save state, etc.
    }

    // ==================== Resource Loading ====================

    /**
     * Loads an FXML file from the resources directory.
     *
     * @param resourcePath path relative to the ui package
     * @return the loaded Parent node
     * @throws IOException if the FXML file cannot be loaded
     */
    private Parent loadFXML(String resourcePath) throws IOException {
        URL fxmlUrl = getResourceURL(resourcePath);
        if (fxmlUrl == null) {
            throw new IOException("FXML file not found: " + resourcePath);
        }

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        return loader.load();
    }

    /**
     * Applies a stylesheet to a scene.
     *
     * @param scene        the scene to style
     * @param resourcePath path relative to the ui package
     */
    private void applyStylesheet(Scene scene, String resourcePath) {
        URL cssUrl = getResourceURL(resourcePath);
        if (cssUrl == null) {
            System.err.println("[NoDriverApp] WARNING: Stylesheet not found: " + resourcePath);
            return;
        }

        scene.getStylesheets().add(cssUrl.toExternalForm());
    }

    /**
     * Gets a resource URL relative to this class's package.
     *
     * @param resourcePath the resource path
     * @return the URL, or null if not found
     */
    private URL getResourceURL(String resourcePath) {
        return NoDriverApp.class.getResource(resourcePath);
    }
}