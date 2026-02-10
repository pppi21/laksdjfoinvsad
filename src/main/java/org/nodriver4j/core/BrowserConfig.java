package org.nodriver4j.core;

import org.nodriver4j.services.AutoSolveAIService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configuration for launching a browser instance.
 *
 * <p>This class is the single source of truth for all browser-specific settings.
 * It holds everything needed to configure how a browser should behave, but does NOT
 * hold runtime allocations like port numbers.</p>
 *
 * <p>Instances are immutable and created via the {@link Builder} pattern:</p>
 * <pre>{@code
 * BrowserConfig config = BrowserConfig.builder()
 *     .executablePath("/path/to/chrome")
 *     .headless(true)
 *     .fingerprintEnabled(true)
 *     .resourceBlocking(true)
 *     .interactionOptions(InteractionOptions.fast())
 *     .build();
 * }</pre>
 *
 * <p>For persistent browser sessions (e.g., tasks), provide a {@code userDataDir}
 * to reuse cookies and localStorage across runs:</p>
 * <pre>{@code
 * BrowserConfig config = BrowserConfig.builder()
 *     .executablePath("/path/to/chrome")
 *     .userDataDir(Path.of("/data/task-42"))
 *     .build();
 * }</pre>
 *
 * <p>When using {@link BrowserManager}, a default config is provided and the manager
 * handles runtime allocations (ports, proxies). For standalone usage, you can pass
 * the config directly to {@link Browser#launch}.</p>
 *
 * @see Browser#launch(BrowserConfig, int, java.util.function.IntConsumer)
 * @see BrowserManager
 */
public class BrowserConfig {

    private static final String DEFAULT_WEBRTC_POLICY = "disable_non_proxied_udp";

    // Required
    private final String executablePath;

    // Launch settings
    private final boolean headless;
    private final boolean headlessGpuAcceleration;
    private final String webrtcPolicy;
    private final List<String> arguments;

    // Features
    private final boolean fingerprintEnabled;
    private final boolean resourceBlocking;
    private final InteractionOptions interactionOptions;

    // Optional services/resources (can be set per-browser or shared)
    private final ProxyConfig proxyConfig;
    private final AutoSolveAIService autoSolveAIService;

    // Optional persistent user data directory
    private final Path userDataDir;

    private BrowserConfig(Builder builder) {
        this.executablePath = builder.executablePath;
        this.headless = builder.headless;
        this.headlessGpuAcceleration = builder.headlessGpuAcceleration;
        this.webrtcPolicy = builder.webrtcPolicy;
        this.arguments = Collections.unmodifiableList(new ArrayList<>(builder.arguments));
        this.fingerprintEnabled = builder.fingerprintEnabled;
        this.resourceBlocking = builder.resourceBlocking;
        this.interactionOptions = builder.interactionOptions;
        this.proxyConfig = builder.proxyConfig;
        this.autoSolveAIService = builder.autoSolveAIService;
        this.userDataDir = builder.userDataDir;
    }

    // ==================== Getters ====================

    /**
     * Gets the path to the Chrome executable.
     *
     * @return the executable path
     */
    public String executablePath() {
        return executablePath;
    }

    /**
     * Checks if headless mode is enabled.
     *
     * @return true if running without GUI
     */
    public boolean headless() {
        return headless;
    }

    /**
     * Checks if GPU acceleration is enabled in headless mode.
     *
     * @return true if GPU acceleration is enabled
     */
    public boolean headlessGpuAcceleration() {
        return headlessGpuAcceleration;
    }

    /**
     * Gets the WebRTC IP handling policy.
     *
     * @return the WebRTC policy string
     */
    public String webrtcPolicy() {
        return webrtcPolicy;
    }

    /**
     * Gets additional Chrome command-line arguments.
     *
     * @return unmodifiable list of arguments
     */
    public List<String> arguments() {
        return arguments;
    }

    /**
     * Checks if fingerprint spoofing is enabled.
     *
     * @return true if fingerprinting is enabled
     */
    public boolean fingerprintEnabled() {
        return fingerprintEnabled;
    }

    /**
     * Checks if resource blocking is enabled.
     *
     * <p>When enabled, unnecessary resources (fonts, media, tracking scripts)
     * are blocked to improve page load performance. Images and stylesheets
     * are preserved for CAPTCHA compatibility and accurate element positioning.</p>
     *
     * <p>Blocked resource types: Media, Font, Prefetch, Ping, Manifest,
     * CSPViolationReport, SignedExchange, TextTrack</p>
     *
     * <p>Blocked URL patterns: Analytics and tracking services (Google Analytics,
     * Facebook, Hotjar, Mixpanel, Sentry, etc.)</p>
     *
     * @return true if resource blocking is enabled
     */
    public boolean resourceBlocking() {
        return resourceBlocking;
    }

    /**
     * Gets the interaction options for human-like behavior.
     *
     * @return the interaction options (never null)
     */
    public InteractionOptions interactionOptions() {
        return interactionOptions;
    }

    /**
     * Gets the proxy configuration, if set.
     *
     * @return the proxy config, or null if no proxy
     */
    public ProxyConfig proxyConfig() {
        return proxyConfig;
    }

    /**
     * Gets the AutoSolve AI service, if configured.
     *
     * @return the AutoSolve AI service, or null if not configured
     */
    public AutoSolveAIService autoSolveAIService() {
        return autoSolveAIService;
    }

    /**
     * Gets the user data directory for persistent browser sessions.
     *
     * <p>When set, Browser.launch() uses this directory instead of generating
     * a temporary one, and preserves it on close. This allows cookies,
     * localStorage, and other session data to persist across runs.</p>
     *
     * @return the user data directory path, or null if not set (temp dir will be generated)
     */
    public Path userDataDir() {
        return userDataDir;
    }

    /**
     * Checks if a proxy is configured.
     *
     * @return true if proxy configuration is present
     */
    public boolean hasProxy() {
        return proxyConfig != null;
    }

    /**
     * Checks if AutoSolve AI is configured.
     *
     * @return true if AutoSolve AI service is present
     */
    public boolean hasAutoSolveAI() {
        return autoSolveAIService != null;
    }

    /**
     * Checks if a persistent user data directory is configured.
     *
     * @return true if a user data directory path is set
     */
    public boolean hasUserDataDir() {
        return userDataDir != null;
    }

    /**
     * Creates a new builder initialized with this config's values.
     * Useful for creating modified copies.
     *
     * @return a Builder with current values
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.executablePath = this.executablePath;
        builder.headless = this.headless;
        builder.headlessGpuAcceleration = this.headlessGpuAcceleration;
        builder.webrtcPolicy = this.webrtcPolicy;
        builder.arguments = new ArrayList<>(this.arguments);
        builder.fingerprintEnabled = this.fingerprintEnabled;
        builder.resourceBlocking = this.resourceBlocking;
        builder.interactionOptions = this.interactionOptions;
        builder.proxyConfig = this.proxyConfig;
        builder.autoSolveAIService = this.autoSolveAIService;
        builder.userDataDir = this.userDataDir;
        return builder;
    }

    /**
     * Creates a new builder for BrowserConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return String.format(
                "BrowserConfig{executable=%s, headless=%s, fingerprint=%s, resourceBlocking=%s, proxy=%s, autoSolve=%s, userDataDir=%s}",
                executablePath,
                headless,
                fingerprintEnabled ? "enabled" : "disabled",
                resourceBlocking ? "enabled" : "disabled",
                proxyConfig != null ? proxyConfig.host() : "none",
                autoSolveAIService != null ? "configured" : "none",
                userDataDir != null ? userDataDir : "auto"
        );
    }

    // ==================== Builder ====================

    /**
     * Builder for creating BrowserConfig instances.
     *
     * <p>Required: {@link #executablePath(String)}</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * BrowserConfig config = BrowserConfig.builder()
     *     .executablePath("/path/to/chrome")
     *     .headless(false)
     *     .fingerprintEnabled(true)
     *     .resourceBlocking(true)
     *     .webrtcPolicy("disable_non_proxied_udp")
     *     .interactionOptions(InteractionOptions.defaults())
     *     .build();
     * }</pre>
     */
    public static class Builder {

        private String executablePath;
        private boolean headless = false;
        private boolean headlessGpuAcceleration = false;
        private String webrtcPolicy = DEFAULT_WEBRTC_POLICY;
        private List<String> arguments = new ArrayList<>();
        private boolean fingerprintEnabled = true;
        private boolean resourceBlocking = false;
        private InteractionOptions interactionOptions = InteractionOptions.defaults();
        private ProxyConfig proxyConfig;
        private AutoSolveAIService autoSolveAIService;
        private Path userDataDir;

        private Builder() {}

        /**
         * Sets the path to the Chrome executable. Required.
         *
         * @param executablePath path to chrome or chrome-headless-shell
         * @return this builder
         */
        public Builder executablePath(String executablePath) {
            this.executablePath = executablePath;
            return this;
        }

        /**
         * Enables or disables headless mode.
         *
         * <p>Default: false (visible browser window)</p>
         *
         * @param headless true to run without GUI
         * @return this builder
         */
        public Builder headless(boolean headless) {
            this.headless = headless;
            return this;
        }

        /**
         * Enables GPU acceleration in headless mode.
         *
         * <p>When enabled, Chrome uses actual GPU hardware for rendering instead of
         * software rendering (SwiftShader). Only applies when headless mode is enabled.</p>
         *
         * <p>Default: false</p>
         *
         * @param enabled true to enable GPU acceleration in headless mode
         * @return this builder
         */
        public Builder headlessGpuAcceleration(boolean enabled) {
            this.headlessGpuAcceleration = enabled;
            return this;
        }

        /**
         * Sets the WebRTC IP handling policy.
         *
         * <p>Default: "disable_non_proxied_udp" (prevents WebRTC IP leaks when using proxy)</p>
         *
         * @param webrtcPolicy one of: "default", "default_public_interface_only",
         *                     "default_public_and_private_interfaces", "disable_non_proxied_udp"
         * @return this builder
         */
        public Builder webrtcPolicy(String webrtcPolicy) {
            this.webrtcPolicy = webrtcPolicy;
            return this;
        }

        /**
         * Adds a Chrome command-line argument.
         *
         * @param argument the argument to add (e.g., "--disable-extensions")
         * @return this builder
         */
        public Builder argument(String argument) {
            if (argument != null && !argument.isBlank()) {
                this.arguments.add(argument);
            }
            return this;
        }

        /**
         * Sets all Chrome command-line arguments, replacing any previously added.
         *
         * @param arguments the arguments to set
         * @return this builder
         */
        public Builder arguments(List<String> arguments) {
            this.arguments = arguments != null ? new ArrayList<>(arguments) : new ArrayList<>();
            return this;
        }

        /**
         * Enables or disables fingerprint spoofing.
         *
         * <p>When enabled, a random browser fingerprint is loaded and applied
         * to make the browser appear as a different device.</p>
         *
         * <p>Default: true</p>
         *
         * @param enabled true to enable fingerprinting
         * @return this builder
         */
        public Builder fingerprintEnabled(boolean enabled) {
            this.fingerprintEnabled = enabled;
            return this;
        }

        /**
         * Enables or disables resource blocking for improved performance.
         *
         * <p>When enabled, unnecessary resources are blocked via CDP Fetch interception:</p>
         * <ul>
         *   <li><strong>Blocked types:</strong> Media, Font, Prefetch, Ping, Manifest,
         *       CSPViolationReport, SignedExchange, TextTrack</li>
         *   <li><strong>Blocked URLs:</strong> Analytics/tracking (Google Analytics, Facebook,
         *       Hotjar, Mixpanel, Sentry, etc.)</li>
         *   <li><strong>Preserved:</strong> Document, Stylesheet, Image, Script, XHR, Fetch,
         *       WebSocket (required for CAPTCHAs and accurate automation)</li>
         * </ul>
         *
         * <p>Default: false</p>
         *
         * @param enabled true to enable resource blocking
         * @return this builder
         */
        public Builder resourceBlocking(boolean enabled) {
            this.resourceBlocking = enabled;
            return this;
        }

        /**
         * Sets the interaction options for human-like behavior.
         *
         * <p>These options control timing and movement patterns for mouse clicks,
         * typing, scrolling, and other interactions.</p>
         *
         * <p>Default: {@link InteractionOptions#defaults()}</p>
         *
         * @param options the interaction options
         * @return this builder
         * @throws IllegalArgumentException if options is null
         */
        public Builder interactionOptions(InteractionOptions options) {
            if (options == null) {
                throw new IllegalArgumentException("InteractionOptions cannot be null");
            }
            this.interactionOptions = options;
            return this;
        }

        /**
         * Sets the proxy configuration.
         *
         * @param proxyConfig the proxy configuration (null to disable proxy)
         * @return this builder
         */
        public Builder proxy(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            return this;
        }

        /**
         * Sets the proxy configuration from a proxy string.
         *
         * <p>Format: host:port:username:password</p>
         *
         * @param proxyString the proxy string to parse
         * @return this builder
         * @throws IllegalArgumentException if the proxy string format is invalid
         */
        public Builder proxy(String proxyString) {
            this.proxyConfig = new ProxyConfig(proxyString);
            return this;
        }

        /**
         * Sets the AutoSolve AI service for captcha solving.
         *
         * @param service the AutoSolve AI service (null to disable)
         * @return this builder
         */
        public Builder autoSolveAIService(AutoSolveAIService service) {
            this.autoSolveAIService = service;
            return this;
        }

        /**
         * Sets the AutoSolve AI service by API key.
         *
         * <p>Creates a new AutoSolveAIService with the provided key.</p>
         *
         * @param apiKey the AutoSolve AI API key
         * @return this builder
         */
        public Builder autoSolveAIKey(String apiKey) {
            if (apiKey != null && !apiKey.isBlank()) {
                this.autoSolveAIService = new AutoSolveAIService(apiKey);
            }
            return this;
        }

        /**
         * Sets a persistent user data directory for the browser.
         *
         * <p>When set, the browser will use this directory for its profile data
         * (cookies, localStorage, etc.) and will NOT delete it on close.
         * This allows sessions to persist across browser restarts.</p>
         *
         * <p>When not set (null), a temporary directory is generated and
         * automatically cleaned up when the browser closes.</p>
         *
         * <p>Default: null (auto-generated temp directory)</p>
         *
         * @param userDataDir path to the persistent user data directory, or null for temp
         * @return this builder
         */
        public Builder userDataDir(Path userDataDir) {
            this.userDataDir = userDataDir;
            return this;
        }

        /**
         * Builds the BrowserConfig with the configured settings.
         *
         * @return a new immutable BrowserConfig instance
         * @throws IllegalStateException if executablePath is not set
         */
        public BrowserConfig build() {
            if (executablePath == null || executablePath.isBlank()) {
                throw new IllegalStateException("executablePath is required");
            }
            return new BrowserConfig(this);
        }
    }
}