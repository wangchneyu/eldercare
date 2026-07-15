package com.eldercare.common.file;

import com.eldercare.common.file.config.FileProperties;
import com.eldercare.common.file.service.impl.CosFileStorageServiceImpl;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class CosFileStorageServiceTest {

    private CosFileStorageServiceImpl cosFileStorageService;
    private FileProperties fileProperties;
    private COSClient mockCosClient;

    @BeforeEach
    public void setUp() {
        fileProperties = new FileProperties();
        fileProperties.setType("cos");
        
        FileProperties.CosConfig cosConfig = fileProperties.getCos();
        cosConfig.setSecretId("test-secret-id");
        cosConfig.setSecretKey("test-secret-key");
        cosConfig.setRegion("ap-guangzhou");
        cosConfig.setBucketName("test-bucket-123456789");
        cosConfig.setDomain("https://custom.domain.com");

        cosFileStorageService = new CosFileStorageServiceImpl(fileProperties);
        mockCosClient = mock(COSClient.class);
        cosFileStorageService.setCosClient(mockCosClient);
        
        cosFileStorageService.init();
    }

    @Test
    public void testUpload() {
        String content = "Hello COS!";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        String fileId = cosFileStorageService.upload(inputStream, "test.txt", "text/plain");

        assertNotNull(fileId);
        assertTrue(fileId.contains(".txt"));
        
        verify(mockCosClient, times(1)).putObject(
                eq("test-bucket-123456789"),
                eq(fileId),
                any(InputStream.class),
                any()
        );
    }

    @Test
    public void testDownload() throws Exception {
        String fileId = "20260715/abcde.txt";
        COSObject mockCosObject = mock(COSObject.class);
        String content = "Downloaded Content";
        ByteArrayInputStream bais = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        
        COSObjectInputStream cosObjectInputStream = new COSObjectInputStream(bais, null) {
            @Override
            public void close() throws java.io.IOException {
                bais.close();
            }
        };
        when(mockCosObject.getObjectContent()).thenReturn(cosObjectInputStream);
        when(mockCosClient.getObject("test-bucket-123456789", fileId)).thenReturn(mockCosObject);

        try (InputStream in = cosFileStorageService.download(fileId)) {
            byte[] bytes = in.readAllBytes();
            String downloaded = new String(bytes, StandardCharsets.UTF_8);
            assertEquals(content, downloaded);
        }
        
        verify(mockCosClient, times(1)).getObject("test-bucket-123456789", fileId);
    }

    @Test
    public void testGetPreviewUrlWithCustomDomain() throws Exception {
        String fileId = "20260715/abcde.txt";
        URL officialUrl = new URL("https://test-bucket-123456789.cos.ap-guangzhou.myqcloud.com/" + fileId + "?q-sign-algorithm=sha1");
        when(mockCosClient.generatePresignedUrl(eq("test-bucket-123456789"), eq(fileId), any(Date.class), eq(HttpMethodName.GET)))
                .thenReturn(officialUrl);

        String previewUrl = cosFileStorageService.getPreviewUrl(fileId, 10);
        
        assertEquals("https://custom.domain.com/" + fileId + "?q-sign-algorithm=sha1", previewUrl);
    }

    @Test
    public void testGetPreviewUrlWithoutCustomDomain() throws Exception {
        fileProperties.getCos().setDomain(null);
        String fileId = "20260715/abcde.txt";
        URL officialUrl = new URL("https://test-bucket-123456789.cos.ap-guangzhou.myqcloud.com/" + fileId + "?q-sign-algorithm=sha1");
        when(mockCosClient.generatePresignedUrl(eq("test-bucket-123456789"), eq(fileId), any(Date.class), eq(HttpMethodName.GET)))
                .thenReturn(officialUrl);

        String previewUrl = cosFileStorageService.getPreviewUrl(fileId, 10);
        
        assertEquals(officialUrl.toString(), previewUrl);
    }

    @Test
    public void testDelete() {
        String fileId = "20260715/abcde.txt";
        
        cosFileStorageService.delete(fileId);
        
        verify(mockCosClient, times(1)).deleteObject("test-bucket-123456789", fileId);
    }
}
