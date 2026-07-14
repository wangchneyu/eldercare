package com.eldercare.common.notify;

import com.eldercare.common.notify.dto.SmsRequest;
import com.eldercare.common.notify.dto.WeChatRequest;
import com.eldercare.common.notify.dto.VoiceRequest;
import com.eldercare.common.notify.service.NotifyService;
import com.eldercare.common.notify.service.impl.MockNotifyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class NotifyServiceTest {

    private NotifyService notifyService;

    @BeforeEach
    public void setUp() {
        notifyService = new MockNotifyServiceImpl();
    }

    @Test
    public void testSendSms() {
        SmsRequest request = new SmsRequest();
        request.setPhone("13800138000");
        request.setTemplateId("SMS_12345");
        Map<String, String> params = new HashMap<>();
        params.put("code", "123456");
        request.setParams(params);

        assertDoesNotThrow(() -> notifyService.sendSms(request));
    }

    @Test
    public void testSendWeChat() {
        WeChatRequest request = new WeChatRequest();
        request.setTouser("openid_test");
        request.setTemplateId("WX_TEMPLATE_001");
        request.setPage("pages/index/index");
        Map<String, String> data = new HashMap<>();
        data.put("thing1", "照护任务已派发");
        request.setData(data);

        assertDoesNotThrow(() -> notifyService.sendWeChat(request));
    }

    @Test
    public void testSendVoice() {
        VoiceRequest request = new VoiceRequest();
        request.setPhone("13800138000");
        request.setTemplateId("VOICE_ALARM");
        Map<String, String> params = new HashMap<>();
        params.put("msg", "长者突发摔倒，请紧急前往！");
        request.setParams(params);

        assertDoesNotThrow(() -> notifyService.sendVoice(request));
    }
}
