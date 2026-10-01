# MeshAnd

Native Android app (Kotlin + Jetpack Compose) that connects **directly over BLE** to a Meshtastic
radio (target: LILYGO T-Beam Supreme) and shows the mesh NodeDB with live positions. The long-term
goal is to show Meshtastic node locations in OsmAnd.

## Phases
- **Phase 1 (BLE → NodeDB → simple UI): done.** Tested on a real phone (OUKITEL K10000 Max, Android 7.0)
  with the T-Beam and a second node: BLE connect, NodeDB, positions and altitude all work.
- **Phase 2 (nodes on the OsmAnd map): done and tested** on the phone with the free OsmAnd. The bridge shows nodes on the map and updates them live.
- **Phase 3 ("field-ready" + team list in OsmAnd): done and tested on the phone.**
  - Foreground service that keeps the link alive.
  - Auto-reconnect.
  - Last-known positions kept across reconnects.
  - Remembers the radio and auto-connects on start.
  - OsmAnd "Meshtastic team" map widget and side-menu item, opening `TeamActivity`: members sorted by distance, with Show on map / Navigate.

The user runs the app from Android Studio (Play button); don't install/launch it via adb unless asked.

Out of scope until asked:
- MQTT, backend, database, auth
- messaging, waypoints, telemetry history
- auto-start at boot, fancy UI
- sending our own position or other data to the mesh (the app is read-only toward the radio)

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
- **Side-menu items:** `NavDrawerItem` uri is launched with `ACTION_VIEW`. `navigate` with a (0,0) start uses OsmAnd's current location.

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
