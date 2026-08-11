package com.eldercare.edge.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 工具：原始 payload 指纹、notificationId 派生键。
 */
public final class Sha256Util {

    private static final ThreadLocal<MessageDigest> DIGEST =
            ThreadLocal.withInitial(() -> {
                try {
                    return MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException("SHA-256 not available", e);
                }
            });

    private Sha256Util() {
    }

    public static String sha256Hex(byte[] data) {
        MessageDigest digest = DIGEST.get();
        digest.reset();
        return HexFormat.of().formatHex(digest.digest(data));
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 本地通知幂等键：SHA-256(siteId + deviceId + messageId + messageType + payloadSha256) 小写十六进制。
     * 它不是 C05 eventId。
     */
    public static String notificationId(String siteId, String deviceId, String messageId,
                                        String messageType, String payloadSha256) {
        return sha256Hex(siteId + deviceId + messageId + messageType + payloadSha256);
    }
}
