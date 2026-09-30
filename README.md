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
- Toolchain: AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8.0, compileSdk 37, minSdk 26.

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
| 8–11 (API 26–30) | `ACCESS_FINE_LOCATION` | `BLUETOOTH`/`BLUETOOTH_ADMIN` are install-time. System Location must be **on** for scans to return results. |

## Testing with a T-Beam Supreme
1. Make sure the radio is **not connected to any other phone or app**, because Meshtastic accepts one BLE client at a time. Disconnect it in the official app, or turn off Bluetooth on the other phone.
2. Launch MeshAnd and tap **Grant Bluetooth permissions**.
3. Tap **Scan for radios**. The T-Beam appears, named something like `Meshtastic_xxxx` or its long name.
   - If nothing appears, untick **Meshtastic only** and scan again to see every BLE device.
4. Tap **Connect**. On the first connection Android shows a pairing dialog. Type the **6-digit PIN shown on the T-Beam's screen**. If the radio is set to fixed-PIN mode, the default is `123456`.
5. The status moves through Connecting → handshake Stage1/Stage2 → **Connected**, and the node list appears.
6. To see live updates, let another node broadcast its position, or trigger one from that node. Its card updates and "Last seen" resets. Logcat prints `Position update received: …`.

If pairing gets stuck, remove the radio under Android Settings → Bluetooth → the radio → Forget, then connect again.

## Logcat
```bash
adb logcat -s MeshAnd MeshAnd/SDK             # app + Meshtastic SDK logs
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
