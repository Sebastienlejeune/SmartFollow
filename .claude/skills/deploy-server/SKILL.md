---
name: deploy-server
description: Deploy the SmartFollow server to its AWS Lightsail instance (or roll it back) with deploy/deploy.ps1. Use whenever server code, the web page (server/src/main/resources/web), or anything shared with the server (alarm-engine, core-model, monitor, sync, data-share) changed and the change must go live, or when the user asks to update, deploy, redeploy or roll back the server.
---

# Deploying the SmartFollow server

The server runs on an AWS Lightsail instance behind Caddy; the instance, SSH key and public
address are in `deploy/deploy.local.psd1` (not in git, see `deploy.local.example.psd1`).
Everything goes through one script, which the user has allowed once and for all: do not run
`scp`/`ssh` deployment commands by hand.

```powershell
.\deploy\deploy.ps1              # test, build, copy, install, restart, check
.\deploy\deploy.ps1 -SkipBuild   # deploy the package already in server/build/distributions
.\deploy\deploy.ps1 -Rollback    # restore the version that ran before the last deployment
```

Run it from the repository root, with the PowerShell tool.

## When

- After any change to `server/`, including the web page.
- After any change to the shared modules or to the JSON exchanged with the app
  (`AlarmPreferences`, `AlarmType`, `UserActionRecord`, `SyncApi` types). **Deploy the server
  before the user installs the new app**: an old server rejects unknown enum values (for
  example a new alarm type) with HTTP 400, and sharing turns red on the phone.
- Unknown JSON fields are ignored by the server, so adding a field to a shared class is safe
  in either order, but the server only uses it once deployed.

## What the script does

1. `gradlew :server:test :server:distZip` (unless `-SkipBuild`), with `JAVA_HOME` and
   `GRADLE_USER_HOME` defaulting to `C:\Softs\tmp\...`.
2. Copies `smartfollow-server.zip`, moves the running version to
   `/opt/smartfollow/smartfollow-server.previous`, unzips the new one, restarts `smartfollow`.
3. Checks the service is active, prints `running` / `fresh` / `lastError` from `/api/status`,
   then checks the public page (200) and that the API refuses requests without a token (401).

## Reporting

- Tell the user the result: service state, whether glucose is fresh, `lastError` if any.
- On failure the script prints the last 30 journal lines and stops: read them, explain, and
  offer `-Rollback` if the previous version was working.
- Never print the values of `/etc/smartfollow/smartfollow.env` (Dexcom password, API and
  action tokens, ntfy topic). The script only uses them on the instance.
- The alarm state, settings and history live in `/var/lib/smartfollow` and survive a deployment.
