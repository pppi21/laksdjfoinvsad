package org.nodriver4j.tools;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ProxyConverter {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java ProxyConverter <input_file> [output_file]");
            System.out.println("Example: java ProxyConverter proxies.txt converted_proxies.txt");
            return;
        }

        String inputFile = args[0];
        String outputFile = args.length >= 2 ? args[1] : "converted_proxies.txt";

        try {
            List<String> lines = Files.readAllLines(Paths.get(inputFile));
            List<String> convertedProxies = new ArrayList<>();

            // Pattern to match: protocol://user:pass@host:port
            Pattern pattern = Pattern.compile("(?:https?://)?([^:]+):([^@]+)@([^:]+):(\\d+)");

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String user = matcher.group(1);
                    String pass = matcher.group(2);
                    String host = matcher.group(3);
                    String port = matcher.group(4);

                    // Convert to host:port:user:pass format
                    String converted = String.format("%s:%s:%s:%s", host, port, user, pass);
                    convertedProxies.add(converted);
                    System.out.println("Converted: " + converted);
                } else {
                    System.err.println("Could not parse: " + line);
                }
            }

            // Write to output file
            Files.write(Paths.get(outputFile), convertedProxies);
            System.out.println("\nSuccessfully converted " + convertedProxies.size() + " proxies.");
            System.out.println("Output saved to: " + outputFile);

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}