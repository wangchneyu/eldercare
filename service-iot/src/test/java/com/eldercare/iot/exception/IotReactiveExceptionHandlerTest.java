package com.eldercare.iot.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eldercare.common.core.domain.R;
import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.iot.enums.IotErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ServerWebInputException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class IotReactiveExceptionHandlerTest {

    private final IotReactiveExceptionHandler handler = new IotReactiveExceptionHandler();
    private final Logger logger = (Logger) LoggerFactory.getLogger(IotReactiveExceptionHandler.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void bizExceptionLogsWarnWithoutStackTrace() {
        ResponseEntity<R<Void>> response = handler.handleBizException(new BizException(IotErrorCode.DEVICE_NOT_FOUND));

        assertEquals(404, response.getStatusCode().value());
        assertEquals(212001, response.getBody().getCode());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertNull(event.getThrowableProxy());
    }

    @Test
    void unknownExceptionLogsErrorWithStackTrace() {
        ResponseEntity<R<Void>> response = handler.handleUnknown(new IllegalStateException("unexpected"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals(100001, response.getBody().getCode());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.ERROR, event.getLevel());
        assertNotNull(event.getThrowableProxy());
    }

    @Test
    void invalidInputReturnsBadRequestErrorCode() {
        ResponseEntity<R<Void>> response = handler.handleInput(new ServerWebInputException("invalid request"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(SystemErrorCode.BAD_REQUEST.getCode(), response.getBody().getCode());
    }

    @Test
    void missingResourceReturnsNotFoundErrorCode() {
        ResponseEntity<R<Void>> response = handler.handleNotFound(new NoResourceFoundException("/missing"));

        assertEquals(404, response.getStatusCode().value());
        assertEquals(SystemErrorCode.NOT_FOUND.getCode(), response.getBody().getCode());
    }
}
