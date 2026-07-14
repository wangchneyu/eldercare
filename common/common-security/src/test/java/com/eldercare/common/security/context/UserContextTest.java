package com.eldercare.common.security.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class UserContextTest {

    @AfterEach
    public void tearDown() {
        UserContext.clear();
    }

    @Test
    public void testUserContextLifecycle() {
        UserInfo userInfo = new UserInfo();
        userInfo.setId(100L);
        userInfo.setUsername("test_user");
        userInfo.setRoles(Arrays.asList("CARE_STAFF", "ELDER"));

        // 1. 设置上下文
        UserContext.setUser(userInfo);
        
        // 2. 读取验证
        UserInfo contextUser = UserContext.getUser();
        assertNotNull(contextUser);
        assertEquals(100L, contextUser.getId());
        assertEquals("test_user", contextUser.getUsername());
        assertEquals(2, contextUser.getRoles().size());
        assertTrue(contextUser.getRoles().contains("CARE_STAFF"));

        // 3. 清理验证
        UserContext.clear();
        assertNull(UserContext.getUser());
    }

    @Test
    public void testUserContextThreadIsolation() throws InterruptedException {
        UserInfo mainUser = new UserInfo();
        mainUser.setId(1L);
        mainUser.setUsername("main_thread");
        UserContext.setUser(mainUser);

        AtomicReference<UserInfo> subThreadUser = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread thread = new Thread(() -> {
            // 在子线程中，由于 ThreadLocal 的隔离，获取的用户应该为 null
            subThreadUser.set(UserContext.getUser());
            
            // 子线程中设置新用户
            UserInfo childUser = new UserInfo();
            childUser.setId(2L);
            childUser.setUsername("child_thread");
            UserContext.setUser(childUser);
            
            latch.countDown();
        });

        thread.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // 验证主线程用户没有被子线程污染
        assertEquals(1L, UserContext.getUser().getId());
        assertEquals("main_thread", UserContext.getUser().getUsername());

        // 验证子线程在未设置前读取到的用户为 null
        assertNull(subThreadUser.get());
    }
}
