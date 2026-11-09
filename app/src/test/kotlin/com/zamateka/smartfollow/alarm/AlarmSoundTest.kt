package com.zamateka.smartfollow.alarm

import kotlin.test.Test
import kotlin.test.assertEquals

class AlarmSoundTest {

    @Test
    fun soundUriRoundTrips() {
        listOf(AlarmSound.Default, AlarmSound.BuiltIn(BuiltInSound.HIGH), AlarmSound.Phone("content://media/internal/audio/12"))
            .forEach { assertEquals(it, AlarmSound.of(it.toSoundUri())) }
        assertEquals("builtin:urgent_high", AlarmSound.BuiltIn(BuiltInSound.URGENT_HIGH).toSoundUri())
    }

    @Test
    fun unknownBuiltInSoundFallsBackToTheDefault() {
        assertEquals(AlarmSound.Default, AlarmSound.of("builtin:unknown"))
    }

    @Test
    fun lowSoonUsesTheLowSoundWithoutItsOwn() {
        assertEquals(BuiltInSound.LOW, BuiltInSound.defaultFor(AlarmType.LOW_SOON, SoundTheme.DEFAULT))
        assertEquals(BuiltInSound.LOW_SOON, BuiltInSound.defaultFor(AlarmType.LOW_SOON, SoundTheme.PRIVATE))
        assertEquals(false, BuiltInSound.LOW_SOON in BuiltInSound.of(SoundTheme.DEFAULT))
    }
}
