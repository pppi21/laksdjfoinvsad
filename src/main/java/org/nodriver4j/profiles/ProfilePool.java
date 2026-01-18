package org.nodriver4j.profiles;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Thread-safe pool for managing profile consumption and output.
 *
 * <p>ProfilePool handles the lifecycle of profiles from CSV files:</p>
 * <ul>
 *   <li>Loading profiles from an input CSV file</li>
 *   <li>Consuming profiles one at a time (thread-safe, removes from file)</li>
 *   <li>Writing completed profiles to an output CSV file</li>
 * </ul>
 *
 * <p>This class uses file locking for both intra-JVM thread safety and
 * cross-process safety, allowing multiple browser instances to safely
 * consume unique profiles.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create pool with explicit paths
 * ProfilePool pool = new ProfilePool(
 *     Path.of("input_profiles.csv"),
 *     Path.of("completed_profiles.csv")
 * );
 *
 * // Or use environment variables
 * ProfilePool pool = new ProfilePool();
 *
 * // Consume a profile
 * Profile profile = pool.consumeFirst();
 *
 * // After script completes, write with extra fields
 * Profile completed = profile.toBuilder()
 *     .accountLoginInfo(email + ":" + password)
 *     .extraField("Birthday Month", "02")
 *     .extraField("Birthday Day", "01")
 *     .extraField("Birthday Year", "1995")
 *     .build();
 * pool.writeCompleted(completed);
 * }</pre>
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code profiles_input} - Path to input CSV file</li>
 *   <li>{@code profiles_output} - Path to output CSV file</li>
 * </ul>
 *
 * @see Profile
 */
public class ProfilePool {

    private static final String INPUT_ENV_VAR = "profiles_input";
    private static final String OUTPUT_ENV_VAR = "profiles_output";

    /**
     * Lock object for intra-JVM thread safety when consuming/writing profiles.
     * Combined with FileLock for cross-process safety.
     */
    private static final Object FILE_LOCK = new Object();

    private final Path inputPath;
    private final Path outputPath;

    /**
     * Creates a ProfilePool using environment variables for file paths.
     *
     * <p>Requires the following environment variables to be set:</p>
     * <ul>
     *   <li>{@code profiles_input} - Path to input CSV file</li>
     *   <li>{@code profiles_output} - Path to output CSV file</li>
     * </ul>
     *
     * @throws IllegalStateException if required environment variables are not set
     */
    public ProfilePool() {
        String inputEnv = System.getenv(INPUT_ENV_VAR);
        String outputEnv = System.getenv(OUTPUT_ENV_VAR);

        if (inputEnv == null || inputEnv.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable '" + INPUT_ENV_VAR + "' is not set. " +
                            "Set it to the path of your input profiles CSV file.");
        }

        if (outputEnv == null || outputEnv.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable '" + OUTPUT_ENV_VAR + "' is not set. " +
                            "Set it to the path for completed profiles CSV file.");
        }

        this.inputPath = Path.of(inputEnv);
        this.outputPath = Path.of(outputEnv);
    }

    /**
     * Creates a ProfilePool with explicit file paths.
     *
     * <p>This constructor takes priority over environment variables.</p>
     *
     * @param inputPath  path to the input CSV file containing profiles
     * @param outputPath path to the output CSV file for completed profiles
     * @throws IllegalArgumentException if either path is null
     */
    public ProfilePool(Path inputPath, Path outputPath) {
        if (inputPath == null) {
            throw new IllegalArgumentException("Input path cannot be null");
        }
        if (outputPath == null) {
            throw new IllegalArgumentException("Output path cannot be null");
        }

        this.inputPath = inputPath;
        this.outputPath = outputPath;
    }

    /**
     * Creates a ProfilePool with explicit file paths as strings.
     *
     * @param inputPath  path to the input CSV file
     * @param outputPath path to the output CSV file
     * @throws IllegalArgumentException if either path is null or blank
     */
    public ProfilePool(String inputPath, String outputPath) {
        if (inputPath == null || inputPath.isBlank()) {
            throw new IllegalArgumentException("Input path cannot be null or blank");
        }
        if (outputPath == null || outputPath.isBlank()) {
            throw new IllegalArgumentException("Output path cannot be null or blank");
        }

        this.inputPath = Path.of(inputPath);
        this.outputPath = Path.of(outputPath);
    }

    // ==================== Consumption ====================

    /**
     * Consumes (reads and removes) the first profile from the input file.
     *
     * <p>This operation is atomic and thread-safe:</p>
     * <ul>
     *   <li>Uses synchronized block for intra-JVM thread safety</li>
     *   <li>Uses FileLock for cross-process safety</li>
     * </ul>
     *
     * <p>The profile is permanently removed from the input file after being read.
     * The header row is preserved.</p>
     *
     * @return the first available Profile from the file
     * @throws IOException if the file cannot be read/written or has no valid profiles
     */
    public Profile consumeFirst() throws IOException {
        synchronized (FILE_LOCK) {
            if (!Files.exists(inputPath)) {
                throw new IOException("Profiles input file not found: " + inputPath);
            }

            try (RandomAccessFile raf = new RandomAccessFile(inputPath.toFile(), "rw");
                 FileChannel channel = raf.getChannel();
                 FileLock lock = channel.lock()) {

                // Read entire file
                long fileSize = raf.length();
                if (fileSize == 0) {
                    throw new IOException("Profiles input file is empty: " + inputPath);
                }

                byte[] bytes = new byte[(int) fileSize];
                raf.readFully(bytes);
                String content = new String(bytes, StandardCharsets.UTF_8);

                // Detect line separator used in file (preserve original format)
                String lineSeparator = content.contains("\r\n") ? "\r\n" : "\n";

                // Split by line breaks
                String[] linesArray = content.split("\\r?\\n", -1);
                List<String> lines = new ArrayList<>(Arrays.asList(linesArray));

                // Find first valid profile (non-empty, non-header, non-comment)
                String headerRow = null;
                String consumedRow = null;
                int consumedIndex = -1;

                for (int i = 0; i < lines.size(); i++) {
                    String trimmed = lines.get(i).trim();

                    if (trimmed.isEmpty()) {
                        continue;
                    }

                    // First non-empty line is the header
                    if (headerRow == null) {
                        headerRow = trimmed;
                        continue;
                    }

                    // Skip comments
                    if (trimmed.startsWith("#")) {
                        continue;
                    }

                    // Found a valid profile row
                    consumedRow = lines.get(i); // Use original (not trimmed) to preserve formatting
                    consumedIndex = i;
                    break;
                }

                if (consumedRow == null) {
                    throw new IOException("No valid profiles remaining in file: " + inputPath);
                }

                // Parse the profile
                Profile profile = Profile.fromCSVRow(consumedRow);

                // Remove consumed line
                lines.remove(consumedIndex);

                // Rebuild content preserving original line separator
                String newContent = String.join(lineSeparator, lines);

                // Rewrite file atomically (truncate then write)
                raf.seek(0);
                raf.setLength(0);
                raf.write(newContent.getBytes(StandardCharsets.UTF_8));

                System.out.println("[ProfilePool] Consumed profile: " + profile.emailAddress());

                return profile;
            }
        }
    }

    // ==================== Output ====================

    /**
     * Writes a completed profile to the output file.
     *
     * <p>If the output file doesn't exist, it will be created with a header row.
     * If the output file already exists, the profile is appended without headers.</p>
     *
     * <p>This operation is thread-safe using file locking.</p>
     *
     * @param profile the completed profile to write
     * @throws IOException if the file cannot be written
     */
    public void writeCompleted(Profile profile) throws IOException {
        if (profile == null) {
            throw new IllegalArgumentException("Profile cannot be null");
        }

        synchronized (FILE_LOCK) {
            // Ensure parent directory exists
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }

            boolean fileExists = Files.exists(outputPath) && Files.size(outputPath) > 0;

            try (RandomAccessFile raf = new RandomAccessFile(outputPath.toFile(), "rw");
                 FileChannel channel = raf.getChannel();
                 FileLock lock = channel.lock()) {

                // Move to end of file
                raf.seek(raf.length());

                // Write header if new file
                if (!fileExists) {
                    String headerRow = profile.toCSVHeaderRow();
                    raf.write(headerRow.getBytes(StandardCharsets.UTF_8));
                    raf.write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
                }

                // Write profile row
                String dataRow = profile.toCSVRow();
                raf.write(dataRow.getBytes(StandardCharsets.UTF_8));
                raf.write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));

                System.out.println("[ProfilePool] Wrote completed profile: " + profile.emailAddress());
            }
        }
    }

    // ==================== Status ====================

    /**
     * Counts how many valid profiles remain in the input file.
     *
     * <p>This is a read-only operation that does not consume any profiles.
     * A valid profile is a non-empty, non-header, non-comment line.</p>
     *
     * @return the count of remaining profiles
     * @throws IOException if the file cannot be read
     */
    public int countRemaining() throws IOException {
        synchronized (FILE_LOCK) {
            if (!Files.exists(inputPath)) {
                return 0;
            }

            List<String> lines = Files.readAllLines(inputPath, StandardCharsets.UTF_8);
            int count = 0;
            boolean headerSkipped = false;

            for (String line : lines) {
                String trimmed = line.trim();

                if (trimmed.isEmpty()) {
                    continue;
                }

                // Skip header (first non-empty line)
                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }

                // Skip comments
                if (trimmed.startsWith("#")) {
                    continue;
                }

                count++;
            }

            return count;
        }
    }

    /**
     * Counts how many profiles have been written to the output file.
     *
     * <p>This is a read-only operation.</p>
     *
     * @return the count of completed profiles (excludes header)
     * @throws IOException if the file cannot be read
     */
    public int countCompleted() throws IOException {
        synchronized (FILE_LOCK) {
            if (!Files.exists(outputPath)) {
                return 0;
            }

            List<String> lines = Files.readAllLines(outputPath, StandardCharsets.UTF_8);
            int count = 0;
            boolean headerSkipped = false;

            for (String line : lines) {
                String trimmed = line.trim();

                if (trimmed.isEmpty()) {
                    continue;
                }

                // Skip header
                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }

                count++;
            }

            return count;
        }
    }

    /**
     * Checks if there are any profiles remaining in the input file.
     *
     * @return true if at least one profile remains
     * @throws IOException if the file cannot be read
     */
    public boolean hasRemaining() throws IOException {
        return countRemaining() > 0;
    }

    // ==================== Path Accessors ====================

    /**
     * Returns the input file path.
     *
     * @return the path to the input CSV file
     */
    public Path inputPath() {
        return inputPath;
    }

    /**
     * Returns the output file path.
     *
     * @return the path to the output CSV file
     */
    public Path outputPath() {
        return outputPath;
    }

    @Override
    public String toString() {
        return String.format("ProfilePool{input=%s, output=%s}", inputPath, outputPath);
    }
}