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
