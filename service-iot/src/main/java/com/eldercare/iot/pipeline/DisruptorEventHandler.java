package com.eldercare.iot.pipeline;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.entity.IotDeviceBinding;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.entity.IotDeviceModel;
import com.eldercare.iot.enums.BindingType;
import com.eldercare.iot.enums.LifecycleStatus;
import com.eldercare.iot.mapper.IotDeviceBindingMapper;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.mapper.IotDeviceModelMapper;
import com.eldercare.iot.metrics.IotMetrics;
import com.eldercare.iot.parser.DeviceMessageParser;
import com.eldercare.iot.parser.ParserRegistry;
import com.eldercare.iot.parser.model.ParsedEvent;
import com.eldercare.iot.parser.model.ParsedSosEvent;
import com.eldercare.iot.parser.model.ParsedVitalSign;
import com.eldercare.iot.parser.model.RawDeviceMessage;
import com.lmax.disruptor.EventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Disruptor 事件处理器 —— 唯一一次解析厂商 payload。
 * <p>
 * 执行：设备存在/启用校验 → 协议版本校验 → parser_code 路由解析 → 绑定快照注入 → 下游路由。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DisruptorEventHandler implements EventHandler<IotEvent> {

    private final IotDeviceInstanceMapper instanceMapper;
    private final IotDeviceModelMapper modelMapper;
    private final IotDeviceBindingMapper bindingMapper;
    private final ParserRegistry parserRegistry;
    private final MessagePipeline messagePipeline;
    private final IotMetrics metrics;

    @Override
    public void onEvent(IotEvent event, long sequence, boolean endOfBatch) {
        RawDeviceMessage raw = event.getRawMessage();
        if (raw == null) {
            return;
        }
        try {
            TraceContext.setTraceId(raw.traceId());
            process(raw);
        } catch (Exception e) {
            // Keep the MQTT ack for broker redelivery and keep the consumer thread alive.
            metrics.mqttMessageProcessingFailed("disruptor_handler");
            log.error("Disruptor processing failed; awaiting MQTT redelivery: eventId={}, traceId={}",
                    raw.eventId(), raw.traceId(), e);
        } finally {
            TraceContext.clear();
            event.clear();
        }
    }

    private void process(RawDeviceMessage raw) {
        // ① 校验设备存在且已启用
        IotDeviceInstance instance = instanceMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotDeviceInstance>()
                        .eq(IotDeviceInstance::getDeviceId, raw.deviceId())
        );
        if (instance == null) {
            log.warn("设备不存在，丢弃消息: deviceId={}, traceId={}", raw.deviceId(), raw.traceId());
            rejectAndAck(raw, "unknown_device");
            return;
        }
        if (!LifecycleStatus.ACTIVE.getCode().equals(instance.getLifecycleStatus())) {
            log.warn("设备未启用，丢弃消息: deviceId={}, status={}, traceId={}",
                    raw.deviceId(), instance.getLifecycleStatus(), raw.traceId());
            rejectAndAck(raw, "inactive_device");
            return;
        }

        // ② 校验协议版本
        IotDeviceModel model = modelMapper.selectById(instance.getModelId());
        if (model == null) {
            log.warn("设备型号不存在: deviceId={}, modelId={}", raw.deviceId(), instance.getModelId());
            rejectAndAck(raw, "unknown_model");
            return;
        }
        if (StringUtils.hasText(model.getDeviceType()) && !raw.deviceType().equals(model.getDeviceType())) {
            log.warn("Topic device type does not match registered model: deviceId={}, topicType={}, modelType={}",
                    raw.deviceId(), raw.deviceType(), model.getDeviceType());
            rejectAndAck(raw, "device_type_mismatch");
            return;
        }
        DeviceMessageParser parser = parserRegistry.getParser(model.getParserCode()).orElse(null);
        if (parser == null) {
            log.warn("解析器不存在: parserCode={}", model.getParserCode());
            rejectAndAck(raw, "unknown_parser");
            return;
        }
        String protocolVersion = raw.envelope().has("protocolVersion")
                ? raw.envelope().get("protocolVersion").asText()
                : null;
        if (!parser.supportsProtocolVersion(protocolVersion)) {
            log.warn("协议版本不受支持: parserCode={}, protocolVersion={}", model.getParserCode(), protocolVersion);
            rejectAndAck(raw, "unsupported_protocol");
            return;
        }

        // ③ 解析 payload（只在此处执行）
        ParsedEvent parsed = parser.parse(raw).orElse(null);
        if (parsed == null) {
            metrics.mqttMessageParseFailed("device_parser");
            log.warn("Payload 解析失败: deviceId={}, messageType={}", raw.deviceId(), raw.messageType());
            rejectAndAck(raw, "parse_failed");
            return;
        }

        // ④ 注入绑定快照
        BindingSnapshot snapshot = loadSnapshot(raw.deviceId(), raw.parkId());
        ParsedEvent enriched = enrich(parsed, snapshot);
        if (enriched instanceof ParsedSosEvent sos && !hasCompleteLocation(sos)) {
            rejectAndAck(raw, "missing_location_binding");
            return;
        }
        if (StringUtils.hasText(snapshot.parkId()) && !snapshot.parkId().equals(raw.parkId())) {
            rejectAndAck(raw, "park_id_mismatch");
            return;
        }

        // ⑤ 路由到下游
        messagePipeline.handle(enriched, raw, model.getHeartbeatTimeoutSeconds());

        // ⑥ 非 P0 消息在成功路由后即可 ack；P0 由 OutboxService 在事务提交后 ack
        if (!(enriched instanceof ParsedSosEvent) && raw.requiresAck()) {
            raw.ackMqtt();
            metrics.mqttMessageAcked(String.valueOf(raw.mqttQos()));
        }
    }

    private BindingSnapshot loadSnapshot(String deviceId, String routeParkId) {
        List<IotDeviceBinding> bindings = bindingMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotDeviceBinding>()
                        .eq(IotDeviceBinding::getDeviceId, deviceId)
                        .eq(IotDeviceBinding::getStatus, com.eldercare.iot.enums.BindingStatus.ACTIVE.getCode())
        );

        Long elderId = null;
        String elderBindingId = null;
        String parkId = routeParkId;
        String buildingId = null;
        String roomId = null;
        String roomNo = null;
        String locationBindingId = null;
        Map<String, Object> location = null;

        for (IotDeviceBinding binding : bindings) {
            if (BindingType.ELDER.getCode().equals(binding.getBindingType())) {
                elderId = binding.getElderId();
                elderBindingId = binding.getBindingId();
                if (binding.getParkId() != null) {
                    parkId = binding.getParkId();
                }
                buildingId = binding.getBuildingId();
                roomId = binding.getRoomId();
                roomNo = binding.getRoomNo();
            } else if (BindingType.LOCATION.getCode().equals(binding.getBindingType())) {
                locationBindingId = binding.getBindingId();
                if (StringUtils.hasText(binding.getParkId())) {
                    parkId = binding.getParkId();
                }
                buildingId = binding.getBuildingId();
                roomId = binding.getRoomId();
                roomNo = binding.getRoomNo();
                Map<String, Object> loc = new HashMap<>();
                loc.put("locationId", binding.getLocationId());
                loc.put("locationType", binding.getLocationType());
                loc.put("locationName", binding.getLocationName());
                loc.put("floorId", binding.getFloorId());
                location = loc;
            }
        }

        return new BindingSnapshot(elderId, elderBindingId, parkId, buildingId, roomId, roomNo, locationBindingId, location);
    }

    private ParsedEvent enrich(ParsedEvent parsed, BindingSnapshot snapshot) {
        if (parsed instanceof ParsedVitalSign vs) {
            return vs.withElderId(snapshot.elderId());
        }
        if (parsed instanceof ParsedSosEvent sos) {
            return sos.withSnapshot(
                    snapshot.elderId(),
                    snapshot.elderBindingId(),
                    snapshot.buildingId(),
                    snapshot.roomId(),
                    snapshot.roomNo(),
                    snapshot.locationBindingId(),
                    snapshot.location()
            );
        }
        return parsed;
    }

    private void rejectAndAck(RawDeviceMessage raw, String reason) {
        metrics.mqttMessageRejected(reason);
        if (raw.requiresAck()) {
            raw.ackMqtt();
            metrics.mqttMessageAcked(String.valueOf(raw.mqttQos()));
        }
    }

    private boolean hasCompleteLocation(ParsedSosEvent event) {
        if (!StringUtils.hasText(event.locationBindingId()) || !StringUtils.hasText(event.parkId()) || event.location() == null) {
            return false;
        }
        return StringUtils.hasText((String) event.location().get("locationId"))
                && StringUtils.hasText((String) event.location().get("locationType"))
                && StringUtils.hasText((String) event.location().get("locationName"));
    }
}
