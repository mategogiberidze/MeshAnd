# MeshAnd

**See your Meshtastic team on the OsmAnd map, with no internet and no mobile coverage.**

MeshAnd is a small Android app that connects to your own [Meshtastic](https://meshtastic.org)
radio over Bluetooth, picks up the positions of everyone else in your mesh, and puts them on the
map in [OsmAnd](https://osmand.net), the offline map app you're probably already using to
navigate.

![How MeshAnd moves positions from the radios to the OsmAnd map](docs/images/meshand-architecture.gif)

> **This project is 100% vibe coded.** Every line of code, test and document was written by an
> AI (Claude Code), steered by a human who tested it on real radios and a real phone. It works
> for us, but treat it like any hobby project: read the code before trusting it with anything
> important.

## Why it exists

Meshtastic radios are great outdoors. They're cheap, they run for days on a battery, and they
pass GPS positions and messages between each other over long-range LoRa radio, kilometres apart,
where phones have no signal.

But during a hike or a ride you don't want to keep jumping between apps. Your map, offline tiles,
tracks and navigation already live in OsmAnd. MeshAnd brings your friends into that map. Each
teammate is a coloured dot that moves as their radio reports in, and a tap shows how far away
they are and when you last heard from them. You can also ask OsmAnd to navigate you to them.

## What it does

- **Connects straight to your radio** over Bluetooth using the official Meshtastic SDK. The
  official Meshtastic app isn't needed.
- **Puts every teammate on the OsmAnd map** as a coloured point that updates live. Each person
  keeps the same colour everywhere.
- **Adds a team list inside OsmAnd.** A "Meshtastic team" widget on the map and an item in
  OsmAnd's menu list everyone nearest first, with distance and direction ("1.2 km NE"). From
  there, one tap shows them on the map or starts navigation to them.
- **Keeps working in your pocket.** It stays connected with the screen off, reconnects by itself
  when the link drops, and remembers your radio.
- **Warns you when someone goes quiet.** You choose which teammates to watch, and MeshAnd sends a
  notification if one hasn't been heard for 15 minutes to 2 hours.
- **Only shows people heard in the last 24 hours**, so old nodes don't clutter the map.
- **Only listens.** MeshAnd never sends anything to the mesh.

## What you need

- **An Android phone** running Android 7.0 or newer, with **OsmAnd** installed. The free
  version from Google Play is enough.
- **A Meshtastic radio with GPS for every person**, all on the same private channel. Either:
  - **buy a ready-made one.** MeshAnd was built and tested with the **LILYGO T-Beam Supreme**.
    Other Meshtastic radios with Bluetooth and GPS should work too, but haven't been tested yet.
  - **build your own** from an ESP32 or nRF52 board with a LoRa radio and a GPS module, and flash
    it with the [Meshtastic web flasher](https://flasher.meshtastic.org).

## Getting started

1. **Set up the radios** with the official Meshtastic app: region, a private team channel, and
   faster position updates. The defaults only send a position about once an hour.
   [docs/radio-setup.md](docs/radio-setup.md) explains what to change and why.
2. **Install MeshAnd** on your phone. Download the APK from this repository's **Releases** page
   and open it on the phone. Android will ask you to allow installing apps from that source.
3. **Connect.** Open MeshAnd, grant the Bluetooth permission, scan, and tap your radio. On the
   first connection, type the 6-digit PIN shown on the radio's screen.
4. **Turn on "Show on OsmAnd".** The first time, OsmAnd blocks new apps until you allow them:
   open **OsmAnd → Menu → Plugins** and switch **MeshAnd** on.
5. **Use OsmAnd as usual.** Your team appears on the map. If the "Meshtastic team" widget doesn't
   show, enable it under **OsmAnd → Menu → Configure screen**.

From then on, MeshAnd reconnects to your radio by itself. A small notification shows while it's
connected, with a button to disconnect.

## Good to know

- **Positions are only as fresh as the radios send them.** A private channel and the position
  settings in [radio-setup.md](docs/radio-setup.md) make a huge difference.
- **One phone per radio.** A radio talks to only one app over Bluetooth at a time.
- **Phone makers that kill background apps.** Some aggressively stop apps in the background. If
  the link drops with the screen off, exempt MeshAnd from battery optimisation.
- **Tested setup:** an OUKITEL K10000 Max (Android 7.0), a T-Beam Supreme plus a second Meshtastic node, and the free
  OsmAnd 5.4. Reports from other phones and radios are very welcome.

## For developers

Building, project structure, logs and the release process are in
[docs/development.md](docs/development.md). The rules and facts AI coding agents follow in this
repository are in [AGENTS.md](AGENTS.md).

## Credits

- [Meshtastic](https://meshtastic.org) and its official
  [Kotlin SDK](https://github.com/meshtastic/meshtastic-sdk), which handles the radio protocol.
- [OsmAnd](https://osmand.net) and its API for other apps, which makes the map integration
  possible.
- The diagram above was made with [Archify](https://github.com/tt-a1i/archify).

MeshAnd uses the Meshtastic SDK, which is licensed under GPL-3.0, so MeshAnd's own source has to
be shared under a GPL-3.0-compatible licence when the app is distributed.
