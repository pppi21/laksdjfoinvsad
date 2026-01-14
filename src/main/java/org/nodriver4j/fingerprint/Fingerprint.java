package org.nodriver4j.fingerprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable container for a complete browser fingerprint profile.
 *
 * A Fingerprint represents all the identifying characteristics of a browser
 * that can be used for tracking/detection. This class aggregates various
 * fingerprint components (User-Agent, WebGL, Screen, etc.) into a single
 * cohesive profile that can be applied to a browser instance.
 *
 * Fingerprints are constructed from raw JSON data (typically loaded from
 * a JSONL file of pre-collected real-world fingerprints). The constructor
 * handles all parsing and sub-component creation internally.
 */
public final class Fingerprint {

    private final PlatformInfo userAgent;

    // TODO: Uncomment as components are implemented
    // private final WebGLInfo webGL;
    // private final ScreenInfo screen;
    // private final AudioInfo audio;
    // private final CanvasInfo canvas;
    // private final PluginsInfo plugins;
    // private final FontsInfo fonts;
    // private final HeadersInfo headers;

    /**
     * Creates a new Fingerprint by parsing raw JSON data.
     *
     * Expected JSON structure (from Bablosoft API with enriched clientHints):
     * <pre>
     * {
     *   "ua": "Mozilla/5.0 ...",
     *   "clientHints": {
     *     "brands": [{"brand": "...", "version": "..."}],
     *     "fullVersionList": [{"brand": "...", "version": "..."}],
     *     "platform": "Windows",
     *     "platformVersion": "10.0.0",
     *     "architecture": "x86",
     *     "bitness": "64",
     *     "mobile": false,
     *     "model": "",
     *     "wow64": false,
     *     "navigatorPlatform": "Win32"
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
        this.userAgent = parseUserAgentInfo(json);

        // TODO: Uncomment as components are implemented
        // this.webGL = parseWebGLInfo(json);
        // this.screen = parseScreenInfo(json);
        // this.audio = parseAudioInfo(json);
        // this.canvas = parseCanvasInfo(json);
        // this.plugins = parsePluginsInfo(json);
        // this.fonts = parseFontsInfo(json);
        // this.headers = parseHeadersInfo(json);
    }

    /**
     * Parses PlatformInfo from the JSON data.
     */
    private PlatformInfo parseUserAgentInfo(JsonObject json) {
        String ua = json.get("ua").getAsString();
        JsonObject clientHints = json.getAsJsonObject("clientHints");

        return new PlatformInfo(
                ua,
                clientHints.get("navigatorPlatform").getAsString(),
                parseBrandVersionList(clientHints.getAsJsonArray("brands")),
                parseBrandVersionList(clientHints.getAsJsonArray("fullVersionList")),
                clientHints.get("platform").getAsString(),
                clientHints.get("platformVersion").getAsString(),
                clientHints.get("architecture").getAsString(),
                clientHints.get("bitness").getAsString(),
                clientHints.get("mobile").getAsBoolean(),
                clientHints.get("model").getAsString(),
                clientHints.get("wow64").getAsBoolean()
        );
    }

    /**
     * Parses a JSON array of brand/version objects into a list.
     */
    private List<PlatformInfo.BrandVersion> parseBrandVersionList(JsonArray array) {
        List<PlatformInfo.BrandVersion> list = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonObject obj = array.get(i).getAsJsonObject();
            list.add(new PlatformInfo.BrandVersion(
                    obj.get("brand").getAsString(),
                    obj.get("version").getAsString()
            ));
        }
        return list;
    }

    /**
     * User-Agent string, navigator properties, and Client Hints metadata.
     */
    public PlatformInfo userAgent() {
        return userAgent;
    }

    // TODO: Uncomment as components are implemented
    // public WebGLInfo webGL() { return webGL; }
    // public ScreenInfo screen() { return screen; }
    // public AudioInfo audio() { return audio; }
    // public CanvasInfo canvas() { return canvas; }
    // public PluginsInfo plugins() { return plugins; }
    // public FontsInfo fonts() { return fonts; }
    // public HeadersInfo headers() { return headers; }

    @Override
    public String toString() {
        return "Fingerprint{" +
                "userAgent=" + (userAgent != null ? userAgent.userAgent() : "null") +
                '}';
    }
}