package com.eldercare.common.notify.service.impl;

import com.eldercare.common.notify.dto.SmsRequest;
import com.eldercare.common.notify.dto.VoiceRequest;
import com.eldercare.common.notify.dto.WeChatRequest;
import com.eldercare.common.notify.service.INotifyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;

/**
 * 阿里云短信通知实现（骨架）
 * <p>
 * 当前为骨架实现，doSendSms/doSendWeChat/doSendVoice 方法仅记录日志，
 * 未对接真实 SDK。生产环境使用前需完成以下对接：
 * <ul>
 *   <li>短信: com.aliyun:alibabacloud-dysmsapi20170525</li>
 *   <li>微信: 微信订阅消息 API</li>
 *   <li>语音: com.aliyun:alibabacloud-dyvmsapi20170525</li>
 * </ul>
 * <p>
 * 通过 {@code eldercare.notify.type=aliyun} 激活。
 * 异步发送，失败记 ERROR 日志 + 重试 1 次，不回滚业务。
 */
@Slf4j
public class AliyunSmsNotifyServiceImpl implements INotifyService {

    @Async
    @Override
    public void sendSms(SmsRequest request) {
        try {
            doSendSms(request);
            log.info("阿里云短信发送成功: phone={}, templateId={}", request.getPhone(), request.getTemplateId());
        } catch (Exception e) {
            log.error("阿里云短信发送失败，重试 1 次: phone={}, templateId={}", request.getPhone(), request.getTemplateId(), e);
            try {
                doSendSms(request);
            } catch (Exception retryEx) {
                log.error("阿里云短信重试仍失败: phone={}", request.getPhone(), retryEx);
            }
        }
    }

    @Async
    @Override
    public void sendWeChat(WeChatRequest request) {
        try {
            doSendWeChat(request);
            log.info("微信订阅消息发送成功: openId={}, templateId={}", request.getTouser(), request.getTemplateId());
        } catch (Exception e) {
            log.error("微信订阅消息发送失败，重试 1 次: openId={}", request.getTouser(), e);
            try {
                doSendWeChat(request);
            } catch (Exception retryEx) {
                log.error("微信订阅消息重试仍失败: openId={}", request.getTouser(), retryEx);
            }
        }
    }

    @Async
    @Override
    public void sendVoice(VoiceRequest request) {
        try {
            doSendVoice(request);
            log.info("语音呼叫发送成功: phone={}", request.getPhone());
        } catch (Exception e) {
            log.error("语音呼叫发送失败，重试 1 次: phone={}", request.getPhone(), e);
            try {
                doSendVoice(request);
            } catch (Exception retryEx) {
                log.error("语音呼叫重试仍失败: phone={}", request.getPhone(), retryEx);
            }
        }
    }

    // ==================== 实际调用（待对接阿里云 SDK） ====================

    private void doSendSms(SmsRequest request) {
        // TODO: 对接阿里云短信 SDK (com.aliyun:alibabacloud-dysmsapi20170525)
        // Config config = new Config().setAccessKeyId(accessKeyId).setAccessKeySecret(accessKeySecret);
        // Client client = new Client(config);
        // SendSmsRequest smsRequest = new SendSmsRequest()...
        log.info("[Aliyun SMS] phone={}, template={}, params={}", request.getPhone(), request.getTemplateId(), request.getParams());
    }

    private void doSendWeChat(WeChatRequest request) {
        // TODO: 对接微信订阅消息 API (POST https://api.weixin.qq.com/cgi-bin/message/subscribe/send)
        log.info("[WeChat] openId={}, template={}, data={}", request.getTouser(), request.getTemplateId(), request.getData());
    }

    private void doSendVoice(VoiceRequest request) {
        // TODO: 对接阿里云语音呼叫 SDK (com.aliyun:alibabacloud-dyvmsapi20170525)
        log.info("[Voice] phone={}, template={}", request.getPhone(), request.getTemplateId());
    }
}
