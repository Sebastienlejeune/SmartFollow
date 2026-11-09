# Security policy

SmartFollow handles health data (glucose readings) and Dexcom account credentials, and its
optional server is exposed on the internet. Please report vulnerabilities privately.

## Reporting a vulnerability

- Use GitHub's private vulnerability reporting: **Security** tab > **Report a vulnerability**.
- Do not open a public issue, and do not include real tokens, passwords or readings in the
  report.
- Describe the affected part (Android app, server, web page), the version or commit, and the
  steps to reproduce.

You should get an answer within a week. This is a personal project maintained on spare time:
fixes go into the latest version only.

## Scope

In scope: the Android app, the server and its web page, the deployment scripts and
documentation of this repository.

Out of scope: Dexcom's services and the Dexcom Share API, the ntfy service, and the security
of the machine each user deploys the server on.

## Good practices for users

- Generate the server tokens with `openssl rand -hex 32` and keep them private; anyone with
  the API token can read the data and act on the alarms.
- Keep the server behind HTTPS (the deployment guide uses Caddy) and up to date.
- Prefer a self-hosted ntfy server, or an access-protected topic: alarms carry glucose values.
