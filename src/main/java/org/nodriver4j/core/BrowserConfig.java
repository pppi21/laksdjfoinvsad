package org.nodriver4j.core;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Configuration for launching a browser instance.
 */
public class BrowserConfig {

    private static final int DEFAULT_PORT = 9222;

    private final String executablePath;
    private final int port;
    private final boolean headless;
    private final Path userDataDir;

    private BrowserConfig(Builder builder) {
        this.executablePath = builder.executablePath;
        this.port = builder.port;
        this.headless = builder.headless;
        this.userDataDir = builder.userDataDir;
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

    public static Builder builder() {
        return new Builder();
    }

    private static Path generateTempUserDataDir() {
        String tempDir = System.getProperty("java.io.tmpdir");
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        return Path.of(tempDir, "nodriver4j-" + uniqueId);
    }

    public static class Builder {

        private String executablePath;
        private int port = DEFAULT_PORT;
        private boolean headless = false;
        private Path userDataDir;

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
            if (executablePath == null || executablePath.isBlank()) {
                throw new IllegalStateException("executablePath is required");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalStateException("port must be between 1 and 65535");
            }
            this.userDataDir = generateTempUserDataDir();
            System.out.println(this.userDataDir);
            return new BrowserConfig(this);
        }
    }
}