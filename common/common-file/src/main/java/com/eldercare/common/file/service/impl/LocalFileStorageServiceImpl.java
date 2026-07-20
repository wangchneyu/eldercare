package com.eldercare.common.file.service.impl;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.common.file.config.FileProperties;
import com.eldercare.common.file.service.IFileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class LocalFileStorageServiceImpl implements IFileStorageService {

    private final FileProperties fileProperties;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Override
    public String upload(InputStream inputStream, String originalFilename, String contentType) {
        try {
            // 1. 生成基于日期和UUID的文件目录与文件名
            String dateFolder = LocalDate.now().format(DATE_FORMATTER);
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String newFilename = UUID.randomUUID().toString().replace("-", "") + extension;
            String relativePath = dateFolder + "/" + newFilename;

            // 2. 创建物理目录
            Path targetDir = Paths.get(fileProperties.getLocal().getUploadPath(), dateFolder);
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            // 3. 写入文件
            Path targetFile = targetDir.resolve(newFilename);
            Files.copy(inputStream, targetFile);

            log.info("[LocalFileStorage] 文件上传成功: {} -> {}", originalFilename, targetFile.toAbsolutePath());
            return relativePath;
        } catch (IOException e) {
            log.error("[LocalFileStorage] 文件上传失败", e);
            throw new BizException(SystemErrorCode.INTERNAL_ERROR, e);
        }
    }

    @Override
    public InputStream download(String fileIdOrPath) {
        Path targetFile = Paths.get(fileProperties.getLocal().getUploadPath(), fileIdOrPath);
        if (!Files.exists(targetFile)) {
            log.warn("[LocalFileStorage] 文件下载失败，文件不存在: {}", targetFile.toAbsolutePath());
            throw new BizException(SystemErrorCode.BAD_REQUEST);
        }
        try {
            return new FileInputStream(targetFile.toFile());
        } catch (FileNotFoundException e) {
            throw new BizException(SystemErrorCode.INTERNAL_ERROR, e);
        }
    }

    @Override
    public String getPreviewUrl(String fileIdOrPath, int expiryMinutes) {
        // 本地环境生成一个映射本地 Controller 的临时预览链接
        String domain = fileProperties.getLocal().getDomain();
        if (domain.endsWith("/")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        // 生成本地预览地址
        return domain + "/files/preview/" + fileIdOrPath + "?expires=" + (System.currentTimeMillis() + expiryMinutes * 60 * 1000L);
    }

    @Override
    public void delete(String fileIdOrPath) {
        try {
            Path targetFile = Paths.get(fileProperties.getLocal().getUploadPath(), fileIdOrPath);
            if (Files.exists(targetFile)) {
                Files.delete(targetFile);
                log.info("[LocalFileStorage] 文件删除成功: {}", targetFile.toAbsolutePath());
            } else {
                log.warn("[LocalFileStorage] 文件删除失败，文件不存在: {}", targetFile.toAbsolutePath());
            }
        } catch (IOException e) {
            log.error("[LocalFileStorage] 文件删除异常", e);
            throw new BizException(SystemErrorCode.INTERNAL_ERROR, e);
        }
    }
}
