-- MQ 消费记录表（幂等最终保障层）
-- 各业务服务需在自己的数据库中执行本 DDL
-- 依据《代码规范文档》二："幂等以数据库唯一约束、消费记录表或状态机条件更新为准"

CREATE TABLE IF NOT EXISTS `mq_consumed` (
    `event_id`    VARCHAR(64)  NOT NULL COMMENT '事件唯一ID（BaseEvent.eventId）',
    `topic`       VARCHAR(128) NOT NULL COMMENT '消息 Topic',
    `consumed_at` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消费时间',
    PRIMARY KEY (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ 消费幂等记录表';

-- 说明:
-- 1. event_id 为主键，利用数据库唯一约束保证最终正确性
-- 2. Redis 层（eldercare:mq:consumed:{topic}:{eventId}，TTL 24h）仅作并发优化
-- 3. Redis 宕机或标记过期时，本表兜底防重复消费
-- 4. 建议定期清理超过 7 天的历史记录（避免表无限增长）
