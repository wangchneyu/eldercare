package com.eldercare.edge.storage;

import com.eldercare.edge.config.EdgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 磁盘配额守卫：默认总配额 1 GiB，其中至少 256 MiB 为 P0 预留。
 * <ul>
 *   <li>非 P0（体征/心跳）在 used ≥ (max - reserve) 后按明确指标丢弃并 ACK，保护 P0 余量；</li>
 *   <li>P0（SOS/FALL）在 used + size &gt; max 时拒绝落库——绝不 ACK，让本地 EMQX 持久会话继续保留消息。</li>
 * </ul>
 */
@Component
public class QuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(QuotaGuard.class);

    public enum Capacity {
        OK,
        /** P0 配额耗尽：不落库、不 ACK。 */
        P0_BLOCKED,
        /** 非 P0 达到上限：按指标丢弃并 ACK。 */
        NON_P0_DROPPED
    }

    private final EdgeProperties properties;
    private final AtomicLong usedBytes = new AtomicLong(0);

    public QuotaGuard(EdgeProperties properties) {
        this.properties = properties;
        if (properties.getSqlite().getP0ReserveBytes() >= properties.getSqlite().getMaxBytes()) {
            throw new IllegalArgumentException("edge.sqlite.p0-reserve-bytes 必须小于 max-bytes");
        }
    }

    /** 启动时以数据库实际占用初始化。 */
    public void resetUsed(long used) {
        usedBytes.set(used);
    }

    public long usedBytes() {
        return usedBytes.get();
    }

    public void addUsed(long delta) {
        usedBytes.addAndGet(delta);
    }

    public void subtractUsed(long delta) {
        usedBytes.addAndGet(-delta);
    }

    public Capacity check(int priority, int payloadSize) {
        long used = usedBytes.get();
        long max = properties.getSqlite().getMaxBytes();
        long reserve = properties.getSqlite().getP0ReserveBytes();
        if (priority == 0) {
            if (used + payloadSize > max) {
                log.error("edge_sqlite_capacity kind=P0_BLOCKED used={} incoming={} max={} "
                                + "siteId={} reason={}", used, payloadSize, max,
                        properties.getSiteId(), "p0_quota_exhausted_do_not_ack");
                return Capacity.P0_BLOCKED;
            }
            return Capacity.OK;
        }
        long nonP0Limit = max - reserve;
        if (used + payloadSize > nonP0Limit) {
            log.warn("edge_sqlite_capacity kind=NON_P0_DROPPED used={} incoming={} limit={} "
                            + "siteId={} reason={}", used, payloadSize, nonP0Limit,
                    properties.getSiteId(), "non_p0_limit_protecting_p0_reserve");
            return Capacity.NON_P0_DROPPED;
        }
        return Capacity.OK;
    }
}