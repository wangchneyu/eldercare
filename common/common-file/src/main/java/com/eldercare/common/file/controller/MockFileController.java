package com.eldercare.common.file.controller;

import com.eldercare.common.file.service.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "eldercare.file", name = "type", havingValue = "local", matchIfMissing = true)
public class MockFileController {

    private final FileStorageService fileStorageService;

    @GetMapping("/files/preview/**")
    public void preview(HttpServletRequest request, HttpServletResponse response) {
        String uri = request.getRequestURI();
        String prefix = "/files/preview/";
        int index = uri.indexOf(prefix);
        if (index == -1) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String fileIdOrPath = uri.substring(index + prefix.length());
        log.info("[LocalFileStorage] 预览本地文件: {}", fileIdOrPath);

        try (InputStream inputStream = fileStorageService.download(fileIdOrPath)) {
            // 获取并设置 Content-Type
            String contentType = Files.probeContentType(Paths.get(fileIdOrPath));
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
            response.setContentType(contentType);
            
            // 将文件流写入响应中
            StreamUtils.copy(inputStream, response.getOutputStream());
        } catch (Exception e) {
            log.error("[LocalFileStorage] 预览文件出错, fileIdOrPath: {}", fileIdOrPath, e);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}
