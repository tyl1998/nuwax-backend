package com.xspaceagi.system.spec.utils;

import java.net.URI;

public class FileUrlResolver {

    private static final int PORT;

    static {
        String portStr = System.getProperty("server.port", System.getenv().getOrDefault("APP_PORT", "8080"));
        PORT = Integer.parseInt(portStr);
    }

    public static String toAbsoluteUrl(String url) {
        if (url == null) return url;
        if (url.startsWith("/")) {
            return "http://localhost:" + PORT + url;
        }
        try {
            URI uri = new URI(url);
            URI localUri = new URI("http", null, "localhost", PORT, uri.getPath(), uri.getQuery(), null);
            return localUri.toString();
        } catch (Exception e) {
            return url;
        }
    }
}
