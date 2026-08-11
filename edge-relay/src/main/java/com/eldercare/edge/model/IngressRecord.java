package com.eldercare.edge.model;

import com.eldercare.edge.enums.IngressStatus;

/**
 * edge_ingress_message 一行（原始上行 append-only 队列）。
 */
public class IngressRecord {

    private long id;
    private String topic;
    private byte[] payload;
    private String payloadSha256;
    private String deviceId;
    private String sourceMessageId;
    private String messageType;
    private String occurredAt;
    private int priority;
    private IngressStatus status = IngressStatus.PENDING;
    private Long leaseUntil;
    private int attempts;
    private String lastError;
    private String receivedAt;
    private String forwardedAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public String getPayloadSha256() {
        return payloadSha256;
    }

    public void setPayloadSha256(String payloadSha256) {
        this.payloadSha256 = payloadSha256;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getSourceMessageId() {
        return sourceMessageId;
    }

    public void setSourceMessageId(String sourceMessageId) {
        this.sourceMessageId = sourceMessageId;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(String occurredAt) {
        this.occurredAt = occurredAt;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public IngressStatus getStatus() {
        return status;
    }

    public void setStatus(IngressStatus status) {
        this.status = status;
    }

    public Long getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Long leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(String receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getForwardedAt() {
        return forwardedAt;
    }

    public void setForwardedAt(String forwardedAt) {
        this.forwardedAt = forwardedAt;
    }
}
