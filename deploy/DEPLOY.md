# Deploying the SmartFollow server on AWS Lightsail

The server watches Dexcom Share like the Android app does, sends the alarms to an iPhone
through [ntfy](https://ntfy.sh), serves a web page, and shares resugarings, OK presses and the
alarm settings between devices. The Android app works without it.

```
Dexcom Share ──► SmartFollow server (Lightsail) ──ntfy──► iPhone (ntfy app + web page)
                         ▲      │
                         └──────┴── Android app (optional sharing)
```

Expected cost: Lightsail Linux, 1 GB plan, about 7 USD per month with a static IPv4.

## 1. Create the instance

1. In the Lightsail console, create an instance: region **Paris (eu-west-3)**, platform
   **Linux/Unix**, blueprint **OS only > Ubuntu 24.04 LTS**, plan **1 GB RAM** (with IPv4).
2. **Networking > Create static IP** and attach it to the instance (free while attached).
3. **Networking > IPv4 firewall**: keep SSH (22) and HTTP (80), add **HTTPS (443)**.
   Do not open 8080: the application only listens locally, behind Caddy.

## 2. Get a domain name

HTTPS needs a domain name pointing to the static IP. Free options:

- [DuckDNS](https://www.duckdns.org): create `something.duckdns.org` and set it to the static IP.
- `<ip-with-dashes>.sslip.io` (for example `203-0-113-10.sslip.io`): works without an account,
  but Let's Encrypt rate limits are shared with other users and issuance can fail.

## 3. Build the package (on the development PC)

```sh
./gradlew :server:distZip
```

The package is `server/build/distributions/smartfollow-server.zip`. Copy it with the deploy
files, using the SSH key downloaded from the Lightsail console (Account > SSH keys). Keep the
key outside the repository, for example in `~/.ssh/`: it gives full access to the server.
`.gitignore` excludes `*.pem` files as a safety net.

```sh
scp -i LightsailDefaultKey-eu-west-3.pem server/build/distributions/smartfollow-server.zip \
    deploy/smartfollow.service deploy/smartfollow.env.example deploy/Caddyfile ubuntu@<static-ip>:
```

## 4. Install (on the instance)

```sh
ssh -i LightsailDefaultKey-eu-west-3.pem ubuntu@<static-ip>

sudo apt update && sudo apt install -y openjdk-21-jre-headless caddy unzip
sudo useradd --system --home /var/lib/smartfollow --shell /usr/sbin/nologin smartfollow
sudo install -d -o smartfollow -g smartfollow -m 700 /var/lib/smartfollow
sudo install -d -m 755 /opt/smartfollow /etc/smartfollow
sudo unzip -o smartfollow-server.zip -d /opt/smartfollow

sudo install -m 600 smartfollow.env.example /etc/smartfollow/smartfollow.env
sudo nano /etc/smartfollow/smartfollow.env   # fill in every value, see below

sudo install -m 644 smartfollow.service /etc/systemd/system/smartfollow.service
sudo systemctl daemon-reload
sudo systemctl enable --now smartfollow
journalctl -u smartfollow -f                 # Ctrl+C to quit
```

In `smartfollow.env`:

- `DEXCOM_USERNAME` / `DEXCOM_PASSWORD`: the wearer's Dexcom account, as in the app.
- `SMARTFOLLOW_PUBLIC_URL`: `https://<your domain>`.
- `SMARTFOLLOW_API_TOKEN` and `SMARTFOLLOW_ACTION_TOKEN`: two different values from
  `openssl rand -hex 32`.
- `NTFY_TOPIC`: a long random name, for example `smartfollow-` followed by `openssl rand -hex 12`.

Then HTTPS:

```sh
sed "s/follow.example.com/<your domain>/" Caddyfile | sudo tee /etc/caddy/Caddyfile
sudo systemctl reload caddy
curl -I https://<your domain>/            # expect HTTP/2 200
```

## 5. Connect the phones

- **Android app**: Settings > Sharing server: the server address and the API token,
  then "Test and save". The app sends its alarm settings, which the server then uses.
- **iPhone, notifications**: install the free **ntfy** app, allow notifications, and subscribe
  to the topic (server `ntfy.sh`). Alarms arrive with "Resugaring done" and "OK" buttons,
  and are repeated while they last: every `NTFY_REPEAT_MINUTES` (10), every
  `NTFY_URGENT_LOW_REPEAT_MINUTES` (3) for the urgent low. An OK is not announced. A quiet night summary arrives
  when the night ends (hours set in the app, in the server's `SMARTFOLLOW_TIMEZONE`). The bell
  of the web page chooses which notifications are sent to the topic (kept in
  `ntfy-filter.json`).
- **iPhone, web page**: open `https://<your domain>/#token=<API token>` in Safari, then
  Share > **Add to Home Screen**. The token is kept on the phone and removed from the address.
- Keep **Dexcom Follow** on the iPhone with only Urgent Low and Signal Loss: unlike ntfy, it
  can ring when the iPhone is in silent mode.

## Updating

From Windows, `deploy/deploy.ps1` does the steps below and checks the result: copy
`deploy/deploy.local.example.psd1` to `deploy/deploy.local.psd1` (not in git) with the
instance, the SSH key and the public address, then run `.\deploy\deploy.ps1` (`-Rollback`
restores the previous version). By hand:

```sh
./gradlew :server:distZip
scp -i <key> server/build/distributions/smartfollow-server.zip ubuntu@<static-ip>:
ssh -i <key> ubuntu@<static-ip> \
  'sudo unzip -o smartfollow-server.zip -d /opt/smartfollow && sudo systemctl restart smartfollow'
```

The alarm state, settings, ntfy filter and 90-day history are in `/var/lib/smartfollow` and
survive updates. The app asks for actions by long polling (requests held up to 5 minutes):
a proxy in front of the server must not cut idle responses sooner (Caddy does not).

## Privacy notes

- Glucose values are health data. They stay on the instance, except the notification texts,
  which go through the ntfy server (ntfy.sh by default). A self-hosted ntfy server avoids that.
- Anyone with the API token can read the data and act; keep it private and change it (then
  update the phones) if it leaks. The action token can only send "Resugaring done" and "OK".
