# SmartFollow: context for Claude Code

Rules of the project. A maintainer's own context (setup, server, current state) goes in
`CLAUDE.local.md`, which git ignores:

@CLAUDE.local.md

## Rules

- Everything code-related is in English: code, comments, commits, branches, PRs, docs in the
  repository, UI strings (French lives in `values-fr`).
- Never weaken safety alarms without the user's explicit decision.
- Keep the Android app and the web page (`server/src/main/resources/web/index.html`) identical
  in behaviour: every change to what the app shows or lets the parents do (chart, cursor,
  history, statistics, banners, buttons, texts) is made on the web page in the same change,
  and the other way round. Only phone-specific things stay app-only (alarm sound and volume,
  setup issues, settings editing, Dexcom account), and the choice of ntfy notifications stays
  web-only; say so when something cannot be mirrored.
- Commit/push only when asked; branch first (`main` is the default), open a PR,
  delete merged branches after the user validates.
- Never commit secrets, personal data or files that cannot be published (the private sound
  themes in `app/src/private`, `deploy/deploy.local.psd1`, `*.env`, SSH keys).

## Architecture

See `README.md`. Modules: `core-model` (readings, trend, `GlucoseStatistics`), `alarm-engine`
(pure alarm logic, `AlarmPreferences`, `EventTracker`, `HistoryEntries`), `data-share` (Dexcom
Share client), `monitor` (`Monitor`, shared by app and server), `sync` (server API,
`ActionSharing`), `server` (Ktor, ntfy, web page), `app` (Android), `tools` (Share probe,
sound and icon generators). The Android app works without the server; the server is optional
sharing.

## Deployment

- See `deploy/DEPLOY.md`. Update the server only through `deploy/deploy.ps1` (skill
  `deploy-server`, rollback with `-Rollback`); its target is in `deploy/deploy.local.psd1`.
- Config with secrets: `/etc/smartfollow/smartfollow.env` on the server. Never print its
  values (Dexcom password, tokens, ntfy topic); only report whether they are set.
- Deploy the server before installing an app version that changes the shared JSON (new alarm
  types), or sharing fails with HTTP 400.
