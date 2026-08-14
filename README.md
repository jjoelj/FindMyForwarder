# FindMyForwarder

An Android app that forwards this phone's location to a jailbroken iPhone running
**[LocationSpoofServer](https://github.com/jjoelj/LocationSpoofServer)**, so the
iPhone reports your real position as its own — to Find My, and to anything else
on the device that asks where it is. The same connection reads back the iPhone's
battery level and the locations of your Find My friends, which the app draws on a
map and in home-screen widgets.

Setup is one QR scan. The server app displays a code containing its URL and API
token; this app parses both and starts forwarding.

## Requirements

- An iPhone running [LocationSpoofServer](https://github.com/jjoelj/LocationSpoofServer),
  reachable over Tailscale. Without it this app has nowhere to send anything.
- Android 13 (API 33) or newer.
- Google Play services — used for activity recognition, fused location, and the
  QR scanner.

## Setup

1. Install the APK from [Releases](https://github.com/jjoelj/FindMyForwarder/releases).
2. Open the app and grant the permissions it asks for. The Status screen lists
   any that are missing.
3. Open LocationSpoofServer on the iPhone to show its QR code, then in
   **Settings → Forwarding Endpoint** tap **Scan**.

The code carries the full endpoint — `https://<node>.<tailnet>.ts.net/?token=…` —
so both fields fill in at once. If Tailscale isn't connected on the iPhone the
code contains only the token, and you'll need to type a reachable base URL
yourself; the scan leaves whatever is already there alone.

Regenerating the token on the phone invalidates the old one immediately. When
that happens the app stops retrying and tells you to scan again.

## How it works

Location updates are driven by activity transitions, not a timer. Play Services
broadcasts a transition (walking, driving, cycling, still), which starts a
foreground service that turns on location updates and posts each fix to the
server. Entering **Still** turns the updates off but leaves the service parked in
the foreground — a process with no running foreground service goes cached, and
Android defers broadcasts to cached processes, which would strand the next
transition in a queue until you opened the app.

Everything is plain `GET` requests with the token as a query parameter:

| Endpoint | Used for |
| --- | --- |
| `/set?lat=&lon=&token=` | Push a location fix |
| `/friends?token=` | Read the server's cached Find My friend locations |
| `/friends/refresh?token=` | Force the phone to re-read Find My, then return the result |
| `/battery?token=` | iPhone battery level, charging state, external power |

## In the app

| Screen | What's there |
| --- | --- |
| **Friends** | Map of friends' last known locations, clustered when pins overlap. Names and photos come from your contacts when a handle matches. |
| **Status** | Whether the endpoint is configured, the service is running, when the last fix was sent, and the iPhone's battery. Warns when the iPhone drops below 20% and isn't charging. |
| **Logs** | On-device log of transitions, posts and failures. First place to look when something stops arriving. |
| **Settings** | Endpoint and token, theme, permission status. |

Three home-screen widgets: **Large Map** (all friends), **Nearby Friends**
(within a configurable radius), and **Track a Friend** (one friend, picked when
you place it).

## Permissions

| Permission | Why |
| --- | --- |
| Location, including background | The thing being forwarded. Background access is what lets it work with the app closed. |
| Activity recognition | Triggers updates on movement instead of polling. |
| Notifications | The foreground service notification, which Android requires. |
| Contacts | Puts real names and photos on Find My handles. Read-only, stays on the device. |
| Ignore battery optimizations | Keeps the OS from freezing the service on long drives. |
| Boot completed | Re-registers for transitions after a reboot. |

Nothing is sent anywhere except the base URL you configure.

## Building

```sh
./gradlew assembleRelease
```

Local builds are signed with the debug key and versioned `dev`, so they install
but are obviously not releases.

Pushing a `v*` tag builds a signed APK and publishes it to Releases.
`versionName` comes from the tag, `versionCode` from the run number. Signing
needs three repository secrets and one variable:

| Name | | Value |
| --- | --- | --- |
| `KEYSTORE_BASE64` | secret | The keystore, base64-encoded |
| `KEYSTORE_PASSWORD` | secret | Its password |
| `KEY_PASSWORD` | secret | Same password (PKCS12 requires it) |
| `KEY_ALIAS` | variable | Alias of the key inside the keystore |

Keep that keystore backed up. Android only accepts an update signed with the key
that signed the installed build, so losing it means every user has to uninstall
and reinstall.

## License

Apache 2.0 — see [LICENSE](LICENSE).
