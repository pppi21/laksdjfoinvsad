package org.nodriver4j.scripts;

import com.google.gson.JsonObject;
import org.nodriver4j.core.Page;

import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * Warms up a browser profile by visiting common websites to collect cookies.
 * This makes the browser profile appear more natural to anti-bot systems.
 *
 * <p>ProfileWarmer uses the high-level {@link Page} API for navigation and
 * cookie retrieval, ensuring consistent behavior with user automation code.</p>
 */
public class ProfileWarmer {

    private static final int PAGE_LOAD_TIMEOUT_MS = 30000;
    private static final int SETTLE_TIME_MS = 2000;

    private final Page page;

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
     * Creates a ProfileWarmer that operates on the given page.
     *
     * @param page the Page instance to use for navigation and cookie retrieval
     */
    public ProfileWarmer(Page page) {
        this.page = page;
    }

    /**
     * Warms the browser profile by visiting predefined sites.
     * Blocks until warming is complete.
     *
     * @return a WarmingResult containing all collected cookies and any warnings
     */
    public WarmingResult warm() {
        List<String> warnings = new ArrayList<>();
        Map<String, List<Cookie>> cookiesBySite = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : WARM_SITES.entrySet()) {
            String url = entry.getKey();

            System.out.println("[ProfileWarmer] Visiting: " + url);

            try {
                navigateAndWait(url);

                // Allow time for cookies/trackers to set
                Thread.sleep(SETTLE_TIME_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                String warning = "Warming interrupted while visiting " + url;
                System.err.println("[ProfileWarmer] WARNING: " + warning);
                warnings.add(warning);
                break;
            } catch (Exception e) {
                String warning = "Failed to visit " + url + ": " + e.getMessage();
                System.err.println("[ProfileWarmer] WARNING: " + warning);
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

            // Log all cookies found
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
                        System.err.println("[ProfileWarmer] WARNING: " + warning);
                        warnings.add(warning);
                    }
                }
            }

        } catch (TimeoutException e) {
            String warning = "Failed to retrieve cookies: " + e.getMessage();
            System.err.println("[ProfileWarmer] WARNING: " + warning);
            warnings.add(warning);
        }

        int totalCookies = cookiesBySite.values().stream().mapToInt(List::size).sum();
        System.out.println("[ProfileWarmer] Warming complete. Total cookies collected: " + totalCookies);

        return new WarmingResult(cookiesBySite, warnings);
    }

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
            System.err.println("[ProfileWarmer] Page load timeout for " + url + ", continuing...");
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
     */
    private void logCookies(Map<String, List<Cookie>> cookiesBySite) {
        System.out.println("\n[ProfileWarmer] ===== Cookies Collected =====");
        for (Map.Entry<String, List<Cookie>> entry : cookiesBySite.entrySet()) {
            String domain = entry.getKey();
            List<Cookie> cookies = entry.getValue();
            System.out.println("\n  Domain: " + domain);
            for (Cookie cookie : cookies) {
                System.out.println("    - " + cookie.name + " = " + truncateValue(cookie.value, 50));
            }
        }
        System.out.println("\n[ProfileWarmer] ================================\n");
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