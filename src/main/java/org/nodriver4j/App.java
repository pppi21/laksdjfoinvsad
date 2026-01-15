package org.nodriver4j;

import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserConfig;

import java.io.IOException;

public class App {

    public static void main(String[] args) {
        String executablePath = System.getenv("chromepath");

        BrowserConfig config = BrowserConfig.builder()
                .executablePath(executablePath)
                .fingerprintEnabled(true)
                .proxyFromEnv()
                .webrtcPolicy("default")
                .warmProfile(true) // Enable profile warming
                .build();

        try {
            Browser browser = Browser.launch(config);
            System.out.println("Browser launched with fingerprint: " + browser.getFingerprint());
            System.out.println("User data dir: " + config.getUserDataDir());

            System.out.println("Browser launched successfully on port " + config.getPort());
            System.out.println("Browser is ready for use. Close manually when done.");

        } catch (IOException e) {
            System.err.println("Failed to launch browser: " + e.getMessage());
            e.printStackTrace();
        }
    }
}