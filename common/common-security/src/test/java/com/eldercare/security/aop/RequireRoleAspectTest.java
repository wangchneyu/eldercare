package com.eldercare.security.aop;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.security.annotation.RequireRole;
import com.eldercare.security.context.SecurityContextHolder;
import com.eldercare.security.domain.LoginUser;
import com.eldercare.security.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * RequireRoleAspect 集成测试
 * <p>
 * 通过 Spring 上下文验证 AOP 切面在真实环境下的行为
 */
@SpringBootTest(properties = "eldercare.security.enabled=false")
@Import({RequireRoleAspectTest.TestService.class, RequireRoleAspectTest.TestAopConfig.class})
public class RequireRoleAspectTest {

    @Autowired
    private TestService testService;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clear();
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clear();
    }

    // ==================== 有权限访问 ====================

    @Test
    public void testAdminAccessAdminOnlyMethod() {
        SecurityContextHolder.setLoginUser(new LoginUser(1L, "admin", Set.of(UserRole.ADMIN)));
        String result = testService.adminOnly();
        Assertions.assertEquals("admin-done", result);
    }

    @Test
    public void testCaregiverAccessCaregiverMethod() {
        SecurityContextHolder.setLoginUser(new LoginUser(2L, "caregiver1", Set.of(UserRole.CAREGIVER)));
        String result = testService.caregiverOnly();
        Assertions.assertEquals("caregiver-done", result);
    }

    @Test
    public void testAdminAccessMultiRoleMethod() {
        SecurityContextHolder.setLoginUser(new LoginUser(1L, "admin", Set.of(UserRole.ADMIN)));
        String result = testService.adminOrCaregiver();
        Assertions.assertEquals("multi-done", result);
    }

    // ==================== 无权限访问 ====================

    @Test
    public void testFamilyAccessAdminOnlyMethod() {
        SecurityContextHolder.setLoginUser(new LoginUser(3L, "family1", Set.of(UserRole.FAMILY)));

        BizException ex = Assertions.assertThrows(BizException.class,
                () -> testService.adminOnly());
        Assertions.assertEquals(SystemErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    public void testOperatorAccessCaregiverMethod() {
        SecurityContextHolder.setLoginUser(new LoginUser(4L, "operator1", Set.of(UserRole.OPERATOR)));

        BizException ex = Assertions.assertThrows(BizException.class,
                () -> testService.caregiverOnly());
        Assertions.assertEquals(SystemErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    // ==================== 未登录访问 ====================

    @Test
    public void testUnauthenticatedAccess() {
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> testService.adminOnly());
        Assertions.assertEquals(SystemErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    public void testUnauthenticatedAccessMultiRole() {
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> testService.adminOrCaregiver());
        Assertions.assertEquals(SystemErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    // ==================== 多角色用户 ====================

    @Test
    public void testMultiRoleUserAccess() {
        SecurityContextHolder.setLoginUser(
                new LoginUser(5L, "multi", Set.of(UserRole.FAMILY, UserRole.OPERATOR)));

        // FAMILY 不能访问 ADMIN 方法
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> testService.adminOnly());
        Assertions.assertEquals(SystemErrorCode.FORBIDDEN, ex.getErrorCode());

        // FAMILY 不能访问 CAREGIVER 方法
        ex = Assertions.assertThrows(BizException.class,
                () -> testService.caregiverOnly());
        Assertions.assertEquals(SystemErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    // ==================== 测试配置 ====================

    @TestConfiguration
    public static class TestAopConfig {
        @Bean
        public RequireRoleAspect requireRoleAspect() {
            return new RequireRoleAspect();
        }
    }

    // ==================== 测试 Service ====================

    @Component
    public static class TestService {

        @RequireRole(UserRole.ADMIN)
        public String adminOnly() {
            return "admin-done";
        }

        @RequireRole(UserRole.CAREGIVER)
        public String caregiverOnly() {
            return "caregiver-done";
        }

        @RequireRole({UserRole.ADMIN, UserRole.CAREGIVER})
        public String adminOrCaregiver() {
            return "multi-done";
        }
    }
}
