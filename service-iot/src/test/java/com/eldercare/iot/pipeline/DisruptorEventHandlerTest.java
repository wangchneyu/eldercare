package com.eldercare.iot.pipeline;

import com.eldercare.iot.entity.IotDeviceBinding;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.entity.IotDeviceModel;
import com.eldercare.iot.enums.BindingStatus;
import com.eldercare.iot.enums.BindingType;
import com.eldercare.iot.mapper.IotDeviceBindingMapper;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.mapper.IotDeviceModelMapper;
import com.eldercare.iot.metrics.IotMetrics;
import com.eldercare.iot.mqtt.InboundMqttMessage;
import com.eldercare.iot.parser.DeviceMessageParser;
import com.eldercare.iot.parser.ParserRegistry;
import com.eldercare.iot.parser.model.ParsedEvent;
import com.eldercare.iot.parser.model.ParsedSosEvent;
import com.eldercare.iot.parser.model.ParsedVitalSign;
import com.eldercare.iot.parser.model.RawDeviceMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DisruptorEventHandler 单元测试：验证设备启用校验、协议版本校验、单次解析、绑定快照注入。
 */
@ExtendWith(MockitoExtension.class)
class DisruptorEventHandlerTest {

    @Mock
    IotDeviceInstanceMapper instanceMapper;
    @Mock
    IotDeviceModelMapper modelMapper;
    @Mock
    IotDeviceBindingMapper bindingMapper;
    @Mock
    ParserRegistry parserRegistry;
    @Mock
    MessagePipeline messagePipeline;
    @Mock
    IotMetrics metrics;

    @InjectMocks
    DisruptorEventHandler handler;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nullRawMessage_isIgnored() {
        IotEvent event = new IotEvent();
        handler.onEvent(event, 0, false);
        assertNull(event.getRawMessage());
        verifyNoInteractions(instanceMapper, messagePipeline);
    }

    @Test
    void deviceNotFound_dropsMessage() {
        AtomicInteger acknowledgements = new AtomicInteger();
        RawDeviceMessage raw = rawMessage("VITAL_SIGN", acknowledgements);
        when(instanceMapper.selectOne(any())).thenReturn(null);

        handler.onEvent(wrap(raw), 0, false);

        verify(messagePipeline, never()).handle(any(), any(), any());
        assertEquals(1, acknowledgements.get());
        verify(metrics).mqttMessageRejected("unknown_device");
    }

    @Test
    void deviceDisabled_dropsMessage() {
        RawDeviceMessage raw = rawMessage("VITAL_SIGN");
        IotDeviceInstance inst = new IotDeviceInstance();
        inst.setDeviceId("DEV-001");
        inst.setLifecycleStatus("DISABLED");
        when(instanceMapper.selectOne(any())).thenReturn(inst);

        handler.onEvent(wrap(raw), 0, false);

        verify(messagePipeline, never()).handle(any(), any(), any());
    }

    @Test
    void unsupportedProtocolVersion_dropsMessageWithoutParse() {
        RawDeviceMessage raw = rawMessage("VITAL_SIGN");
        when(instanceMapper.selectOne(any())).thenReturn(activeInstance());
        when(modelMapper.selectById(1L)).thenReturn(model());
        DeviceMessageParser parser = mock(DeviceMessageParser.class);
        when(parser.supportsProtocolVersion("1.0")).thenReturn(false);
        when(parserRegistry.getParser("simulator")).thenReturn(Optional.of(parser));

        handler.onEvent(wrap(raw), 0, false);

        verify(parser, never()).parse(any());
        verify(messagePipeline, never()).handle(any(), any(), any());
    }

    @Test
    void parserReturnsEmpty_dropsMessage() {
        RawDeviceMessage raw = rawMessage("VITAL_SIGN");
        when(instanceMapper.selectOne(any())).thenReturn(activeInstance());
        when(modelMapper.selectById(1L)).thenReturn(model());
        DeviceMessageParser parser = mockParser(Optional.empty());
        when(parserRegistry.getParser("simulator")).thenReturn(Optional.of(parser));

        handler.onEvent(wrap(raw), 0, false);

        verify(parser, times(1)).parse(raw);
        verify(messagePipeline, never()).handle(any(), any(), any());
    }

    @Test
    void vitalSign_parsedOnce_andEnrichedWithElderId() throws Exception {
        RawDeviceMessage raw = rawMessage("VITAL_SIGN");
        when(instanceMapper.selectOne(any())).thenReturn(activeInstance());
        when(modelMapper.selectById(1L)).thenReturn(model());
        ParsedVitalSign parsed = new ParsedVitalSign(
                raw.eventId(), "msg-1", raw.deviceId(), raw.occurredAt(), raw.traceId(),
                raw.parkId(), raw.deviceType(), null, 75, 16, 1, "IN_BED", null);
        DeviceMessageParser parser = mockParser(Optional.of(parsed));
        when(parserRegistry.getParser("simulator")).thenReturn(Optional.of(parser));
        when(bindingMapper.selectList(any())).thenReturn(List.of(elderBinding()));

        handler.onEvent(wrap(raw), 0, false);

        verify(parser, times(1)).parse(raw);
        ArgumentCaptor<ParsedEvent> captor = ArgumentCaptor.forClass(ParsedEvent.class);
        verify(messagePipeline).handle(captor.capture(), eq(raw), eq(null));
        ParsedVitalSign enriched = (ParsedVitalSign) captor.getValue();
        assertEquals(123L, enriched.elderId());
    }

    @Test
    void sosEvent_enrichedWithBindingSnapshot() throws Exception {
        RawDeviceMessage raw = rawMessage("SOS");
        when(instanceMapper.selectOne(any())).thenReturn(activeInstance());
        when(modelMapper.selectById(1L)).thenReturn(model());
        ParsedSosEvent parsed = new ParsedSosEvent(
                raw.eventId(), "msg-1", raw.deviceId(), raw.occurredAt(), raw.traceId(),
                raw.parkId(), raw.deviceType(), "SOS_TRIGGERED",
                null, null, null, null, null, null, null, "BUTTON_PRESS", 85, null);
        DeviceMessageParser parser = mockParser(Optional.of(parsed));
        when(parserRegistry.getParser("simulator")).thenReturn(Optional.of(parser));
        when(bindingMapper.selectList(any())).thenReturn(List.of(locationBinding()));

        handler.onEvent(wrap(raw), 0, false);

        ArgumentCaptor<ParsedEvent> captor = ArgumentCaptor.forClass(ParsedEvent.class);
        verify(messagePipeline).handle(captor.capture(), eq(raw), eq(null));
        ParsedSosEvent enriched = (ParsedSosEvent) captor.getValue();
        assertEquals("BIND-LOC", enriched.locationBindingId());
        assertEquals("LOC-001", enriched.location().get("locationId"));
    }

    @Test
    void fallEvent_enrichedWithElderAndLocationSnapshot() throws Exception {
        RawDeviceMessage raw = rawMessage("FALL");
        when(instanceMapper.selectOne(any())).thenReturn(activeInstance());
        when(modelMapper.selectById(1L)).thenReturn(model());
        ParsedSosEvent parsed = new ParsedSosEvent(
                raw.eventId(), "msg-1", raw.deviceId(), raw.occurredAt(), raw.traceId(),
                raw.parkId(), "WATCH", "FALL_DETECTED",
                null, null, null, null, null, null, null, "FALL_DOWN", 80, null);
        DeviceMessageParser parser = mockParser(Optional.of(parsed));
        when(parserRegistry.getParser("simulator")).thenReturn(Optional.of(parser));
        when(bindingMapper.selectList(any())).thenReturn(List.of(elderBinding(), locationBinding()));

        handler.onEvent(wrap(raw), 0, false);

        ArgumentCaptor<ParsedEvent> captor = ArgumentCaptor.forClass(ParsedEvent.class);
        verify(messagePipeline).handle(captor.capture(), eq(raw), eq(null));
        ParsedSosEvent enriched = (ParsedSosEvent) captor.getValue();
        assertEquals("FALL_DETECTED", enriched.eventType());
        assertEquals(123L, enriched.elderId());
        assertEquals("BIND-LOC", enriched.locationBindingId());
    }

    @Test
    void noActiveBinding_enrichmentKeepsRouteParkId() throws Exception {
        RawDeviceMessage raw = rawMessage("VITAL_SIGN");
        when(instanceMapper.selectOne(any())).thenReturn(activeInstance());
        when(modelMapper.selectById(1L)).thenReturn(model());
        ParsedVitalSign parsed = new ParsedVitalSign(
                raw.eventId(), "msg-1", raw.deviceId(), raw.occurredAt(), raw.traceId(),
                raw.parkId(), raw.deviceType(), null, 75, 16, 1, "IN_BED", null);
        DeviceMessageParser parser = mockParser(Optional.of(parsed));
        when(parserRegistry.getParser("simulator")).thenReturn(Optional.of(parser));
        when(bindingMapper.selectList(any())).thenReturn(Collections.emptyList());

        handler.onEvent(wrap(raw), 0, false);

        ArgumentCaptor<ParsedEvent> captor = ArgumentCaptor.forClass(ParsedEvent.class);
        verify(messagePipeline).handle(captor.capture(), eq(raw), eq(null));
        ParsedVitalSign enriched = (ParsedVitalSign) captor.getValue();
        assertNull(enriched.elderId());
    }

    private RawDeviceMessage rawMessage(String messageType) {
        return rawMessage(messageType, null);
    }

    private RawDeviceMessage rawMessage(String messageType, AtomicInteger acknowledgements) {
        try {
            JsonNode envelope = mapper.readTree(("""
                    {
                        "messageId": "msg-1",
                        "deviceId": "DEV-001",
                        "messageType": "%s",
                        "protocolVersion": "1.0",
                        "occurredAt": "2026-07-24T02:30:00Z"
                    }
                    """).formatted(messageType));
            InboundMqttMessage inbound = acknowledgements == null ? null : new InboundMqttMessage(
                    "elder/P001/MATTRESS/DEV-001/up/telemetry", null, 1, 1, msg -> acknowledgements.incrementAndGet());
            return new RawDeviceMessage(
                    "elder/P001/MATTRESS/DEV-001/up/telemetry",
                    envelope, "P001", "MATTRESS", "DEV-001", messageType,
                    "EVT-001", "trace-001", OffsetDateTime.parse("2026-07-24T02:30:00Z"), 1, 1, inbound);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private IotEvent wrap(RawDeviceMessage raw) {
        IotEvent event = new IotEvent();
        event.setRawMessage(raw);
        return event;
    }

    private IotDeviceInstance activeInstance() {
        IotDeviceInstance inst = new IotDeviceInstance();
        inst.setDeviceId("DEV-001");
        inst.setModelId(1L);
        inst.setLifecycleStatus("ACTIVE");
        return inst;
    }

    private IotDeviceModel model() {
        IotDeviceModel model = new IotDeviceModel();
        model.setId(1L);
        model.setParserCode("simulator");
        return model;
    }

    private DeviceMessageParser mockParser(Optional<ParsedEvent> result) {
        DeviceMessageParser parser = mock(DeviceMessageParser.class);
        // DisruptorEventHandler 通过 registry 按 parserCode 获取解析器，不再回调 parserCode()
        when(parser.parse(any())).thenReturn(result);
        when(parser.supportsProtocolVersion("1.0")).thenReturn(true);
        return parser;
    }

    private IotDeviceBinding elderBinding() {
        IotDeviceBinding b = new IotDeviceBinding();
        b.setBindingType(BindingType.ELDER.getCode());
        b.setBindingId("BIND-ELDER");
        b.setElderId(123L);
        b.setParkId("P001");
        b.setBuildingId("B001");
        b.setRoomId("R001");
        b.setRoomNo("301");
        b.setStatus(BindingStatus.ACTIVE.getCode());
        return b;
    }

    private IotDeviceBinding locationBinding() {
        IotDeviceBinding b = new IotDeviceBinding();
        b.setBindingType(BindingType.LOCATION.getCode());
        b.setBindingId("BIND-LOC");
        b.setLocationId("LOC-001");
        b.setLocationType("PUBLIC_AREA");
        b.setLocationName("三楼活动区");
        b.setFloorId("F3");
        b.setStatus(BindingStatus.ACTIVE.getCode());
        return b;
    }
}
