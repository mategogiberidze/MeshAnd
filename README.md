<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/wordmark-dark.svg">
  <img alt="MeshAnd" src="docs/images/wordmark-light.svg" width="320">
</picture>

### Your Meshtastic team, live on the OsmAnd map.<br>No internet. No mobile coverage. No extra app to juggle.

[![Latest release](https://img.shields.io/github/v/release/mategogiberidze/MeshAnd?color=1F5A47&label=release)](https://github.com/mategogiberidze/MeshAnd/releases/latest)
[![CI](https://github.com/mategogiberidze/MeshAnd/actions/workflows/ci.yml/badge.svg)](https://github.com/mategogiberidze/MeshAnd/actions/workflows/ci.yml)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-1F5A47)](LICENSE)
![Android 7+](https://img.shields.io/badge/Android-7%2B-3DDC84?logo=android&logoColor=white)

**[Download the APK](https://github.com/mategogiberidze/MeshAnd/releases/latest)** ·
[Set up your radios](docs/radio-setup.md) ·
[What's new](CHANGELOG.md) ·
[Report a bug](https://github.com/mategogiberidze/MeshAnd/issues/new/choose)

</div>

<br>

MeshAnd is a small Android app that connects to your own [Meshtastic](https://meshtastic.org)
radio over Bluetooth, picks up the positions of everyone else in your mesh, and puts them on the
map in [OsmAnd](https://osmand.net), the offline map app you're probably already using to
navigate.

![How MeshAnd moves positions from the radios to the OsmAnd map](docs/images/meshand-architecture.gif)

> [!NOTE]
> **This project is 100% vibe coded.** Every line of code, test and document was written by an
> AI (Claude Code), steered by a human who tested it on real radios and a real phone. It works
> for us, but treat it like any hobby project: read the code before trusting it with anything
> important.

## Features

<table>
<tr>
<td width="50%" valign="top">

### 🗺️ Team on the map
Every teammate is a coloured point on the OsmAnd map that moves as their radio reports in. Each
person keeps the same colour everywhere.

</td>
<td width="50%" valign="top">

### 👥 Team list inside OsmAnd
A map widget and a menu item list everyone nearest first, with distance. One tap shows them on
the map or starts navigation to them.

</td>
</tr>
<tr>
<td valign="top">

### 〰️ Trails
See where someone has been over the last 30 minutes to 6 hours, drawn as a line in their colour,
with a marker where it started.

</td>
<td valign="top">

### 📍 Shared pins
Tap a place in OsmAnd, then **Share → MeshAnd pin**. Your team gets a notification and a pin on
their map. It's sent as a tiny text message: `meshand: 41.75002,44.77124`.

</td>
</tr>
<tr>
<td valign="top">

### 🛰️ Is the GPS working?
See when your radio last got a new GPS position and how many satellites it sees. Teammates whose
radio keeps re-sending an old position are flagged "GPS not updating".

</td>
<td valign="top">

### 🔔 Quiet-teammate alerts
Choose who to watch, and get a notification if someone hasn't been heard for 15 minutes to
2 hours.

</td>
</tr>
</table>

**Also:**
- **Stays connected in your pocket.** It works with the screen off, reconnects by itself and
  remembers your radio.
- **Remembers trails and pins** across restarts, with one button to clear them.
- **Light or dark theme**, or follow the phone.
- **Tells you about new versions** by asking GitHub when it opens. This is its only internet use,
  and you can switch it off.
- **Mostly just listens.** Apart from pins you choose to send, MeshAnd never transmits anything
  and never changes your radio's settings.
- **Only shows people heard in the last 24 hours**, so old nodes don't clutter the map.

## Why it exists

Meshtastic radios are great outdoors. They're cheap, they run for days on a battery, and they
pass GPS positions and messages between each other over long-range LoRa radio, kilometres apart,
where phones have no signal.

But during a hike or a ride you don't want to keep jumping between apps. Your map, offline tiles,
tracks and navigation already live in OsmAnd. MeshAnd brings your friends into that map.

## Built on Meshtastic and OsmAnd

MeshAnd is a small bridge between two great open-source projects. All the hard work happens in
them.

<table>
<tr>
<td width="50%" valign="top">

### 📡 Meshtastic
**[meshtastic.org](https://meshtastic.org)**

An open-source project that turns cheap LoRa radios into an off-grid mesh network. The radios
pass text messages and GPS positions to each other over several kilometres, hopping through other
radios to reach further, with no phone network or internet. Channels are encrypted, and the
radios run for days on a battery.

You set up the radios with the official Meshtastic apps (Android, iPhone, or the
[web client](https://client.meshtastic.org)) and flash new ones with the
[web flasher](https://flasher.meshtastic.org).

[Documentation](https://meshtastic.org/docs/) ·
[GitHub](https://github.com/meshtastic)

</td>
<td width="50%" valign="top">

### 🗺️ OsmAnd
**[osmand.net](https://osmand.net)**

An open-source map and navigation app built on
[OpenStreetMap](https://www.openstreetmap.org). It works fully offline once you've downloaded a
region's maps, and is loved by hikers, cyclists and travellers for its detailed trails, contour
lines, recorded tracks and turn-by-turn navigation.

The free version from Google Play is all MeshAnd needs. Unlike most map apps, OsmAnd for Android
lets other apps draw on its map, which is what makes MeshAnd possible.

[Documentation](https://osmand.net/docs/intro) ·
[GitHub](https://github.com/osmandapp/OsmAnd)

</td>
</tr>
</table>

MeshAnd is an independent project. It isn't affiliated with or endorsed by Meshtastic or OsmAnd.
Meshtastic® is a registered trademark of Meshtastic LLC.

## What you need

| | |
|---|---|
| 📱 **Phone** | Android 7.0 or newer, with **OsmAnd** installed. The free version from Google Play is enough. |
| 📡 **Radios** | One Meshtastic radio **with GPS** per person, all on the same private channel. MeshAnd was built and tested with the **LILYGO T-Beam Supreme**. Other Meshtastic radios with Bluetooth and GPS should work too. You can also build your own from an ESP32 or nRF52 board with a LoRa radio and GPS, flashed with the [Meshtastic web flasher](https://flasher.meshtastic.org). |

## Getting started

1. **Set up the radios** with the official Meshtastic app: region, a private team channel, role
   **Client**, and faster position updates. The defaults only send a position about once an hour, so read
   [docs/radio-setup.md](docs/radio-setup.md) for what to change and why.
2. **Install MeshAnd.** Download the APK from the
   [latest release](https://github.com/mategogiberidze/MeshAnd/releases/latest) and open it on
   the phone. Android will ask you to allow installing apps from that source.
3. **Connect.** Open MeshAnd, grant the Bluetooth permission, scan, and tap your radio. On the
   first connection, type the 6-digit PIN shown on the radio's screen.
4. **Turn on "Show on OsmAnd".** The first time, OsmAnd blocks new apps until you allow them:
   open **OsmAnd → Menu → Plugins** and switch **MeshAnd** on.
5. **Use OsmAnd as usual.** Your team appears on the map. If the "Meshtastic team" widget doesn't
   show, enable it under **OsmAnd → Menu → Configure screen**.

From then on, MeshAnd reconnects to your radio by itself. A small notification shows while it's
connected, with a button to disconnect.

## Releases and updates

- **Where:** every version is on the
  [Releases page](https://github.com/mategogiberidze/MeshAnd/releases) as a ready-to-install APK,
  with notes on what changed. The full history is in [CHANGELOG.md](CHANGELOG.md).
- **Staying up to date:** when you open MeshAnd, it checks GitHub for a newer version and shows a
  **Download** button. Install the new APK over the old one; your radio and settings are kept. You
  can turn the check off in **Settings → About**.
- **Automatic updates:** with [Obtainium](https://github.com/ImranR98/Obtainium), add
  `https://github.com/mategogiberidze/MeshAnd` and it installs new releases for you.
- **Is the download genuine?** Every release is signed with the same key, and Android refuses an
  update signed by anyone else. Each APK also comes with a `.sha256` file to check the download.
- **Build it yourself:** see [docs/development.md](docs/development.md). A build you make yourself
  is signed with a different key, so uninstall the release version before installing it.

## Good to know

- **Positions are only as fresh as the radios send them.** A private channel and the position
  settings in [radio-setup.md](docs/radio-setup.md) make a huge difference.
- **One phone per radio.** A radio talks to only one app over Bluetooth at a time.
- **Phone makers that kill background apps.** Some aggressively stop apps in the background. If
  the link drops with the screen off, exempt MeshAnd from battery optimisation.
- **Android only, by design.** What makes MeshAnd useful is drawing teammates *inside* OsmAnd,
  and only OsmAnd for Android lets other apps add map layers. iPhone users in your team can
  follow everyone in the [Meshtastic iOS app](https://meshtastic.org/docs/software/apple/). Their
  radios are part of the same mesh, so they still show up in MeshAnd.
- **Tested on:** an OUKITEL K10000 Max phone (Android 7.0) and a Ulefone RugKing Pad 2 Pro tablet
  (Android 16), with a T-Beam Supreme plus a second Meshtastic node, and both the free OsmAnd
  and OsmAnd Pro.
  Reports from other phones and radios are very welcome.

## Contributing

Bug reports, test results from other phones and radios, and pull requests are all welcome. See
[CONTRIBUTING.md](CONTRIBUTING.md).

- **Developer docs:** building, project structure, logs and the release process are in
  [docs/development.md](docs/development.md).
- **Agent rules:** the rules and facts AI coding agents follow in this repository are in
  [AGENTS.md](AGENTS.md).
- **Release notes:** every version's changes are in [CHANGELOG.md](CHANGELOG.md).

## Credits

- [Meshtastic](https://meshtastic.org) and its official
  [Kotlin SDK](https://github.com/meshtastic/meshtastic-sdk), which handles the radio protocol.
- [OsmAnd](https://osmand.net) and its API for other apps, which makes the map integration
  possible.
- The diagram above was made with [Archify](https://github.com/tt-a1i/archify). The logo was
  made with Claude Design.

## License

MeshAnd is free software under the [GNU General Public License v3.0](LICENSE). It builds on the
GPL-3.0 Meshtastic SDK. You can use, change and share it, as long as anything you distribute
based on it stays under the GPL with its source available.
