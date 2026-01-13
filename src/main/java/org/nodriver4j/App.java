package org.nodriver4j;

import org.nodriver4j.core.Browser;
import org.nodriver4j.core.BrowserConfig;

import java.io.IOException;

public class App {

    public static void main(String[] args) {
        String executablePath = "C:\\Users\\leofo\\Documents\\NoDriver4j\\src\\main\\resources\\chrome\\chrome-win64\\chrome.exe";

        BrowserConfig config = BrowserConfig.builder()
                .executablePath(executablePath)
                .build();

        try {
            Browser browser = Browser.launch(config);
            System.out.println("Browser launched successfully on port " + config.getPort());
        } catch (IOException e) {
            System.err.println("Failed to launch browser: " + e.getMessage());
        }
    }
}