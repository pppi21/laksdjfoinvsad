package org.nodriver4j.core;

/**
 * Determines when a navigation is considered complete.
 *
 * <p>Used by {@link NavigationController#navigate} and {@link Page#navigate}
 * to control how long the caller blocks after initiating a page load.</p>
 */
public enum WaitUntil {

    /**
     * Return as soon as the CDP {@code Page.navigate} response arrives.
     * No lifecycle event is awaited.
     */
    COMMIT,

    /**
     * Wait for the {@code DOMContentLoaded} event — the HTML is fully parsed
     * and all deferred scripts have executed, but images/stylesheets/subframes
     * may still be loading.
     */
    DOM_CONTENT_LOADED,

    /**
     * Wait for the {@code load} event — all resources (images, stylesheets,
     * subframes) have finished loading.
     */
    LOAD,

    /**
     * Wait for network activity to settle after navigation.
     */
    NETWORK_IDLE
}
