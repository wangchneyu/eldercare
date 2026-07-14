package com.eldercare.common.audit.utils;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HmacSignatureUtilTest {

    private final String secret = "TestSecr3tK3yForHmacValidation!";

    @Test
    public void testSignAndVerify() {
        String data = "id=12345&name=elderly&timestamp=1781298412";
        
        // 1. 签名
        String signature = HmacSignatureUtil.sign(data, secret);
        assertNotNull(signature);
        assertFalse(signature.isEmpty());

        // 2. 校验成功
        assertTrue(HmacSignatureUtil.verify(data, signature, secret));

        // 3. 校验失败案例 (被篡改的数据)
        String tamperedData = "id=12345&name=elderly&timestamp=1781298413";
        assertFalse(HmacSignatureUtil.verify(tamperedData, signature, secret));

        // 4. 校验失败案例 (错误密钥)
        assertFalse(HmacSignatureUtil.verify(data, signature, "WrongSecretKey!"));
    }

    @Test
    public void testBuildSigningString() {
        Map<String, String[]> params = new HashMap<>();
        params.put("z", new String[]{"last"});
        params.put("a", new String[]{"first"});
        params.put("b", new String[]{"val1", "val2"});

        String body = "{\"data\":\"test\"}";
        String timestamp = "1781298412";
        String nonce = "random_nonce_123";

        // TreeMap 应该将其自动按 alphabetical 顺序进行排序: a=first&b=val1,val2&z=last
        String signingString = HmacSignatureUtil.buildSigningString(params, body, timestamp, nonce);

        String expected = "a=first&b=val1,val2&z=last&body={\"data\":\"test\"}&timestamp=1781298412&nonce=random_nonce_123";
        assertEquals(expected, signingString);
    }
}
