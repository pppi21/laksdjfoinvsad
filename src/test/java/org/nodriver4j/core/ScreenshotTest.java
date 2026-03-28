package org.nodriver4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nodriver4j.math.BoundingBox;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Screenshot — full-page and element screenshots")
class ScreenshotTest extends BrowserTestBase {

    @Test
    @DisplayName("screenshot() creates a non-empty PNG file in screenshots/")
    void screenshotCreatesFile() throws IOException {
        navigateTo("text-content.html");

        Path screenshotsDir = Path.of("screenshots");

        // Count existing files before
        long countBefore = countFilesIn(screenshotsDir);

        page.screenshot();

        // Verify a new file appeared
        long countAfter = countFilesIn(screenshotsDir);
        assertTrue(countAfter > countBefore,
                "A new screenshot file should have been created");

        // Find the newest file and verify it's non-empty
        Path newest = findNewestFile(screenshotsDir);
        assertNotNull(newest, "Should find the screenshot file");
        assertTrue(Files.size(newest) > 0, "Screenshot file should be non-empty");
    }

    @Test
    @DisplayName("screenshotBytes returns a non-null, non-empty byte array")
    void screenshotBytesReturnsData() {
        navigateTo("text-content.html");
        byte[] png = page.screenshotBytes();
        assertNotNull(png, "Screenshot bytes should not be null");
        assertTrue(png.length > 100, "Screenshot should have substantial data, got " + png.length + " bytes");
        // PNG files start with the signature bytes: 137 80 78 71
        assertEquals((byte) 0x89, png[0], "Should start with PNG signature");
        assertEquals((byte) 0x50, png[1]);
        assertEquals((byte) 0x4E, png[2]);
        assertEquals((byte) 0x47, png[3]);
    }

    @Test
    @DisplayName("screenshotElementBytes captures a specific element with reasonable dimensions")
    void screenshotElementBytesCaptures() {
        navigateTo("text-content.html");

        // Get the bounding box for the element to know expected dimensions
        BoundingBox box = page.querySelector("#simple-text");
        assertNotNull(box, "Target element should exist");

        byte[] elementPng = page.screenshotElementBytes("#simple-text");
        assertNotNull(elementPng, "Element screenshot should not be null");
        assertTrue(elementPng.length > 100, "Element screenshot should have substantial data");

        // Verify PNG signature
        assertEquals((byte) 0x89, elementPng[0], "Should start with PNG signature");

        // Element screenshot should be smaller than full-page screenshot
        byte[] fullPng = page.screenshotBytes();
        assertTrue(elementPng.length < fullPng.length,
                "Element screenshot should be smaller than full page");
    }

    private long countFilesIn(Path dir) throws IOException {
        if (!Files.exists(dir)) return 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.png")) {
            long count = 0;
            for (Path ignored : stream) count++;
            return count;
        }
    }

    private Path findNewestFile(Path dir) throws IOException {
        if (!Files.exists(dir)) return null;
        Path newest = null;
        long newestTime = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.png")) {
            for (Path file : stream) {
                long modified = Files.getLastModifiedTime(file).toMillis();
                if (modified > newestTime) {
                    newestTime = modified;
                    newest = file;
                }
            }
        }
        return newest;
    }
}
