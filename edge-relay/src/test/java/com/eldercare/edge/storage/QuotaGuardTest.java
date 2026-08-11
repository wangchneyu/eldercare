package com.eldercare.edge.storage;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.support.EdgeTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 配额边界：P0 到总配额才阻断，非 P0 在 (max - reserve) 处让出 P0 预留。 */
class QuotaGuardTest {

    @Test
    void p0UsesFullQuota_nonP0StopsAtReserve() {
        EdgeProperties properties = EdgeTestSupport.minimalProperties();
        properties.getSqlite().setMaxBytes(1000);
        properties.getSqlite().setP0ReserveBytes(400);
        QuotaGuard guard = new QuotaGuard(properties);
        // 非 P0：used 0 + 610 > 600 → 丢弃
        assertEquals(QuotaGuard.Capacity.NON_P0_DROPPED, guard.check(1, 610));
        // P0：used 0 + 1001 > 1000 → 阻断
        assertEquals(QuotaGuard.Capacity.P0_BLOCKED, guard.check(0, 1001));
        // P0：恰好占满 → 通过
        assertEquals(QuotaGuard.Capacity.OK, guard.check(0, 1000));
    }

    @Test
    void p0StillAcceptedInsideReserveArea_nonP0Dropped() {
        EdgeProperties properties = EdgeTestSupport.minimalProperties();
        properties.getSqlite().setMaxBytes(1000);
        properties.getSqlite().setP0ReserveBytes(400);
        QuotaGuard guard = new QuotaGuard(properties);
        guard.addUsed(700);
        assertEquals(QuotaGuard.Capacity.OK, guard.check(0, 100),
                "P0 可以进入预留区（700+100 ≤ 1000）");
        assertEquals(QuotaGuard.Capacity.NON_P0_DROPPED, guard.check(1, 100),
                "非 P0 不能进入预留区（700+100 > 600）");
    }
}