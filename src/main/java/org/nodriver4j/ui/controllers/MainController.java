package org.nodriver4j.ui.controllers;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Controller for the main application layout.
 *
 * <p>Manages sidebar navigation and dynamically loads pages into the content area.
 * Only one page is visible at a time.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Handle sidebar navigation clicks</li>
 *   <li>Load and cache page FXML files</li>
 *   <li>Switch between pages in the content area</li>
 *   <li>Manage active state styling on nav items</li>
 * </ul>
 */
public class MainController implements Initializable {

    // ==================== FXML Injected Fields ====================

    @FXML
    private VBox navContainer;

    @FXML
    private HBox navTasks;

    @FXML
    private HBox navProfiles;

    @FXML
    private HBox navProxies;

    @FXML
    private HBox navSettings;

    @FXML
    private StackPane contentArea;

    // ==================== Internal State ====================

    /**
     * Available pages in the application.
     */
    public enum Page {
        TASK_MANAGER("fxml/task-manager.fxml"),
        PROFILE_MANAGER("fxml/profile-manager.fxml"),
        PROXY_MANAGER("fxml/proxy-manager.fxml"),
        SETTINGS("fxml/settings.fxml");

        private final String fxmlPath;

        Page(String fxmlPath) {
            this.fxmlPath = fxmlPath;
        }

        public String fxmlPath() {
            return fxmlPath;
        }
    }

    /**
     * Cache of loaded pages to avoid reloading FXML on every navigation.
     */
    private final Map<Page, Node> pageCache = new HashMap<>();

    /**
     * Cache of page controllers for accessing page-specific methods.
     */
    private final Map<Page, Object> controllerCache = new HashMap<>();

    /**
     * Currently active page.
     */
    private Page currentPage;

    /**
     * Currently active nav item (for styling).
     */
    private HBox activeNavItem;

    // ==================== Initialization ====================

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        System.out.println("[MainController] Initializing...");

        // Set initial active nav item
        activeNavItem = navTasks;

        // Load the default page (Task Manager)
        navigateTo(Page.TASK_MANAGER);

        System.out.println("[MainController] Initialized successfully");
    }

    // ==================== Navigation Handlers ====================

    @FXML
    private void onNavTasksClicked() {
        navigateTo(Page.TASK_MANAGER, navTasks);
    }

    @FXML
    private void onNavProfilesClicked() {
        // Future: navigateTo(Page.PROFILE_MANAGER, navProfiles);
        System.out.println("[MainController] Profiles page not yet implemented");
    }

    @FXML
    private void onNavProxiesClicked() {
        // Future: navigateTo(Page.PROXY_MANAGER, navProxies);
        System.out.println("[MainController] Proxies page not yet implemented");
    }

    @FXML
    private void onNavSettingsClicked() {
        // Future: navigateTo(Page.SETTINGS, navSettings);
        System.out.println("[MainController] Settings page not yet implemented");
    }

    // ==================== Navigation Logic ====================

    /**
     * Navigates to a page without changing nav item styling.
     * Used for initial page load.
     *
     * @param page the page to navigate to
     */
    private void navigateTo(Page page) {
        navigateTo(page, null);
    }

    /**
     * Navigates to a page and updates the active nav item styling.
     *
     * @param page    the page to navigate to
     * @param navItem the nav item to mark as active (can be null)
     */
    private void navigateTo(Page page, HBox navItem) {
        if (page == currentPage) {
            return; // Already on this page
        }

        System.out.println("[MainController] Navigating to: " + page);

        // Load or retrieve cached page
        Node pageNode = getOrLoadPage(page);
        if (pageNode == null) {
            System.err.println("[MainController] Failed to load page: " + page);
            return;
        }

        // Update content area
        contentArea.getChildren().clear();
        contentArea.getChildren().add(pageNode);

        // Update nav item styling
        if (navItem != null) {
            updateActiveNavItem(navItem);
        }

        currentPage = page;
    }

    /**
     * Gets a cached page or loads it from FXML.
     *
     * @param page the page to get
     * @return the page node, or null if loading failed
     */
    private Node getOrLoadPage(Page page) {
        // Check cache first
        if (pageCache.containsKey(page)) {
            return pageCache.get(page);
        }

        // Load from FXML
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("../" + page.fxmlPath()));
            Node pageNode = loader.load();

            // Cache the page and its controller
            pageCache.put(page, pageNode);
            controllerCache.put(page, loader.getController());

            System.out.println("[MainController] Loaded page: " + page);
            return pageNode;

        } catch (IOException e) {
            System.err.println("[MainController] Error loading page " + page + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Updates the active nav item styling.
     *
     * @param newActiveItem the new active nav item
     */
    private void updateActiveNavItem(HBox newActiveItem) {
        if (activeNavItem != null) {
            activeNavItem.getStyleClass().remove("active");
        }

        newActiveItem.getStyleClass().add("active");
        activeNavItem = newActiveItem;
    }

    // ==================== Public API ====================

    /**
     * Gets the controller for a specific page.
     *
     * @param page the page
     * @param <T>  the controller type
     * @return the controller, or null if page hasn't been loaded
     */
    @SuppressWarnings("unchecked")
    public <T> T getPageController(Page page) {
        return (T) controllerCache.get(page);
    }

    /**
     * Gets the TaskManagerController.
     *
     * @return the TaskManagerController, or null if not loaded
     */
    public TaskManagerController taskManagerController() {
        return getPageController(Page.TASK_MANAGER);
    }

    /**
     * Gets the currently active page.
     *
     * @return the current page
     */
    public Page currentPage() {
        return currentPage;
    }

    /**
     * Programmatically navigates to the Task Manager page.
     */
    public void showTaskManager() {
        navigateTo(Page.TASK_MANAGER, navTasks);
    }
}