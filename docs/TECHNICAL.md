# SmartFollow: technical documentation

How SmartFollow works, module by module. For an overview, see the [README](../README.md).

## Modules

| Module | Content |
|---|---|
| `app` | Android app: foreground monitoring service, alarms, Compose UI, optional sharing. |
| `server` | Optional server: monitoring, ntfy notifications, web page, shared actions. See [deploy/DEPLOY.md](../deploy/DEPLOY.md). |
| `core-model` | Shared types: `GlucoseReading`, `Trend`. |
| `alarm-engine` | Pure alarm logic (`AlarmEngine`): thresholds with hysteresis, resugaring snooze, safety overrides, stale data detection. |
| `data-share` | Dexcom Share API client (`DexcomShareClient`), based on the protocol documented by [pydexcom](https://github.com/gagebenne/pydexcom). |
| `monitor` | `Monitor`: polling loop, alarm evaluation and user actions, shared by the app and the server (JVM). |
| `sync` | Server API (`SyncApi`), its client and `ActionSharing`, which shares the app's actions and settings (JVM). |
| `tools/share-probe` | Command-line tool that checks a real Share account and runs the alarm engine on its data. |
| `tools/sounds`, `tools/icons` | Scripts generating the built-in alarm sounds and the web page icons. |

`core-model`, `alarm-engine` and `data-share` are Kotlin Multiplatform modules (JVM target for
now) so that a future iOS app can reuse them.

## Server and sharing

```
Dexcom Share ──► server ──ntfy──► iPhone (ntfy app + web page)
                  ▲  │
                  └──┴── Android app (optional)
```

- The server runs the same `Monitor` as the app, with the alarm preferences replicated from
  the app. Alarms go to an [ntfy](https://ntfy.sh) topic with "Resugaring done" and "OK"
  buttons. A continuous alarm is repeated while it lasts, since a notification only rings
  once: every 10 minutes, every 3 minutes for the urgent low. An OK is not announced to the
  other parent (the alarm stopping is enough); a resugaring is. The web page's bell chooses
  which notifications go to ntfy (`NtfyFilter`: each alarm, resugarings made elsewhere, night
  summary; everything on by default, nothing locked).
- Every user action (resugaring, cancellation, OK) has an id and a time. The app applies its
  own actions at once and sends them to the server; it asks the server for the others'
  actions by long polling (the server holds the request up to 4 minutes and answers as soon
  as one arrives, so the phone's radio can sleep; a local action interrupts the wait) and
  applies them at the time they were made (`Monitor.applyRemote`), so that both phones run the
  same snooze. Each action is applied once on each device.
- The app never depends on the server: without one, or while it is unreachable, it keeps
  monitoring and ringing on its own, and only shows that sharing is unavailable.
- The web page (`server/src/main/resources/web`) mirrors the app and must stay identical in
  behaviour: Monitoring (glucose, alarm banner with OK, chart with the same ranges, clock-time
  axis, threshold gutter and touch cursor, resugaring and snooze, last update, refresh),
  History (90 days, the last 7 grouped by foldable days, older ones by summed-up weeks) and
  Statistics (same metrics and CSV export), plus About, language and sign out. The server keeps
  the readings 90 days for it (`ReadingLogFile`). Only phone-specific features stay in the app:
  alarm sound and volume (muted alarms, their banner and the "Discreet" quick settings tile
  included), settings, Dexcom account, setup checks. Two tokens protect the API: the full one for the app
  and the page, and an action-only one embedded in the notification buttons.

## Alarm rules

Conditions, from highest to lowest priority:

| Alarm | Triggers when | During a resugaring snooze |
|---|---|---|
| `URGENT_LOW` | glucose < 55 mg/dL | still rings |
| `FAST_DROP` | drop faster than 2 mg/dL/min | only checked during the snooze |
| `NO_RECOVERY` | glucose not above its resugaring value after 15 min | only checked during the snooze |
| `LOW` | glucose < 70 mg/dL | silenced, rings again when the snooze ends (25 min) |
| `LOW_SOON` | glucose still >= 70, but the trend reaches 70 within 20 min and the last reading is not rising | silenced |
| `STALE_DATA` | no reading for more than 15 min | waits for the end of the snooze (the sensor often struggles after a severe low) |
| `URGENT_HIGH` | glucose > 300 mg/dL (can be disabled) | still rings |
| `HIGH` | glucose > 250 mg/dL (can be disabled) | still rings |

- A zone is left only once glucose is 5 mg/dL past its threshold (hysteresis).
- The rate of change is a least-squares slope over the last 15 minutes (at least 3 readings).
- A reading older than 15 minutes is not trusted: only `STALE_DATA` is raised.
- Continuous alarms (`AlarmSettings.continuousAlarms`, by default urgent low, high and urgent
  high) ring until acknowledged. The others ring once (`Evaluation.alert`, their sound played
  once on Android, one notification on ntfy), then again after their repeat interval while
  they last. Low soon rings once per episode (`AlarmSettings.ONCE_PER_EPISODE`): only again if
  the prediction ends and comes back.
- Acknowledging silences all active conditions for their repeat interval (low soon: until it
  ends); a condition that clears and comes back rings immediately.

The values above are the defaults of `AlarmSettings`; most can be changed in the app settings.
Alarms listed in `AlarmSettings.disabledAlarms` never sound, but their conditions are still reported.

## Android app

- `MonitorService` is a foreground service (`specialUse` type) holding a partial wake lock.
  It polls Share just after each expected upload (`SharePollingPolicy`) and re-evaluates the
  alarms every 30 seconds, so stale data and snooze expiry are detected without new readings.
- Alarms play their sound on the alarm stream at the set volume (restored afterwards),
  vibrate, and open `AlarmActivity` over the lock screen through a full-screen notification.
  A continuous alarm loops its sound until OK; a non-continuous one plays it once. A muted
  alarm (`AlarmTypePreferences.muted`) only shows its notification, without sound, vibration
  or full screen, and does not keep another alarm from ringing; it is still logged, and the
  server's ntfy notifications do not change. The
  notification offers "OK" and "Resugaring done"; touching it opens the app and counts as OK,
  for every type of alarm. Any OK or resugaring, on this phone or another one, stops the sound,
  unless a continuous alarm still rings (an urgent low goes on after a resugaring).
- The alarm state (snooze, acknowledgements) is persisted and monitoring resumes after a reboot.
- The Share password is encrypted with an Android Keystore key (AES-GCM).
- The main screen lists the system settings still missing for reliable alarms: notifications,
  battery optimization exemption, Do Not Disturb access and full-screen alarms (Android 14+).
- The app keeps the last 24 hours of readings (`ReadingHistory`): a full download every
  6 polls (about 30 min), which also picks up readings backfilled by the sensor, and only the
  last minutes otherwise. The chart shows the last 1, 3, 6, 12 or 24 hours, with grid lines
  and labels at round clock times (`clockTicks`: every 15 min, hour, 2, 3 or 6 hours).
- A History page (swipe left from the main screen, or its tab) shows the last 90 days of
  events, kept on the phone in SQLite (`EventStore`): conditions starting and ending
  (threshold crossed, data lost...), alarms ringing, OK presses, resugarings, snooze ends and
  monitoring start/stop. `EventTracker` (alarm engine) derives them from successive
  evaluations; glucose conditions are only judged on fresh readings, so a data gap is not
  logged as a return to normal. Resugarings also show on the chart.
  `HistoryEntries` turns them into lines: one per alarm condition ("Low", "until 21:40
  (37 min) · 3 alarms", with the lowest glucose of the episode, the highest for a high), the
  resugarings and profile changes as they are. A low soon that never rang, OK presses,
  monitoring start/stop and snooze ends are not shown. The last 7 days are grouped by day under
  a title with a chevron and their number of lines; older lines by week (Monday to Sunday),
  with a summary under the title (`HistoryEntries.summarize`: episodes by type with the lowest
  or highest glucose, resugarings). Only today is unfolded at first; the stored events do not
  change.
- The big "Resugaring done" button only shows while a low alarm condition is active (urgent
  low, low, low soon, fast drop or no recovery), as in the alarm notifications.
- The settings (`AlarmPreferences`, `SettingsScreen`) are a list of sections for the edited
  profile, chosen at the top ("Profile edited: School ▾", with a banner and an "Activate"
  button when it is not the active one): Alarms (each alarm with what triggers it and its
  state: rings, muted or off), Glucose ranges, Sound (theme, alarm volume from 10 to 100 %
  with a test), Night and resugaring (night page, snooze duration from 20 to 60 min). Then,
  for the whole app: sharing server, Dexcom account, restoring the profile's default settings
  (confirmed, with an undo), about.
  - Each alarm has its page (`AlarmSettingsScreen`): rings / muted / off (urgent low and stale
    data cannot be turned off; muting a safety alarm shows a red warning), its trigger (the
    threshold, set on the ranges page, the stale data delay or the low soon prediction from
    10 to 30 min), once or until OK, the repeat interval after OK (none for low soon, which
    rings once per episode), the sound (a bottom sheet listing the theme's sounds, each one
    playable, and the phone's sounds), vibration, and a test (a silent notification for a
    muted alarm).
  - Glucose ranges (`ThresholdsScreen`): a coloured ruler, stretched at the bottom so that the
    lows stay easy to set (`GlucoseScale`), whose limits are dragged, each with − and +
    buttons and a value that can be typed.
  - Night (`NightScreen`): start and end hours (22:00 to 07:00 by default) on a 24-hour bar,
    how much lower the alarms ring (0 to 90 %, none by default, with a test) and the night
    summary.
  Changes apply immediately, including to a running snooze.
- Built-in sounds come in themes (`SoundTheme`): Chimes (soft chimes of about two
  seconds, `res/raw/alarm_default_*`, generated by `tools/sounds/generate_sounds.py`) and an
  optional private theme. Each theme has a different sound for urgent low, low (also fast drop
  and no recovery), urgent high, high and stale data. An alarm can ring with another alarm's
  built-in sound (`soundUri` `builtin:<sound>`, which follows the theme) or a phone ringtone.
  Android forbids subfolders in `res/raw`: a theme is a set of `alarm_<theme>_<alarm>` files.
- The private theme ("Personal", id `private`, the default when present) is made of your own
  sound files, which are not in the repository: put `alarm_private_urgent_low.wav`, `_low`,
  `_low_soon`, `_high`, `_urgent_high` and `_stale_data` in `app/src/private/res/raw/`, which
  git ignores, and rebuild. Without them the theme is not offered and Chimes is used.
- Profiles (`AlarmProfiles`): named sets of alarm settings, "Home" (the settings of the
  versions without profiles) and "School" (a copy) at first, and "Discreet"
  (`AlarmPreferences.discreet`: every alarm muted but low soon, low and urgent low), added
  once, also to existing installs, and not added again once deleted. The active one drives the alarms;
  it is chosen from its name in the middle of the top bar, whose menu also opens their
  management (create as a copy of the active one, rename, delete, never the last one). The
  settings open on the active profile and can edit another one without activating it. A change
  of profile is logged in the history and replicated to the server with the settings
  (`AlarmPreferences.profileId` / `profileName`); the web page shows the active profile and
  the server logs the change too. The persistent notification shows the active profile.
  When the active profile mutes alarms, its name turns red in the top bar and the main screen
  shows a banner ("5 alarms muted on this phone", the alarms that still ring, and "Back to"
  the previous profile).
- A quick settings tile (`DiscreetTileService`) switches to "Discreet" and back to the previous
  profile (`AlarmProfiles.discreetToggleTarget`), waiting for the phone to be unlocked; a long
  press opens the app.
- The top bar holds sign out (left, with confirmation), the active profile (middle) and the
  settings (right). The sharing status (green or red cloud, only when a server is set) sits
  next to the Refresh and Stop monitoring buttons. The app version is shown
  at the bottom of the main screen.
- On the chart, each alarm threshold shows its value in a left gutter. Colours are symmetric:
  green in the target range (low threshold to the top of the target, 180 by default), orange up
  to the urgent low or the high threshold, red beyond. All these limits are set on the
  Glucose ranges page of the settings. Touching the chart shows the value and time of the reading
  under the finger, following it while dragging.
- A Statistics page shows the standard CGM metrics over 24 h, 7, 14, 30 or 90 days
  (`GlucoseStatistics`): time in the consensus ranges (< 54, 54-69, 70-180, 181-250, > 250),
  mean glucose, GMI, coefficient of variation and data coverage. Readings are kept 2 years
  on the phone (`ReadingStore`), since Dexcom Share only returns the last 24 hours.
- When the other parent logs or cancels a resugaring on another device, a quiet notification
  tells it (`Monitor.actions`); an OK is not notified.
- At the end of the night (set on the Night page of the settings), a quiet
  notification sums it up (`NightSummary`): time in the target, below the low threshold and
  above the top of the target, lowest, mean and highest glucose, alarms and resugarings. The
  server sends the same summary to ntfy.
- The Statistics page exports every kept reading as a CSV file (UTC and local time, mg/dL,
  trend), saved where the user chooses.
- UI strings are in English, with a French translation in `values-fr`. The app follows the
  system language; on Android 13+ the Language setting (or Android's per-app language, from
  the locale config generated by the build) picks French or English for the whole app
  (`AppLanguage`), notifications included. The web page has the same choice on its bell page,
  kept in the browser; the ntfy notifications use the server's `SMARTFOLLOW_LANGUAGE`.

Requires Android 10 (API 29) or later.

## Check a Dexcom Share account

Use the Dexcom account of the **sensor wearer**, not a Follow account. Sharing must be
enabled in the Dexcom G7 app, with at least one follower.

```powershell
$env:DEXCOM_USERNAME = "..."
$env:DEXCOM_PASSWORD = "..."
$env:DEXCOM_REGION = "OUTSIDE_US"   # US, OUTSIDE_US (default) or JAPAN
./gradlew :tools:share-probe:run
```

## Demo accounts

Debug builds accept two demo accounts, with any password, that never reach Dexcom: `demo`
(a normal day) and `demo-low` (a low going on). They get synthetic readings from an in-memory
Share server (`app/src/debug/.../DemoShare.kt`), which is handy to try the app or take
screenshots without real health data. Release builds have no demo account.
