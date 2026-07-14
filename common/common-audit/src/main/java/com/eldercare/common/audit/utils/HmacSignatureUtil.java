package com.eldercare.common.audit.utils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

public class HmacSignatureUtil {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * 对数据进行 HMAC-SHA256 签名，并返回 Base64 编码的签名串
     * @param data 待签名数据
     * @param secret 签名密钥
     * @return 签名后的 Base64 字符串
     */
    public static String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(secretKey);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 签名计算失败", e);
        }
    }

    /**
     * 安全验证签名，防止定时攻击
     * @param data 原始签名数据
     * @param signature 待验证的签名值
     * @param secret 签名密钥
     * @return 校验是否匹配
     */
    public static boolean verify(String data, String signature, String secret) {
        if (signature == null || secret == null) {
            return false;
        }
        String expectedSign = sign(data, secret);
        // MessageDigest.isEqual 是恒定时间比较算法，防止旁路/时间攻击
        return MessageDigest.isEqual(
            expectedSign.getBytes(StandardCharsets.UTF_8), 
            signature.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 拼接并规范化参数及 Body 内容，用于构建待签名的源串
     * @param params 排序参数
     * @param body 请求体数据
     * @param timestamp 时间戳
     * @param nonce 随机值
     * @return 拼接完成的规范化字符串
     */
    public static String buildSigningString(Map<String, String[]> params, String body, String timestamp, String nonce) {
        TreeMap<String, String[]> sortedParams = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> entry : sortedParams.entrySet()) {
            sb.append(entry.getKey()).append("=").append(String.join(",", entry.getValue())).append("&");
        }
        if (body != null && !body.isEmpty()) {
            sb.append("body=").append(body).append("&");
        }
        sb.append("timestamp=").append(timestamp).append("&");
        sb.append("nonce=").append(nonce);
        return sb.toString();
    }
}
