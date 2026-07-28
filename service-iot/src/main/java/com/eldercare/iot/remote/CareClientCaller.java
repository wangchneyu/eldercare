package com.eldercare.iot.remote;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.common.feign.client.CareClient;
import com.eldercare.iot.enums.IotErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * care-service 调用边界。C07 长者校验契约冻结前，生产默认使用 Mock 实现。
 */
public interface CareClientCaller {

    void validateActiveElder(Long elderId);
}

@Slf4j
@Component
@ConditionalOnProperty(name = "iot.care-validation.mode", havingValue = "feign")
class FeignCareClientCaller implements CareClientCaller {

    private final CareClient careClient;

    FeignCareClientCaller(CareClient careClient) {
        this.careClient = careClient;
    }

    @Override
    public void validateActiveElder(Long elderId) {
        if (elderId == null) {
            throw new BizException(IotErrorCode.ELDER_NOT_FOUND);
        }
        try {
            // C07 尚未冻结长者查询契约。先通过 common-feign CareClient 封装调用边界，
            // 将 elderId 作为临时 planId；正式契约落地时只替换此处，不影响绑定服务。
            var carePlan = careClient.getCarePlan(elderId);
            if (carePlan == null
                    || !elderId.equals(carePlan.getElderId())
                    || !"ACTIVE".equalsIgnoreCase(carePlan.getStatus())) {
                throw new BizException(IotErrorCode.ELDER_NOT_FOUND);
            }
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("care-service 长者校验调用失败: elderId={}", elderId);
            throw new BizException(SystemErrorCode.REMOTE_CALL_FAILED, exception);
        }
    }
}

@Component
@ConditionalOnProperty(name = "iot.care-validation.mode", havingValue = "mock", matchIfMissing = true)
class MockCareClientCaller implements CareClientCaller {

    private final String scenario;

    MockCareClientCaller(
            @org.springframework.beans.factory.annotation.Value("${iot.care-validation.mock-scenario:active}")
            String scenario) {
        this.scenario = scenario;
    }

    @Override
    public void validateActiveElder(Long elderId) {
        if ("unavailable".equalsIgnoreCase(scenario)) {
            throw new BizException(SystemErrorCode.REMOTE_CALL_FAILED);
        }
        if (elderId == null || "not-found".equalsIgnoreCase(scenario)) {
            throw new BizException(IotErrorCode.ELDER_NOT_FOUND);
        }
    }
}
