package com.flowchat.app.domain.device

import com.flowchat.app.domain.chat.TaskRecoveryGate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceClockFactsTest {
    @Test fun snapshotReturnsExplicitDateTimeWeekdayAndTimezoneFields() {
        val sunday = ZonedDateTime.of(2026, 11, 22, 5, 31, 42, 0, ZoneId.of("Asia/Tokyo"))
        val snapshot = DeviceClockFacts.snapshot(sunday)
        assertEquals("2026-11-22", snapshot.getValue("local_date").jsonPrimitive.content)
        assertEquals("05:31:42", snapshot.getValue("local_time").jsonPrimitive.content)
        assertEquals("Sunday", snapshot.getValue("weekday").jsonPrimitive.content)
        assertEquals("星期日", snapshot.getValue("weekday_zh").jsonPrimitive.content)
        assertEquals("Asia/Tokyo", snapshot.getValue("timezone").jsonPrimitive.content)
        assertEquals("+09:00", snapshot.getValue("utc_offset").jsonPrimitive.content)
        assertEquals("2026-11-22T05:31:42+09:00", snapshot.getValue("iso_datetime").jsonPrimitive.content)
        assertEquals("android_system_clock", snapshot.getValue("source").jsonPrimitive.content)
        assertEquals("read_only_device_clock", snapshot.getValue("scope").jsonPrimitive.content)

        val wednesday = ZonedDateTime.of(2026, 11, 25, 14, 5, 9, 0, ZoneId.of("Asia/Shanghai"))
        assertEquals("Wednesday", DeviceClockFacts.snapshot(wednesday).getValue("weekday").jsonPrimitive.content)
        assertEquals("星期三", DeviceClockFacts.snapshot(wednesday).getValue("weekday_zh").jsonPrimitive.content)
    }

    @Test fun deviceTimeToolIsMarkedReadOnlyInRecoveryGate() {
        assertTrue(TaskRecoveryGate.isReadOnly("get_device_time"))
    }
}
