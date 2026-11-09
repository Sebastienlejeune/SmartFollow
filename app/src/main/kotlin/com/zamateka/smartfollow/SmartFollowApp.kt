package com.zamateka.smartfollow

import android.app.Application
import android.content.Context
import com.zamateka.smartfollow.alarm.AlarmPreferences
import com.zamateka.smartfollow.alarm.AlarmProfile
import com.zamateka.smartfollow.alarm.AlarmProfiles
import com.zamateka.smartfollow.alarm.Notifications
import com.zamateka.smartfollow.history.EventStore
import com.zamateka.smartfollow.history.HistoryDatabase
import com.zamateka.smartfollow.history.ReadingStore
import com.zamateka.smartfollow.monitor.AlarmStateStore
import com.zamateka.smartfollow.monitor.Monitor
import com.zamateka.smartfollow.settings.SettingsStore
import com.zamateka.smartfollow.settings.ShareAccount
import com.zamateka.smartfollow.share.DexcomShareClient
import com.zamateka.smartfollow.sync.ActionSharing
import com.zamateka.smartfollow.sync.SyncServer
import com.zamateka.smartfollow.tile.DiscreetTileService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class SmartFollowApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
    }
}

/** Process-wide singletons. */
class AppContainer(private val context: Context) {
    val settings = SettingsStore(context)

    val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
    }

    private var cachedClient: Pair<ShareAccount, DexcomShareClient>? = null

    private val historyDatabase = HistoryDatabase(context)
    val eventStore = EventStore(historyDatabase)
    val readingStore = ReadingStore(historyDatabase)

    private val _profiles = MutableStateFlow(settings.profiles)

    /** The profiles of alarm settings; change them through the functions below. */
    val profiles: StateFlow<AlarmProfiles> = _profiles.asStateFlow()

    /** Optional sharing with the SmartFollow server; inactive while no server is set. */
    val actionSharing: ActionSharing = ActionSharing(
        httpClient = httpClient,
        server = { settings.syncServer },
        // Tagged with the profile, so that the server and the web page know which one is active.
        preferences = { settings.profiles.active.let { it.preferences.copy(profileId = it.id, profileName = it.name) } },
        monitor = { monitor },
    )

    val monitor: Monitor = Monitor(
        initialSettings = settings.alarmPreferences.toAlarmSettings(),
        stateStore = AlarmStateStore(context),
        clientProvider = ::currentShareClient,
        localSource = "android",
        eventLog = eventStore::record,
        onUserAction = actionSharing::share,
        onReadings = readingStore::record,
    )

    /**
     * Saves the settings of profile [id]; those of the active profile also apply at once and are
     * replicated to the server. Returns what was saved.
     */
    fun updateProfilePreferences(id: String, preferences: AlarmPreferences): AlarmPreferences {
        save(settings.profiles.update(id, preferences.sanitized()), activeChanged = id == settings.profiles.activeId)
        return settings.profiles[id]?.preferences ?: preferences
    }

    /** Makes profile [id] drive the alarms, and records it in the history. */
    fun activateProfile(id: String) {
        val before = settings.profiles
        val after = before.activate(id)
        if (after.activeId == before.activeId) return
        settings.previousProfileId = before.activeId
        save(after, activeChanged = true)
        monitor.logProfileChange(after.active.name)
    }

    /** The profile active before the current one, if it still exists. */
    val previousProfile: AlarmProfile?
        get() = settings.previousProfileId?.let { settings.profiles[it] }?.takeIf { it.id != settings.profiles.activeId }

    /**
     * Switches to "Discreet", or from it back to the previous profile ([AlarmProfiles.discreetToggleTarget]);
     * `false` if "Discreet" was deleted.
     */
    fun toggleDiscreet(): Boolean {
        val target = settings.profiles.discreetToggleTarget(settings.previousProfileId) ?: return false
        activateProfile(target)
        return true
    }

    /** Adds a copy of the active profile named [name]; `false` if the name is empty or taken. */
    fun createProfile(name: String): Boolean {
        val before = settings.profiles
        val after = before.create(UUID.randomUUID().toString(), name)
        if (after == before) return false
        save(after, activeChanged = false)
        return true
    }

    /** `false` if the name is empty or taken. */
    fun renameProfile(id: String, name: String): Boolean {
        val before = settings.profiles
        val after = before.rename(id, name)
        if (after == before) return false
        // The server shows the name of the active profile.
        save(after, activeChanged = id == after.activeId)
        return true
    }

    /** Removes a profile, never the last one; removing the active one activates another. */
    fun deleteProfile(id: String) {
        val before = settings.profiles
        val after = before.delete(id)
        if (after == before) return
        val activeChanged = after.activeId != before.activeId
        save(after, activeChanged)
        if (activeChanged) monitor.logProfileChange(after.active.name)
    }

    private fun save(profiles: AlarmProfiles, activeChanged: Boolean) {
        settings.profiles = profiles
        _profiles.value = settings.profiles
        if (activeChanged) {
            monitor.updateSettings(settings.alarmPreferences.toAlarmSettings())
            actionSharing.settingsChanged()
        }
        // The quick settings tile shows whether "Discreet" is active and how many alarms it mutes.
        DiscreetTileService.requestUpdate(context)
    }

    /** `null` stops sharing; a new server receives the current settings at the next exchange. */
    fun updateSyncServer(server: SyncServer?) {
        settings.syncServer = server
        actionSharing.settingsChanged()
    }

    /** Debug builds answer the "demo" accounts with synthetic readings ([DemoShare]). */
    fun shareClient(account: ShareAccount) =
        DexcomShareClient(DemoShare.httpClientFor(account) ?: httpClient, account.region, account.username, account.password)

    /** Reuses the client, and so its Share session, as long as the account does not change. */
    @Synchronized
    private fun currentShareClient(): DexcomShareClient? {
        val account = settings.account ?: return null
        cachedClient?.takeIf { it.first == account }?.let { return it.second }
        return shareClient(account).also { cachedClient = account to it }
    }
}

val Context.container: AppContainer get() = (applicationContext as SmartFollowApp).container
