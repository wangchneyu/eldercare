package com.eldercare.common.file;

import com.eldercare.common.file.config.FileProperties;
import com.eldercare.common.file.service.FileStorageService;
import com.eldercare.common.file.service.impl.LocalFileStorageServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class FileStorageServiceTest {

    private FileStorageService fileStorageService;
    private FileProperties fileProperties;

    @TempDir
    Path tempDir;

    @BeforeEach
    public void setUp() {
        fileProperties = new FileProperties();
        fileProperties.setType("local");
        fileProperties.getLocal().setUploadPath(tempDir.toString());
        fileProperties.getLocal().setDomain("http://localhost:8080");
        
        fileStorageService = new LocalFileStorageServiceImpl(fileProperties);
    }

    @Test
    public void testUploadDownloadPreviewDeleteFlow() throws IOException {
        // 1. 测试上传
        String content = "Hello Eldercare Platform File Storage SDK!";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        String fileId = fileStorageService.upload(inputStream, "test.txt", "text/plain");

        assertNotNull(fileId);
        assertTrue(fileId.contains("/"));
        
        // 验证文件在物理目录上确实生成了
        Path uploadedFilePath = tempDir.resolve(fileId);
        assertTrue(Files.exists(uploadedFilePath));

        // 2. 测试下载
        try (InputStream downloadedStream = fileStorageService.download(fileId)) {
            byte[] bytes = downloadedStream.readAllBytes();
            String downloadedContent = new String(bytes, StandardCharsets.UTF_8);
            assertEquals(content, downloadedContent);
        }

        // 3. 测试签名URL生成
        String previewUrl = fileStorageService.getPreviewUrl(fileId, 10);
        assertNotNull(previewUrl);
        assertTrue(previewUrl.startsWith("http://localhost:8080/files/preview/"));
        assertTrue(previewUrl.contains("expires="));

        // 4. 测试删除
        fileStorageService.delete(fileId);
        assertFalse(Files.exists(uploadedFilePath));
    }
}
