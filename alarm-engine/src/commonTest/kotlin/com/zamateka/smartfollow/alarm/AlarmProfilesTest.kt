package com.zamateka.smartfollow.alarm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AlarmProfilesTest {

    private val home = AlarmPreferences(lowMgDl = 75)
    private val initial = AlarmProfiles.initial(home, "Maison", "Ecole")

    @Test
    fun initialProfilesCopyTheCurrentSettingsAndHomeIsActive() {
        assertEquals(listOf("Maison", "Ecole"), initial.profiles.map { it.name })
        assertEquals(AlarmProfiles.HOME_ID, initial.active.id)
        assertTrue(initial.profiles.all { it.preferences == home })
    }

    @Test
    fun updateOnlyChangesItsProfile() {
        val school = AlarmPreferences(lowMgDl = 80)
        val updated = initial.update(AlarmProfiles.SCHOOL_ID, school)
        assertEquals(home, updated.active.preferences)
        assertEquals(school, updated[AlarmProfiles.SCHOOL_ID]?.preferences)
    }

    @Test
    fun createCopiesTheActiveProfileWithoutActivatingIt() {
        val created = initial.create("p3", " Mamie ")
        assertEquals("Mamie", created["p3"]?.name)
        assertEquals(home, created["p3"]?.preferences)
        assertEquals(AlarmProfiles.HOME_ID, created.activeId)
    }

    @Test
    fun namesMustBeFilledAndUnique() {
        assertSame(initial, initial.create("p3", "  "))
        assertSame(initial, initial.create("p3", "maison"))
        assertSame(initial, initial.rename(AlarmProfiles.SCHOOL_ID, "MAISON"))
        assertEquals("Collège", initial.rename(AlarmProfiles.SCHOOL_ID, "Collège")[AlarmProfiles.SCHOOL_ID]?.name)
        assertTrue(initial.isAvailable("Maison", except = AlarmProfiles.HOME_ID), "a profile keeps its own name")
    }

    @Test
    fun activateOnlyKnownProfiles() {
        assertEquals(AlarmProfiles.SCHOOL_ID, initial.activate(AlarmProfiles.SCHOOL_ID).activeId)
        assertSame(initial, initial.activate("unknown"))
    }

    @Test
    fun deletingTheActiveProfileActivatesAnotherOneButTheLastOneStays() {
        val deleted = initial.delete(AlarmProfiles.HOME_ID)
        assertEquals(listOf(AlarmProfiles.SCHOOL_ID), deleted.profiles.map { it.id })
        assertEquals(AlarmProfiles.SCHOOL_ID, deleted.activeId)
        assertSame(deleted, deleted.delete(AlarmProfiles.SCHOOL_ID))
        assertFalse(deleted.profiles.isEmpty())
    }

    @Test
    fun discreetProfileIsAddedOnceWithoutActivatingIt() {
        val added = initial.withDiscreet("Discret")
        val discreet = added[AlarmProfiles.DISCREET_ID]
        assertEquals("Discret", discreet?.name)
        assertEquals(home.discreet(), discreet?.preferences)
        assertEquals(AlarmProfiles.HOME_ID, added.activeId)
        assertSame(added, added.withDiscreet("Discret"))
    }

    @Test
    fun discreetToggleGoesToDiscreetAndBack() {
        val profiles = initial.withDiscreet("Discret")
        assertEquals(AlarmProfiles.DISCREET_ID, profiles.activate(AlarmProfiles.SCHOOL_ID).discreetToggleTarget(previousId = null))
        val discreet = profiles.activate(AlarmProfiles.DISCREET_ID)
        assertEquals(AlarmProfiles.SCHOOL_ID, discreet.discreetToggleTarget(previousId = AlarmProfiles.SCHOOL_ID))
        assertEquals(AlarmProfiles.HOME_ID, discreet.discreetToggleTarget(previousId = null), "home when the previous one is unknown")
        assertEquals(AlarmProfiles.HOME_ID, discreet.discreetToggleTarget(previousId = "deleted"))
        assertEquals(AlarmProfiles.SCHOOL_ID, discreet.delete(AlarmProfiles.HOME_ID).discreetToggleTarget(previousId = null))
        assertEquals(null, profiles.delete(AlarmProfiles.DISCREET_ID).discreetToggleTarget(previousId = null))
    }

    @Test
    fun discreetProfileGetsAFreeName() {
        val taken = initial.rename(AlarmProfiles.SCHOOL_ID, "Discret")
        assertEquals("Discret 2", taken.withDiscreet("Discret")[AlarmProfiles.DISCREET_ID]?.name)
    }
}
