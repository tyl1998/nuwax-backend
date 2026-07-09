package com.xspaceagi.file.infra.storage;

import com.xspaceagi.file.domain.storage.FileStorageStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * S3 Protocol Storage Implementation
 */
@Slf4j
@Component
//@ConditionalOnProperty(name = "storage.type", havingValue = "s3")
public class S3FileStorageStrategy implements FileStorageStrategy {

    @Value("${s3.endpoint:}")
    private String endpoint;

    @Value("${s3.public-endpoint:}")
    private String publicEndpoint;

    @Value("${s3.access-key:}")
    private String accessKey;

    @Value("${s3.secret-key:}")
    private String secretKey;

    @Value("${s3.bucket-name:}")
    private String bucketName;

    @Value("${s3.region:us-east-1}")
    private String region;

    private volatile S3Client s3Client;
    private volatile S3Presigner s3Presigner;

    private S3Client getS3Client() {
        if (s3Client == null) {
            synchronized (this) {
                if (s3Client == null) {
                    AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
                    s3Client = S3Client.builder()
                            .endpointOverride(URI.create(endpoint))
                            .credentialsProvider(StaticCredentialsProvider.create(credentials))
                            .region(Region.of(region))
                            .forcePathStyle(true)
                            .build();
                }
            }
        }
        return s3Client;
    }

    private S3Presigner getS3Presigner() {
        if (s3Presigner == null) {
            synchronized (this) {
                if (s3Presigner == null) {
                    AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);

                    software.amazon.awssdk.services.s3.presigner.S3Presigner.Builder builder = S3Presigner.builder()
                            .endpointOverride(URI.create(endpoint))
                            .credentialsProvider(StaticCredentialsProvider.create(credentials))
                            .region(Region.of(region));

                    // Configure path-style access for presigner
                    builder.serviceConfiguration(
                            software.amazon.awssdk.services.s3.S3Configuration.builder()
                                    .pathStyleAccessEnabled(true)
                                    .build()
                    );

                    s3Presigner = builder.build();
                }
            }
        }
        return s3Presigner;
    }

    @Override
    public String upload(InputStream inputStream, String fileName, String contentType, String targetType) {
        try {
            S3Client client = getS3Client();
            String fileKey = FileKeyGenerator.generate(fileName, targetType, getStorageType());

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileKey)
                    .contentType(contentType)
                    .build();

            client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, inputStream.available()));

            log.info("File uploaded to S3 successfully: {}", fileKey);
            return fileKey;
        } catch (Exception e) {
            log.error("Failed to upload file to S3", e);
            throw new RuntimeException("File upload failed", e);
        }
    }

    @Override
    public InputStream download(String fileKey) {
        try {
            S3Client client = getS3Client();
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileKey)
                    .build();
            return client.getObject(getObjectRequest);
        } catch (Exception e) {
            log.error("Failed to download file from S3: {}", fileKey, e);
            throw new RuntimeException("File download failed", e);
        }
    }

    @Override
    public boolean delete(String fileKey) {
        try {
            S3Client client = getS3Client();
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileKey)
                    .build();
            client.deleteObject(deleteObjectRequest);
            log.info("File deleted from S3 successfully: {}", fileKey);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete file from S3: {}", fileKey, e);
            return false;
        }
    }

    @Override
    public String getFileUrl(String fileKey) {
        return fileKey;
    }

    @Override
    public String generatePresignedUrl(String fileKey, int expireSeconds) {
        return generatePresignedUrl(fileKey, expireSeconds, null);
    }

    @Override
    public String generatePresignedUrl(String fileKey, int expireSeconds, String fileName) {
        return doPresign(fileKey, expireSeconds, fileName, true);
    }

    @Override
    public String generateInternalPresignedUrl(String fileKey, int expireSeconds) {
        // 内部访问：保留 S3_ENDPOINT（集群内可达），不替换为公网/办公网 public-endpoint。
        // 签名是基于 internal endpoint 计算的，host 与签名一致，MinIO 验签通过。
        return doPresign(fileKey, expireSeconds, null, false);
    }

    private String doPresign(String fileKey, int expireSeconds, String fileName, boolean replacePublic) {
        try {
            S3Presigner presigner = getS3Presigner();

            // Remove leading slash from fileKey if present
            String objectKey = fileKey.startsWith("/") ? fileKey.substring(1) : fileKey;

            GetObjectRequest.Builder requestBuilder = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey);

            // Add Content-Disposition header if fileName is provided
            if (fileName != null && !fileName.isEmpty()) {
                requestBuilder.responseContentDisposition("attachment; filename=\"" + URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "\"");
            }

            GetObjectRequest getObjectRequest = requestBuilder.build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(expireSeconds))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            String url = presignedRequest.url().toString();

            // 内部访问（replacePublic=false）保留 internal endpoint，不替换。
            // 外部访问（replacePublic=true）将 internal endpoint 替换为 public-endpoint，
            // 由 nginx 代理并以 internal host 头转发给 MinIO 验签。
            if (replacePublic && org.apache.commons.lang3.StringUtils.isNotBlank(publicEndpoint)) {
                String internalBase = endpoint.endsWith("/") ? endpoint : endpoint + "/";
                String publicBase = publicEndpoint.endsWith("/") ? publicEndpoint : publicEndpoint + "/";
                url = url.replace(internalBase, publicBase);
                // Also replace without trailing slash
                url = url.replace(endpoint, publicEndpoint);
            }

            log.info("S3 presigned URL generated successfully: {}", fileKey);
            return url;
        } catch (Exception e) {
            log.error("Failed to generate S3 presigned URL: {}", fileKey, e);
            return null;
        }
    }

    @Override
    public String getStorageType() {
        return "s3";
    }
}
