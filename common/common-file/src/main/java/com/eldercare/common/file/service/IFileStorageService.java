package com.eldercare.common.file.service;

import java.io.InputStream;

public interface IFileStorageService {
    /**
     * 上传文件
     * @param inputStream 文件输入流
     * @param originalFilename 原始文件名
     * @param contentType 媒体类型
     * @return 返回文件相对路径或唯一ID (fileId/path)
     */
    String upload(InputStream inputStream, String originalFilename, String contentType);

    /**
     * 下载文件
     * @param fileIdOrPath 文件标识或相对路径
     * @return 文件输入流
     */
    InputStream download(String fileIdOrPath);

    /**
     * 获取文件临时预览签名URL
     * @param fileIdOrPath 文件标识或相对路径
     * @param expiryMinutes 签名过期分钟数
     * @return 预览 URL
     */
    String getPreviewUrl(String fileIdOrPath, int expiryMinutes);

    /**
     * 删除文件
     * @param fileIdOrPath 文件标识或相对路径
     */
    void delete(String fileIdOrPath);
}
