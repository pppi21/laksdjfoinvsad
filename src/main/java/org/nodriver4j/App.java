package org.nodriver4j;

import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserConfig;

import java.io.IOException;

public class App {

    public static void main(String[] args) {
        String executablePath = "C:\\Users\\leofo\\Documents\\NoDriver4j\\src\\main\\resources\\chrome\\chrome-win64\\chrome.exe"; // <-- Update this

        BrowserConfig config = BrowserConfig.builder()
                .executablePath(executablePath)
                .warmProfile(true) // Enable profile warming
                .build();

        try {
            System.out.println("Launching browser with profile warming...");
            System.out.println("User data dir: " + config.getUserDataDir());

            Browser browser = Browser.launch(config);

            System.out.println("Browser launched successfully on port " + config.getPort());
            System.out.println("Browser is ready for use. Close manually when done.");

        } catch (IOException e) {
            System.err.println("Failed to launch browser: " + e.getMessage());
            e.printStackTrace();
        }
    }
}