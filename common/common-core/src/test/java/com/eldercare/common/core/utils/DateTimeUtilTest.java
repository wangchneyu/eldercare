package com.eldercare.common.core.utils;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DateTimeUtilTest {
    @Test
    void shouldFormatParseAndConvertDateTimes() {
        LocalDateTime value = LocalDateTime.of(2026, 7, 14, 9, 30, 15);
        assertEquals("2026-07-14 09:30:15", DateTimeUtil.format(value));
        assertEquals(value, DateTimeUtil.parseDateTime("2026-07-14 09:30:15"));
        assertEquals(LocalDate.of(2026, 7, 14), DateTimeUtil.parseDate("2026-07-14"));
        assertEquals(value, DateTimeUtil.toLocalDateTime(DateTimeUtil.toInstant(value)));
        assertEquals(Instant.parse("2026-07-14T01:30:15Z"), DateTimeUtil.toInstant(value));
        assertThrows(RuntimeException.class, () -> DateTimeUtil.parseDate("2026/07/14"));
    }

    @Test
    void shouldJudgeOverdueAtBoundary() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 10, 0);
        assertTrue(DateTimeUtil.isOverdue(now.minusSeconds(1), now));
        assertFalse(DateTimeUtil.isOverdue(now, now));
    }
}