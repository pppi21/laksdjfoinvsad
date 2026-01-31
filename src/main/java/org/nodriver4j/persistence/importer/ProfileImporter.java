package org.nodriver4j.persistence.importer;

import org.nodriver4j.persistence.entity.ProfileEntity;
import org.nodriver4j.persistence.entity.ProfileGroupEntity;
import org.nodriver4j.profiles.Profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports profiles from CSV files into entity objects.
 *
 * <p>This importer reads CSV files in the standard profile format and converts
 * them to {@link ProfileEntity} objects ready for persistence. It also creates
 * a {@link ProfileGroupEntity} to group the imported profiles.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProfileImporter importer = new ProfileImporter();
 * ImportResult result = importer.importFromFile(
 *     Path.of("profiles.csv"),
 *     "Uber Accounts"
 * );
 *
 * // Check for warnings
 * if (result.hasWarnings()) {
 *     result.warnings().forEach(System.err::println);
 * }
 *
 * // Save to database
 * ProfileGroupEntity savedGroup = groupRepo.save(result.group());
 * result.profiles().forEach(p -> p.groupId(savedGroup.id()));
 * profileRepo.saveAll(result.profiles());
 * }</pre>
 *
 * <h2>CSV Format</h2>
 * <p>Expects the standard 31-column format with headers:</p>
 * <pre>
 * Email Address,Profile Name,Only One Checkout,Name on Card,...
 * </pre>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Read and parse CSV files</li>
 *   <li>Convert CSV rows to ProfileEntity objects</li>
 *   <li>Create ProfileGroupEntity for the import batch</li>
 *   <li>Collect warnings for rows that fail to parse</li>
 * </ul>
 *
 * <h2>NOT Responsible For</h2>
 * <ul>
 *   <li>Saving to database (caller uses repositories)</li>
 *   <li>Validation beyond basic parsing</li>
 *   <li>Managing existing groups</li>
 * </ul>
 *
 * @see ProfileEntity
 * @see ProfileGroupEntity
 */
public class ProfileImporter {

    // ==================== Import Result ====================

    /**
     * Result of a profile import operation.
     *
     * <p>Contains the created group, list of parsed profiles, and any
     * warnings encountered during parsing.</p>
     *
     * @param group    the profile group (not yet persisted, no ID)
     * @param profiles the parsed profiles (not yet persisted, no IDs, no groupId set)
     * @param warnings list of warning messages for rows that failed to parse
     */
    public record ImportResult(
            ProfileGroupEntity group,
            List<ProfileEntity> profiles,
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
         * Gets the count of successfully parsed profiles.
         *
         * @return the number of profiles
         */
        public int profileCount() {
            return profiles != null ? profiles.size() : 0;
        }

        /**
         * Gets the count of warnings (failed rows).
         *
         * @return the number of warnings
         */
        public int warningCount() {
            return warnings != null ? warnings.size() : 0;
        }
    }

    // ==================== Import Methods ====================

    /**
     * Imports profiles from a CSV file.
     *
     * <p>The group name defaults to the filename without extension.</p>
     *
     * @param csvPath path to the CSV file
     * @return the import result containing group, profiles, and warnings
     * @throws IOException if the file cannot be read
     */
    public ImportResult importFromFile(Path csvPath) throws IOException {
        String filename = csvPath.getFileName().toString();
        String groupName = filename.contains(".")
                ? filename.substring(0, filename.lastIndexOf('.'))
                : filename;

        return importFromFile(csvPath, groupName);
    }

    /**
     * Imports profiles from a CSV file with a specified group name.
     *
     * @param csvPath   path to the CSV file
     * @param groupName name for the profile group
     * @return the import result containing group, profiles, and warnings
     * @throws IOException if the file cannot be read
     */
    public ImportResult importFromFile(Path csvPath, String groupName) throws IOException {
        if (!Files.exists(csvPath)) {
            throw new IOException("CSV file not found: " + csvPath);
        }

        String content = Files.readString(csvPath, StandardCharsets.UTF_8);
        return importFromContent(content, groupName);
    }

    /**
     * Imports profiles from CSV content string.
     *
     * @param csvContent the CSV content
     * @param groupName  name for the profile group
     * @return the import result containing group, profiles, and warnings
     */
    public ImportResult importFromContent(String csvContent, String groupName) {
        if (csvContent == null || csvContent.isBlank()) {
            return new ImportResult(
                    new ProfileGroupEntity(groupName),
                    List.of(),
                    List.of("CSV content is empty")
            );
        }

        ProfileGroupEntity group = new ProfileGroupEntity(groupName);
        List<ProfileEntity> profiles = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Split into lines (handle both \n and \r\n)
        String[] lines = csvContent.split("\\r?\\n", -1);

        boolean headerSkipped = false;
        int rowNumber = 0;

        for (String line : lines) {
            rowNumber++;
            String trimmed = line.trim();

            // Skip empty lines
            if (trimmed.isEmpty()) {
                continue;
            }

            // Skip comment lines
            if (trimmed.startsWith("#")) {
                continue;
            }

            // Skip header row (first non-empty, non-comment line)
            if (!headerSkipped) {
                headerSkipped = true;
                continue;
            }

            // Parse data row
            try {
                ProfileEntity entity = parseRow(line);
                profiles.add(entity);
            } catch (Exception e) {
                warnings.add("Row " + rowNumber + ": " + e.getMessage());
            }
        }

        System.out.println("[ProfileImporter] Imported " + profiles.size() + " profiles" +
                (warnings.isEmpty() ? "" : " with " + warnings.size() + " warning(s)"));

        return new ImportResult(group, profiles, warnings);
    }

    // ==================== Parsing ====================

    /**
     * Parses a single CSV row into a ProfileEntity.
     *
     * <p>Uses {@link Profile#fromCSVRow(String)} for parsing, then converts
     * to ProfileEntity.</p>
     *
     * @param csvRow the CSV row string
     * @return the parsed ProfileEntity
     * @throws IllegalArgumentException if the row cannot be parsed
     */
    private ProfileEntity parseRow(String csvRow) {
        // Use existing Profile parsing logic
        Profile profile = Profile.fromCSVRow(csvRow);

        // Convert to entity
        return convertToEntity(profile);
    }

    /**
     * Converts a Profile DTO to a ProfileEntity.
     *
     * @param profile the Profile to convert
     * @return the ProfileEntity
     */
    private ProfileEntity convertToEntity(Profile profile) {
        return ProfileEntity.builder()
                // Core fields
                .emailAddress(profile.emailAddress())
                .profileName(profile.profileName())
                .onlyOneCheckout(profile.onlyOneCheckout())
                // Payment fields
                .nameOnCard(profile.nameOnCard())
                .cardType(profile.cardType())
                .cardNumber(profile.cardNumber())
                .expirationMonth(profile.expirationMonth())
                .expirationYear(profile.expirationYear())
                .cvv(profile.cvv())
                // Address flag
                .sameBillingShipping(profile.sameBillingShipping())
                // Shipping fields
                .shippingName(profile.shippingName())
                .shippingPhone(profile.shippingPhone())
                .shippingAddress(profile.shippingAddress())
                .shippingAddress2(profile.shippingAddress2())
                .shippingAddress3(profile.shippingAddress3())
                .shippingPostCode(profile.shippingPostCode())
                .shippingCity(profile.shippingCity())
                .shippingState(profile.shippingState())
                .shippingCountry(profile.shippingCountry())
                // Billing fields
                .billingName(profile.billingName())
                .billingPhone(profile.billingPhone())
                .billingAddress(profile.billingAddress())
                .billingAddress2(profile.billingAddress2())
                .billingAddress3(profile.billingAddress3())
                .billingPostCode(profile.billingPostCode())
                .billingCity(profile.billingCity())
                .billingState(profile.billingState())
                .billingCountry(profile.billingCountry())
                // Email access
                .catchallEmail(profile.catchallEmail())
                .imapPassword(profile.imapPassword())
                // Note: accountLoginInfo is output-only in original, not imported
                .build();
    }
}