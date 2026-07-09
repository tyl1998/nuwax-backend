package com.xspaceagi.file.domain.storage;

import java.io.InputStream;

/**
 * 文件存储策略接口
 */
public interface FileStorageStrategy {

    /**
     * 上传文件
     *
     * @param inputStream 文件流
     * @param fileName    文件名
     * @param contentType 文件类型
     * @param targetType  业务类型
     * @return 文件key
     */
    String upload(InputStream inputStream, String fileName, String contentType, String targetType);

    /**
     * 下载文件
     *
     * @param fileKey 文件key
     * @return 文件流
     */
    InputStream download(String fileKey);

    /**
     * 删除文件
     *
     * @param fileKey 文件key
     * @return 是否成功
     */
    boolean delete(String fileKey);

    /**
     * 获取文件访问URL
     *
     * @param fileKey 文件key
     * @return 访问URL
     */
    String getFileUrl(String fileKey);

    /**
     * 生成签名URL（用于云存储）
     *
     * @param fileKey 文件key
     * @param expireSeconds 过期时间（秒）
     * @return 签名URL，如果不支持则返回null
     */
    default String generatePresignedUrl(String fileKey, int expireSeconds) {
        return null;
    }

    /**
     * 生成签名URL（用于云存储），支持指定下载文件名
     *
     * @param fileKey 文件key
     * @param expireSeconds 过期时间（秒）
     * @param fileName 下载时的文件名
     * @return 签名URL，如果不支持则返回null
     */
    default String generatePresignedUrl(String fileKey, int expireSeconds, String fileName) {
        return generatePresignedUrl(fileKey, expireSeconds);
    }

    /**
     * 生成内部签名URL（用于后端集群内访问云存储）
     * <p>
     * 与 {@link #generatePresignedUrl(String, int)} 的区别：保留内部 endpoint
     * （如 MinIO 的 172.17.x:9000），不替换为公网/办公网 public-endpoint。
     * 这样后端在集群内访问时，URL 既可达（集群内网络）又签名有效（host 与签名一致）。
     * </p>
     * 默认实现直接复用公网签名URL（云存储未配置内部 endpoint 时的兜底）。
     *
     * @param fileKey 文件key
     * @param expireSeconds 过期时间（秒）
     * @return 内部可达的签名URL
     */
    default String generateInternalPresignedUrl(String fileKey, int expireSeconds) {
        return generatePresignedUrl(fileKey, expireSeconds);
    }

    /**
     * 获取存储类型
     *
     * @return 存储类型
     */
    String getStorageType();
}
