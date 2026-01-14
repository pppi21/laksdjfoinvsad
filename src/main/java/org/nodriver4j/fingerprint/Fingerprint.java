package org.nodriver4j.fingerprint;

import com.google.gson.JsonObject;

/**
 * Immutable container for a complete browser fingerprint profile.
 *
 * A Fingerprint represents all the identifying characteristics of a browser
 * that can be used for tracking/detection. This class aggregates various
 * fingerprint components (Platform, WebGL, Screen, etc.) into a single
 * cohesive profile that can be applied to a browser instance.
 *
 * Fingerprints are constructed from raw JSON data (typically loaded from
 * a JSONL file of pre-collected real-world fingerprints). The constructor
 * handles all parsing and sub-component creation internally.
 *
 * IMPORTANT: Fingerprint profiles do NOT contain Chrome-version-dependent data
 * (ua, brands, fullVersionList). These values are queried from the actual
 * browser at runtime to avoid detection by fingerprint scanners.
 */
public final class Fingerprint {

    private final PlatformInfo platformInfo;

    // TODO: Uncomment as components are implemented
    // private final WebGLInfo webGL;
    // private final ScreenInfo screen;
    // private final AudioInfo audio;
    // private final CanvasInfo canvas;
    // private final PluginsInfo plugins;
    // private final FontsInfo fonts;

    /**
     * Creates a new Fingerprint by parsing raw JSON data.
     *
     * Expected JSON structure (version-independent profile):
     * <pre>
     * {
     *   "clientHints": {
     *     "platform": "Windows",
     *     "navigatorPlatform": "Win32",
     *     "platformVersion": "10.0.0",
     *     "architecture": "x86",
     *     "bitness": "64",
     *     "mobile": false,
     *     "model": "",
     *     "wow64": false
     *   },
     *   "vendor": "Google Inc. (NVIDIA)",
     *   "renderer": "ANGLE (...)",
     *   "width": 1920,
     *   "height": 1080,
     *   ...
     * }
     * </pre>
     *
     * @param json the raw fingerprint JSON object
     */
    public Fingerprint(JsonObject json) {
        this.platformInfo = parsePlatformInfo(json);

        // TODO: Uncomment as components are implemented
        // this.webGL = parseWebGLInfo(json);
        // this.screen = parseScreenInfo(json);
        // this.audio = parseAudioInfo(json);
        // this.canvas = parseCanvasInfo(json);
        // this.plugins = parsePluginsInfo(json);
        // this.fonts = parseFontsInfo(json);
    }

    /**
     * Parses PlatformInfo from the JSON data.
     *
     * Extracts platform/OS-dependent fields from clientHints.
     * Does NOT parse ua, brands, or fullVersionList as these are
     * version-dependent and queried from the actual browser at runtime.
     */
    private PlatformInfo parsePlatformInfo(JsonObject json) {
        JsonObject clientHints = json.getAsJsonObject("clientHints");

        return new PlatformInfo(
                clientHints.get("navigatorPlatform").getAsString(),  // navigator.platform
                clientHints.get("platform").getAsString(),           // Client Hints platform
                clientHints.get("platformVersion").getAsString(),
                clientHints.get("architecture").getAsString(),
                clientHints.get("bitness").getAsString(),
                clientHints.get("mobile").getAsBoolean(),
                clientHints.get("model").getAsString(),
                clientHints.get("wow64").getAsBoolean()
        );
    }

    /**
     * Platform metadata including navigator.platform and Client Hints platform info.
     *
     * Note: UA string and brand lists are NOT stored here. They are queried
     * from the actual browser at runtime when applying spoofs.
     */
    public PlatformInfo platformInfo() {
        return platformInfo;
    }

    // TODO: Uncomment as components are implemented
    // public WebGLInfo webGL() { return webGL; }
    // public ScreenInfo screen() { return screen; }
    // public AudioInfo audio() { return audio; }
    // public CanvasInfo canvas() { return canvas; }
    // public PluginsInfo plugins() { return plugins; }
    // public FontsInfo fonts() { return fonts; }

    @Override
    public String toString() {
        return "Fingerprint{" +
                "platformInfo=" + platformInfo +
                '}';
    }
}