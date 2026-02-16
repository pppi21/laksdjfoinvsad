package org.nodriver4j.scripts;

import com.google.gson.JsonObject;
import org.nodriver4j.core.Page;
import org.nodriver4j.services.TaskLogger;

import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * Warms up a browser profile by visiting common websites to collect cookies.
 * This makes the browser profile appear more natural to anti-bot systems.
 *
 * <p>SessionWarmer uses the high-level {@link Page} API for navigation and
 * cookie retrieval, ensuring consistent behavior with user automation code.</p>
 *
 * <p>When a {@link TaskLogger} is provided, warming progress is reported to
 * the UI in real time (e.g., "Warming: Visiting google.com..."). When no
 * logger is provided, progress is printed to stdout only.</p>
 *
 * @see TaskLogger
 */
public class SessionWarmer {

    private static final int PAGE_LOAD_TIMEOUT_MS = 30000;
    private static final int SETTLE_TIME_MS = 2000;

    private final Page page;

    /**
     * Optional logger for live UI updates during warming.
     * When null, progress is written to stdout only.
     */
    private final TaskLogger logger;

    /**
     * Sites to visit during warming, with their expected cookie names.
     */
    private static final LinkedHashMap<String, List<String>> WARM_SITES = new LinkedHashMap<>();

    static {
        WARM_SITES.put("https://www.google.com", Arrays.asList("NID", "AEC", "SOCS"));
        WARM_SITES.put("https://www.youtube.com", Arrays.asList("VISITOR_INFO1_LIVE", "YSC", "PREF"));
        WARM_SITES.put("https://www.facebook.com", Arrays.asList("datr", "sb", "fr"));
        WARM_SITES.put("https://www.amazon.com", Arrays.asList("session-id", "i18n-prefs", "ubid-main"));
        WARM_SITES.put("https://www.x.com", Arrays.asList("guest_id", "gt", "ct0"));
    }

    /**
     * Creates a SessionWarmer that operates on the given page with no UI logging.
     *
     * <p>Progress is printed to stdout only. Use {@link #SessionWarmer(Page, TaskLogger)}
     * to enable live UI feedback.</p>
     *
     * @param page the Page instance to use for navigation and cookie retrieval
     */
    public SessionWarmer(Page page) {
        this(page, null);
    }

    /**
     * Creates a SessionWarmer that operates on the given page with optional
     * UI logging.
     *
     * @param page   the Page instance to use for navigation and cookie retrieval
     * @param logger the TaskLogger for live UI updates, or null for stdout only
     */
    public SessionWarmer(Page page, TaskLogger logger) {
        this.page = page;
        this.logger = logger;
    }

    /**
     * Warms the browser profile by visiting predefined sites.
     * Blocks until warming is complete.
     *
     * <p>When a {@link TaskLogger} is provided, each site visit is reported
     * to the UI so the user can see warming progress in real time.</p>
     *
     * @return a WarmingResult containing all collected cookies and any warnings
     */
    public WarmingResult warm() {
        List<String> warnings = new ArrayList<>();
        Map<String, List<Cookie>> cookiesBySite = new LinkedHashMap<>();

        int siteIndex = 0;
        int totalSites = WARM_SITES.size();

        for (Map.Entry<String, List<String>> entry : WARM_SITES.entrySet()) {
            String url = entry.getKey();
            siteIndex++;

            String visitMsg = "Warming: Visiting " + extractDomain(url) +
                    " (" + siteIndex + "/" + totalSites + ")";
            log(visitMsg);

            try {
                navigateAndWait(url);

                // Allow time for cookies/trackers to set
                Thread.sleep(SETTLE_TIME_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                String warning = "Warming interrupted while visiting " + url;
                warn(warning);
                warnings.add(warning);
                break;
            } catch (Exception e) {
                String warning = "Failed to visit " + url + ": " + e.getMessage();
                warn(warning);
                warnings.add(warning);
            }
        }

        // Collect all cookies after visiting all sites
        try {
            List<Cookie> allCookies = getAllCookies();

            // Group cookies by domain for logging
            for (Cookie cookie : allCookies) {
                String domain = cookie.domain;
                cookiesBySite.computeIfAbsent(domain, k -> new ArrayList<>()).add(cookie);
            }

            // Log all cookies found (verbose — stdout only, not UI)
            logCookies(cookiesBySite);

            // Verify expected cookies
            Set<String> foundCookieNames = new HashSet<>();
            for (Cookie cookie : allCookies) {
                foundCookieNames.add(cookie.name);
            }

            for (Map.Entry<String, List<String>> entry : WARM_SITES.entrySet()) {
                String url = entry.getKey();
                List<String> expectedCookies = entry.getValue();

                for (String expectedCookie : expectedCookies) {
                    if (!foundCookieNames.contains(expectedCookie)) {
                        String warning = "Expected cookie '" + expectedCookie + "' not found (from " + url + ")";
                        System.err.println("[SessionWarmer] WARNING: " + warning);
                        warnings.add(warning);
                    }
                }
            }

        } catch (TimeoutException e) {
            String warning = "Failed to retrieve cookies: " + e.getMessage();
            warn(warning);
            warnings.add(warning);
        }

        int totalCookies = cookiesBySite.values().stream().mapToInt(List::size).sum();
        String completeMsg = "Warming complete. " + totalCookies + " cookies collected.";
        log(completeMsg);

        return new WarmingResult(cookiesBySite, warnings);
    }

    // ==================== Logging Helpers ====================

    /**
     * Logs an informational message to both the UI (if logger is present)
     * and stdout.
     *
     * @param message the message to log
     */
    private void log(String message) {
        System.out.println("[SessionWarmer] " + message);
        if (logger != null) {
            logger.log(message);
        }
    }

    /**
     * Logs a warning message to stderr and, if a logger is present,
     * to the UI as an informational message (not error-colored, since
     * warming warnings are non-fatal).
     *
     * @param message the warning message
     */
    private void warn(String message) {
        System.err.println("[SessionWarmer] WARNING: " + message);
        if (logger != null) {
            logger.log(message);
        }
    }

    /**
     * Extracts a readable domain name from a URL for display purposes.
     *
     * <p>Example: "https://www.google.com" → "google.com"</p>
     *
     * @param url the full URL
     * @return the domain portion without "www."
     */
    private String extractDomain(String url) {
        try {
            String domain = url.replaceFirst("https?://", "");
            domain = domain.split("/")[0];
            if (domain.startsWith("www.")) {
                domain = domain.substring(4);
            }
            return domain;
        } catch (Exception e) {
            return url;
        }
    }

    // ==================== Navigation ====================

    /**
     * Navigates to a URL and waits for the page to load.
     *
     * @param url the URL to navigate to
     */
    private void navigateAndWait(String url) {
        try {
            page.navigate(url, PAGE_LOAD_TIMEOUT_MS);
        } catch (TimeoutException e) {
            // Page.navigate() handles timeouts gracefully internally,
            // but we catch here just in case the behavior changes
            System.err.println("[SessionWarmer] Page load timeout for " + url + ", continuing...");
        }
    }

    /**
     * Retrieves all cookies from the browser.
     *
     * @return list of all cookies
     * @throws TimeoutException if the cookie retrieval times out
     */
    private List<Cookie> getAllCookies() throws TimeoutException {
        List<JsonObject> rawCookies = page.getCookies();

        List<Cookie> cookies = new ArrayList<>();
        for (JsonObject obj : rawCookies) {
            Cookie cookie = new Cookie(
                    getStringOrNull(obj, "name"),
                    getStringOrNull(obj, "value"),
                    getStringOrNull(obj, "domain"),
                    getStringOrNull(obj, "path"),
                    obj.has("expires") ? obj.get("expires").getAsDouble() : -1,
                    obj.has("httpOnly") && obj.get("httpOnly").getAsBoolean(),
                    obj.has("secure") && obj.get("secure").getAsBoolean(),
                    obj.has("sameSite") ? obj.get("sameSite").getAsString() : "None"
            );
            cookies.add(cookie);
        }
        return cookies;
    }

    /**
     * Safely extracts a string value from a JsonObject.
     */
    private String getStringOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    /**
     * Logs collected cookies grouped by domain.
     *
     * <p>This is verbose debug output — always goes to stdout, never to the
     * UI logger (would be too noisy for the single-line log label).</p>
     */
    private void logCookies(Map<String, List<Cookie>> cookiesBySite) {
        System.out.println("\n[SessionWarmer] ===== Cookies Collected =====");
        for (Map.Entry<String, List<Cookie>> entry : cookiesBySite.entrySet()) {
            String domain = entry.getKey();
            List<Cookie> cookies = entry.getValue();
            System.out.println("\n  Domain: " + domain);
            for (Cookie cookie : cookies) {
                System.out.println("    - " + cookie.name + " = " + truncateValue(cookie.value, 50));
            }
        }
        System.out.println("\n[SessionWarmer] ================================\n");
    }

    /**
     * Truncates a string value for display purposes.
     */
    private String truncateValue(String value, int maxLength) {
        if (value == null) return "(null)";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }

    /**
     * Represents a browser cookie.
     */
    public static class Cookie {
        public final String name;
        public final String value;
        public final String domain;
        public final String path;
        public final double expires;
        public final boolean httpOnly;
        public final boolean secure;
        public final String sameSite;

        public Cookie(String name, String value, String domain, String path,
                      double expires, boolean httpOnly, boolean secure, String sameSite) {
            this.name = name;
            this.value = value;
            this.domain = domain;
            this.path = path;
            this.expires = expires;
            this.httpOnly = httpOnly;
            this.secure = secure;
            this.sameSite = sameSite;
        }

        @Override
        public String toString() {
            return "Cookie{name='" + name + "', domain='" + domain + "', value='" + value + "'}";
        }
    }

    /**
     * Result of a warming operation.
     */
    public static class WarmingResult {
        private final Map<String, List<Cookie>> cookiesByDomain;
        private final List<String> warnings;

        public WarmingResult(Map<String, List<Cookie>> cookiesByDomain, List<String> warnings) {
            this.cookiesByDomain = cookiesByDomain;
            this.warnings = warnings;
        }

        public Map<String, List<Cookie>> getCookiesByDomain() {
            return cookiesByDomain;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public int getTotalCookieCount() {
            return cookiesByDomain.values().stream().mapToInt(List::size).sum();
        }
    }
}