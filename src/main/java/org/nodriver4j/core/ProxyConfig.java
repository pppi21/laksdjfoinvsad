package org.nodriver4j.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for HTTP proxy connection with authentication support.
 * Parses proxy strings in the format: host:port:username:password
 *
 * Example: res-us.lightningproxies.net:9999:user-zone-session-abc:password123
 */
public class ProxyConfig {

    private static final String PROXIES_ENV_VAR = "proxies";

    private final String host;
    private final int port;
    private final String username;
    private final String password;

    /**
     * Creates a ProxyConfig by loading the first proxy from the file
     * specified by the "proxies" environment variable.
     *
     * @throws IOException if the file cannot be read or is empty
     * @throws IllegalStateException if the environment variable is not set
     */
    public ProxyConfig() throws IOException {
        this(loadFirstProxyFromEnvFile());
    }

    /**
     * Creates a ProxyConfig from a proxy string.
     *
     * @param proxyString proxy in format host:port:username:password
     * @throws IllegalArgumentException if the format is invalid
     */
    public ProxyConfig(String proxyString) {
        if (proxyString == null || proxyString.isBlank()) {
            throw new IllegalArgumentException("Proxy string cannot be null or blank");
        }

        // Split with limit of 4 to handle passwords containing colons
        String[] parts = proxyString.trim().split(":", 4);

        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "Invalid proxy format. Expected host:port:username:password, got: " + proxyString);
        }

        this.host = parts[0];
        this.port = parsePort(parts[1]);
        this.username = parts[2];
        this.password = parts[3];

        validate();
    }

    /**
     * Creates a ProxyConfig with explicit parameters.
     *
     * @param host     proxy hostname
     * @param port     proxy port
     * @param username authentication username
     * @param password authentication password
     */
    public ProxyConfig(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;

        validate();
    }

    /**
     * Loads the first non-empty line from the proxies file.
     */
    private static String loadFirstProxyFromEnvFile() throws IOException {
        String filePath = System.getenv(PROXIES_ENV_VAR);

        if (filePath == null || filePath.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable '" + PROXIES_ENV_VAR + "' is not set. " +
                            "Set it to the absolute path of your proxies.txt file.");
        }

        Path path = Path.of(filePath);

        if (!Files.exists(path)) {
            throw new IOException("Proxies file not found: " + filePath);
        }

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    return line.trim();
                }
            }
        }

        throw new IOException("Proxies file is empty or contains only comments: " + filePath);
    }

    private int parsePort(String portString) {
        try {
            int port = Integer.parseInt(portString.trim());
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port number: " + portString);
        }
    }

    private void validate() {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Proxy host cannot be null or blank");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Proxy username cannot be null or blank");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Proxy password cannot be null or blank");
        }
    }

    /**
     * Returns the proxy server address in Chrome's expected format.
     * Example: "http://res-us.lightningproxies.net:9999"
     *
     * @return proxy URL without credentials (credentials handled via CDP)
     */
    public String toProxyServerArg() {
        return "http://" + host + ":" + port;
    }

    /**
     * Checks if this proxy requires authentication.
     * Currently always returns true since we require username/password.
     *
     * @return true if authentication is required
     */
    public boolean requiresAuth() {
        return true;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    @Override
    public String toString() {
        // Mask password for security in logs
        return String.format("ProxyConfig{host=%s, port=%d, user=%s, pass=***}",
                host, port, username);
    }
}