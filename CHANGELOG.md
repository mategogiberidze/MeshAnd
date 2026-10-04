# Changelog

What changed in each MeshAnd version. The section for a version becomes the description of its
[GitHub Release](https://github.com/mategogiberidze/MeshAnd/releases), so write it for people
using the app, not for developers.

## 0.2.0

Trails, shared pins, a GPS check, and a new look.

### New

- **Trails.** Tap a teammate on the OsmAnd map and choose **Trail** to see where they've been over
  the last 30 minutes to 6 hours, drawn in their colour. The track is named after them, a marker
  shows where and when it started, and you can reset it at any time.
- **Shared pins.** Show your team a place: tap it in OsmAnd, then **Share → MeshAnd pin**. Only
  the coordinates go out (plus a short description if you tick the box), as a tiny text message:
  `meshand: 41.75002,44.77124`. Teammates with MeshAnd get a notification and a map pin in your
  colour, with Navigate. Everyone else can read it in the Meshtastic app.
- **Is my GPS working?** MeshAnd shows when your radio last got a new GPS position and how many
  satellites it sees, and warns you when the position gets old.
- **How old is that position?** Teammates on the map and in the team list show when their
  position was taken, and "GPS not updating" when their radio keeps re-sending an old one.
- **Update check.** When you open MeshAnd it asks GitHub whether a newer version exists and
  offers the download. You can turn this off in Settings.
- **Trails and pins survive restarts.** They're saved on the phone; **Settings → Clear saved
  data** removes them.

### Improved

- **New logo and a redesigned app.** Four pages (Radio, Nodes, Pins, Settings) instead of one
  long screen, a search box for long node lists, and easier-to-read text outdoors.
- **Light or dark theme**, or follow the phone (Settings → Appearance).
- **Team list in OsmAnd** has the same new look, with separate Team and Pins pages, distance
  badges, and one-tap Trail and Alert switches.
- **Pins show the real time** as well as how long ago ("14:05 (5 min ago)").
- **Your own radio is hidden on the OsmAnd map** by default. Switch it back on in Settings if you
  need it.

### Updating

- Install the new APK over 0.1.0. Your radio pairing and settings are kept.
- If you installed MeshAnd from Android Studio, uninstall it first: those builds use a
  different signing key.
- MeshAnd now has the Internet permission. It is used **only** for the update check.
- Sending a pin is the only time MeshAnd transmits anything to the mesh, and only when you tap
  **Send pin**.

## 0.1.0

First public release.

- Connects to a Meshtastic radio over Bluetooth with the official Meshtastic SDK. The Meshtastic
  app isn't needed.
- Puts every teammate on the OsmAnd map as a coloured point that updates live.
- "Meshtastic team" list inside OsmAnd (map widget and menu item), nearest first, with Show on
  map and Navigate.
- Only shows people heard in the last 24 hours.
- Optional alerts when a teammate hasn't been heard for a while.
- Stays connected in the background, reconnects by itself, and remembers your radio.
