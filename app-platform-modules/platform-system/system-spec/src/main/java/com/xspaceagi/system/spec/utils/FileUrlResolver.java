package com.xspaceagi.system.spec.utils;

import java.net.URI;
import java.util.function.Function;

public class FileUrlResolver {

    private static final int PORT;

    /**
     * 云存储（s3/cos/oss）内部访问时的 URL 解析钩子。
     * 由 file 模块在启动时注册：根据文件 URL 重新生成“集群内可达”的
     * 内部签名 URL（保留 S3_ENDPOINT，不替换为公网/办公网地址）。
     * 未注册（本地存储 / 未配置 S3_ENDPOINT）时，走原来的 localhost 逻辑。
     */
    private static volatile Function<String, String> internalResolver = null;

    public static void setInternalResolver(Function<String, String> resolver) {
        internalResolver = resolver;
    }

    static {
        String portStr = System.getProperty("server.port", System.getenv().getOrDefault("APP_PORT", "8080"));
        PORT = Integer.parseInt(portStr);
    }

    /**
     * 判断是否云存储 URL（路径形如 /nuwax-files/(s3|cos|oss)/... 或 /api/f/(s3|cos|oss)/...）。
     */
    private static boolean isCloudUrl(String url) {
        if (url == null) return false;
        int idx = url.indexOf("://");
        String rest = idx >= 0 ? url.substring(idx + 3) : url;
        int slash = rest.indexOf('/');
        if (slash < 0) return false;
        String path = rest.substring(slash);
        return path.matches("/(nuwax-files|api/f)/(s3|cos|oss)/.*");
    }

    public static String toAbsoluteUrl(String url) {
        if (url == null) return url;
        // 云存储：委托给注册的内部解析器，生成集群内可达的签名 URL
        if (internalResolver != null && isCloudUrl(url)) {
            try {
                String resolved = internalResolver.apply(url);
                if (resolved != null && !resolved.isEmpty()) {
                    return resolved;
                }
            } catch (Exception e) {
                // 解析失败，回退到原始逻辑
            }
        }
        // 原始逻辑：相对路径拼 localhost，绝对 URL 把 host 换成 localhost
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
