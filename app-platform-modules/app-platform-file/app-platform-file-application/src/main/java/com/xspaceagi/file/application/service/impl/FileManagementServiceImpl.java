package com.xspaceagi.file.application.service.impl;

import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.file.domain.repository.FileRecordRepository;
import com.xspaceagi.file.domain.storage.FileStorageStrategy;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.utils.FileUrlResolver;
import jakarta.annotation.PostConstruct;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * File Management Service Implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileManagementServiceImpl implements FileManagementService {

    private final FileRecordRepository fileRecordRepository;
    private final Map<String, FileStorageStrategy> storageStrategyMap;
    private final TenantConfigApplicationService tenantConfigApplicationService;

    @Value("${storage.type:file}")
    private String defaultStorageType;

    private FileStorageStrategy getStorageStrategy() {
        if ("file".equals(defaultStorageType)){
            defaultStorageType = "local";
        }
        return storageStrategyMap.values().stream()
                .filter(strategy -> strategy.getStorageType().equals(defaultStorageType))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Storage strategy not found: " + defaultStorageType));
    }

    @Override
    public FileRecordDomain uploadFile(MultipartFile file, Long tenantId, Long userId,
                                       String targetType, Long targetId, String metadata, boolean isAuthRequired) {

        FileStorageStrategy strategy = getStorageStrategy();

        // Upload file, pass targetType for fileKey generation
        String fileKey;
        try {
            fileKey = strategy.upload(file.getInputStream(), file.getOriginalFilename(),
                    file.getContentType(), targetType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String fileUrl = "/api/f/" + fileKey;

        // Get fileBaseUrl from tenant config for full URL (needed by frontend)
        RequestContext<?> requestContext = RequestContext.get();
        TenantConfigDto tenantConfigDto = requestContext != null
                ? (TenantConfigDto) requestContext.getTenantConfig()
                : null;
        if (tenantConfigDto == null && tenantId != null) {
            tenantConfigDto = tenantConfigApplicationService.getTenantConfig(tenantId);
        }
        if (tenantConfigDto != null && tenantConfigDto.getSiteUrl() != null) {
            String siteUrl = tenantConfigDto.getSiteUrl();
            if (siteUrl.endsWith("/")) {
                siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
            }
            fileUrl = siteUrl + fileUrl;
        }

        // Get file extension
        String fileName = file.getOriginalFilename();
        String fileExtension = getFileExtension(fileName);

        // Save file record
        FileRecordDomain fileRecord = FileRecordDomain.builder()
                .tenantId(tenantId)
                .userId(userId)
                .targetType(targetType)
                .targetId(targetId)
                .fileName(fileName)
                .fileSize(file.getSize())
                .fileType(file.getContentType())
                .fileExtension(fileExtension)
                .metadata(metadata)
                .fileKey(fileKey)
                .storageType(strategy.getStorageType())
                .fileUrl(fileUrl)
                .authRequired(isAuthRequired)
                .status("active")
                .created(new Date())
                .modified(new Date())
                .build();

        return fileRecordRepository.save(fileRecord);

    }

    private static String getFileExtension(String fileName) {
        String fileExtension = "";
        if (fileName != null && fileName.contains(".")) {
            fileExtension = fileName.substring(fileName.lastIndexOf(".") + 1);
//            List<String> fileTypes = List.of("pdf", "txt", "doc", "docx", "md", "json", "xml", "xls", "xlsx", "ppt", "pptx", "mp4", "mov", "mp3", "wav", "aac", "flac", "ogg", "wma", "aiff", "m4a", "amr", "midi", "opus", "ra", "zip", "rar", "7z", "tar", "gz", "bz2", "tgz", "tar.gz", "tar.bz2", "tar.7z", "tar.gz", "jpg", "jpeg", "jpe", "png", "gif", "bmp", "ico", "icns", "svg", "webp", "heic", "mkv", "webm");
//            if (!fileTypes.contains(fileExtension.toLowerCase())) {
//                throw new BizException("Unsupported file type");
//            }
        }
        return fileExtension;
    }

    @Override
    public InputStream downloadFile(String fileKey) {
        FileRecordDomain fileRecord = fileRecordRepository.findByFileKey(fileKey);
        if (fileRecord == null) {
            throw new RuntimeException("File not found");
        }

        FileStorageStrategy strategy = storageStrategyMap.values().stream()
                .filter(s -> s.getStorageType().equals(fileRecord.getStorageType()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Storage strategy not found"));

        return strategy.download(fileKey);
    }

    @Override
    public boolean deleteFile(Long fileId) {
        FileRecordDomain fileRecord = fileRecordRepository.findById(fileId);
        if (fileRecord == null) {
            return false;
        }

        // Delete file from storage
        FileStorageStrategy strategy = storageStrategyMap.values().stream()
                .filter(s -> s.getStorageType().equals(fileRecord.getStorageType()))
                .findFirst()
                .orElse(null);

        if (strategy != null) {
            strategy.delete(fileRecord.getFileKey());
        }

        // Physically delete database record
        return fileRecordRepository.deleteById(fileId);
    }

    @Override
    public boolean batchDeleteFiles(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return false;
        }
        boolean allSuccess = true;
        for (Long fileId : fileIds) {
            if (fileId == null) {
                allSuccess = false;
                continue;
            }
            boolean deleted = deleteFile(fileId);
            if (!deleted) {
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    @Override
    public FileRecordDomain getFileById(Long fileId) {
        return fileRecordRepository.findById(fileId);
    }

    @Override
    public FileRecordDomain getFileByKey(String fileKey) {
        return TenantFunctions.callWithIgnoreCheck(() -> fileRecordRepository.findByFileKey(fileKey));
    }

    @Override
    public List<FileRecordDomain> listUserFiles(Long tenantId, Long userId) {
        return fileRecordRepository.findByTenantIdAndUserId(tenantId, userId);
    }

    @Override
    public List<FileRecordDomain> listTargetFiles(Long tenantId, String targetType, Long targetId) {
        return fileRecordRepository.findByTarget(tenantId, targetType, targetId);
    }

    @Override
    public String getFileUrl(String fileKey) {
        FileRecordDomain fileRecord = fileRecordRepository.findByFileKey(fileKey);
        if (fileRecord == null) {
            throw new RuntimeException("File not found");
        }
        return fileRecord.getFileUrl();
    }

    @Override
    public String generatePresignedUrl(String fileKey, int expireSeconds) {
        FileRecordDomain fileRecord = fileRecordRepository.findByFileKey(fileKey);
        if (fileRecord == null) {
            throw new RuntimeException("File not found");
        }

        FileStorageStrategy strategy = storageStrategyMap.values().stream()
                .filter(s -> s.getStorageType().equals(fileRecord.getStorageType()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Storage strategy not found"));

        return strategy.generatePresignedUrl(fileKey, expireSeconds);
    }

    @Override
    public String generatePresignedUrlByType(String fileKey, String storageType, int expireSeconds, Integer download) {
        FileStorageStrategy strategy = storageStrategyMap.values().stream()
                .filter(s -> s.getStorageType().equals(storageType))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Storage strategy not found: " + storageType));

        // If download is requested, query database for original filename
        if (download != null && download == 1) {
            FileRecordDomain fileRecord = TenantFunctions.callWithIgnoreCheck(() -> fileRecordRepository.findByFileKey(fileKey));
            if (fileRecord != null && fileRecord.getFileName() != null) {
                return strategy.generatePresignedUrl(fileKey, expireSeconds, fileRecord.getFileName());
            }
        }

        return strategy.generatePresignedUrl(fileKey, expireSeconds);
    }

    @PostConstruct
    public void initInternalFileResolver() {
        FileUrlResolver.setInternalResolver(this::resolveInternalFileUrl);
    }

    /**
     * 将云存储文件 URL 解析为“集群内可达”的内部签名 URL。
     * <p>
     * 存储的 docUrl 形如：
     * - http://{publicEndpoint}/nuwax-files/s3/default/20260709/{uuid}.pdf
     * - http://{siteUrl}/api/f/s3/default/20260709/{uuid}.pdf
     * 二者都对应 MinIO 对象 key = s3/default/20260709/{uuid}.pdf（bucket=nuwax-files）。
     * 这里重新用 S3_ENDPOINT 生成签名 URL（host 与签名一致，且集群内可达）。
     * 无法解析或非云存储时返回 null，由 FileUrlResolver 回退到原来的 localhost 逻辑。
     */
    public String resolveInternalFileUrl(String docUrl) {
        if (docUrl == null) {
            return null;
        }
        int idx = docUrl.indexOf("://");
        String rest = idx >= 0 ? docUrl.substring(idx + 3) : docUrl;
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String path = rest.substring(slash);
        String fileKey;
        if (path.startsWith("/nuwax-files/")) {
            fileKey = path.substring("/nuwax-files/".length());
        } else if (path.startsWith("/api/f/")) {
            fileKey = path.substring("/api/f/".length());
        } else {
            return null;
        }
        String[] parts = fileKey.split("/", 2);
        if (parts.length < 2) {
            return null;
        }
        String storageType = parts[0];
        if (!"s3".equals(storageType) && !"cos".equals(storageType) && !"oss".equals(storageType)) {
            return null;
        }
        FileStorageStrategy strategy = storageStrategyMap.values().stream()
                .filter(s -> s.getStorageType().equals(storageType))
                .findFirst()
                .orElse(null);
        if (strategy == null) {
            return null;
        }
        try {
            return strategy.generateInternalPresignedUrl(fileKey, 3600);
        } catch (Exception e) {
            log.warn("Failed to resolve internal file url: {}", docUrl, e);
            return null;
        }
    }
}
