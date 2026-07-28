package com.eldercare.iot.mq;

import com.eldercare.iot.entity.IotVitalDeliveryOutbox;
import com.eldercare.iot.mapper.IotVitalDeliveryOutboxMapper;
import com.eldercare.iot.metrics.IotMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VitalDeliveryOutboxServiceTest {

    @Mock
    IotVitalDeliveryOutboxMapper outboxMapper;
    @Mock
    IotMetrics metrics;
    @InjectMocks
    VitalDeliveryOutboxService service;

    @Test
    void captureFinalFailure_persistsOnlyTheFrozenEnvelope() {
        when(outboxMapper.insertOnConflict(any())).thenReturn(1);
        VitalDeliveryMessage delivery = new VitalDeliveryMessage(
                "EVT-1", "MSG-1", "DEV-1", "MATTRESS", "trace-1", "{\"eventId\":\"EVT-1\"}");

        service.captureFinalFailure(delivery, 4, new IllegalStateException("broker unavailable"));

        ArgumentCaptor<IotVitalDeliveryOutbox> captor = ArgumentCaptor.forClass(IotVitalDeliveryOutbox.class);
        verify(outboxMapper).insertOnConflict(captor.capture());
        IotVitalDeliveryOutbox saved = captor.getValue();
        assertEquals("EVT-1", saved.getEventId());
        assertEquals("elder-vital-raw", saved.getTopic());
        assertEquals("MATTRESS", saved.getTag());
        assertEquals("PENDING", saved.getStatus());
        assertEquals(4, saved.getRetryCount());
        assertEquals("{\"eventId\":\"EVT-1\"}", saved.getRawEnvelopeJson());
        assertTrue(saved.getExpiresAt().isAfter(saved.getCreatedAt().plusHours(23)));
        verify(metrics).vitalDeliverySaved("MATTRESS");
    }

    @Test
    void captureFinalFailure_duplicateDoesNotCreateAnotherRecord() {
        when(outboxMapper.insertOnConflict(any())).thenReturn(0);
        VitalDeliveryMessage delivery = new VitalDeliveryMessage(
                "EVT-1", "MSG-1", "DEV-1", "MATTRESS", "trace-1", "{}");

        service.captureFinalFailure(delivery, 4, new RuntimeException("down"));

        verify(metrics).vitalDeliveryDuplicate("MATTRESS");
        verify(metrics, never()).vitalDeliverySaved(anyString());
    }
}
