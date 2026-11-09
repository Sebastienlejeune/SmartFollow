# Contributing to SmartFollow

Thanks for your interest. SmartFollow raises glucose alarms that parents rely on at night:
every change is reviewed with that in mind.

## Before you start

- **Not a medical device**: read the [Disclaimer](README.md#disclaimer).
- For anything bigger than a small fix, open an issue first to discuss it.
- Security problems go through the [security policy](SECURITY.md), never a public issue.

## Rules of the project

- **English** for everything in the repository: code, comments, commits, pull requests,
  documentation and UI strings. The French translation lives in `app/src/main/res/values-fr`.
- **Safety alarms are never weakened silently.** A change that could make an alarm ring later,
  less often or not at all (urgent low, stale data, thresholds, snooze, defaults) must say so
  in its pull request and be covered by tests.
- **The Android app and the web page** (`server/src/main/resources/web/index.html`) **behave
  the same.** A change to what the app shows or lets the parents do (chart, history,
  statistics, banners, buttons, texts) is made on the web page in the same pull request, and
  the other way round. Phone-specific features stay app-only: alarm sound and volume, settings,
  Dexcom account, setup checks. The choice of ntfy notifications stays web-only.
- **The app never depends on the server**: it must keep monitoring and ringing on its own.
- Keep comments and documentation (README, `deploy/DEPLOY.md`, [CHANGELOG](CHANGELOG.md)) in
  line with the code in the same pull request.
- Never commit secrets, real readings, Dexcom credentials or private sound files.

## Build and test

Requires JDK 17 or later and the Android SDK (see the [README](README.md#build-and-test)):

```sh
./gradlew allTests :monitor:test :sync:test :server:test :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug
```

The same commands run on every pull request (`.github/workflows/ci.yml`).

## Pull requests

- One topic per pull request, with a description of the change and how it was tested.
- Add or update unit tests: the alarm logic (`alarm-engine`) is pure Kotlin and easy to test.
- By contributing, you agree that your contribution is licensed under the MIT License of the
  project.

## Code of conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md).
