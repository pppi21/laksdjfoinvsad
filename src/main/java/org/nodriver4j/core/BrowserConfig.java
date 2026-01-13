package org.nodriver4j.core;

/**
 * Configuration for launching a browser instance.
 */
public class BrowserConfig {

    private static final int DEFAULT_PORT = 9222;

    private final String executablePath;
    private final int port;
    private final boolean headless;

    private BrowserConfig(Builder builder) {
        this.executablePath = builder.executablePath;
        this.port = builder.port;
        this.headless = builder.headless;
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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private String executablePath;
        private int port = DEFAULT_PORT;
        private boolean headless = false;

        private Builder() {}

        public Builder executablePath(String executablePath) {
            this.executablePath = executablePath;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder headless(boolean headless) {
            this.headless = headless;
            return this;
        }

        public BrowserConfig build() {
            if (executablePath == null || executablePath.isEmpty()) {
                throw new IllegalStateException("executablePath is required");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalStateException("port must be between 1 and 65535");
            }
            return new BrowserConfig(this);
        }
    }
}