package com.eldercare.iot.remote;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.iot.enums.IotErrorCode;
import org.springframework.stereotype.Component;

/**
 * C07 长者校验边界。正式的按 elderId 查询契约冻结前，只允许使用 Mock，
 * 禁止将 elderId 映射到其他 care-service 接口的参数。
 */
public interface CareClientCaller {

    void validateActiveElder(Long elderId);
}

@Component
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
