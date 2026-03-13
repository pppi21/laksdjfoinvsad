package org.nodriver4j.ui.profile;

import javafx.fxml.FXML;
import org.nodriver4j.persistence.Database;
import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.entity.ProfileGroupEntity;
import org.nodriver4j.persistence.importer.ProfileImporter;
import org.nodriver4j.persistence.repository.ProfileGroupRepository;
import org.nodriver4j.persistence.repository.ProfileRepository;
import org.nodriver4j.ui.controllers.GroupManagerController;
import org.nodriver4j.ui.controllers.MainController;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * Controller for the Profile Manager page.
 *
 * <p>Manages profile group creation, display, and interaction. Extends
 * {@link GroupManagerController} which provides the shared page layout,
 * card management, empty state toggle, and error alert infrastructure.</p>
 *
 * <p>This controller is paired with {@code group-manager.fxml} (shared layout).
 * {@link MainController} assigns this controller programmatically via
 * {@code loader.setController(new ProfileManagerController())} before loading.</p>
 *
 * <h2>Creation Flow</h2>
 * <ol>
 *   <li>User clicks the "+" button</li>
 *   <li>{@link CreateProfileGroupDialog} collects group name + CSV file path</li>
 *   <li>{@link ProfileImporter} parses the CSV into entities</li>
 *   <li>Group is persisted first (persist-first pattern)</li>
 *   <li>All parsed profiles are batch-saved with the group ID</li>
 *   <li>A {@link ProfileGroupCard} is inserted at the beginning of the grid</li>
 *   <li>If any CSV rows failed to parse, a warning alert is shown</li>
 * </ol>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Provide profile-manager-specific page text</li>
 *   <li>Load persisted profile groups on initialization</li>
 *   <li>Handle "Add" button → dialog → import → persist → card</li>
 *   <li>Handle card click → navigation to profile group detail</li>
 *   <li>Handle card delete → persist → remove card</li>
 *   <li>Build {@link ProfileGroupCard} instances with wired callbacks</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>FXML injection for shared layout (inherited from {@link GroupManagerController})</li>
 *   <li>Card list / grid synchronization (inherited)</li>
 *   <li>Empty state toggle (inherited)</li>
 *   <li>Error alert display (inherited)</li>
 *   <li>CSV parsing logic (delegated to {@link ProfileImporter})</li>
 *   <li>SQL queries (delegated to repositories)</li>
 *   <li>Profile group detail page (separate controller)</li>
 * </ul>
 *
 * @see GroupManagerController
 * @see ProfileGroupCard
 * @see CreateProfileGroupDialog
 * @see ProfileImporter
 */
public class ProfileManagerController extends GroupManagerController<ProfileGroupCard> {

    // ==================== Repositories ====================

    private final ProfileGroupRepository profileGroupRepository = new ProfileGroupRepository();
    private final ProfileRepository profileRepository = new ProfileRepository();

    // ==================== Services ====================

    private final ProfileImporter profileImporter = new ProfileImporter();

    // ==================== Callbacks ====================

    /**
     * Callback invoked when a profile group card is clicked.
     * Set by {@link MainController} to navigate to the profile group detail page.
     * Accepts the group ID as a parameter.
     */
    private LongConsumer onNavigateToGroup;

    // ==================== Page Text ====================

    @Override
    protected String pageTitle() {
        return "Profiles";
    }

    @Override
    protected String pageSubtitle() {
        return "Import and manage your profile groups";
    }

    @Override
    protected String emptyStateIcon() {
        return "👤";
    }

    @Override
    protected String emptyStateTitle() {
        return "No Profile Groups";
    }

    @Override
    protected String emptyStateDescription() {
        return "Click the + button to import profiles from a CSV file";
    }

    // ==================== Data Loading ====================

    /**
     * Loads all profile groups from the database and creates cards for them.
     *
     * <p>Groups are returned newest-first from the repository, which matches
     * the desired grid display order. Profile counts are queried per group.</p>
     */
    @Override
    protected void loadGroups() {
        try {
            List<ProfileGroupEntity> groups = profileGroupRepository.findAll();

            for (ProfileGroupEntity group : groups) {
                int profileCount = (int) profileRepository.countByGroupId(group.id());
                ProfileGroupCard card = buildCard(group.id(), group.name(), profileCount);
                addCard(card);
            }

            System.out.println("[ProfileManagerController] Loaded " + groups.size()
                    + " profile groups from database");

        } catch (Database.DatabaseException e) {
            System.err.println("[ProfileManagerController] Failed to load profile groups: "
                    + e.getMessage());
            showErrorAlert("Failed to Load Profile Groups",
                    "Could not load profile groups from the database.",
                    e.getMessage());
        }
    }

    // ==================== Add Button ====================

    /**
     * Handles the "Add" button click.
     *
     * <p>Opens the CSV import dialog, then orchestrates the import flow:
     * parse CSV → persist group → batch-persist profiles → create card.
     * Shows a warning alert if any CSV rows failed to parse.</p>
     */
    @FXML
    @Override
    protected void onAddClicked() {
        System.out.println("[ProfileManagerController] Add button clicked");

        Optional<CreateProfileGroupDialog.Result> result =
                CreateProfileGroupDialog.show(ownerWindow());

        result.ifPresent(this::createProfileGroup);
    }

    // ==================== Profile Group Management ====================

    /**
     * Creates a new profile group from dialog data.
     *
     * <p>Flow:</p>
     * <ol>
     *   <li>Import CSV file via {@link ProfileImporter}</li>
     *   <li>Persist the group entity (persist-first pattern)</li>
     *   <li>Set group ID on all parsed profile entities</li>
     *   <li>Batch-save all profiles</li>
     *   <li>Build card and insert at beginning of grid</li>
     *   <li>Show warning alert if any rows failed to parse</li>
     * </ol>
     *
     * @param data the dialog result containing group name and CSV path
     */
    private void createProfileGroup(CreateProfileGroupDialog.Result data) {
        System.out.println("[ProfileManagerController] Creating profile group: "
                + data.groupName() + " from " + data.csvPath());

        // Step 1: Parse CSV
        ProfileImporter.ImportResult importResult;
        try {
            importResult = profileImporter.importFromFile(data.csvPath(), data.groupName());
        } catch (IOException e) {
            System.err.println("[ProfileManagerController] Failed to read CSV: " + e.getMessage());
            showErrorAlert("Failed to Import CSV",
                    "Could not read the CSV file.",
                    e.getMessage());
            return;
        }

        if (importResult.profileCount() == 0 && !importResult.hasWarnings()) {
            showErrorAlert("No Profiles Found",
                    "The CSV file did not contain any valid profiles.",
                    "Ensure the file has a header row followed by data rows "
                            + "with at least 31 columns.");
            return;
        }

        // Step 2: Persist group first
        ProfileGroupEntity groupEntity;
        try {
            groupEntity = importResult.group();
            groupEntity = profileGroupRepository.save(groupEntity);
        } catch (Database.DatabaseException e) {
            System.err.println("[ProfileManagerController] Failed to save profile group: "
                    + e.getMessage());
            showErrorAlert("Failed to Create Profile Group",
                    "Could not save the profile group to the database.",
                    e.getMessage());
            return;
        }

        // Step 3: Set group ID on all profiles and batch-save
        long groupId = groupEntity.id();
        List<ProfileEntity> profiles = importResult.profiles();

        try {
            for (ProfileEntity profile : profiles) {
                profile.groupId(groupId);
            }
            profileRepository.saveAll(profiles);
        } catch (Database.DatabaseException e) {
            System.err.println("[ProfileManagerController] Failed to save profiles: "
                    + e.getMessage());
            // Group was created but profiles failed — clean up the empty group
            try {
                profileGroupRepository.deleteById(groupId);
            } catch (Database.DatabaseException cleanupEx) {
                System.err.println("[ProfileManagerController] Failed to clean up group: "
                        + cleanupEx.getMessage());
            }
            showErrorAlert("Failed to Save Profiles",
                    "The profile group was created but profiles could not be saved.",
                    e.getMessage());
            return;
        }

        // Step 4: Build card and add to grid (newest-first)
        int profileCount = profiles.size();
        ProfileGroupCard card = buildCard(groupId, data.groupName(), profileCount);
        addCardFirst(card);

        System.out.println("[ProfileManagerController] Created profile group '"
                + data.groupName() + "' with " + profileCount + " profiles");

        // Step 5: Show warnings if any rows failed
        if (importResult.hasWarnings()) {
            String warningText = String.join("\n", importResult.warnings());
            showErrorAlert("Import Warnings",
                    importResult.warningCount() + " row(s) could not be parsed.",
                    warningText);
        }
    }

    // ==================== Card Building ====================

    /**
     * Builds a {@link ProfileGroupCard} with click and delete callbacks wired.
     *
     * @param groupId      the database ID
     * @param groupName    the group name
     * @param profileCount the number of profiles in the group
     * @return a fully configured card
     */
    private ProfileGroupCard buildCard(long groupId, String groupName, int profileCount) {
        ProfileGroupCard card = new ProfileGroupCard(groupId, groupName, profileCount);

        // Click → navigate to profile group detail
        card.setOnClick(() -> {
            if (onNavigateToGroup != null) {
                onNavigateToGroup.accept(groupId);
            }
        });

        // Delete confirmed → remove from DB and grid
        card.setOnDelete(() -> onProfileGroupDeleted(card));

        return card;
    }

    // ==================== Delete ====================

    /**
     * Handles a confirmed profile group deletion.
     *
     * <p>Deletes the group from the database (CASCADE deletes child profiles),
     * then removes the card from the grid.</p>
     *
     * @param card the card representing the group to delete
     */
    private void onProfileGroupDeleted(ProfileGroupCard card) {
        long groupId = card.groupId();
        String groupName = card.groupName();

        System.out.println("[ProfileManagerController] Deleting profile group: "
                + groupName + " (ID " + groupId + ")");

        try {
            profileGroupRepository.deleteById(groupId);
            removeCard(card);

            System.out.println("[ProfileManagerController] Deleted profile group: " + groupName);

        } catch (Database.DatabaseException e) {
            System.err.println("[ProfileManagerController] Failed to delete profile group: "
                    + e.getMessage());
            showErrorAlert("Failed to Delete Profile Group",
                    "Could not delete '" + groupName + "' from the database.",
                    e.getMessage());
        }
    }

    // ==================== Navigation Callback ====================

    /**
     * Sets the callback for navigating to a profile group detail page.
     *
     * <p>Called by {@link MainController} during initialization to wire
     * cross-page navigation.</p>
     *
     * @param callback a consumer that accepts the group ID
     */
    public void setOnNavigateToGroup(LongConsumer callback) {
        this.onNavigateToGroup = callback;
    }
}