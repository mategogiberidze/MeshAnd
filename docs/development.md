# Developing MeshAnd

MeshAnd is Android-only. The OsmAnd integration uses OsmAnd's Android API (AIDL), which doesn't
exist on iOS.

## Building

Open the project in **Android Studio** and press Run with a phone connected. You need a real
phone, because the Android emulator has no Bluetooth to talk to a radio.

From a terminal, use Android Studio's bundled Java:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Toolchain: AGP 9.4, Kotlin 2.4, Gradle 9.8, compileSdk 37, minSdk 24 (Android 7.0).

## How the code is organised

```
app/src/main/java/com/meshand/app/
  MeshAndApp.kt                 process-wide objects (AppGraph), wired by hand, no DI framework
  service/                      foreground service that keeps the radio link alive
  data/meshtastic/              BLE scan, pairing, Meshtastic SDK session, protobuf → app model
  data/repository/              live node list (radio's node database + live position packets)
  data/osmand/                  OsmAnd bridge: map layer, team widget, side-menu item, navigation
  data/alerts/                  "teammate not heard" notifications
  data/settings/                SharedPreferences (saved radio, alert settings)
  domain/                       app-owned models, distance/bearing, colours, 24 h rule
  ui/                           Compose screens (connect, nodes, team list)
```

Main libraries:
- **[Meshtastic SDK](https://github.com/meshtastic/meshtastic-sdk)** 0.1.0
  (`org.meshtastic:sdk-core`, `sdk-transport-ble`). It's pre-1.0 and GPL-3.0. Its declared
  minSdk is 26, but its code works on 24, so the manifest overrides the minimum.
- **Kable** 0.44.1 for scanning, the same BLE library the SDK uses.
- **OsmAnd AIDL client** 5.4, vendored in `app/libs/` because it isn't on Maven Central.

Notes on things the libraries don't do:
- The SDK keeps the radio's node database, but doesn't apply live position packets to it.
  `NodeRepository` merges those itself.
- OsmAnd keeps custom layers only in memory, so the bridge re-sends everything every 30 seconds
  and whenever OsmAnd restarts.

`AGENTS.md` has a fuller list of verified API facts and project rules.

## Logs

Android Studio's Logcat, or the terminal:

```bash
adb logcat -s MeshAnd MeshAnd/SDK MeshAnd/OsmAnd MeshAnd/Alerts
```

The app never logs channel keys or radio configuration.

## Releases

Releases are built and signed by GitHub Actions:
- **`ci.yml`** runs tests, lint and a debug build on every push to `main` and on pull requests.
- **`release.yml`** runs when you push a version tag. It builds the signed APK and attaches it
  to a GitHub Release.

To publish a version:
1. Raise `versionName`, and **always** `versionCode`, in `app/build.gradle.kts`. Commit and push.
2. Push a tag that matches the version, for example `git tag v0.1.1 && git push origin v0.1.1`.
3. A few minutes later, the Releases page on GitHub has `MeshAnd-0.1.1.apk`.

### Signing key

- **Where it is:** the release key is `signing/meshand-release.jks`, and its password is in
  `keystore.properties`. Both are gitignored.
- **Back them up together.** Android only installs an update over an existing copy if it's signed
  with the same key.
- **Without the properties file** (e.g. in a fresh clone), local release builds come out
  unsigned. See `keystore.properties.example`.

For GitHub Actions, the key is stored as two repository secrets. The first command uploads the
key; the second uploads its password from `keystore.properties`. Neither shows the secret on
screen:

```bash
base64 -i signing/meshand-release.jks | gh secret set MESHAND_KEYSTORE_BASE64 --repo mategogiberidze/MeshAnd
```
```bash
grep '^storePassword=' keystore.properties | cut -d= -f2- | tr -d '\n' | gh secret set MESHAND_KEYSTORE_PASSWORD --repo mategogiberidze/MeshAnd
```

### Debug vs release builds

Android Studio's Run button installs a *debug* build signed with a different key, so a debug
build and a release build can't replace each other. Uninstall MeshAnd when switching between
them. This clears the saved radio and alert settings; the Bluetooth pairing stays.

To run the release build from Android Studio, use **Build Variants → app → release**.

## Android permissions

| Android | What MeshAnd asks for |
|---|---|
| 12 and newer | Nearby devices (Bluetooth scan and connect), notifications (13+, optional) |
| 7 to 11 | Location, which Android requires for Bluetooth scanning. Location must also be switched on. |

## Debugging Bluetooth

- **Pairing stuck:** forget the radio in Android's Bluetooth settings and connect again.
- **Radio not found:** untick "Meshtastic only" in MeshAnd's scan to see every nearby Bluetooth
  device.
- **Deep GATT problems:** enable *Bluetooth HCI snoop log* in Developer options, reproduce the
  problem, then open the `btsnoop_hci.log` from an `adb bugreport` in Wireshark.
