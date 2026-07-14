package com.eldercare.common.core.utils;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IdUtilTest {
    @Test
    void shouldGenerateExpectedIdFormats() {
        assertTrue(IdUtil.uuid().matches("[0-9a-f-]{36}"));
        assertTrue(IdUtil.uuid32().matches("[0-9a-f]{32}"));
        assertTrue(IdUtil.prefixed("ALARM").matches("ALARM_[0-9a-f-]{36}"));
        assertThrows(IllegalArgumentException.class, () -> IdUtil.prefixed(" "));
    }

    @Test
    void shouldGenerateUniqueIds() {
        Set<String> values = new HashSet<>();
        for (int index = 0; index < 1_000; index++) {
            values.add(IdUtil.uuid32());
        }
        assertEquals(1_000, values.size());
    }
}