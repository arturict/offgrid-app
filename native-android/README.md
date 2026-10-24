# OffGrid Android (experimental)

A native Android client for the OffGrid LoRa messenger: Kotlin, Jetpack Compose (Material 3),
Room, CameraX + ZXing. The phone talks to a Heltec WiFi LoRa 32 V3 ("antenna") over USB-C
using the OffGrid Link JSON Lines protocol. All keys stay on the antenna; the phone keeps
contacts, messages and settings.

This is a standalone experiment next to the team app in `OffGridCH/app`. It has no remote.

> **Status 2026-10-24 (version 0.3.2):** this app is the submitted OffGrid app for Zentral Hack 2026;
> see the [root README](../README.md). Parts of this file were written during the night before the
> team firmware sent over real LoRa. Since then the firmware (`OffGridCH/firmware` 0.3.0) sends,
> relays and acknowledges over the radio, and groups use the firmware group API from
> `OffGridCH/firmware#16` (built, not tested on boards). Sections below that say the firmware has
> no radio or that groups work only in the simulator describe that earlier state.

- Package `ch.offgrid.android`, minSdk 26, target/compile SDK 35, version 0.1.0-lab3 (versionCode 2),
  written against the lab firmware image 0.2.0-lab.3 and the team stack `feature/mesh-direct`
- Specs followed: `OffGridCH/firmware/docs/offgrid-link.md` (link), `offgrid-protocol.md`
  (radio protocol, crypto test vectors), designs in `offgrid-landing/design/*.png`

## Build and test

Requirements: JDK 17 or newer (21 used here), Android SDK with platform 35 and build-tools 35.
`local.properties` (gitignored) needs `sdk.dir` with forward slashes on Windows:

```properties
sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
# optional, see Analytics
POSTHOG_KEY=phc_...
```

```sh
./gradlew testDebugUnitTest lint assembleDebug assembleRelease
```

| APK | Path | Use |
|---|---|---|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | Development: test menu, simulated contacts, debug pairing buttons |
| Release | `app/build/outputs/apk/release/app-release.apk` (signed) or `app-release-unsigned.apk` | Download from the landing page: no test menu, no debug buttons, no TCP bridge, no forced codec. Signed only with the release key below; without it the APK is unsigned, never signed with the debug key. |

Release signing reads four values from Gradle properties (`-P…` or `~/.gradle/gradle.properties`) or
environment variables of the same names; no key or password is in this repository:

```sh
OFFGRID_RELEASE_STORE_FILE=/path/to/offgrid-release.jks
OFFGRID_RELEASE_STORE_PASSWORD=…
OFFGRID_RELEASE_KEY_ALIAS=offgrid
OFFGRID_RELEASE_KEY_PASSWORD=…
./gradlew assembleRelease
```

When only some of them are set, or the keystore file does not exist, the build stops with the missing names.

The lab test APK for phones with a lab board is the debug build with `OFFGRID_TEXT_CODEC=ogz1` and
without `OFFGRID_TCP_LINK` in `local.properties`: it switches a lab image to OGZ1 with `lab.set` after
unlocking and counts bytes with the codec, and it uses USB, not the TCP bridge.

## Install

1. Enable USB debugging on the phone (Settings, About phone, tap "Build number" seven times,
   then Developer options, USB debugging) and connect it, or start an emulator.
2. Check that it shows up: `adb devices`.
3. Install or update: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   (or the signed release APK). Debug and release builds share the package name but not the
   signing key: switching between them needs an uninstall, which deletes the app's data.
4. Without adb: copy the APK to the phone and open it in the file manager; Android asks once to
   allow installs from that app.

On the emulator the app uses the simulated antenna; on a phone it waits for a Heltec on USB-C.
A long press on the antenna picture in "Antenne verbinden" switches a debug build to the
simulated antenna on a phone as well.

Unit tests cover line framing and the 2048-byte limit, the request/response/event codec and
client (timeouts, parse errors without id, unplugging, float RSSI), the simulated antenna end to
end (provision, pair, send, deliver, wrong token) and its lab.3 behaviour (random refs, waiting,
`recent`, cancel, authenticated factory reset, OGZ1), the crypto test vectors from the protocol
spec (fingerprint, node ids, token wrap) and vectors for the unlock and reset wrap computed with
Python `cryptography` from the firmware's `Security::unwrap` (`tools/wrap_vectors.py`), the
outbox reconciliation, the antenna session against the simulator (wrong phone, rate limit,
auto-lock, factory reset, backup token), message and name limits, backup encryption, geo
formatting and analytics.

## Architecture

| Package | Content |
|---|---|
| `link` | `Link` interface, `LineFramer`, `LinkCodec`, `LinkClient` (ids, one request in flight, timeouts, events) |
| `link/usb` | `UsbSerialLink` on usb-serial-for-android (CP210x, CH34x, ESP32-S3 CDC), permission request |
| `link/sim` | `SimulatedHeltec`, an in-process antenna that speaks the same JSON Lines, with virtual peers |
| `crypto` | X25519/HKDF/AES-GCM token wrap, fingerprint, Keystore-backed unlock token |
| `domain` | `AntennaManager` (hello, unlock, heartbeat, reconnect), messaging, pairing, backup, location, limits |
| `data` | Room entities and DAOs |
| `analytics` | Closed event list and a small PostHog `/batch/` client |
| `ui` | Navigation, components and one file per screen group |

The emulator uses the simulated antenna automatically; a real phone uses USB. Only the debug
build has the test menu (long press on the "OffGrid" title in the contact list): demo contacts
and a demo group, a peer code in the clipboard, all peers in or out of radio range, unplugging
the simulated antenna, switching between simulated and USB link, and `mock.receive` when on
USB. Simulated peers answer a message that contains a "?".

## Notifications

Incoming messages post one notification per conversation (channel "Nachrichten"), except for
the chat that is open on screen; opening a chat clears its notification. The lock screen shows
only "Neue Nachricht", never the sender or the text. A tap opens the chat on top of the contact
list, also from a cold start. The app asks for the Android 13 notification permission once at
"Los gehts" and again when background receive is switched on in Einstellungen; refusing is
fine, the app works without it.

Deep links are internal to the notifications: `offgrid://chat/<contactId>` and
`offgrid://gruppe/<groupId>`. A link to a contact or group that no longer exists returns to the
contact list.

## Motion and accessibility

Screens change with a short shared-axis slide; bubbles, sheets, status changes and the
segmented control animate. With "Remove animations" (animator duration scale 0) the finite
animations jump and the infinite ones (spinners, the search ripple, pulses) stand still.
Checked on the emulator: TalkBack labels and headings, 48 dp touch targets except where the
design draws 44 dp (back button, tabs), font scale 200 %, 360 dp and 480 dp wide screens,
landscape.

## Testing on the emulator

```sh
emulator -avd <avd> -gpu swiftshader_indirect   # -gpu auto produced black screenshots here
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Onboarding runs against the simulated antenna. In "Kontakt hinzufügen" the debug button
"Simulierten Kontakt scannen" stands in for the camera; "Code einfügen" accepts a peer code from
the test menu. Location uses a fixed simulated fix on the emulator.

## Testing with a real phone and a Heltec

1. Flash the firmware's default environment (UART0 through the board's CP2102 bridge), from the
   firmware repository: `pio run -e heltec_wifi_lora_32_V3 -t upload --upload-port <port>`.
   The `serial_mock_usb` build (native USB CDC) also works with this app.
2. Install the debug APK on the phone: `adb install -r app-debug.apk`.
3. Connect the Heltec with a USB-C OTG cable (phone as host). Android asks whether OffGrid may
   open the device; allow it. Plugging in also offers to open the app.
4. The app sends `sys.hello`, provisions the fresh antenna with this phone's token and shows
   "Wie heisst du?".
5. The firmware has no radio yet, so a second person cannot actually reach you. To test
   receiving with one board: pair any valid OG1 code (for example the protocol spec's Bob test
   vector, pasted under "Code einfügen"), then use the test menu entry
   "Mock: Nachricht empfangen", which calls `mock.receive` from the first active contact.
   Outgoing messages go through the mock's delivery scenario (sent, then delivered).
6. Keep the board powered while testing. The mock keeps identity, provisioning and contacts in
   RAM only: any reset (power cycle, EN button, a serial tool toggling DTR/RTS) gives the board
   a new identity and the app shows its contacts as pending until they are paired again.

## Analytics

PostHog, following the privacy concept from `OffGridCH/app` PR #10:

- EU host `https://eu.i.posthog.com`, endpoint `/batch/`
- Closed event list as a sealed class: `app_opened`, `screen_viewed` (route pattern such as
  `chat/:contactId`, never an id), `pairing_started`, `pairing_completed`, `message_sent`
- Every event carries only `platform: "android"`, `$lib`, `$process_person_profile: false`,
  `$geoip_disable: true`, plus `screen` on `screen_viewed`. No content, contacts, keys or places.
- The distinct id is a random UUID used only for analytics (not an install, device or key id).
  "Alle Daten löschen" resets it.
- No autocapture, no session replay, no person profiles.
- The key comes from `local.properties` (`POSTHOG_KEY`) into `BuildConfig.POSTHOG_KEY`. Without
  a key the client is a no-op: nothing is queued, no id is created, nothing is sent.

Why an own client instead of `posthog-android`: five fixed events need one HTTP call. The SDK
would bring lifecycle and screen autocapture, replay, feature flags, a person model and its own
storage, all of which would have to be switched off and kept off across SDK updates. The own
client (`analytics/Analytics.kt`, about 150 lines) cannot send anything outside the sealed list,
and the tests check that no property beyond the allowed ones is ever sent. Events are batched
2 seconds after the last one, at most 200 are queued, and failed batches are kept for the
next try.

## Spec decisions

- **Keys stay on the antenna.** The phone holds only an unlock token derived from a Keystore
  key (`HMAC(K_phone, "og-unlock-v1" || device_id)`), wrapped per session with X25519 + HKDF +
  AES-GCM as link spec 6 describes (label `og-unlock-wrap-v1`, salt the 16-byte session nonce,
  AAD the 6-byte device_id, envelope iv || ciphertext || tag = 60 bytes). Every `sec.*` call uses a
  fresh `sys.hello` session; a session that expired or a state that changed in between gets one
  more try. `E_UNLOCK_FAILED`, `E_RATE_LIMITED`, `E_NO_SESSION` and `E_INTERNAL` each have their
  own text on "Antenne verbinden", other codes are shown with the code, and the reason of an
  auto-lock (`sec.state`) is shown until the antenna is open again.
- **"Alle Daten löschen" erases the antenna** with `sys.factory_reset` (`method: "app"`, token
  wrapped with the label `og-reset-wrap-v1`, link spec 6.11). The phone's data is only deleted
  when the antenna confirmed; afterwards the app sets the empty antenna up again for this phone.
  Without an antenna only the phone is cleared and the dialog says that the antenna keeps its
  contacts.
- **Reflashed or reset antenna:** a known antenna that reports `unprovisioned` is provisioned
  again. The token is derived each time, so nothing stored has to be replaced; a token from a
  backup import for that antenna is dropped. Contacts the antenna no longer lists become
  "Neu koppeln" (slot detached, history kept) with a banner on the contact list; pairing the same
  key again reuses the row.
- **Delivery state survives reconnects.** A `msg_ref` is only valid within one boot of one antenna,
  and `msg.status` events are lost while the phone is away. Each accepted message stores the link
  session its ref belongs to; events only update messages of the current session. After every
  connect (antenna ready, contacts synced) the app compares its open messages with `msg.pending`:
  still queued takes over the antenna state, an entry in `recent` (lab.3) applies its final state,
  and anything else becomes "Nicht zugestellt" (reason `lost`) with "Erneut senden". The app never
  resends on its own. The lab image's `waiting` state shows a clock and "Wartet auf <Name>"
  instead of a check mark. See `domain/OutboxReconciler.kt`.
- **Pairing is mutual QR.** Both phones scan each other (`pair.begin`/`pair.complete`). The
  contact stays "pending" until the first delivered message; the app sends a small ping after
  pairing so the firmware can activate it.
- **Message limit 83 bytes UTF-8** (link spec 7.5, `limits.max_text_bytes` from `sys.hello`).
  The design shows "153"/"120 Zeichen"; the composer shows the device limit instead. Umlauts
  count as two bytes.
- **Display and group names: 24 bytes UTF-8** (link spec 7.2/7.3). The team app's skills say
  40 characters; the antenna cannot store that.
- **Avatars** are the fingerprint pattern from the design ("Dein Muster"), derived from the key
  fingerprint, so the same contact looks the same on both phones.
- **Band** is shown as "868 MHz" from `radio.get`.
- **Received locations have no accuracy:** the geo payload carries none, so "Empfangen" shows
  coordinates and time only.
- **Backup** (".ogx") is encrypted with the user's password (PBKDF2 + AES-GCM) and carries this
  phone's unlock token for the antenna. A new phone unlocks once with it and immediately calls
  `sec.rotate_token` (link spec 6.10) to replace it with its own Keystore token.
- **Groups** are a proposed link 1.1 extension (`group.create`, `group.send`, ...). They work in
  the simulator only; the real firmware answers `E_UNKNOWN_CMD`, which the app shows as
  "Diese Antennen-Firmware kennt noch keine Gruppen."
- **Composer placeholder** uses the contact's display name ("Nachricht an Anna Meier"), as the
  screens skill says. `Chat.png` shows only the first name, `chat-standort.png` the full name.

## Firmware status

[`docs/hardware.md`](docs/hardware.md) is the current record of the app against a real Heltec
V3: what the team firmware does, where it differs from the link spec, the lab radio
image (`lab_radio`) and what only a second board can show. It is kept by the agent working on the link layer.

## Missing for a real antenna

- Firmware: `group.*` if groups stay in scope. Real token authentication, persistent contacts and
  real delivery come with the team stack `feature/mesh-direct` and the lab image 0.2.0-lab.3; the
  app's side of them was checked against the firmware source, independent vectors and the
  simulator, not on a board.
- App: a test on a physical phone with a lab.3 board (security, factory reset, reconnect
  reconciliation), battery use of the background receive service, and a
  decision on the 83-byte versus 153-character conflict.

## Screenshots

Captured on 2026-10-24 on the emulator (1080 x 2400, 440 dpi, API 35) with the simulated
antenna and the debug build, in [`docs/screenshots`](docs/screenshots). Side-by-side images
(design left, app right) for every screen with a design PNG are in
[`docs/compare`](docs/compare).

| File | Design |
|---|---|
| `00-splash` | Logo |
| `01-antenne-verbinden`, `01b-antenne-verbinden-fehler` | Antenne verbinden (found, and no antenna) |
| `02-wie-heisst-du`, `02b-mitteilungen-erlauben` | Name step (no design image; from the screen spec), notification permission |
| `03-kontakte-leer`, `04-kontakte`, `04b-kontakte-antenne-getrennt` | kontakte-leer, Kontakte |
| `05-kontakt-hinzufuegen`, `05a-code-scannen`, `05b-code-gescannt` | kontakt-hinzufuegen |
| `06-handshake-abgeschlossen` | Handshake abgeschlossen |
| `07-chat`, `07b-reaktion-waehlen`, `07c-reaktion` | Chat (reactions have no design image) |
| `08-chat-standort` | chat-standort |
| `09-standort-senden`, `10-standort-ansehen` | Standort senden, Standort ansehen |
| `11-chat-antenne-getrennt` | chat-antenne-getrennt |
| `12-einstellungen`, `12b-einstellungen-unten`, `12c-alle-daten-loeschen` | Einstellungen |
| `13-daten-exportieren`, `14-daten-importieren` | Daten exportieren, Daten importieren |
| `15-neue-gruppe`, `16-gruppenchat` | Neue Gruppe, Gruppenchat (optional) |
| `17-benachrichtigung` | Message notification (no design image) |
| `90-testmenue` | Debug test menu (not in the design) |

## Licenses

Schibsted Grotesk and Geist Mono are bundled under the SIL Open Font License, see
`docs/licenses`.
