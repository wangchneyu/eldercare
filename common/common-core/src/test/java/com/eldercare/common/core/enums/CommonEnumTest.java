package com.eldercare.common.core.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommonEnumTest {
    @Test
    void shouldResolveCodesAndRejectUnknownCodes() {
        assertEquals(AlarmLevel.CRITICAL, AlarmLevel.fromCode("CRITICAL"));
        assertEquals(TaskStatus.IN_PROGRESS, TaskStatus.fromCode("IN_PROGRESS"));
        assertEquals(CareLevel.ASSISTED, CareLevel.fromCode("ASSISTED"));
        assertEquals(RoleType.CAREGIVER, RoleType.fromCode("CAREGIVER"));
        assertEquals(EventSource.DEVICE, EventSource.fromCode("DEVICE"));
        assertEquals(DeviceType.TV, DeviceType.fromCode("TV"));
        assertThrows(IllegalArgumentException.class, () -> AlarmLevel.fromCode("UNKNOWN"));
    }

    @Test
    void shouldKeepCodesUniqueWithinEachEnum() {
        assertEquals(AlarmLevel.values().length, Arrays.stream(AlarmLevel.values()).map(AlarmLevel::getCode).collect(java.util.stream.Collectors.toSet()).size());
        assertEquals(TaskStatus.values().length, new HashSet<>(Arrays.stream(TaskStatus.values()).map(TaskStatus::getCode).toList()).size());
        assertEquals(CareLevel.values().length, new HashSet<>(Arrays.stream(CareLevel.values()).map(CareLevel::getCode).toList()).size());
        assertEquals(RoleType.values().length, new HashSet<>(Arrays.stream(RoleType.values()).map(RoleType::getCode).toList()).size());
        assertEquals(EventSource.values().length, new HashSet<>(Arrays.stream(EventSource.values()).map(EventSource::getCode).toList()).size());
        assertEquals(DeviceType.values().length, new HashSet<>(Arrays.stream(DeviceType.values()).map(DeviceType::getCode).toList()).size());
    }
}