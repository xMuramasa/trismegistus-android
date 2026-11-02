# trismegistus-android: spec v1 (2026-11-02)

A native Android companion for Hermes Agent. It talks to the Trismegistus relay (`xMuramasa/trismegistus`, deployed
at `hermes.lab.x-muramasa.dev`, tailnet only) over the same WebSocket protocol and REST API as the macOS app
(`xMuramasa/trismegistus-mac`), and its usability follows that app. Martin's Samsung Galaxy S21 FE is the target
(tailnet name `martins-s21-fe`); nothing is distributed.

It replaces the Expo app that lived in `trismegistus/apps/mobile` (phase 2a, built 2026-10-24 and never checked on
the phone; removed 2026-11-02, code at the tag `expo-archive`). Its open issues (trismegistus#6, #8, #10, #11, #14,
#15, #17) were closed as superseded; what they asked for is listed under "Carried over" below.

## Decisions (2026-11-02)

- **Native Kotlin + Jetpack Compose**, not Expo or a wrapped web build: same reasoning as the Mac app (a native
  client per platform, sharing only the protocol). Material 3 components as the native base, restyled with the
  Endfield surface; custom composables only for content and identity (the Mac app's rule).
- **Design first, in Penpot.** Phone frames are drawn from the Mac app's agreed design (sidebar design 09f, #41; chat
  kind tabs, #48) and its tokens before any UI code. Light mode only, Endfield (paper and ink, yellow for the one
  primary action, square edges, hairlines), Inter and JetBrains Mono bundled as in the Mac app (#33). Every colour,
  spacing and type value comes from a copy of trismegistus-mac's `design/tokens.json` through a generator into
  Kotlin (as the Mac app does into `Tokens.swift`), so the two apps don't drift.
- **Usability mapped from the Mac app** (see Surfaces). Its six sections, Chat's kind tabs, the command transcript,
  images in the composer, approvals with Once / Session / Deny. **Navigation: a drawer** (design B, chosen
  2026-11-02): ☰ in the steel top bar or a swipe from the left edge opens the Mac sidebar (the six sections with their
  marks, relay status, device and settings); the open section gets the full height. Draft: `design/phone-draft/`.
- **First version: Chat, approvals and notifications** (plus pairing and settings). GitHub, News, Agents, Jobs and
  Commands follow in a later phase.
- **The phone pairs as a `manager`** (`pair --name s21 --role manager --replace`). Decided by Hermes when Martin
  left it open: the relay accepts image attachments only from managers (`POST /api/attachments`), and images are
  part of the Mac app's chat; a paired phone can already approve commands that run on optiplex, which outweighs
  editing jobs or issue status. Flip with `trismegistus-relay devices set-role <name> viewer` (no re-pairing); the
  app reads its role from `GET /api/me` and hides what a viewer can't do, as the Mac app does.
- **Notifications while the app is closed: self-hosted push through ntfy with UnifiedPush**, no Firebase, no Google
  services. The relay posts a content-free push ("Approval needed", "Reply ready") to the endpoint each device
  registered; the app wakes, connects, and shows the real notification (with Once / Session / Deny for an
  approval). Needs a relay change and an ntfy container in the homelab (trismegistus issue, see Phases). While the
  app is open it holds its own socket like the Mac app, with no foreground service.
- **Two Gradle modules.** `:core` is a plain Kotlin/JVM library: protocol types, the relay client (WebSocket + REST),
  reconnect/replay, the state model; no Android dependencies, so its tests run on the JVM in the build container.
  `:app` is the Compose app and depends on it. The same split as the Mac app's TrismegistusKit / App.
- **Protocol types are hand-written** `kotlinx.serialization` sealed classes, discriminated on `type` (and `kind`
  for events), not generated. Contract tests: every file in `protocol/examples/valid/` must decode and round-trip;
  every `invalid/` file must fail to decode or be listed in `core/src/test/resources/relay-enforced.txt` with the
  schema constraint only the relay checks. `protocol/` is a copy of the trismegistus repo's folder
  (`scripts/sync-protocol.sh`); a test recomputes `scripts/protocol-hash.sh` and fails when `protocol/SOURCE` is stale.
- **Pairing:** `trismegistus-relay pair --name s21 --replace` prints a QR and a code. The app scans the QR (CameraX
  + ZXing core, no Play services) or takes the pasted code. The token is encrypted with an Android Keystore key
  (AES-GCM, not exportable) and stored in the app's private DataStore; never in logs, backups or the repo
  (`allowBackup=false`).
- **Build:** only in Docker on optiplex through `scripts/build.sh` (JDK 21 + Android SDK image from
  `docker/Dockerfile`, the project's Gradle wrapper). State outside the repo in
  `~/.local/share/trismegistus-android/{gradle,android,signing}`. Release builds are signed with a key generated
  once in the build image (`signing/keystore.properties`, mode 600); back it up, since Android refuses an update
  signed with another key.
- **Install:** `scripts/publish.sh` copies the signed release to `https://dl.lab.x-muramasa.dev/trismegistus.apk`
  (tailnet); Martin taps it on the phone (installs from the browser allowed once). For quick iterations, wireless
  debugging (Android 11+) paired once from optiplex over the tailnet.
- No analytics, crash reporters or network calls of the app's own.
- Tracking: GitHub Issues on this repo plus the "Work" project. Direct commits to `main`, `Fixes #N`.

## Verified (2026-11-02)

- Toolchain current at bootstrap: Gradle 9.8.2, AGP 9.5.0 (supports up to API 37; minimum Gradle 9.6.0; default
  build tools 36.0.0), SDK platform `android-37.0`, command-line tools 16111833. AGP 9.5.0 brings Kotlin 2.2.10 as
  its minimum; Kotlin Gradle plugin 2.4.21 is the newest stable.
- Newest stable libraries: Compose BOM 2026.09.00, activity-compose 1.13.0, navigation-compose 2.10.2,
  lifecycle-runtime-compose 2.11.0, datastore-preferences 1.3.0, CameraX 1.6.2, OkHttp 5.5.1,
  kotlinx-serialization-json 1.12.0, kotlinx-coroutines 1.11.0, ZXing core 3.5.4, UnifiedPush connector 3.3.5.
- The protocol copy is the same as trismegistus-mac's (`protocol/SOURCE` hashes match at trismegistus `48c3c2a`).
- A throwaway two-module spike built in this repo's image with these versions: `:core` (Kotlin JVM 2.4.21 +
  serialization plugin, kotlinx-serialization exposed as `api`, OkHttp, mockwebserver3, kotlin-test) passed
  `:core:test`; `:app` (AGP 9.5.0 built-in Kotlin, `org.jetbrains.kotlin.plugin.compose` 2.4.21, Compose BOM, Material
  3, `compileSdk = 37`, `minSdk = 31`) passed `assembleDebug` and `lintDebug`; `aapt2 dump badging` showed
  `compileSdkVersion='37'`, `targetSdkVersion:'37'`. The repo's Gradle cache holds those dependencies.
- The release key exists (`signing/release.jks`, alias `trismegistus`, RSA 4096, generated 2026-11-02; SHA-256
  `DD:EE:3C:75:…:9B:2B:74`).

## Architecture

```
core/                 Kotlin/JVM library (no Android)
  protocol/           ClientMessage / ServerMessage / Event sealed classes, JSON glue
  relay/              RelayClient: OkHttp WebSocket; auth first (5 s); ping every 25 s, drop after 2 missed pongs;
                      reconnect with backoff (1 s doubling to 30 s) and on app foreground; resubscribe from the last
                      seq per session; replay_gap -> reload history over REST
                      RelayRest: sessions (list, create, rename, pin, delete, tags), messages, models, attachments,
                      media; later jobs, GitHub, agents, news
  state/              SessionStore (StateFlow): sessions, per-session timeline, run status, pending approvals,
                      dedupe by (session_id, seq); conversation kinds (APP / TELEGRAM / TERMINAL / MORE); command log
  test/               ContractTests, RelayClientTests (OkHttp MockWebServer serving recorded frames: auth -> ready,
                      subscribe -> replay -> live, ping/pong, drop -> reconnect -> resubscribe, replay_gap),
                      StoreTests. trismegistus-mac's TrismegistusKit tests are the reference behaviour to port.
app/                  Compose app: theme from generated tokens, screens, Keystore token store, notifications,
                      UnifiedPush receiver, QR scanner
design/               tokens.json (copy of trismegistus-mac's), generator, Penpot exports
protocol/             copy of xMuramasa/trismegistus protocol/ (schema.json + examples), SOURCE hashes
scripts/              build.sh, publish.sh, sync-protocol.sh, protocol-hash.sh
docker/               the build image
```

`applicationId` `dev.xmuramasa.trismegistus`. `minSdk` 31 (Android 12, what the S21 FE shipped with; it runs a
newer release now), newer APIs behind version checks; `compileSdk` and `targetSdk` 37.

## Surfaces (v1: Chat first)

- **Navigation shell** with room for all six sections (Chat, GitHub, News, Agents, Jobs, Commands); v1 shows Chat
  and leaves the others out (not greyed placeholders). The pattern comes from the Penpot draft.
- **Conversations:** the kind tabs as on the Mac (#48): bare icon tabs with counts, APP (`api_server`, default),
  TELEGRAM, TERMINAL (`cli`, `tui`), MORE (One-shots, Cron, other sources, All); the header names the tab
  (`TELEGRAM // 4`); a filter field; pinned first; badges on a tab while its conversations need you (magenta: an
  approval waits; green: a run goes). Rows: name, source icon, read-only lock, last activity, RUN / APPROVAL marks.
  Long-press: pin, rename, tags, delete. New conversation (an APP conversation). The client subscribes to every
  listed session so marks and approvals work for conversations that are not open.
- **Conversation:** messages and answers (Markdown; code blocks with a language label and copy), foldable tool rows
  with duration and error flag, subagent rows, a working indicator, one marker per run
  (`4 COMMANDS · 6.1 s · 1 failed`) that opens the **command transcript** as a bottom sheet (the Mac's terminal
  pane: `$ command`, output, exit code, duration; commands only or all tools). Model picker in the header, as on
  the Mac.
- **Composer:** multi-line, grows to about 6 lines then scrolls; Enter is a newline and the send button sends; during
  a run the button becomes **Steer** and a Stop button shows. Images: up to 4 per message from the photo picker, the
  camera, or Android's share sheet (share to Trismegistus), resized to 2048 px and stripped of EXIF/GPS before
  upload; a tray above the field. Attaching needs the `manager` role (the relay's rule); a viewer sees why.
- **Approvals:** a banner at the top of the conversation and a notification, with the command, description and
  Once / Session / Deny (never `always`). Words on the buttons, no gestures.
- **Pairing:** first start; scan the QR or paste the code; shows the relay URL.
- **Settings:** relay URL, device name, connection status, unpair (local, with the `devices revoke` command shown, as
  on the Mac), notification toggles (approvals, run finished while closed, sound for approvals), the push
  distributor's status.
- **Notifications:** approval requested, run finished while the app is closed, run failed. Tapping one opens the
  conversation in its kind's tab.

Later: the GitHub, News, Agents, Jobs and Commands sections (with the Mac app's actions, as a manager), a two-pane
layout for the Galaxy Tab S8, dark mode.

## Carried over from the Expo backlog

- trismegistus#6: a dead socket never shows Connected (ping/pong check).
- trismegistus#8: after a drop, resubscribe from the last `seq` and resend what was pending.
- trismegistus#10: the duplicate filter keeps new events after `replay_gap`.
- trismegistus#11: late history never shows an answer twice.
- trismegistus#14: Stop, steer, reconnect with backoff, settings, an offline cache of sessions and recent messages.
- trismegistus#15: approvals and notifications (now through ntfy, not Expo push / Firebase).
- trismegistus#17: an app icon and splash in the Trismegistus identity.

## Security

- Token only in Keystore-encrypted storage; `RelayClient` takes it as a value and never logs it. Log lines carry ids
  and kinds, never message text, approval commands or tokens.
- TLS to `hermes.lab.x-muramasa.dev` (tailnet-only name, Caddy certificate); no cleartext traffic, no user CAs.
- Pushes carry no content and no ids that reveal it; the app fetches the details over its own authenticated socket.
- A paired, unlocked phone can approve commands that run on optiplex, same trust level as the Mac. A biometric check
  before approving can be added later.
- Permissions: INTERNET, POST_NOTIFICATIONS, CAMERA (pairing and photos, asked when used). Nothing else without a
  reason in this spec.

## Phases

| # | Phase | Done when |
|---|---|---|
| 0 | Design: Penpot phone draft (controls page; navigation alternatives; conversations, conversation, transcript sheet, composer with images, approval, pairing, settings, empty and reconnecting states) | Martin picks the navigation and approves the frames; the agreed design is written into the issue |
| 1 | `:core`: protocol types, contract tests, RelayClient against MockWebServer, REST, SessionStore | `scripts/build.sh :core:test` green; every valid example round-trips; the fake exercises auth, replay, live events, approval, ping/pong, reconnect, replay_gap |
| 2 | App: tokens and theme, navigation shell, pairing, Chat (tabs, conversation, transcript, composer, images), approvals in the app, settings; publish script | `scripts/build.sh` green (tests, lint, both APKs); Martin installs it from dl.lab, pairs, sends a prompt and answers an approval from the banner |
| 3 | Notifications: UnifiedPush through ntfy (after the relay and homelab side) | An approval raised with the app closed is answered from the phone's notification; a finished run notifies |
| 4 | The other sections: GitHub, News, Agents, Jobs, Commands | Each section works against the live relay as a viewer |

Each phase: Claude Code (Opus) writes the code from a brief; Hermes runs `scripts/build.sh`, inspects the APK
(`aapt2 dump badging` / `permissions`, `apksigner verify`), commits and pushes; the report goes on the phase's issue.
Phases 0 and 1 can run in parallel.

## Open

- Whether the Galaxy Tab S8 gets a two-pane layout (later; not in v1).
