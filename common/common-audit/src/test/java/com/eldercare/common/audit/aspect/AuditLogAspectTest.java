package com.eldercare.common.audit.aspect;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eldercare.common.audit.annotation.AuditLog;
import com.eldercare.common.audit.config.AuditAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig
public class AuditLogAspectTest {

    @Autowired
    private TestService testService;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger logger;

    @Configuration
    @Import(AuditAutoConfiguration.class)
    static class TestConfig {
        @Bean
        public TestService testService() {
            return new TestService();
        }
    }

    @Service
    static class TestService {
        @AuditLog(value = "测试操作", saveParams = true, saveResult = true)
        public String execute(String param) {
            return "Result: " + param;
        }

        @AuditLog(value = "测试失败操作", saveParams = true)
        public void executeWithException() {
            throw new RuntimeException("操作异常测试");
        }
    }

    @BeforeEach
    public void setUp() {
        logger = (Logger) LoggerFactory.getLogger(AuditLogAspect.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
    }

    @AfterEach
    public void tearDown() {
        logger.detachAppender(listAppender);
    }

    @Test
    public void testAuditLogSuccess() {
        String result = testService.execute("hello");
        assertEquals("Result: hello", result);

        // 验证日志被正确打印
        assertFalse(listAppender.list.isEmpty());
        ILoggingEvent logEvent = listAppender.list.get(0);
        String message = logEvent.getFormattedMessage();
        assertTrue(message.contains("[AUDIT_LOG]"));
        assertTrue(message.contains("\"operation\":\"测试操作\""));
        assertTrue(message.contains("\"status\":\"SUCCESS\""));
        assertTrue(message.contains("\"params\":\"[\\\"hello\\\"]\""));
        assertTrue(message.contains("\"result\":\"\\\"Result: hello\\\"\""));
    }

    @Test
    public void testAuditLogFailure() {
        assertThrows(RuntimeException.class, () -> testService.executeWithException());

        // 验证异常情况下日志被正确打印，且状态为 FAIL
        assertFalse(listAppender.list.isEmpty());
        ILoggingEvent logEvent = listAppender.list.get(0);
        String message = logEvent.getFormattedMessage();
        assertTrue(message.contains("[AUDIT_LOG]"));
        assertTrue(message.contains("\"operation\":\"测试失败操作\""));
        assertTrue(message.contains("\"status\":\"FAIL\""));
        assertTrue(message.contains("\"error\":\"操作异常测试\""));
    }
}
