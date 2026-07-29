package com.eldercare.iot.support;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.redis.lock.RedisLock;
import com.eldercare.common.redis.service.RedisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    RedisService redisService;
    @Mock
    RedisLock redisLock;
    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void firstRequestExecutesAndStoresReplay() {
        IdempotencyService service = new IdempotencyService(redisService, redisLock, objectMapper);
        when(redisLock.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(redisLock.unlock(anyString(), anyString())).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();

        String result = service.execute("device-register", "key-1", Map.of("serialNo", "SN-1"), String.class,
                () -> "device-1-" + calls.incrementAndGet());

        assertEquals("device-1-1", result);
        assertEquals(1, calls.get());
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(redisService).set(anyString(), value.capture(), anyLong());
        assertEquals(fingerprint(Map.of("serialNo", "SN-1")) + "\n\"device-1-1\"", value.getValue());
    }

    @Test
    void completedRequestReplaysWithoutExecutingAgain() {
        IdempotencyService service = new IdempotencyService(redisService, redisLock, objectMapper);
        Map<String, String> request = Map.of("serialNo", "SN-1");
        String cachedValue = fingerprint(request) + "\n\"device-1\"";
        when(redisService.get(anyString())).thenReturn(cachedValue);

        String result = service.execute("device-register", "key-1", request, String.class,
                () -> { throw new AssertionError("should replay cached result"); });

        assertEquals("device-1", result);
    }

    @Test
    void reusedKeyWithDifferentRequestIsRejected() {
        IdempotencyService service = new IdempotencyService(redisService, redisLock, objectMapper);
        String cachedValue = fingerprint(Map.of("serialNo", "SN-1")) + "\n\"device-1\"";
        when(redisService.get(anyString())).thenReturn(cachedValue);

        assertThrows(BizException.class, () -> service.execute("device-register", "key-1",
                Map.of("serialNo", "SN-2"), String.class, () -> "unexpected"));
    }

    @Test
    void voidOperationIsReplayedWithoutRunningAgain() {
        IdempotencyService service = new IdempotencyService(redisService, redisLock, objectMapper);
        when(redisLock.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(redisLock.unlock(anyString(), anyString())).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();

        service.executeVoid("device-unbind:DEV-1", "key-void", "DEV-1", calls::incrementAndGet);
        String stored = fingerprint("DEV-1") + "\n\"INSTANCE\"";
        when(redisService.get(anyString())).thenReturn(stored);
        service.executeVoid("device-unbind:DEV-1", "key-void", "DEV-1", calls::incrementAndGet);

        assertEquals(1, calls.get());
    }

    private String fingerprint(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(request));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
