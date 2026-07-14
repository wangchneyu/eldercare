package com.eldercare.security.context;

import com.eldercare.security.domain.LoginUser;
import com.eldercare.security.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * SecurityContextHolder 单元测试
 */
public class SecurityContextHolderTest {

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clear();
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clear();
    }

    @Test
    public void testSetAndGetLoginUser() {
        LoginUser user = new LoginUser(1L, "testUser", Set.of(UserRole.ADMIN));
        SecurityContextHolder.setLoginUser(user);

        LoginUser retrieved = SecurityContextHolder.getLoginUser();
        Assertions.assertNotNull(retrieved);
        Assertions.assertEquals(1L, retrieved.getUserId());
        Assertions.assertEquals("testUser", retrieved.getUsername());
        Assertions.assertTrue(retrieved.hasRole(UserRole.ADMIN));
    }

    @Test
    public void testGetLoginUserWhenEmpty() {
        Assertions.assertNull(SecurityContextHolder.getLoginUser());
    }

    @Test
    public void testClear() {
        LoginUser user = new LoginUser(2L, "clearUser", Set.of(UserRole.FAMILY));
        SecurityContextHolder.setLoginUser(user);
        Assertions.assertNotNull(SecurityContextHolder.getLoginUser());

        SecurityContextHolder.clear();
        Assertions.assertNull(SecurityContextHolder.getLoginUser());
    }

    @Test
    public void testGetCurrentUserId() {
        Assertions.assertNull(SecurityContextHolder.getCurrentUserId());

        SecurityContextHolder.setLoginUser(new LoginUser(100L, "user100", Set.of(UserRole.CAREGIVER)));
        Assertions.assertEquals(100L, SecurityContextHolder.getCurrentUserId());
    }

    @Test
    public void testThreadIsolation() throws InterruptedException {
        SecurityContextHolder.setLoginUser(new LoginUser(1L, "mainUser", Set.of(UserRole.ADMIN)));

        // 子线程中不应看到父线程的用户信息
        final LoginUser[] childUser = new LoginUser[1];
        Thread thread = new Thread(() -> childUser[0] = SecurityContextHolder.getLoginUser());
        thread.start();
        thread.join();

        Assertions.assertNull(childUser[0]);
        Assertions.assertNotNull(SecurityContextHolder.getLoginUser());
    }
}
