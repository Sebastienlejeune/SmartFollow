package com.zamateka.smartfollow.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.container
import com.zamateka.smartfollow.monitor.MonitorService
import com.zamateka.smartfollow.sync.SyncClient
import com.zamateka.smartfollow.ui.theme.SmartFollowTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acknowledgeIfAsked(intent)
    }

    /** Touching an alarm notification opens the app and counts as "OK". */
    private fun acknowledgeIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ACKNOWLEDGE, false) == true) container.monitor.acknowledge()
    }

    companion object {
        const val EXTRA_ACKNOWLEDGE = "com.zamateka.smartfollow.extra.ACKNOWLEDGE"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = container
        val settings = container.settings
        if (settings.monitoringEnabled && settings.account != null) MonitorService.start(this)
        // Not again when the activity is recreated, for instance on rotation.
        if (savedInstanceState == null) acknowledgeIfAsked(intent)

        val appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

        setContent {
            SmartFollowTheme {
                var account by remember { mutableStateOf(settings.account) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                val profiles by container.profiles.collectAsStateWithLifecycle()
                // The profile shown by the settings: the active one each time they open.
                var editedProfileId by rememberSaveable { mutableStateOf(profiles.activeId) }
                val profileActions = remember {
                    ProfileActions(
                        onActivate = container::activateProfile,
                        onCreate = container::createProfile,
                        onRename = container::renameProfile,
                        onDelete = container::deleteProfile,
                    )
                }
                var syncServer by remember { mutableStateOf(settings.syncServer) }
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                Scaffold { padding ->
                    Box(Modifier.padding(padding)) {
                        if (account == null) {
                            SetupScreen(
                                onTest = { candidate ->
                                    runCatching { container.shareClient(candidate).latestReadings(minutes = 60, maxCount = 12) }
                                        .map { }
                                },
                                onConnected = { connected ->
                                    settings.account = connected
                                    account = connected
                                    MonitorService.start(this@MainActivity)
                                },
                            )
                        } else if (showSettings) {
                            BackHandler { showSettings = false }
                            val edited = profiles[editedProfileId] ?: profiles.active
                            val sharing by container.actionSharing.state.collectAsStateWithLifecycle()
                            SettingsScreen(
                                settings = SettingsContext(
                                    profiles = profiles,
                                    editedProfileId = edited.id,
                                    onEditProfile = { editedProfileId = it },
                                    onActivateProfile = container::activateProfile,
                                    syncServer = syncServer,
                                    sharing = sharing,
                                    onSaveSyncServer = { candidate ->
                                        runCatching { SyncClient(container.httpClient, candidate.url, candidate.token).status() }
                                            .map {
                                                container.updateSyncServer(candidate)
                                                syncServer = candidate
                                            }
                                    },
                                    onRemoveSyncServer = {
                                        container.updateSyncServer(null)
                                        syncServer = null
                                    },
                                    account = account,
                                    version = appVersion,
                                ),
                                preferences = edited.preferences,
                                onChange = { container.updateProfilePreferences(edited.id, it) },
                                onBack = { showSettings = false },
                            )
                        } else {
                            val status by container.monitor.status.collectAsStateWithLifecycle()
                            val events by container.eventStore.events.collectAsStateWithLifecycle()
                            val sharing by container.actionSharing.state.collectAsStateWithLifecycle()
                            var issues by remember { mutableStateOf(setupIssues(this@MainActivity)) }
                            // Settings granted in system screens are only seen when coming back.
                            LifecycleResumeEffect(Unit) {
                                issues = setupIssues(this@MainActivity)
                                onPauseOrDispose { }
                            }
                            val pagerState = rememberPagerState(pageCount = { Page.entries.size })
                            val scope = rememberCoroutineScope()
                            Column {
                                SmartFollowTopBar(
                                    profiles = profiles,
                                    profileActions = profileActions,
                                    onSignOut = {
                                        MonitorService.stop(this@MainActivity)
                                        container.monitor.reset()
                                        settings.account = null
                                        account = null
                                    },
                                    onShowSettings = {
                                        editedProfileId = profiles.activeId
                                        showSettings = true
                                    },
                                )
                                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                                    Page.entries.forEach { page ->
                                        Tab(
                                            selected = pagerState.currentPage == page.ordinal,
                                            onClick = { scope.launch { pagerState.animateScrollToPage(page.ordinal) } },
                                            text = { Text(stringResource(page.label)) },
                                        )
                                    }
                                }
                                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
                                    when (Page.entries[index]) {
                                        Page.MONITOR -> MonitorScreen(
                                            status = status,
                                            settings = container.monitor.settings,
                                            resugaringTimesMillis = resugaringTimes(events),
                                            issues = issues,
                                            version = appVersion,
                                            sharing = sharing,
                                            mutedAlarms = profiles.active.preferences.takeIf { it.mutedAlarms.isNotEmpty() }?.let { active ->
                                                val previous = container.previousProfile
                                                MutedAlarms(
                                                    muted = active.mutedAlarms,
                                                    audible = active.audibleAlarms,
                                                    previousProfile = previous?.name,
                                                    onBackToPrevious = { previous?.let { container.activateProfile(it.id) } },
                                                )
                                            },
                                            actions = MonitorActions(
                                                onResugaring = { container.monitor.logResugaring() },
                                                onCancelResugaring = container.monitor::cancelResugaring,
                                                onAcknowledge = container.monitor::acknowledge,
                                                onRefresh = container.monitor::refresh,
                                                onStart = { MonitorService.start(this@MainActivity) },
                                                onStop = { MonitorService.stop(this@MainActivity) },
                                                onOpenSettings = { intent -> runCatching { startActivity(intent) } },
                                            ),
                                        )
                                        Page.HISTORY -> HistoryScreen(events = events, readingStore = container.readingStore, settings = container.monitor.settings)
                                        Page.STATISTICS -> StatisticsScreen(container.readingStore, refreshKey = status.lastFetchMillis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Pages of the main screen, swiped horizontally. */
private enum class Page(@param:StringRes val label: Int) {
    MONITOR(R.string.tab_monitor),
    HISTORY(R.string.tab_history),
    STATISTICS(R.string.tab_statistics),
}
