package org.nodriver4j.ui.profile;

import javafx.scene.control.Label;
import org.nodriver4j.ui.components.GroupCard;
import org.nodriver4j.ui.task.TaskGroupCard;

/**
 * A card component that displays profile group information.
 *
 * <p>Extends {@link GroupCard} with profile-group-specific content:</p>
 * <ul>
 *   <li>Profile count (in the stats area)</li>
 * </ul>
 *
 * <p>Common card functionality — layout, delete confirmation, click handling,
 * group ID and name management — is inherited from {@link GroupCard}.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display profile count for the group</li>
 *   <li>Provide update methods for dynamic data changes</li>
 *   <li>Apply profile-group-specific CSS styling</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Card layout skeleton (inherited from {@link GroupCard})</li>
 *   <li>Delete confirmation flow (inherited from {@link GroupCard})</li>
 *   <li>Click handling (inherited from {@link GroupCard})</li>
 *   <li>Database operations (controller handles persistence)</li>
 *   <li>CSV import (dialog handles)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProfileGroupCard card = new ProfileGroupCard(1L, "Uber Accounts", 25);
 * card.setOnClick(() -> System.out.println("Card clicked!"));
 * card.setOnDelete(() -> System.out.println("Delete confirmed!"));
 * flowPane.getChildren().add(card);
 * }</pre>
 *
 * @see GroupCard
 * @see TaskGroupCard
 */
public class ProfileGroupCard extends GroupCard {

    // ==================== UI Components ====================

    private final Label profileCountLabel;

    // ==================== Data ====================

    private int profileCount;

    // ==================== Constructor ====================

    /**
     * Creates a new ProfileGroupCard.
     *
     * @param groupId      the database ID of the profile group
     * @param groupName    the name of the profile group
     * @param profileCount the total number of profiles in this group
     */
    public ProfileGroupCard(long groupId, String groupName, int profileCount) {
        super(groupId, groupName);

        this.profileCount = profileCount;

        // Apply profile-group-specific style class for targeted styling
        getStyleClass().add("profile-group-card");

        // ---- Stats area: profile count ----
        profileCountLabel = new Label(formatProfileCount());
        profileCountLabel.getStyleClass().add("group-card-stat");

        statsBox().getChildren().add(profileCountLabel);
    }

    // ==================== Formatting ====================

    private String formatProfileCount() {
        return profileCount + (profileCount == 1 ? " profile" : " profiles");
    }

    // ==================== Data Updates ====================

    /**
     * Updates the profile count display.
     *
     * @param count the new profile count
     */
    public void setProfileCount(int count) {
        this.profileCount = count;
        profileCountLabel.setText(formatProfileCount());
    }

    // ==================== Getters ====================

    /**
     * Gets the profile count.
     *
     * @return the profile count
     */
    public int profileCount() {
        return profileCount;
    }
}