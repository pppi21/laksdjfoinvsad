package org.nodriver4j.fingerprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.nodriver4j.cdp.CDPClient;

import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Immutable container for User-Agent related fingerprint data.
 *
 * Includes the UA string, navigator properties, and User-Agent Client Hints
 * metadata (Sec-CH-UA-* headers and navigator.userAgentData).
 *
 * All data is applied together via CDP's Emulation.setUserAgentOverride,
 * which ensures consistency between HTTP headers and JavaScript APIs.
 */
public final class UserAgentInfo {

    /**
     * navigator.vendor is always "Google Inc." for Chrome browsers.
     * This is distinct from WebGL vendor which varies by GPU.
     */
    private static final String NAVIGATOR_VENDOR = "Google Inc.";

    // Core UA fields
    private final String userAgent;
    private final String platform;         // navigator.platform (e.g., "Win32")

    // Client Hints fields
    private final List<BrandVersion> brands;           // Low-entropy brands (major version only)
    private final List<BrandVersion> fullVersionList;  // High-entropy brands (full version)
    private final String chPlatform;                   // Client Hints platform (e.g., "Windows")
    private final String platformVersion;              // e.g., "10.0.0"
    private final String architecture;                 // e.g., "x86"
    private final String bitness;                      // e.g., "64"
    private final boolean mobile;
    private final String model;                        // Empty for desktop
    private final boolean wow64;                       // 32-bit process on 64-bit Windows

    public UserAgentInfo(
            String userAgent,
            String platform,
            List<BrandVersion> brands,
            List<BrandVersion> fullVersionList,
            String chPlatform,
            String platformVersion,
            String architecture,
            String bitness,
            boolean mobile,
            String model,
            boolean wow64
    ) {
        this.userAgent = userAgent;
        this.platform = platform;
        this.brands = List.copyOf(brands);
        this.fullVersionList = List.copyOf(fullVersionList);
        this.chPlatform = chPlatform;
        this.platformVersion = platformVersion;
        this.architecture = architecture;
        this.bitness = bitness;
        this.mobile = mobile;
        this.model = model;
        this.wow64 = wow64;
    }

    /**
     * Applies User-Agent spoofing via CDP.
     *
     * This method should be called once after connecting to the browser.
     * It sets up:
     * - HTTP User-Agent header
     * - navigator.userAgent
     * - navigator.platform
     * - navigator.userAgentData (Client Hints)
     * - All Sec-CH-UA-* headers
     * - navigator.vendor (via injected script)
     *
     * @param cdp the CDP client connection
     * @throws TimeoutException if CDP command times out
     */
    public void applyOnce(CDPClient cdp) throws TimeoutException {
        applyUserAgentOverride(cdp);
        applyVendorOverride(cdp);
    }

    /**
     * Applies Emulation.setUserAgentOverride with full Client Hints metadata.
     */
    private void applyUserAgentOverride(CDPClient cdp) throws TimeoutException {
        JsonObject params = new JsonObject();
        params.addProperty("userAgent", userAgent);
        params.addProperty("platform", platform);

        // Build userAgentMetadata object
        JsonObject metadata = new JsonObject();
        metadata.add("brands", buildBrandsArray(brands));
        metadata.add("fullVersionList", buildBrandsArray(fullVersionList));
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

    public String userAgent() {
        return userAgent;
    }

    public String platform() {
        return platform;
    }

    public List<BrandVersion> brands() {
        return brands;
    }

    public List<BrandVersion> fullVersionList() {
        return fullVersionList;
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
        return "UserAgentInfo{" +
                "userAgent='" + userAgent + '\'' +
                ", platform='" + platform + '\'' +
                ", chPlatform='" + chPlatform + '\'' +
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
}