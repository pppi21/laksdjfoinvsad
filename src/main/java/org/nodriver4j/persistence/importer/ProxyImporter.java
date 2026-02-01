package org.nodriver4j.persistence.importer;

import org.nodriver4j.persistence.entity.ProxyEntity;
import org.nodriver4j.persistence.entity.ProxyGroupEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports proxies from plain text files into entity objects.
 *
 * <p>This importer reads text files containing one proxy per line in the
 * standard format {@code host:port:username:password} and converts them
 * to {@link ProxyEntity} objects ready for persistence. It also creates
 * a {@link ProxyGroupEntity} to group the imported proxies.</p>
 *
 * <h2>File Format</h2>
 * <p>Plain text with one proxy per line:</p>
 * <pre>
 * res-us.lightningproxies.net:9999:user-zone-abc:secretpass
 * proxy2.example.com:8080:user2:pass2
 * # This is a comment (skipped)
 * </pre>
 *
 * <p>Empty lines and lines starting with {@code #} are ignored.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProxyImporter importer = new ProxyImporter();
 * ImportResult result = importer.importFromFile(
 *     Path.of("proxies.txt"),
 *     "Lightning Proxies"
 * );
 *
 * // Check for warnings
 * if (result.hasWarnings()) {
 *     result.warnings().forEach(System.err::println);
 * }
 *
 * // Save to database
 * ProxyGroupEntity savedGroup = groupRepo.save(result.group());
 * result.proxies().forEach(p -> p.groupId(savedGroup.id()));
 * proxyRepo.saveAll(result.proxies());
 * }</pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Read and parse plain text proxy files</li>
 *   <li>Convert proxy strings to ProxyEntity objects</li>
 *   <li>Create ProxyGroupEntity for the import batch</li>
 *   <li>Collect warnings for lines that fail to parse</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Saving to database (caller uses repositories)</li>
 *   <li>Proxy string parsing logic (delegated to ProxyEntity.Builder.fromProxyString)</li>
 *   <li>Managing existing groups or proxy assignments</li>
 * </ul>
 *
 * @see ProxyEntity
 * @see ProxyGroupEntity
 */
public class ProxyImporter {

    // ==================== Import Result ====================

    /**
     * Result of a proxy import operation.
     *
     * <p>Contains the created group, list of parsed proxies, and any
     * warnings encountered during parsing.</p>
     *
     * @param group    the proxy group (not yet persisted, no ID)
     * @param proxies  the parsed proxies (not yet persisted, no IDs, no groupId set)
     * @param warnings list of warning messages for lines that failed to parse
     */
    public record ImportResult(
            ProxyGroupEntity group,
            List<ProxyEntity> proxies,
            List<String> warnings
    ) {
        /**
         * Checks if any warnings were generated during import.
         *
         * @return true if there are warnings
         */
        public boolean hasWarnings() {
            return warnings != null && !warnings.isEmpty();
        }

        /**
         * Gets the count of successfully parsed proxies.
         *
         * @return the number of proxies
         */
        public int proxyCount() {
            return proxies != null ? proxies.size() : 0;
        }

        /**
         * Gets the count of warnings (failed lines).
         *
         * @return the number of warnings
         */
        public int warningCount() {
            return warnings != null ? warnings.size() : 0;
        }
    }

    // ==================== Import Methods ====================

    /**
     * Imports proxies from a text file.
     *
     * <p>The group name defaults to the filename without extension.</p>
     *
     * @param filePath path to the proxy text file
     * @return the import result containing group, proxies, and warnings
     * @throws IOException if the file cannot be read
     */
    public ImportResult importFromFile(Path filePath) throws IOException {
        String filename = filePath.getFileName().toString();
        String groupName = filename.contains(".")
                ? filename.substring(0, filename.lastIndexOf('.'))
                : filename;

        return importFromFile(filePath, groupName);
    }

    /**
     * Imports proxies from a text file with a specified group name.
     *
     * @param filePath  path to the proxy text file
     * @param groupName name for the proxy group
     * @return the import result containing group, proxies, and warnings
     * @throws IOException if the file cannot be read
     */
    public ImportResult importFromFile(Path filePath, String groupName) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IOException("Proxy file not found: " + filePath);
        }

        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        return importFromContent(content, groupName);
    }

    /**
     * Imports proxies from a content string.
     *
     * <p>Each non-empty, non-comment line is parsed as a proxy in the
     * format {@code host:port:username:password}.</p>
     *
     * @param content   the text content containing proxy lines
     * @param groupName name for the proxy group
     * @return the import result containing group, proxies, and warnings
     */
    public ImportResult importFromContent(String content, String groupName) {
        if (content == null || content.isBlank()) {
            return new ImportResult(
                    new ProxyGroupEntity(groupName),
                    List.of(),
                    List.of("Proxy file content is empty")
            );
        }

        ProxyGroupEntity group = new ProxyGroupEntity(groupName);
        List<ProxyEntity> proxies = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String[] lines = content.split("\\r?\\n", -1);
        int lineNumber = 0;

        for (String line : lines) {
            lineNumber++;
            String trimmed = line.trim();

            // Skip empty lines
            if (trimmed.isEmpty()) {
                continue;
            }

            // Skip comments
            if (trimmed.startsWith("#")) {
                continue;
            }

            // Parse proxy line
            try {
                ProxyEntity entity = ProxyEntity.builder()
                        .fromProxyString(trimmed)
                        .build();
                proxies.add(entity);
            } catch (IllegalArgumentException e) {
                warnings.add("Line " + lineNumber + ": " + e.getMessage());
            }
        }

        System.out.println("[ProxyImporter] Imported " + proxies.size() + " proxies" +
                (warnings.isEmpty() ? "" : " with " + warnings.size() + " warning(s)"));

        return new ImportResult(group, proxies, warnings);
    }
}