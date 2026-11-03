# trismegistus-android

A native Android companion for [Hermes Agent](https://hermes-agent.nousresearch.com): chat with Hermes on the home
server, follow its tool calls live, answer approval requests, from the phone. It talks to the Trismegistus relay
([xMuramasa/trismegistus](https://github.com/xMuramasa/trismegistus)) like the macOS app
([xMuramasa/trismegistus-mac](https://github.com/xMuramasa/trismegistus-mac)), and its usability follows that app.
Kotlin and Jetpack Compose. What it does and why: [SPEC.md](SPEC.md).

Status: bootstrapped (build environment, Gradle wrapper, protocol copy, release key). Design and code are next.

## Build

`scripts/build.sh` runs Gradle in a container (`docker/Dockerfile`: JDK 21 + Android SDK platform 37 and build tools
36.0.0) and by default runs the unit tests, lint and both APKs; pass Gradle tasks to run others
(`scripts/build.sh :core:test`). Nothing is needed on the host but Docker.

State outside the repo, in `~/.local/share/trismegistus-android/`:
- `gradle/`: Gradle's caches (delete to start clean);
- `android/`: the debug keystore;
- `signing/`: the release key (`release.jks`) and `keystore.properties`. **Back it up**: Android only installs an
  update signed with the same key; with a new key the app must be uninstalled first (its pairing and settings go
  with it).

`scripts/publish.sh` builds the signed release and publishes it at `https://dl.lab.x-muramasa.dev/trismegistus.apk`
(tailnet only) plus a copy named by version.

## Protocol

`protocol/` is a copy of the relay repo's `protocol/` (schema and examples). `scripts/sync-protocol.sh` refreshes it
from `~/Projects/trismegistus/protocol` and writes `protocol/SOURCE` (`scripts/protocol-hash.sh`); a contract test
fails while the copy is stale.

## Design

`design/phone-draft/`: the agreed Penpot draft (#2) and the controls page. `design/tokens.json` and
`design/endfield-palette.json` are copies (trismegistus-mac's tokens and the Endfield palette they reference),
refreshed by `scripts/sync-tokens.sh` (hashes in `design/TOKENS_SOURCE`); `design/android-tokens.json` adds the
phone's type sizes and component sizes; `design/icons.json` holds the draft's 16-unit stroke icons as SVG paths.
Inter and JetBrains Mono are bundled in `app/src/main/res/font/` (SIL OFL, `licenses/`).
