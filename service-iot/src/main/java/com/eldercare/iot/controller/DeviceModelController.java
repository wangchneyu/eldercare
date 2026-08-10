package com.eldercare.iot.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eldercare.common.core.domain.R;
import com.eldercare.iot.dto.request.DeviceModelCreateRequest;
import com.eldercare.iot.dto.request.DeviceModelUpdateRequest;
import com.eldercare.iot.dto.vo.DeviceModelVO;
import com.eldercare.iot.service.IDeviceModelService;
import com.eldercare.iot.support.IdempotencyService;
import com.eldercare.iot.support.IotAuditLogger;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;

import java.util.function.Supplier;

/**
 * 设备型号管理
 *
 * <p>P1-01：创建/修改/删除支持可选 {@code Idempotency-Key}。操作范围与资源 ID
 * （更新/删除的 modelId）纳入缓存键，同一 Key 同一请求指纹回放首次成功响应；
 * 同一 Key 不同请求指纹返回 212009 幂等冲突。未携带 Key 时保持原有语义。
 * 写操作输出不含敏感字段的结构化审计日志（成功与失败均记录结果）。
 */
@RestController
@RequestMapping("/iot/device-models")
@RequiredArgsConstructor
public class DeviceModelController {

    private final IDeviceModelService deviceModelService;
    private final IdempotencyService idempotencyService;
    private final IotAuditLogger auditLogger;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public R<DeviceModelVO> create(@Valid @RequestBody DeviceModelCreateRequest request,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                   ServerHttpRequest httpRequest) {
        DeviceModelVO vo = withAudit("MODEL_CREATE", null, request.getModelCode(), httpRequest, () ->
                idempotencyService.execute("device-model-create", idempotencyKey, request, DeviceModelVO.class, () -> {
                    DeviceModelVO created = deviceModelService.create(request);
                    auditLogger.success("MODEL_CREATE", String.valueOf(created.getId()),
                            created.getModelCode(), httpRequest);
                    return created;
                }));
        return R.ok(vo);
    }

    @GetMapping
    public R<IPage<DeviceModelVO>> list(
            @RequestParam(required = false) String manufacturer,
            @RequestParam(required = false) String deviceType,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (pageSize > 100) {
            pageSize = 100;
        }
        IPage<DeviceModelVO> result = deviceModelService.list(manufacturer, deviceType, pageNo, pageSize);
        return R.ok(result);
    }

    @PutMapping("/{modelId}")
    public R<DeviceModelVO> update(@PathVariable Long modelId,
                                   @Valid @RequestBody DeviceModelUpdateRequest request,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                   ServerHttpRequest httpRequest) {
        DeviceModelVO vo = withAudit("MODEL_UPDATE", modelId, null, httpRequest, () ->
                idempotencyService.execute("device-model-update:" + modelId, idempotencyKey, request,
                        DeviceModelVO.class, () -> {
                            DeviceModelVO updated = deviceModelService.update(modelId, request);
                            auditLogger.success("MODEL_UPDATE", String.valueOf(modelId),
                                    updated.getModelCode(), httpRequest);
                            return updated;
                        }));
        return R.ok(vo);
    }

    @DeleteMapping("/{modelId}")
    public R<Void> delete(@PathVariable Long modelId,
                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                          ServerHttpRequest httpRequest) {
        withAudit("MODEL_DELETE", modelId, null, httpRequest, () -> {
            // 使用 Void 回放语义：第二次真实删除会 404，回放必须返回首次成功响应
            idempotencyService.executeVoid("device-model-delete:" + modelId, idempotencyKey,
                    String.valueOf(modelId), () -> {
                        deviceModelService.delete(modelId);
                        auditLogger.success("MODEL_DELETE", String.valueOf(modelId), null, httpRequest);
                    });
            return null;
        });
        return R.ok(null);
    }

    /**
     * 业务动作或回放失败时补记 FAILURE 审计后按原异常上抛；
     * 失败响应不会被缓存为成功回放。
     */
    private <T> T withAudit(String operation, Long modelId, String modelCode,
                            ServerHttpRequest httpRequest, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            auditLogger.failure(operation, modelId == null ? null : String.valueOf(modelId), modelCode, httpRequest);
            throw e;
        }
    }
}