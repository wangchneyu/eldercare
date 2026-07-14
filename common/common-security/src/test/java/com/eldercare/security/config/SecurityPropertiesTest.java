package com.eldercare.security.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * SecurityProperties 单元测试
 */
public class SecurityPropertiesTest {

    @Test
    public void testDefaultValues() {
        SecurityProperties properties = new SecurityProperties();

        Assertions.assertTrue(properties.isEnabled());
        Assertions.assertNotNull(properties.getSecret());
        Assertions.assertTrue(properties.getSecret().length() >= 32, "密钥长度至少32字符");
        Assertions.assertEquals(7200, properties.getAccessTokenExpiration());
        Assertions.assertEquals(604800, properties.getRefreshTokenExpiration());
        Assertions.assertNotNull(properties.getWhitelist());
        Assertions.assertFalse(properties.getWhitelist().isEmpty(), "默认白名单不应为空");
    }

    @Test
    public void testDefaultWhitelistContainsLoginPaths() {
        SecurityProperties properties = new SecurityProperties();

        Assertions.assertTrue(properties.getWhitelist().contains("/auth/login"));
        Assertions.assertTrue(properties.getWhitelist().contains("/auth/register"));
        Assertions.assertTrue(properties.getWhitelist().contains("/auth/refresh"));
    }

    @Test
    public void testCustomValues() {
        SecurityProperties properties = new SecurityProperties();
        properties.setEnabled(false);
        properties.setSecret("CustomSecretKeyForTestingAtLeast32Chars!");
        properties.setAccessTokenExpiration(1800);
        properties.setRefreshTokenExpiration(3600);

        Assertions.assertFalse(properties.isEnabled());
        Assertions.assertEquals("CustomSecretKeyForTestingAtLeast32Chars!", properties.getSecret());
        Assertions.assertEquals(1800, properties.getAccessTokenExpiration());
        Assertions.assertEquals(3600, properties.getRefreshTokenExpiration());
    }

    @Test
    public void testCustomWhitelist() {
        SecurityProperties properties = new SecurityProperties();
        properties.getWhitelist().add("/custom/public/**");

        Assertions.assertTrue(properties.getWhitelist().contains("/custom/public/**"));
        Assertions.assertTrue(properties.getWhitelist().contains("/auth/login"),
                "自定义白名单不应覆盖默认值");
    }
}
