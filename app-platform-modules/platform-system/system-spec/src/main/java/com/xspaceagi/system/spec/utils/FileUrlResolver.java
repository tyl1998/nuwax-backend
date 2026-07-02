package com.xspaceagi.system.spec.utils;

public class FileUrlResolver {

    private static final String LOCAL_BASE;

    static {
        String port = System.getProperty("server.port", System.getenv().getOrDefault("APP_PORT", "8080"));
        LOCAL_BASE = "http://localhost:" + port;
    }

    public static String toAbsoluteUrl(String url) {
        if (url != null && url.startsWith("/")) {
            return LOCAL_BASE + url;
        }
        return url;
    }
}
