package org.nodriver4j.fingerprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.nodriver4j.cdp.CDPClient;

import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Immutable container for platform-related fingerprint data.
 *
 * Contains OS/hardware-dependent metadata that is applied via CDP's
 * Emulation.setUserAgentOverride. This includes navigator.platform,
 * and User-Agent Client Hints platform metadata.
 *
 * NOTE: This class does NOT store the User-Agent string or Client Hints
 * brand lists (brands, fullVersionList). These are Chrome-version-dependent
 * and are queried from the actual browser at runtime to avoid detection.
 */
public final class PlatformInfo {

    /**
     * navigator.vendor is always "Google Inc." for Chrome browsers.
     * This is distinct from WebGL vendor which varies by GPU.
     */
    private static final String NAVIGATOR_VENDOR = "Google Inc.";

    // Platform fields (OS/hardware-dependent, safe to spoof)
    private final String platform;         // navigator.platform (e.g., "Win32")
    private final String chPlatform;       // Client Hints platform (e.g., "Windows")
    private final String platformVersion;  // e.g., "10.0.0"
    private final String architecture;     // e.g., "x86"
    private final String bitness;          // e.g., "64"
    private final boolean mobile;
    private final String model;            // Empty for desktop
    private final boolean wow64;           // 32-bit process on 64-bit Windows

    public PlatformInfo(
            String platform,
            String chPlatform,
            String platformVersion,
            String architecture,
            String bitness,
            boolean mobile,
            String model,
            boolean wow64
    ) {
        this.platform = platform;
        this.chPlatform = chPlatform;
        this.platformVersion = platformVersion;
        this.architecture = architecture;
        this.bitness = bitness;
        this.mobile = mobile;
        this.model = model;
        this.wow64 = wow64;
    }

    /**
     * Applies platform spoofing via CDP.
     *
     * This method queries the actual browser's User-Agent and brand information,
     * then combines it with the spoofed platform metadata from this profile.
     * This ensures:
     * - UA string and brands match the actual Chrome version (no version spoofing)
     * - Platform metadata matches the fingerprint profile (hardware spoofing)
     *
     * Sets up:
     * - HTTP User-Agent header (actual browser value)
     * - navigator.userAgent (actual browser value)
     * - navigator.platform (spoofed)
     * - navigator.userAgentData (actual brands + spoofed platform)
     * - All Sec-CH-UA-* headers
     * - navigator.vendor (via injected script)
     *
     * @param cdp the CDP client connection
     * @throws TimeoutException if CDP command times out
     */
    public void applyOnce(CDPClient cdp) throws TimeoutException {
        // Query actual browser UA and version info
        BrowserVersionInfo browserInfo = queryBrowserVersion(cdp);

        applyUserAgentOverride(cdp, browserInfo);
        applyVendorOverride(cdp);
    }

    /**
     * Queries the actual browser's User-Agent and version information via CDP.
     *
     * @param cdp the CDP client connection
     * @return browser version info containing UA and brands
     * @throws TimeoutException if CDP command times out
     */
    private BrowserVersionInfo queryBrowserVersion(CDPClient cdp) throws TimeoutException {
        JsonObject result = cdp.send("Browser.getVersion", null);

        String userAgent = result.get("userAgent").getAsString();
        String product = result.get("product").getAsString(); // e.g., "Chrome/143.0.6917.0"

        // Extract version from product string (e.g., "Chrome/143.0.6917.0" -> "143.0.6917.0")
        String fullVersion = product.contains("/")
                ? product.substring(product.indexOf("/") + 1)
                : product;

        // Extract major version (e.g., "143.0.6917.0" -> "143")
        String majorVersion = fullVersion.contains(".")
                ? fullVersion.substring(0, fullVersion.indexOf("."))
                : fullVersion;

        // Build brand lists from actual browser version
        List<BrandVersion> brands = buildBrands(majorVersion);
        List<BrandVersion> fullVersionList = buildFullVersionList(fullVersion);

        return new BrowserVersionInfo(userAgent, fullVersion, brands, fullVersionList);
    }

    /**
     * Builds the low-entropy brands list (major version only).
     * Follows Chrome's brand ordering with GREASE brand.
     */
    private List<BrandVersion> buildBrands(String majorVersion) {
        return List.of(
                new BrandVersion("Google Chrome", majorVersion),
                new BrandVersion("Chromium", majorVersion),
                new BrandVersion("Not.A/Brand", "24")
        );
    }

    /**
     * Builds the high-entropy full version list.
     * Follows Chrome's brand ordering with GREASE brand.
     */
    private List<BrandVersion> buildFullVersionList(String fullVersion) {
        return List.of(
                new BrandVersion("Google Chrome", fullVersion),
                new BrandVersion("Chromium", fullVersion),
                new BrandVersion("Not.A/Brand", "24.0.0.0")
        );
    }

    /**
     * Applies Emulation.setUserAgentOverride with actual browser UA/brands
     * combined with spoofed platform metadata.
     */
    private void applyUserAgentOverride(CDPClient cdp, BrowserVersionInfo browserInfo) throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("userAgent", browserInfo.userAgent());
        params.addProperty("platform", platform);

        // Build userAgentMetadata object with actual brands + spoofed platform
        JsonObject metadata = new JsonObject();
        metadata.add("brands", buildBrandsArray(browserInfo.brands()));
        metadata.add("fullVersionList", buildBrandsArray(browserInfo.fullVersionList()));
        metadata.addProperty("fullVersion", browserInfo.fullVersion()); // Deprecated but still checked
        metadata.addProperty("platform", chPlatform);
        metadata.addProperty("platformVersion", platformVersion);
        metadata.addProperty("architecture", architecture);
        metadata.addProperty("bitness", bitness);
        metadata.addProperty("mobile", mobile);
        metadata.addProperty("model", model);
        metadata.addProperty("wow64", wow64);

        params.add("userAgentMetadata", metadata);

        cdp.send("Emulation.setUserAgentOverride", params);
    }

    /**
     * Injects a script to override navigator.vendor.
     *
     * Uses Page.addScriptToEvaluateOnNewDocument so the override
     * applies to every page/frame automatically.
     */
    private void applyVendorOverride(CDPClient cdp) throws TimeoutException {
        String script = String.format(
                "Object.defineProperty(navigator, 'vendor', {" +
                        "  get: () => '%s'," +
                        "  configurable: true" +
                        "});",
                NAVIGATOR_VENDOR
        );

        JsonObject params = new JsonObject();
        params.addProperty("source", script);

        cdp.send("Page.addScriptToEvaluateOnNewDocument", params);
    }

    /**
     * Builds a JSON array of brand/version objects for Client Hints.
     */
    private JsonArray buildBrandsArray(List<BrandVersion> brandVersions) {
        JsonArray array = new JsonArray();
        for (BrandVersion bv : brandVersions) {
            JsonObject obj = new JsonObject();
            obj.addProperty("brand", bv.brand());
            obj.addProperty("version", bv.version());
            array.add(obj);
        }
        return array;
    }

    // Getters

    public String platform() {
        return platform;
    }

    public String chPlatform() {
        return chPlatform;
    }

    public String platformVersion() {
        return platformVersion;
    }

    public String architecture() {
        return architecture;
    }

    public String bitness() {
        return bitness;
    }

    public boolean mobile() {
        return mobile;
    }

    public String model() {
        return model;
    }

    public boolean wow64() {
        return wow64;
    }

    @Override
    public String toString() {
        return "PlatformInfo{" +
                "platform='" + platform + '\'' +
                ", chPlatform='" + chPlatform + '\'' +
                ", platformVersion='" + platformVersion + '\'' +
                ", architecture='" + architecture + '\'' +
                ", bitness='" + bitness + '\'' +
                '}';
    }

    /**
     * Represents a brand/version pair for Client Hints.
     * Used in both low-entropy brands and high-entropy fullVersionList.
     */
    public record BrandVersion(String brand, String version) {

        @Override
        public String toString() {
            return brand + "/" + version;
        }
    }

    /**
     * Internal container for browser version information queried via CDP.
     */
    private record BrowserVersionInfo(
            String userAgent,
            String fullVersion,
            List<BrandVersion> brands,
            List<BrandVersion> fullVersionList
    ) {}
}