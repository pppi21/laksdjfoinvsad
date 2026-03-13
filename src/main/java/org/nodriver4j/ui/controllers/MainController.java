package org.nodriver4j.ui.controllers;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.nodriver4j.ui.profile.detail.ProfileGroupDetailController;
import org.nodriver4j.ui.profile.ProfileManagerController;
import org.nodriver4j.ui.proxy.detail.ProxyGroupDetailController;
import org.nodriver4j.ui.proxy.ProxyManagerController;
import org.nodriver4j.ui.task.detail.TaskGroupDetailController;
import org.nodriver4j.ui.task.TaskManagerController;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Supplier;

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
 *   <li>Wire cross-page navigation callbacks</li>
 *   <li>Handle parameterized page navigation (e.g., task group detail)</li>
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
     *
     * <p>Pages that use a shared FXML layout (e.g., {@code group-manager.fxml})
     * provide a {@code controllerFactory} so the controller can be assigned
     * programmatically before FXML loading. Pages with an {@code fx:controller}
     * attribute in their FXML use {@code null} (the default).</p>
     */
    public enum Page {
        TASK_MANAGER("fxml/group-manager.fxml", TaskManagerController::new),
        TASK_GROUP_DETAIL("fxml/task-group-detail.fxml"),
        PROFILE_MANAGER("fxml/group-manager.fxml", ProfileManagerController::new),
        PROFILE_GROUP_DETAIL("fxml/profile-group-detail.fxml"),
        PROXY_MANAGER("fxml/group-manager.fxml", ProxyManagerController::new),
        PROXY_GROUP_DETAIL("fxml/proxy-group-detail.fxml"),
        SETTINGS("fxml/settings.fxml");

        private final String fxmlPath;
        private final Supplier<Object> controllerFactory;

        Page(String fxmlPath) {
            this(fxmlPath, null);
        }

        Page(String fxmlPath, Supplier<Object> controllerFactory) {
            this.fxmlPath = fxmlPath;
            this.controllerFactory = controllerFactory;
        }

        public String fxmlPath() {
            return fxmlPath;
        }

        public Supplier<Object> controllerFactory() {
            return controllerFactory;
        }

        public boolean hasControllerFactory() {
            return controllerFactory != null;
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

        // Wire navigation callback so TaskManagerController can open group details
        TaskManagerController tmc = taskManagerController();
        if (tmc != null) {
            tmc.setOnNavigateToGroup(this::showTaskGroupDetail);
        }

        // Pre-load ProfileManager and wire navigation callback
        getOrLoadPage(Page.PROFILE_MANAGER);
        ProfileManagerController pmc = profileManagerController();
        if (pmc != null) {
            pmc.setOnNavigateToGroup(this::showProfileGroupDetail);
        }

        // Pre-load ProxyManager and wire navigation callback
        getOrLoadPage(Page.PROXY_MANAGER);
        ProxyManagerController xmc = proxyManagerController();
        if (xmc != null) {
            xmc.setOnNavigateToGroup(this::showProxyGroupDetail);
        }

        System.out.println("[MainController] Initialized successfully");
    }

    // ==================== Navigation Handlers ====================

    @FXML
    private void onNavTasksClicked() {
        navigateTo(Page.TASK_MANAGER, navTasks);
    }

    @FXML
    private void onNavProfilesClicked() {
        navigateTo(Page.PROFILE_MANAGER, navProfiles);
    }

    @FXML
    private void onNavProxiesClicked() {
        navigateTo(Page.PROXY_MANAGER, navProxies);
    }

    @FXML
    private void onNavSettingsClicked() {
        navigateTo(Page.SETTINGS, navSettings);
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
     * <p>If the {@link Page} provides a controller factory, the controller
     * is created and assigned via {@link FXMLLoader#setController(Object)}
     * before loading. This supports shared FXML layouts (like
     * {@code group-manager.fxml}) that omit the {@code fx:controller}
     * attribute.</p>
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
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/nodriver4j/ui/" + page.fxmlPath()));

            // Set controller programmatically for shared FXML layouts
            if (page.hasControllerFactory()) {
                loader.setController(page.controllerFactory().get());
            }

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
     * Gets the ProfileManagerController.
     *
     * @return the ProfileManagerController, or null if not loaded
     */
    public ProfileManagerController profileManagerController() {
        return getPageController(Page.PROFILE_MANAGER);
    }

    /**
     * Gets the ProxyManagerController.
     *
     * @return the ProxyManagerController, or null if not loaded
     */
    public ProxyManagerController proxyManagerController() {
        return getPageController(Page.PROXY_MANAGER);
    }

    /**
     * Gets the TaskGroupDetailController.
     *
     * @return the TaskGroupDetailController, or null if not loaded
     */
    public TaskGroupDetailController taskGroupDetailController() {
        return getPageController(Page.TASK_GROUP_DETAIL);
    }

    /**
     * Gets the ProfileGroupDetailController.
     *
     * @return the ProfileGroupDetailController, or null if not loaded
     */
    public ProfileGroupDetailController profileGroupDetailController() {
        return getPageController(Page.PROFILE_GROUP_DETAIL);
    }

    /**
     * Gets the ProxyGroupDetailController.
     *
     * @return the ProxyGroupDetailController, or null if not loaded
     */
    public ProxyGroupDetailController proxyGroupDetailController() {
        return getPageController(Page.PROXY_GROUP_DETAIL);
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

        TaskManagerController tmc = taskManagerController();
        if (tmc != null) {
            tmc.refreshCardStats();
        }
    }

    /**
     * Programmatically navigates to the Profile Manager page.
     */
    public void showProfileManager() {
        navigateTo(Page.PROFILE_MANAGER, navProfiles);
    }

    /**
     * Programmatically navigates to the Proxy Manager page.
     */
    public void showProxyManager() {
        navigateTo(Page.PROXY_MANAGER, navProxies);
    }

    /**
     * Navigates to the Task Group Detail page for a specific group.
     *
     * <p>The FXML is loaded and cached on first call. On every call,
     * {@link TaskGroupDetailController#loadGroup(long)} is invoked to
     * refresh the page with the specified group's data. The back callback
     * is wired to return to the Task Manager.</p>
     *
     * <p>The Tasks nav item remains active since the detail page is
     * a sub-page of the Task Manager.</p>
     *
     * @param groupId the database ID of the task group to display
     */
    public void showTaskGroupDetail(long groupId) {
        System.out.println("[MainController] Showing task group detail for group " + groupId);

        // Navigate to the detail page if not already there
        if (currentPage != Page.TASK_GROUP_DETAIL) {
            navigateTo(Page.TASK_GROUP_DETAIL);
        }

        // Load the group data (always called, even if already on the page)
        TaskGroupDetailController controller = taskGroupDetailController();
        if (controller != null) {
            controller.setOnBack(this::showTaskManager);
            controller.loadGroup(groupId);
        }
    }

    /**
     * Navigates to the Profile Group Detail page for a specific group.
     *
     * <p>The FXML is loaded and cached on first call. On every call,
     * {@link ProfileGroupDetailController#loadGroup(long)} is invoked to
     * refresh the page with the specified group's data. The back callback
     * is wired to return to the Profile Manager.</p>
     *
     * <p>The Profiles nav item remains active since the detail page is
     * a sub-page of the Profile Manager.</p>
     *
     * @param groupId the database ID of the profile group to display
     */
    public void showProfileGroupDetail(long groupId) {
        System.out.println("[MainController] Showing profile group detail for group " + groupId);

        // Navigate to the detail page if not already there
        if (currentPage != Page.PROFILE_GROUP_DETAIL) {
            navigateTo(Page.PROFILE_GROUP_DETAIL);
        }

        // Load the group data (always called, even if already on the page)
        ProfileGroupDetailController controller = profileGroupDetailController();
        if (controller != null) {
            controller.setOnBack(this::showProfileManager);
            controller.loadGroup(groupId);
        }
    }

    /**
     * Navigates to the Proxy Group Detail page for a specific group.
     *
     * <p>The FXML is loaded and cached on first call. On every call,
     * {@link ProxyGroupDetailController#loadGroup(long)} is invoked to
     * refresh the page with the specified group's data. The back callback
     * is wired to return to the Proxy Manager.</p>
     *
     * <p>The Proxies nav item remains active since the detail page is
     * a sub-page of the Proxy Manager.</p>
     *
     * @param groupId the database ID of the proxy group to display
     */
    public void showProxyGroupDetail(long groupId) {
        System.out.println("[MainController] Showing proxy group detail for group " + groupId);

        // Navigate to the detail page if not already there
        if (currentPage != Page.PROXY_GROUP_DETAIL) {
            navigateTo(Page.PROXY_GROUP_DETAIL);
        }

        // Load the group data (always called, even if already on the page)
        ProxyGroupDetailController controller = proxyGroupDetailController();
        if (controller != null) {
            controller.setOnBack(this::showProxyManager);
            controller.loadGroup(groupId);
        }
    }
}