# MeshAnd

Native Android app (Kotlin + Jetpack Compose) that connects **directly over BLE** to a Meshtastic
radio (target: LILYGO T-Beam Supreme) and shows the mesh NodeDB with live positions. The long-term
goal is to show Meshtastic node locations in OsmAnd.

## Current phase: Phase 1 (BLE → NodeDB → simple UI) — done
Tested on a real phone (OUKITEL K10000 Max, Android 7.0) connected to the T-Beam: BLE connect, NodeDB,
positions and altitude work. Next phase is OsmAnd integration.
**Do not start OsmAnd integration** (or any later-phase work) unless the user explicitly asks; when
asked, research the current OsmAnd third-party API first instead of assuming old APIs.
The user runs the app from Android Studio (Play button); don't install/launch it via adb unless asked.

Out of scope until asked:
- OsmAnd / AIDL, maps
- MQTT, backend, database, auth
- messaging, waypoints, telemetry history
- background service, auto-start, reconnection logic, fancy UI

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
- `data/meshtastic/MeshtasticClient.kt`:
  - Kable BLE scan (filtered on `BleConstants.MESH_SERVICE_UUID`)
  - OS bonding (`createBond` + broadcast)
  - SDK `RadioClient` lifecycle
  - SDK log → Logcat sink
  - Owns its own Main-thread scope.
- `data/meshtastic/MeshtasticMapper.kt`: protobuf `NodeInfo` / `MeshPacket` → `MeshNode` / `LiveUpdate`, and the merge of the two. Pure functions, unit-tested.
- `data/meshtastic/InMemoryStorageProvider.kt`: the SDK requires a `StorageProvider`. This is an in-memory one, so there is no database.
- `data/repository/NodeRepository.kt`: combines SDK `nodes` (the NodeDB) with live `packets` into `StateFlow<List<MeshNode>>`.
- `domain/model/`: app-owned models (`MeshNode`, `DiscoveredRadio`, `ConnectionStatus`). The UI must never see SDK or protobuf types.
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

## Rules
- Never log channel PSKs, keys, `configBundle`, or `channels`. Keep SDK protocol-payload logging off.
- Log tags: `MeshAnd` for the app, `MeshAnd/SDK` for SDK messages.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
