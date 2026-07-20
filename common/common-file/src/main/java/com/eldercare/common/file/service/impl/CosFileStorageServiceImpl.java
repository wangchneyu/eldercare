package com.eldercare.common.file.service.impl;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.common.file.config.FileProperties;
import com.eldercare.common.file.service.IFileStorageService;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class CosFileStorageServiceImpl implements IFileStorageService {

    private final FileProperties fileProperties;
    private COSClient cosClient;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @PostConstruct
    public void init() {
        if (this.cosClient == null) {
            FileProperties.CosConfig cosConfig = fileProperties.getCos();
            COSCredentials creds = new BasicCOSCredentials(cosConfig.getSecretId(), cosConfig.getSecretKey());
            Region region = new Region(cosConfig.getRegion());
            ClientConfig clientConfig = new ClientConfig(region);
            this.cosClient = new COSClient(creds, clientConfig);
            log.info("[CosFileStorage] COS 客户端初始化成功, region: {}", cosConfig.getRegion());
        }
    }

    // Public setter for testing
    public void setCosClient(COSClient cosClient) {
        this.cosClient = cosClient;
    }

    @PreDestroy
    public void destroy() {
        if (cosClient != null) {
            cosClient.shutdown();
            log.info("[CosFileStorage] COS 客户端已关闭");
        }
    }

    @Override
    public String upload(InputStream inputStream, String originalFilename, String contentType) {
        try {
            // 1. 生成基于日期和UUID的文件名作为对象键
            String dateFolder = LocalDate.now().format(DATE_FORMATTER);
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String newFilename = UUID.randomUUID().toString().replace("-", "") + extension;
            String key = dateFolder + "/" + newFilename;

            FileProperties.CosConfig cosConfig = fileProperties.getCos();

            // 2. 准备 ObjectMetadata
            ObjectMetadata metadata = new ObjectMetadata();
            if (contentType != null) {
                metadata.setContentType(contentType);
            }
            cosClient.putObject(cosConfig.getBucketName(), key, inputStream, metadata);

            log.info("[CosFileStorage] 文件上传成功: {} -> {}/{}", originalFilename, cosConfig.getBucketName(), key);
            return key;
        } catch (Exception e) {
            log.error("[CosFileStorage] 文件上传失败", e);
            throw new BizException(SystemErrorCode.INTERNAL_ERROR, e);
        }
    }

    @Override
    public InputStream download(String fileIdOrPath) {
        try {
            FileProperties.CosConfig cosConfig = fileProperties.getCos();
            COSObject cosObject = cosClient.getObject(cosConfig.getBucketName(), fileIdOrPath);
            return cosObject.getObjectContent();
        } catch (Exception e) {
            log.error("[CosFileStorage] 下载文件失败: {}", fileIdOrPath, e);
            throw new BizException(SystemErrorCode.INTERNAL_ERROR, e);
        }
    }

    @Override
    public String getPreviewUrl(String fileIdOrPath, int expiryMinutes) {
        try {
            FileProperties.CosConfig cosConfig = fileProperties.getCos();
            Date expiration = new Date(System.currentTimeMillis() + expiryMinutes * 60 * 1000L);
            URL url = cosClient.generatePresignedUrl(cosConfig.getBucketName(), fileIdOrPath, expiration, HttpMethodName.GET);
            String signedUrl = url.toString();

            // 如果有自定义的预览域名（如 CDN 域名），将官方域名的前缀替换为自定义域名，但签名参数仍保留
            if (cosConfig.getDomain() != null && !cosConfig.getDomain().trim().isEmpty()) {
                String customDomain = cosConfig.getDomain().trim();
                if (!customDomain.endsWith("/")) {
                    customDomain = customDomain + "/";
                }
                String pathAndQuery = url.getFile();
                if (pathAndQuery.startsWith("/")) {
                    pathAndQuery = pathAndQuery.substring(1);
                }
                return customDomain + pathAndQuery;
            }

            return signedUrl;
        } catch (Exception e) {
            log.error("[CosFileStorage] 获取文件预览URL失败: {}", fileIdOrPath, e);
            throw new BizException(SystemErrorCode.INTERNAL_ERROR, e);
        }
    }

    @Override
    public void delete(String fileIdOrPath) {
        try {
            FileProperties.CosConfig cosConfig = fileProperties.getCos();
            cosClient.deleteObject(cosConfig.getBucketName(), fileIdOrPath);
            log.info("[CosFileStorage] 删除文件成功: {}/{}", cosConfig.getBucketName(), fileIdOrPath);
        } catch (Exception e) {
            log.error("[CosFileStorage] 删除文件失败: {}", fileIdOrPath, e);
            throw new BizException(SystemErrorCode.INTERNAL_ERROR, e);
        }
    }
}
