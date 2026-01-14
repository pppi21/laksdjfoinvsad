package org.nodriver4j.fingerprint;

import com.google.gson.JsonObject;

/**
 * Immutable container for a complete browser fingerprint profile.
 *
 * A Fingerprint represents hardware-dependent identifying characteristics of a browser
 * that can be used for tracking/detection. This class aggregates various
 * fingerprint components (WebGL, Screen, Audio, Canvas, Fonts) into a single
 * cohesive profile that can be applied to a browser instance.
 *
 * Fingerprints are constructed from raw JSON data (typically loaded from
 * a JSONL file of pre-collected real-world fingerprints). The constructor
 * handles all parsing and sub-component creation internally.
 *
 * IMPORTANT: Fingerprint profiles contain ONLY hardware-dependent data.
 * The following are NOT stored (use actual browser values):
 * - User-Agent string and Client Hints (Chrome version dependent)
 * - Platform/OS info (too easy to detect spoofing)
 * - Plugins (use actual browser's plugins)
 * - Headers (version dependent ordering)
 */
public final class Fingerprint {

    private final JsonObject rawJson;

    // TODO: Uncomment as components are implemented
    // private final WebGLInfo webGL;
    // private final ScreenInfo screen;
    // private final AudioInfo audio;
    // private final CanvasInfo canvas;
    // private final FontsInfo fonts;

    /**
     * Creates a new Fingerprint by parsing raw JSON data.
     *
     * Expected JSON structure (hardware-only profile):
     * <pre>
     * {
     *   "vendor": "Google Inc. (NVIDIA)",
     *   "renderer": "ANGLE (...)",
     *   "width": 1920,
     *   "height": 1080,
     *   "availWidth": 1920,
     *   "availHeight": 1040,
     *   "canvas": "...",
     *   "audio": "...",
     *   "audio_properties": {...},
     *   "fonts": {...},
     *   "mimeTypes": {...},
     *   "hasSessionStorage": true,
     *   "hasLocalStorage": true,
     *   "hasIndexedDB": true,
     *   "hasWebSql": false
     * }
     * </pre>
     *
     * @param json the raw fingerprint JSON object
     */
    public Fingerprint(JsonObject json) {
        this.rawJson = json;

        // TODO: Uncomment as components are implemented
        // this.webGL = parseWebGLInfo(json);
        // this.screen = parseScreenInfo(json);
        // this.audio = parseAudioInfo(json);
        // this.canvas = parseCanvasInfo(json);
        // this.fonts = parseFontsInfo(json);
    }

    /**
     * Returns the raw JSON data for this fingerprint.
     * Useful for accessing fields before dedicated Info classes are implemented.
     */
    public JsonObject getRawJson() {
        return rawJson;
    }

    // TODO: Uncomment as components are implemented
    // public WebGLInfo webGL() { return webGL; }
    // public ScreenInfo screen() { return screen; }
    // public AudioInfo audio() { return audio; }
    // public CanvasInfo canvas() { return canvas; }
    // public FontsInfo fonts() { return fonts; }

    @Override
    public String toString() {
        String renderer = rawJson.has("renderer")
                ? rawJson.get("renderer").getAsString()
                : "unknown";
        return "Fingerprint{renderer='" + renderer + "'}";
    }
}