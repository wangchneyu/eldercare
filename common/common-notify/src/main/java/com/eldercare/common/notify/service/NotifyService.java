package com.eldercare.common.notify.service;

import com.eldercare.common.notify.dto.SmsRequest;
import com.eldercare.common.notify.dto.WeChatRequest;
import com.eldercare.common.notify.dto.VoiceRequest;

public interface NotifyService {
    /**
     * 发送短信
     * @param request 短信请求参数
     */
    void sendSms(SmsRequest request);

    /**
     * 发送微信订阅消息
     * @param request 微信订阅消息参数
     */
    void sendWeChat(WeChatRequest request);

    /**
     * 发送语音呼叫
     * @param request 语音呼叫参数
     */
    void sendVoice(VoiceRequest request);
}
