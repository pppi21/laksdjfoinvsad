package org.nodriver4j.ui.components;

import javafx.scene.control.Label;

/**
 * A card component that displays proxy group information.
 *
 * <p>Extends {@link GroupCard} with proxy-group-specific content:</p>
 * <ul>
 *   <li>Proxy count (in the stats area)</li>
 * </ul>
 *
 * <p>Common card functionality — layout, delete confirmation, click handling,
 * group ID and name management — is inherited from {@link GroupCard}.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Display proxy count for the group</li>
 *   <li>Provide update methods for dynamic data changes</li>
 *   <li>Apply proxy-group-specific CSS styling</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Card layout skeleton (inherited from {@link GroupCard})</li>
 *   <li>Delete confirmation flow (inherited from {@link GroupCard})</li>
 *   <li>Click handling (inherited from {@link GroupCard})</li>
 *   <li>Database operations (controller handles persistence)</li>
 *   <li>Text file import (dialog handles)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProxyGroupCard card = new ProxyGroupCard(1L, "Lightning Proxies", 50);
 * card.setOnClick(() -> System.out.println("Card clicked!"));
 * card.setOnDelete(() -> System.out.println("Delete confirmed!"));
 * flowPane.getChildren().add(card);
 * }</pre>
 *
 * @see GroupCard
 * @see ProfileGroupCard
 */
public class ProxyGroupCard extends GroupCard {

    // ==================== UI Components ====================

    private final Label proxyCountLabel;

    // ==================== Data ====================

    private int proxyCount;

    // ==================== Constructor ====================

    /**
     * Creates a new ProxyGroupCard.
     *
     * @param groupId    the database ID of the proxy group
     * @param groupName  the name of the proxy group
     * @param proxyCount the total number of proxies in this group
     */
    public ProxyGroupCard(long groupId, String groupName, int proxyCount) {
        super(groupId, groupName);

        this.proxyCount = proxyCount;

        // Apply proxy-group-specific style class for targeted styling
        getStyleClass().add("proxy-group-card");

        // ---- Stats area: proxy count ----
        proxyCountLabel = new Label(formatProxyCount());
        proxyCountLabel.getStyleClass().add("group-card-stat");

        statsBox().getChildren().add(proxyCountLabel);
    }

    // ==================== Formatting ====================

    private String formatProxyCount() {
        return proxyCount + (proxyCount == 1 ? " proxy" : " proxies");
    }

    // ==================== Data Updates ====================

    /**
     * Updates the proxy count display.
     *
     * @param count the new proxy count
     */
    public void setProxyCount(int count) {
        this.proxyCount = count;
        proxyCountLabel.setText(formatProxyCount());
    }

    // ==================== Getters ====================

    /**
     * Gets the proxy count.
     *
     * @return the proxy count
     */
    public int proxyCount() {
        return proxyCount;
    }
}