# Changelog

Notable changes of SmartFollow. Versions follow [semantic versioning](https://semver.org/);
in 0.x, a minor version may break compatibility.

## 1.0.0 (2026-11-10)

First stable version.

### Added

- The About line at the bottom of the Monitoring screen opens the About window too.
- The web page has the same About section, at the bottom of its Monitoring tab.

### Fixed

- A language change rewrites the persistent notification at once, instead of at the next
  reading; the notification channels are only renamed when the language changes.
- The About and Language windows stay open when the screen rotates.
- The web page no longer needs Safari 15.4 or later to read its language choice.

## 0.8.2 (2026-11-10)

### Changed

- Dependencies updated: Kotlin 2.4, Compose BOM 2026.09, AndroidX core 1.19, activity 1.13,
  lifecycle 2.11, coroutines 1.11, serialization 1.12, Ktor 3.6, Logback 1.6, Android Gradle
  plugin 9.5 and Gradle 9.9 (the app now compiles against Android 37; target unchanged);
  GitHub Actions updated.

## 0.8.1 (2026-11-10)

### Added

- Language setting (system language, French or English), on Android 13 and later; the same
  choice on the web page (bell page), kept in the browser. The ntfy notifications keep the
  server's `SMARTFOLLOW_LANGUAGE`.
- About window (settings): version, author, a dedication and a "Send feedback" button that
  writes to the author.

### Fixed

- The settings stay open when the screen is recreated (rotation, language change).

## 0.8.0 (2026-11-09)

First public version.

### Changed

- **Breaking**: the private sound theme is now named "Personal" (id `private`): its files are
  `app/src/private/res/raw/alarm_private_<alarm>`. A theme stored under an older id falls back
  to it when its files are in the build.
- The project is named SmartFollow.

### Added

- Contribution guide, code of conduct, security policy, third-party notice and this
  changelog; tests and builds run on every pull request (GitHub Actions).
- Shorter README with screenshots; the details moved to `docs/TECHNICAL.md`.
- Demo accounts in debug builds (`demo`, `demo-low`): synthetic readings, never sent to
  Dexcom, for trying the app and taking screenshots.

## 0.7.0 (2026-11-08)

### Added

- Redesigned settings: one list of sections for the edited profile (alarms with their state,
  glucose ranges, sound, night and resugaring), then the settings of the whole app (sharing
  server, Dexcom account, restore with confirmation and undo, about).
- One page per alarm: rings / muted / off, trigger, once or until OK, repeat interval, sound
  chosen in a bottom sheet (theme sounds can be played, phone sounds), vibration, test.
- Glucose ranges set on a draggable coloured ruler; night set on its own page.
- An alarm can ring with another alarm's built-in sound.
- Banner on the main screen when the active profile mutes alarms, with "Back to" the previous
  profile; the profile name turns red in the top bar.
- "Discreet" quick settings tile, switching to "Discreet" and back after unlocking the phone.

## 0.6.0 (2026-11-08)

### Added

- Any alarm, urgent ones included, can be muted on the phone: still detected, logged and
  notified, without sound, vibration or full screen. The server's ntfy notifications do not
  change.
- "Discreet" profile: every alarm muted but low soon, low and urgent low.

### Changed

- History: the last 7 days stay detailed by day, older entries are summed up by week (app and
  web page).
- The chart's time axis shows round clock times instead of relative hours (app and web page).

## 0.5.0 (2026-10-29)

### Changed

- History: one line per alarm condition (start, end, number of alarms, lowest or highest
  glucose); technical events hidden.
- Low soon rings once per episode.
- The app gets the other devices' actions by long polling instead of every 15 seconds.
- The web page's bell chooses which notifications go to ntfy.

## Earlier versions

Profiles of alarm settings, alarms (urgent low, fast drop and no recovery during the
resugaring snooze, low, low soon, stale data, high, urgent high), per-alarm "ring until OK",
sound themes, night volume and night summary, the optional server with ntfy notifications and
its web page, statistics and CSV export.
