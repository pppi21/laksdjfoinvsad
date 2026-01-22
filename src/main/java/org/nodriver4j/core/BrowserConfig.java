package org.nodriver4j.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Configuration for launching a browser instance.
 *
 * <p>This class holds all settings needed to launch a Chrome browser with CDP support.
 * Instances are created via the {@link Builder} pattern.</p>
 *
 * <p>When using {@link BrowserManager}, configuration is handled automatically.
 * Direct use of BrowserConfig is for advanced cases where manual browser
 * management is needed.</p>
 */
public class BrowserConfig {

    private static final String DEFAULT_WEBRTC_POLICY = "disable_non_proxied_udp";

    private final String executablePath;
    private final int port;
    private final boolean headless;
    private final Path userDataDir;
    private final boolean warmProfile;
    private final boolean fingerprintEnabled;
    private final String webrtcPolicy;
    private final ProxyConfig proxyConfig;
    private final ArrayList<String> arguements;
    private final boolean headlessGpuAcceleration;

    private BrowserConfig(Builder builder) {
        this.executablePath = builder.executablePath;
        this.port = builder.port;
        this.headless = builder.headless;
        this.userDataDir = builder.userDataDir;
        this.warmProfile = builder.warmProfile;
        this.fingerprintEnabled = builder.fingerprintEnabled;
        this.webrtcPolicy = builder.webrtcPolicy;
        this.proxyConfig = builder.proxyConfig;
        this.arguements = builder.arguements;
        this.headlessGpuAcceleration = builder.headlessGpuAcceleration;
    }

    public String getExecutablePath() {
        return executablePath;
    }

    public int getPort() {
        return port;
    }

    public boolean isHeadless() {
        return headless;
    }

    public Path getUserDataDir() {
        return userDataDir;
    }

    public boolean isWarmProfile() {
        return warmProfile;
    }

    public boolean isFingerprintEnabled() {
        return fingerprintEnabled;
    }

    public String getWebrtcPolicy() {
        return webrtcPolicy;
    }

    public ProxyConfig getProxyConfig() {
        return proxyConfig;
    }

    public ArrayList<String> getArguements() { return arguements; }

    public boolean isHeadlessGpuAcceleration() { return headlessGpuAcceleration; }

    /**
     * Checks if a proxy is configured for this browser.
     *
     * @return true if proxy configuration is present
     */
    public boolean hasProxy() {
        return proxyConfig != null;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static Path generateTempUserDataDir() {
        String tempDir = System.getProperty("java.io.tmpdir");
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        return Path.of(tempDir, "nodriver4j-" + uniqueId);
    }

    /**
     * Builder for creating BrowserConfig instances.
     *
     * <p>Required fields:</p>
     * <ul>
     *   <li>{@link #executablePath(String)} - path to Chrome executable</li>
     *   <li>{@link #port(int)} - CDP debugging port</li>
     * </ul>
     */
    public static class Builder {

        private String executablePath;
        private int port = -1; // -1 indicates not set
        private boolean headless = false;
        private Path userDataDir;
        private boolean warmProfile = false;
        private boolean fingerprintEnabled = false;
        private String webrtcPolicy = DEFAULT_WEBRTC_POLICY;
        private ProxyConfig proxyConfig;
        private ArrayList<String> arguements;
        private boolean headlessGpuAcceleration = false;

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
         * Sets the CDP debugging port. Required.
         *
         * <p>When using {@link BrowserManager}, the port is allocated automatically.
         * Only set this manually when managing browsers directly.</p>
         *
         * @param port the debugging port (1-65535)
         * @return this builder
         */
        public Builder port(int port) {
            this.port = port;
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
         * Enables or disables profile warming.
         *
         * <p>When enabled, the browser visits common websites after launch
         * to collect cookies and make the profile appear more natural.</p>
         *
         * <p>Default: false</p>
         *
         * @param warmProfile true to enable profile warming
         * @return this builder
         */
        public Builder warmProfile(boolean warmProfile) {
            this.warmProfile = warmProfile;
            return this;
        }

        /**
         * Enables or disables fingerprint spoofing.
         *
         * <p>When enabled, a random browser fingerprint is loaded and applied
         * to make the browser appear as a different device.</p>
         *
         * <p>Default: false</p>
         *
         * @param fingerprintEnabled true to enable fingerprinting
         * @return this builder
         */
        public Builder fingerprintEnabled(boolean fingerprintEnabled) {
            this.fingerprintEnabled = fingerprintEnabled;
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
         * Sets the proxy configuration.
         *
         * @param proxyConfig the proxy configuration
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

        public Builder chromeArguements(ArrayList<String> arguements) {
            this.arguements = arguements;
            return this;
        }

        /**
         * Enables GPU acceleration in headless mode.
         *
         * <p>When enabled, Chrome uses actual GPU hardware for rendering instead of
         * software rendering (SwiftShader). This can improve performance on machines
         * with available GPUs.</p>
         *
         * <p>Only applies when headless mode is also enabled. Default: false</p>
         *
         * @param headlessGpuAcceleration true to enable GPU acceleration in headless mode
         * @return this builder
         */
        public Builder headlessGpuAcceleration(boolean headlessGpuAcceleration) {
            this.headlessGpuAcceleration = headlessGpuAcceleration;
            return this;
        }

        /**
         * Builds the BrowserConfig with the configured settings.
         *
         * @return a new BrowserConfig instance
         * @throws IllegalStateException if required fields are not set
         */
        public BrowserConfig build() {
            if (executablePath == null || executablePath.isBlank()) {
                throw new IllegalStateException("executablePath is required");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalStateException("port is required and must be between 1 and 65535");
            }
            this.userDataDir = generateTempUserDataDir();
            return new BrowserConfig(this);
        }
    }
}