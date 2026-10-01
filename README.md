# MeshAnd: Phase 1

A minimal native Android app (Kotlin + Jetpack Compose) that connects **directly over BLE** to a
Meshtastic radio (tested target: LILYGO T-Beam Supreme). It downloads the radio's NodeDB and shows
live node positions. The official Meshtastic app is not required, and the app doesn't use the
deprecated IMeshService/AIDL API.

```
T-Beam Supreme ──BLE──▶ MeshtasticClient (Kable scan + bonding)
                         └─ org.meshtastic:sdk-core / sdk-transport-ble 0.1.0 (RadioClient)
                              ├─ nodes   (NodeDB snapshot + deltas)
                              └─ packets (live POSITION_APP / NODEINFO_APP / …)
                         ▼
                    NodeRepository → StateFlow<List<MeshNode>> → Compose UI
```

## Stack
- Official Meshtastic SDK [`org.meshtastic:sdk-core` + `sdk-transport-ble` **0.1.0**](https://github.com/meshtastic/meshtastic-sdk).
  It is pre-1.0 and GPL-3.0.
- Kable 0.44.1, the same BLE library the SDK uses, for scanning.
- Toolchain: AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8.0, compileSdk 37, minSdk 24 (Android 7.0; the SDK declares 26 but works on 24, see the manifest override).

## Build & run
You need Android Studio (its bundled JBR works as the JDK) and a physical phone with USB debugging enabled.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb devices                       # phone must show as "device"
./gradlew :app:installDebug
adb shell am start -n com.meshand.app/.MainActivity
./gradlew :app:testDebugUnitTest  # mapper unit tests
```
Or open the folder in Android Studio and press Run with the phone selected.

## Release APK for teammates
The release build is signed with the MeshAnd release key, so teammates can install it without Android Studio.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

**Publishing through GitHub (recommended)**
GitHub Actions builds and signs the APK and attaches it to a GitHub Release.
- `ci.yml` runs the tests, lint and a debug build on every push to `main` and on pull requests.
- `release.yml` runs when you push a version tag.

*One-time setup:* store the signing key as repository secrets. Run these from the project folder; they read the local files, so the secrets never appear on screen:
```bash
base64 -i signing/meshand-release.jks | gh secret set MESHAND_KEYSTORE_BASE64 --repo mategogiberidze/MeshAnd
```
```bash
grep '^storePassword=' keystore.properties | cut -d= -f2- | tr -d '\n' | gh secret set MESHAND_KEYSTORE_PASSWORD --repo mategogiberidze/MeshAnd
```

*Each release:*
1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, then commit and push.
2. Tag the version and push the tag: `git tag v0.4.1 && git push origin v0.4.1`. The tag must equal `v` + `versionName`, or the workflow stops.
3. When the workflow finishes, the GitHub **Releases** page has `MeshAnd-0.4.1.apk` (plus a `.sha256`) to download.

The repository is private, so only people with access to it can download Release files. Make the repo public, or add teammates as collaborators.

**Signing**
- The key is `signing/meshand-release.jks` and its passwords are in `keystore.properties`. Both are gitignored and must never be committed.
- **Back them up together, somewhere safe.** Android only installs an update over an existing copy if it was signed with the same key. Lose the key and every teammate has to uninstall before installing a new version.
- If `keystore.properties` is missing (for example in a fresh clone), the release APK is built unsigned. Copy `keystore.properties.example` to `keystore.properties` and fill it in.

**Versions**
- Bump `versionCode` and `versionName` in `app/build.gradle.kts` for every APK you hand out.
- Android refuses to install a lower `versionCode` over a higher one.
- The version is shown in MeshAnd: on the connect screen, and at the bottom of the node list.

**Installing on a teammate's phone**
1. Send them `app-release.apk` (messenger, USB, cloud drive).
2. On the phone, open the file and allow "Install unknown apps" for the app it was opened from when Android asks.
3. In MeshAnd, connect to their own radio and enter its PIN. In OsmAnd, turn MeshAnd on under Menu → Plugins.

**Debug and release builds can't replace each other.** Android Studio's Play button installs a *debug* build signed with a different key. Uninstall MeshAnd before switching between the two. Uninstalling clears MeshAnd's settings (saved radio, alerts), but the Bluetooth pairing stays.

**Licence:** MeshAnd uses the GPL-3.0 Meshtastic SDK. If you give the APK to people, they're entitled to the source code too, e.g. via this repository.

## Permissions
| Android | Runtime permissions | Notes |
|---|---|---|
| 12+ (API 31+) | `BLUETOOTH_SCAN` (neverForLocation), `BLUETOOTH_CONNECT` | |
| 7–11 (API 24–30) | `ACCESS_FINE_LOCATION` | `BLUETOOTH`/`BLUETOOTH_ADMIN` are install-time. System Location must be **on** for scans to return results. |

## Testing with a T-Beam Supreme
1. Make sure the radio is **not connected to any other phone or app**, because Meshtastic accepts one BLE client at a time. Disconnect it in the official app, or turn off Bluetooth on the other phone.
2. Launch MeshAnd and tap **Grant Bluetooth permissions**.
3. Tap **Scan for radios**. The T-Beam appears, named something like `Meshtastic_xxxx` or its long name.
   - If nothing appears, untick **Meshtastic only** and scan again to see every BLE device.
4. Tap **Connect**. On the first connection Android shows a pairing dialog. Type the **6-digit PIN shown on the T-Beam's screen**. If the radio is set to fixed-PIN mode, the default is `123456`.
5. The status moves through Connecting → handshake Stage1/Stage2 → **Connected**, and the node list appears.
6. To see live updates, let another node broadcast its position, or trigger one from that node. Its card updates and "Last seen" resets. Logcat prints `Position update received: …`.

If pairing gets stuck, remove the radio under Android Settings → Bluetooth → the radio → Forget, then connect again.

## Showing nodes on OsmAnd (Phase 2)
MeshAnd puts every node that has a position on the OsmAnd map as a custom layer, "Meshtastic nodes", and keeps it updated as positions arrive. It uses OsmAnd's official API for other apps (AIDL V2, `net.osmand.aidlapi`). This works with the free OsmAnd from Google Play, OsmAnd+, and the F-Droid build.

1. Connect to the radio as usual. On the nodes screen, turn on **Show on OsmAnd**.
2. **First time only:** since OsmAnd 5.3, a new app is blocked until you allow it. The card will say *"OsmAnd is blocking MeshAnd"*. Open **OsmAnd → Menu → Plugins**, switch **MeshAnd** on, and return to MeshAnd. Within about 30 s the card shows *"Showing N node(s) on the map"*.
3. Switch to OsmAnd and you'll see the nodes:
   - **Your own radio:** blue.
   - **Other nodes:** orange.
   - **Stale nodes:** greyed out. A node goes stale after 30 min without being heard.
   - **Details:** tap a node to see altitude, battery, SNR, hops and last seen.
4. **Show on OsmAnd** on a node card opens OsmAnd centred on that node.

Notes:
- Updates are sent at most once per second, and the whole layer is re-sent every 30 s. OsmAnd forgets custom layers when it restarts, so the re-send restores them.
- The layer is removed when you switch the bridge off. The setting is remembered across app restarts.

### Team list in OsmAnd
MeshAnd adds two entry points inside OsmAnd:
- **A "Meshtastic team" map widget.** It shows a group icon and the number of team members. If you don't see it, open **OsmAnd → Menu → Configure screen**, find **Meshtastic team** among the widgets, and switch it on.
- **A "Meshtastic team" item** in OsmAnd's side menu.

Both open the team list:
- **Who's on it:** every node except your own radio.
- **Order:** nearest first, with distance and direction measured from your T-Beam's GPS (e.g. "1.2 km NE"). Each row also shows when the member was last heard and their battery.
- **Show on map:** jumps OsmAnd to that member.
- **Navigate:** starts OsmAnd walking navigation from your phone's location to that member.

You can also open the list from MeshAnd with **Team list** on the nodes screen.

### Who is shown, and colours
- **Who's shown:** only nodes heard within the **last 24 hours** appear, on the OsmAnd map, in the team list and in MeshAnd's node list. Your own radio always appears. MeshAnd's node list says how many older nodes are hidden.
- **Colours:** each person gets a **random but fixed colour**, worked out from their node ID. The colour is the same on the map, in the team list and in MeshAnd, on every phone and after restarts. Your own radio is always blue.
- **Grey:** a node goes grey on the map after 60 min without being heard.

### Teammate alerts
- **Who triggers them:** in the team list, switch **Alert** on for each teammate you want to watch. It's opt-in, so strangers on the public channel never trigger alerts.
- **When:** choose how long a teammate can be silent in MeshAnd's **Teammate alerts** card: Off, 15m, 30m, 1h (default) or 2h. You get a notification once when they go silent ("Giorgi not heard for 64 min", with last-heard time and direction) and another when they're heard again.
- **Only while connected:** alerts are checked only while MeshAnd is connected to your radio. While your own link is down, everyone looks silent, so that wouldn't mean anything.
- **Choosing the time:** the alert time must be longer than how often teammates' radios transmit; see "Getting more live positions" below. With default Meshtastic settings, a stationary teammate may only send a position once an hour, so keep 1h or more unless the team uses a private channel with faster settings.

## Getting more live positions (radio settings)
MeshAnd only reads what the radios send. How "live" positions are depends on each teammate's Meshtastic settings, which you set in the official Meshtastic app. These values are from the firmware source as of 2026-10; the docs page lists older defaults.

- **Default behaviour:** the radio takes a GPS fix every 2 min. It broadcasts its position every 60 min. "Smart" updates add a broadcast when it has moved 100 m or more, but at most every 5 min.
- **Default public channel:** if the team uses the default public channel (LongFast with the default key), the firmware enforces at least 60 min / 5 min, whatever you configure.

Recommended for a team:
1. **Use a private team channel as the primary channel.** Give it its own name and a random key, then share it with a QR code from the Meshtastic app. This removes the public-channel limits and keeps your positions private. All team radios need the same channel and the same LoRa preset.
2. **Position settings for trips:**
   - smart broadcast on
   - minimum distance 25–50 m
   - smart minimum interval 30–60 s
   - regular broadcast 5–10 min, as a heartbeat while standing still
   - GPS update interval 30–60 s
3. **Role: CLIENT** for people. Avoid TRACKER for anyone who wants to see the others: a tracker sleeps between broadcasts and doesn't receive or relay.
4. **Airtime:** frequent positions use more airtime. A small team is fine on LongFast. Big groups may need a faster preset (e.g. MediumFast, at some cost in range), and every radio must match.
5. **GPS reception:**
   - give the radio a view of the sky: top of the backpack or a shoulder strap, not deep in a pocket
   - expect the first fix outdoors to take a few minutes
   - leave the radio on so later fixes come fast

## Staying connected (field use)
- **Background:** while a radio is connected, MeshAnd runs a foreground service with a permanent notification ("Connected to … · N nodes"). This keeps the radio link and the OsmAnd layer alive with OsmAnd in front and the screen off. The notification has a **Disconnect** button.
- **Automatic reconnect:** if the link drops (out of range, radio rebooted), it reconnects automatically: the SDK retries quickly, then MeshAnd keeps retrying every 5–60 s. Meanwhile the last-known positions stay on the map and turn grey after 30 min.
- **Remembers your radio:** MeshAnd reconnects to the last radio on start without scanning. Tapping **Disconnect** stops this until you connect again.
- **Notifications on Android 13+:** MeshAnd also asks for notification permission so it can show the connection notification. The connection works without it.
- **Aggressive phone makers:** some manufacturers still kill background apps. If the link stops with the screen off, exempt MeshAnd from battery optimisation in Android settings.

The OsmAnd client library is vendored in `app/libs/`; see `app/libs/README.md` for details.

## Logcat
```bash
adb logcat -s MeshAnd MeshAnd/SDK MeshAnd/OsmAnd   # app + Meshtastic SDK + OsmAnd bridge logs
adb logcat -s MeshAnd:I MeshAnd/SDK:I         # less noise
adb logcat | grep -iE "bluetooth|BtGatt|bt_btif|MeshAnd"   # include Android BLE stack
adb shell dumpsys bluetooth_manager | grep -iA3 bonded     # bond state
adb logcat -c                                  # clear before a test run
```
For deep GATT debugging, enable Developer options → *Enable Bluetooth HCI snoop log*, reproduce the problem, then run `adb bugreport` and open `btsnoop_hci.log` in Wireshark.

The app never logs channel keys, PSKs, or config. SDK protocol-payload logging is left off.

## Known limitations
- No automatic reconnection. After a drop, the status shows *Error: Radio disconnected*; scan and connect again.
- Nothing is persisted. The NodeDB is held in memory (`InMemoryStorageProvider`) and downloaded again on every connect.
- The app only works in the foreground. There is no service, so the link stops when the ViewModel is destroyed.
- The SDK 0.1.0 does not fold live `POSITION_APP` / `NODEINFO_APP` packets, per-packet SNR/hops, or last-heard into its NodeDB. `NodeRepository` merges these itself.
  - "Last seen" for live packets uses the phone's clock.
  - Battery comes from the NodeDB plus telemetry, which the SDK merges.
- Coordinates of exactly 0,0 are treated as "no position".
- The SDK is pre-1.0 (API may change), and both the SDK and the protobufs are GPL-3.0.
