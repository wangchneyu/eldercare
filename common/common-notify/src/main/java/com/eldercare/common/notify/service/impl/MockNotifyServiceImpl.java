package com.eldercare.common.notify.service.impl;

import com.eldercare.common.notify.dto.SmsRequest;
import com.eldercare.common.notify.dto.WeChatRequest;
import com.eldercare.common.notify.dto.VoiceRequest;
import com.eldercare.common.notify.service.INotifyService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MockNotifyServiceImpl implements INotifyService {

    @Override
    public void sendSms(SmsRequest request) {
        log.info("[Mock发送短信] 目标手机号: {}, 模板ID: {}, 模板参数: {}", 
                 request.getPhone(), request.getTemplateId(), request.getParams());
    }

    @Override
    public void sendWeChat(WeChatRequest request) {
        log.info("[Mock发送微信订阅消息] 目标OpenId: {}, 模板ID: {}, 跳转页面: {}, 数据: {}", 
                 request.getTouser(), request.getTemplateId(), request.getPage(), request.getData());
    }

    @Override
    public void sendVoice(VoiceRequest request) {
        log.info("[Mock语音呼叫] 目标手机号: {}, 语音模板ID: {}, 模板参数: {}", 
                 request.getPhone(), request.getTemplateId(), request.getParams());
    }
}
