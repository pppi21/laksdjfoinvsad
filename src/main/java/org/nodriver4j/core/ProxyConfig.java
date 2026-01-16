package org.nodriver4j.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration for HTTP proxy connection with authentication support.
 * Parses proxy strings in the format: host:port:username:password
 *
 * <p>Example: res-us.lightningproxies.net:9999:user-zone-session-abc:password123</p>
 *
 * <p>This class supports thread-safe proxy consumption from a shared file,
 * allowing multiple browser instances to each get a unique proxy.</p>
 */
public class ProxyConfig {

    private static final String PROXIES_ENV_VAR = "proxies";

    /**
     * Lock object for intra-JVM thread safety when consuming proxies.
     * Combined with FileLock for cross-process safety.
     */
    private static final Object FILE_LOCK = new Object();

    private final String host;
    private final int port;
    private final String username;
    private final String password;

    /**
     * Creates a ProxyConfig by consuming (reading and removing) the first proxy
     * from the file specified by the "proxies" environment variable.
     *
     * <p>This operation is atomic and thread-safe. The proxy line is permanently
     * removed from the file after being read.</p>
     *
     * @throws IOException if the file cannot be read/written or has no valid proxies
     * @throws IllegalStateException if the environment variable is not set
     */
    public ProxyConfig() throws IOException {
        this(consumeFirstFromEnvFile());
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
     * Consumes (reads and removes) the first proxy from the file specified
     * by the "proxies" environment variable.
     *
     * <p>This operation is thread-safe and process-safe using file locking.</p>
     *
     * @return the first proxy string from the file
     * @throws IOException if the file cannot be read, written, or has no valid proxies
     * @throws IllegalStateException if the environment variable is not set
     */
    public static String consumeFirstFromEnvFile() throws IOException {
        String filePath = System.getenv(PROXIES_ENV_VAR);

        if (filePath == null || filePath.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable '" + PROXIES_ENV_VAR + "' is not set. " +
                            "Set it to the absolute path of your proxies.txt file.");
        }

        return consumeFirstFromFile(Path.of(filePath));
    }

    /**
     * Consumes (reads and removes) the first valid proxy line from a file.
     *
     * <p>This operation is atomic and thread-safe:</p>
     * <ul>
     *   <li>Uses synchronized block for intra-JVM thread safety</li>
     *   <li>Uses FileLock for cross-process safety</li>
     * </ul>
     *
     * <p>A valid proxy line is non-empty and does not start with '#' (comment).
     * The consumed line is permanently removed from the file.</p>
     *
     * @param filePath path to the proxy file
     * @return the first valid proxy string from the file
     * @throws IOException if the file cannot be read, written, or has no valid proxies
     */
    public static String consumeFirstFromFile(Path filePath) throws IOException {
        synchronized (FILE_LOCK) {
            if (!Files.exists(filePath)) {
                throw new IOException("Proxies file not found: " + filePath);
            }

            try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw");
                 FileChannel channel = raf.getChannel();
                 FileLock lock = channel.lock()) {

                // Read entire file
                long fileSize = raf.length();
                if (fileSize == 0) {
                    throw new IOException("Proxies file is empty: " + filePath);
                }

                byte[] bytes = new byte[(int) fileSize];
                raf.readFully(bytes);
                String content = new String(bytes, StandardCharsets.UTF_8);

                // Detect line separator used in file (preserve original format)
                String lineSeparator = content.contains("\r\n") ? "\r\n" : "\n";

                // Split by line breaks (handles both \n and \r\n)
                String[] linesArray = content.split("\\r?\\n", -1);
                List<String> lines = new ArrayList<>(Arrays.asList(linesArray));

                // Find first valid proxy (non-empty, non-comment)
                String consumedProxy = null;
                int consumedIndex = -1;

                for (int i = 0; i < lines.size(); i++) {
                    String trimmed = lines.get(i).trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        consumedProxy = trimmed;
                        consumedIndex = i;
                        break;
                    }
                }

                if (consumedProxy == null) {
                    throw new IOException("No valid proxies remaining in file: " + filePath);
                }

                // Remove consumed line
                lines.remove(consumedIndex);

                // Rebuild content preserving original line separator
                String newContent = String.join(lineSeparator, lines);

                // Rewrite file atomically (truncate then write)
                raf.seek(0);
                raf.setLength(0);
                raf.write(newContent.getBytes(StandardCharsets.UTF_8));

                return consumedProxy;
            }
        }
    }

    /**
     * Checks how many valid proxies remain in the file specified by the
     * "proxies" environment variable.
     *
     * <p>This is a read-only operation that does not consume any proxies.</p>
     *
     * @return the count of valid (non-empty, non-comment) proxy lines
     * @throws IOException if the file cannot be read
     * @throws IllegalStateException if the environment variable is not set
     */
    public static int countRemainingProxies() throws IOException {
        String filePath = System.getenv(PROXIES_ENV_VAR);

        if (filePath == null || filePath.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable '" + PROXIES_ENV_VAR + "' is not set.");
        }

        return countRemainingProxies(Path.of(filePath));
    }

    /**
     * Checks how many valid proxies remain in the specified file.
     *
     * <p>This is a read-only operation that does not consume any proxies.</p>
     *
     * @param filePath path to the proxy file
     * @return the count of valid (non-empty, non-comment) proxy lines
     * @throws IOException if the file cannot be read
     */
    public static int countRemainingProxies(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            return 0;
        }

        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        int count = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                count++;
            }
        }

        return count;
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