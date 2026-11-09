package com.zamateka.smartfollow.alarm

import kotlinx.serialization.Serializable

/** A named set of alarm settings, for instance "Home" or "School". */
@Serializable
data class AlarmProfile(val id: String, val name: String, val preferences: AlarmPreferences)

/**
 * The profiles, exactly one of them active: its preferences drive the alarms. There is always
 * at least one profile. Every change returns a new value; an invalid change returns this one.
 */
@Serializable
data class AlarmProfiles(val profiles: List<AlarmProfile>, val activeId: String) {
    init {
        require(profiles.isNotEmpty()) { "at least one profile is needed" }
        require(profiles.any { it.id == activeId }) { "the active profile must exist" }
    }

    val active: AlarmProfile get() = profiles.first { it.id == activeId }

    operator fun get(id: String): AlarmProfile? = profiles.firstOrNull { it.id == id }

    fun activate(id: String): AlarmProfiles = if (get(id) != null) copy(activeId = id) else this

    fun update(id: String, preferences: AlarmPreferences): AlarmProfiles =
        copy(profiles = profiles.map { if (it.id == id) it.copy(preferences = preferences) else it })

    /** Adds a copy of the active profile's settings under [name], without activating it. */
    fun create(id: String, name: String): AlarmProfiles {
        val cleanName = name.trim()
        if (!isAvailable(cleanName) || get(id) != null) return this
        return copy(profiles = profiles + AlarmProfile(id, cleanName, active.preferences))
    }

    fun rename(id: String, name: String): AlarmProfiles {
        val cleanName = name.trim()
        if (get(id) == null || !isAvailable(cleanName, except = id)) return this
        return copy(profiles = profiles.map { if (it.id == id) it.copy(name = cleanName) else it })
    }

    /** Removes a profile, never the last one; removing the active one activates the first other. */
    fun delete(id: String): AlarmProfiles {
        if (get(id) == null || profiles.size == 1) return this
        val remaining = profiles.filter { it.id != id }
        return AlarmProfiles(remaining, if (id == activeId) remaining.first().id else activeId)
    }

    /**
     * Adds the "Discreet" profile ([AlarmPreferences.discreet] applied to the active profile's
     * settings) under [name], or the first free "[name] 2", "[name] 3"..., without activating it.
     * Does nothing if it already exists.
     */
    fun withDiscreet(name: String): AlarmProfiles {
        if (get(DISCREET_ID) != null) return this
        val cleanName = name.trim()
        val freeName = generateSequence(1) { it + 1 }.map { if (it == 1) cleanName else "$cleanName $it" }.first { isAvailable(it) }
        return copy(profiles = profiles + AlarmProfile(DISCREET_ID, freeName, active.preferences.discreet()))
    }

    /**
     * The profile a "Discreet" switch (quick settings tile) activates: "Discreet" from any other
     * profile; from "Discreet", back to [previousId], else "Home", else the first other one.
     * `null` once "Discreet" has been deleted.
     */
    fun discreetToggleTarget(previousId: String?): String? {
        if (get(DISCREET_ID) == null) return null
        if (activeId != DISCREET_ID) return DISCREET_ID
        return listOfNotNull(previousId, HOME_ID).firstOrNull { it != DISCREET_ID && get(it) != null }
            ?: profiles.first { it.id != DISCREET_ID }.id.takeIf { profiles.size > 1 }
    }

    /** A name is valid if not blank and not used by another profile, whatever the case. */
    fun isAvailable(name: String, except: String? = null): Boolean {
        val cleanName = name.trim()
        return cleanName.isNotEmpty() && profiles.none { it.id != except && it.name.equals(cleanName, ignoreCase = true) }
    }

    companion object {
        const val HOME_ID = "home"
        const val SCHOOL_ID = "school"
        const val DISCREET_ID = "discreet"

        /** The first profiles: [preferences] as "home", and a copy of them as "school". */
        fun initial(preferences: AlarmPreferences, homeName: String, schoolName: String): AlarmProfiles = AlarmProfiles(
            profiles = listOf(
                AlarmProfile(HOME_ID, homeName, preferences),
                AlarmProfile(SCHOOL_ID, schoolName, preferences),
            ),
            activeId = HOME_ID,
        )
    }
}
