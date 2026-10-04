# MeshAnd

Native Android app (Kotlin + Jetpack Compose) that connects **directly over BLE** to a Meshtastic
radio (target: LILYGO T-Beam Supreme) and shows the mesh NodeDB with live positions. The long-term
goal is to show Meshtastic node locations in OsmAnd.

## Phases
- **Phase 1 (BLE → NodeDB → simple UI): done.** Tested on a real phone (OUKITEL K10000 Max, Android 7.0)
  with the T-Beam and a second node: BLE connect, NodeDB, positions and altitude all work.
- **Phase 2 (nodes on the OsmAnd map): done and tested** on the phone with the free OsmAnd. The bridge shows nodes on the map and updates them live.
- **Phase 3 ("field-ready" + team list in OsmAnd): done and tested on the phone.**
- **Phase 4 (done and tested on the phone):**
  - 24 h activity filter (`TeamRules`, `NodeRepository.activeNodes`) for the map, team list and node list.
  - Stable per-person colours (`NodeColors`).
  - Opt-in "teammate not heard" alerts (`data/alerts/`, watched IDs and threshold in `AppSettings`).
  - Foreground service that keeps the link alive.
  - Auto-reconnect.
  - Last-known positions kept across reconnects.
  - Remembers the radio and auto-connects on start.
  - OsmAnd "Meshtastic team" map widget and side-menu item, opening `TeamActivity`: members sorted by distance, with Show on map / Navigate.
- **Phase 5 (trails): the first version is tested on the phone. The follow-ups below are built but not yet tested:**
  - medium width
  - trail files named after the person, with a start waypoint and description
  - trail reset
  - own radio hidden on the map by default
  - "Your radio's GPS" card (`GpsHealth`), and position age on map points / team list / node cards (`PositionAge`)
  - update check (`data/update/UpdateChecker`): on app open, at most hourly, GET GitHub `releases/latest`, banner if newer (`AppVersion`), switchable; INTERNET permission is used only for this
  - `TrailBook`/`TrailRecorder` keep each node's positions for the configured window (30 min to 6 h, default 1 h), saved to disk (`data/storage/LocalStore`, tab-separated files via `StoreCodec`, every 10 s) with pins; "Clear saved data" deletes both.
  - `OsmAndBridge` draws a shown trail as a GPX track named `MeshAnd trail - <name>.gpx` (OsmAnd titles tracks by file name), with `osmand:width` medium, and re-imports it as it grows.
  - Trails are toggled from the team list, or from "Trail"/"Navigate" buttons added to OsmAnd's point menu (`addContextMenuButtons`).

The user runs the app from Android Studio (Play button); don't install/launch it via adb unless asked.

Out of scope until asked:
- iOS: MeshAnd is Android-only by design. OsmAnd's API for other apps exists only on Android.
- MQTT, backend, database, auth
- messaging, waypoints, telemetry history
- auto-start at boot, fancy UI
- sending our own position or other data to the mesh. The app is read-only toward the radio, with one exception the user asked for on 2026-10-04: **pins**, sent only when the user taps Send
  - pins: `ui/pin/SharePinActivity` is a share target (`ACTION_SEND text/plain`, label "MeshAnd pin") that parses coordinates out of OsmAnd's share text (`PinText.parseShared`: title line, `geo:`, `pin=`, …); only messages starting with `meshand:` are pins (the older `MeshAnd pin …` form was dropped on the user's request); the description is sent only when "Send a description" is ticked (off by default, pre-filled with OsmAnd's place name); pins are also listed in `TeamActivity`
  - it sends only `meshand: <lat>,<lon> [description]` (5 decimals, description optional, ≤ 48 UTF-8 bytes: Georgian letters are 3 bytes) with `RadioClient.sendText` on channel 0
  - `PinRepository` parses received text messages, keeps pins for 24 h (saved to disk), notifies, and tracks delivery via `MessageHandle.state`
  - `OsmAndBridge` draws pins on a second layer (`meshand_pins`) with Navigate/Remove point-menu buttons. The layer uses OsmAnd image points (`isImagePoints`): OsmAnd draws its pin-shaped marker around an image loaded from `POINT_IMAGE_URI_PARAM` via `ContentResolver`, served by `PinIconProvider` (`content://<pkg>.pinicons/<rrggbb>.png`, generated, read-only, exported). The zoom bands are circle 1–6, small 7–11, big 12+. OsmAnd's `updateMapLayer` does **not** copy `imagePoints` (only points and zoom bounds), so the pins layer is removed and re-added once per OsmAnd connection
  - **Phone-GPS sharing for GPS-less radios: the user explicitly said "do not do this yet".**

Keep the architecture simple: no DI framework, no extra Clean Architecture layers.

## Build & run
There is no system JDK; use Android Studio's bundled one. `adb` is not on PATH.
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest      # MeshtasticMapperTest (JVM, no device)
./gradlew :app:installDebug && adb shell am start -n com.meshand.app/.MainActivity
adb logcat -s MeshAnd MeshAnd/SDK
```
- The emulator has no real Bluetooth, so BLE must be tested on a physical phone.
- Toolchain: AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.20, Gradle 9.8.0, compileSdk 37, targetSdk 36, minSdk 24 (Android 7; Meshtastic SDK minSdk 26 overridden in the manifest, core library desugaring on).
- Versions live in `gradle/libs.versions.toml`.
- **App version:** `versionCode` / `versionName` in `app/build.gradle.kts` (currently 4 / 0.1.0: versionName was reset to 0.1.0 for the first public release, versionCode keeps rising). Bump both for every APK handed out.
- **Release:** `./gradlew :app:assembleRelease` signs with `signing/meshand-release.jks`, using the passwords in `keystore.properties`. Both are gitignored: **never commit them, never print the passwords.** If the properties file is missing, the release build is unsigned.
- **Remote:** `origin` is `git@github.com:mategogiberidze/MeshAnd.git` (branch `main`).
- **CI:** `.github/workflows/ci.yml` runs tests, lint and a debug build on pushes to `main` and on PRs.
- **Releases:** `.github/workflows/release.yml` runs on a `v*` tag. It requires the tag to equal `versionName`, signs using the secrets `MESHAND_KEYSTORE_BASE64` and `MESHAND_KEYSTORE_PASSWORD`, and publishes `MeshAnd-<version>.apk` to GitHub Releases.

## Architecture (`app/src/main/java/com/meshand/app/`)
- `MeshAndApp.kt`: the `Application` class. Owns `AppGraph` (settings, client, repository, OsmAnd bridge) for the whole process, wired by hand.
- `service/MeshConnectionService.kt`: foreground service (`connectedDevice` type). It runs while `client.activeRadio != null`, shows the status notification with a Disconnect action, and is sticky (reconnects to the saved radio after a restart).
- `data/settings/AppSettings.kt`: SharedPreferences for the saved radio, the auto-connect flag and the OsmAnd-enabled flag. No secrets are stored.
- `data/meshtastic/MeshtasticClient.kt`:
  - Kable BLE scan (filtered on `BleConstants.MESH_SERVICE_UUID`)
  - OS bonding (`createBond` + broadcast)
  - `connectLoop`: retries the initial connection with 5–60 s backoff, uses SDK `autoReconnect` for link drops, and starts a fresh session if the SDK gives up.
  - SDK log → Logcat sink
  - Owns its own Main-thread scope.
  - Shares one `InMemoryStorageProvider` across sessions.
- `data/meshtastic/MeshtasticMapper.kt`: protobuf `NodeInfo` / `MeshPacket` → `MeshNode` / `LiveUpdate`, and the merge of the two. Pure functions, unit-tested.
- `data/meshtastic/InMemoryStorageProvider.kt`: the SDK requires a `StorageProvider`. This is an in-memory one, so there is no database.
- `data/repository/NodeRepository.kt`: combines SDK `nodes` (the NodeDB) with live `packets` into `StateFlow<List<MeshNode>>`.
  - Nodes survive reconnects; they're cleared only when switching to a different radio.
  - Empty SDK snapshots at session start are ignored.
- `data/osmand/OsmAndBridge.kt`: binds to OsmAnd's AIDL V2 service and keeps one custom layer of nodes in sync. It throttles to 1 push/s, re-sends everything every 30 s, and makes binder calls on a single IO thread.
- `data/osmand/OsmAndMapper.kt`: `MeshNode` → `MapPointSpec` (pure, unit-tested).
- `OsmAndBridge` also manages the team map widget (icon `ic_action_group2`, an OsmAnd built-in drawable), the side-menu item (`meshand://team`), and `navigateTo` (OsmAnd `navigate`, `pedestrian` profile).
- `ui/team/`: `TeamActivity` and `teamMembers()`, which sorts by distance from the own radio (pure, tested). `domain/Geo.kt` does distance, bearing and formatting (tested).
- `domain/model/`: app-owned models (`MeshNode`, `DiscoveredRadio`, `ConnectionStatus`, `OsmAndStatus`). The UI must never see SDK or protobuf types.
- `MainViewModel.kt` (AndroidViewModel), `MainActivity.kt`, `BluetoothPermissions.kt`
- `ui/connection/`, `ui/nodes/`

## Meshtastic SDK facts (verified against the 0.1.0 sources; don't assume older APIs)
- **Dependency:** `org.meshtastic:sdk-core` + `sdk-transport-ble` **0.1.0**. It is pre-1.0 and GPL-3.0. Repo: github.com/meshtastic/meshtastic-sdk.
  - Protobufs are Wire-generated in `org.meshtastic.proto`, with snake_case fields (`last_heard`, `latitude_i`, …).
  - Kable is pinned to 0.44.1, the version the SDK is built against.
- **Never use** the deprecated Meshtastic Android `IMeshService`/AIDL API.
- **Live packets are not merged by the SDK.** SDK 0.1.0 does **not** fold live `POSITION_APP` / `NODEINFO_APP` packets, per-packet SNR/hops, or last-heard into its NodeDB. `NodeRepository` merges these itself, and live data wins over NodeDB fields.
- **Clock sync is off.** `autoSyncTimeOnConnect(false)` keeps Phase 1 read-only toward the radio.
- **Pairing:** the T-Beam shows a random 6-digit PIN on its screen. Its GATT characteristics need a bonded, encrypted link.
- **Unit conventions:**
  - Node numbers are uint32 carried in a Kotlin `Int`. Use `MeshtasticMapper.nodeNumToLong`.
  - Coordinates are `*_i × 1e-7`, and 0/absent means no fix.
  - A battery value over 100 means externally powered.
- The SDK source jars can be downloaded from Maven Central to check the API before relying on it.

## OsmAnd API facts (verified against the 5.4 AAR, 2026-10-01)
- **API:** AIDL **V2** (`net.osmand.aidlapi`). Bind `Intent("net.osmand.aidl.OsmandAidlServiceV2").setPackage(pkg)`. Don't use V1 (`net.osmand.aidl`).
- **Packages:** `net.osmand` (free, installed on the test phone), `net.osmand.plus`, `net.osmand.dev`, `net.osmand.huawei`. The API works in the free app.
- **Library:** `net.osmand:android-aidl-lib` from OsmAnd's Ivy repo (`builder.osmand.net/ivy`), not Maven Central. It's vendored as `app/libs/osmand-aidl-lib-5.4.aar`.
- **Allow step:** since OsmAnd 5.3, a new client app is **disabled** until the user enables it in OsmAnd → Menu → Plugins. Until then every call returns false, and `OsmAndStatus.NotAllowed` tells the user.
- **Layers are in-memory in OsmAnd.** Re-add the layer when `updateMapLayer` returns false.
- **Batch updates don't delete.** `updateMapLayer` only adds or replaces points; call `removeMapPoint` for nodes that are gone.
- **Android 7:** OsmAnd 5.4.x still has minSdk 24, but a future release may drop Android 7.
- **Widgets:** `AMapWidget` icons are OsmAnd's own drawable names, and the click `Intent` is started from OsmAnd's app context, so it needs `FLAG_ACTIVITY_NEW_TASK`. The user may need to enable the widget in OsmAnd → Configure screen.
- **GPX tracks** (`importGpx` with raw data): files go to OsmAnd's tracks folder (no subfolders: OsmAnd opens the file before creating parent dirs).
  - On a file's **first** import, OsmAnd 5.4 skips the colour and the `API_IMPORTED` mark, so `removeGpx` would refuse it. MeshAnd re-imports ≥3 s later and also writes the colour as an `osmand:color` GPX extension.
  - To hide reliably: import with `show=false`, then `removeGpx`.
- **Point-menu buttons:** `addContextMenuButtons` with our `layerId` shows them on our points. The click arrives on the callback stub's `onContextMenuButtonClicked(buttonId, pointId, layerId)` on a binder thread. OsmAnd forgets the buttons when it restarts.
- **Side-menu items:** `NavDrawerItem` uri is launched with `ACTION_VIEW`. `navigate` with a (0,0) start uses OsmAnd's current location.

## Meshtastic firmware position facts (firmware master source, 2026-10)
- **Defaults:** `default_broadcast_interval_secs` is 60 min. Smart broadcasts need at least 100 m moved and at least 5 min apart (`default_broadcast_smart_minimum_interval_secs`). The GPS update interval is 2 min.
- **Default public channel:** on the default channel, `NodeDB.cpp` forces at least 60 min / 5 min. A private primary channel lifts this.
- **TRACKER role:** sleeps between broadcasts, and doesn't receive or relay.
- **Docs are behind:** meshtastic.org still lists 15 min / 30 s. Trust the firmware source.

## Position timing (firmware `PositionModule.cpp`, checked 2026-10-04)
- `Position.time` is when the packet was **sent** (radio clock), not the fix time. `Position.timestamp` is the GPS fix time, sent only with the `TIMESTAMP` position flag (off by default).
- A radio that loses its fix keeps broadcasting its last position with a fresh `time`.
- So without `timestamp`, MeshAnd infers fix freshness from coordinate changes (`MeshNode.positionChangedAt`): a GPS with a fix jitters by metres, so repeated identical coordinates mean a stale position. Skipped when the channel truncates positions (`precision_bits` 1–31).

## Rules
- Never log channel PSKs, keys, `configBundle`, or `channels`. Keep SDK protocol-payload logging off.
- Log tags: `MeshAnd` for the app, `MeshAnd/SDK` for SDK messages, `MeshAnd/OsmAnd` for the OsmAnd bridge.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
