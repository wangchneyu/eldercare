package com.eldercare.edge.model;

import com.eldercare.edge.enums.NotificationStatus;

/**
 * edge_terminal_notification 一行：每台终端独立的通知/回执状态。
 */
public class TerminalNotification {

    private long id;
    private String notificationId;
    private long messageId;
    private String terminalId;
    private String payloadJson;
    private NotificationStatus status = NotificationStatus.PENDING;
    private long nextAttemptAt;
    private String brokerAcceptedAt;
    private String receiptedAt;
    private int attempts;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(String notificationId) {
        this.notificationId = notificationId;
    }

    public long getMessageId() {
        return messageId;
    }

    public void setMessageId(long messageId) {
        this.messageId = messageId;
    }

    public String getTerminalId() {
        return terminalId;
    }

    public void setTerminalId(String terminalId) {
        this.terminalId = terminalId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public void setStatus(NotificationStatus status) {
        this.status = status;
    }

    public long getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(long nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getBrokerAcceptedAt() {
        return brokerAcceptedAt;
    }

    public void setBrokerAcceptedAt(String brokerAcceptedAt) {
        this.brokerAcceptedAt = brokerAcceptedAt;
    }

    public String getReceiptedAt() {
        return receiptedAt;
    }

    public void setReceiptedAt(String receiptedAt) {
        this.receiptedAt = receiptedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }
}
