# Setting up your Meshtastic radios

MeshAnd only shows what your radios send. How often teammates move on the map depends far more
on their radio settings than on the app. This page covers what we learned setting up a small team.
The values were checked against the Meshtastic firmware source in October 2026. The official docs
still list some older defaults.

You change all of these with the official tools, not with MeshAnd: the
[Meshtastic app](https://meshtastic.org/docs/software/), or the web client at
[client.meshtastic.org](https://client.meshtastic.org) in Chrome.

## Hardware

Each person needs their own Meshtastic radio with **Bluetooth (BLE)** and, ideally, **GPS**.

- **Ready-made:** MeshAnd was built and tested with the **LILYGO T-Beam Supreme**. It has GPS, a
  screen (used to show the pairing PIN), and a big 18650 battery. Other Meshtastic radios with
  Bluetooth and GPS should work the same way, but haven't been tested.
- **Build your own:** Meshtastic runs on many DIY combinations of an ESP32 or nRF52 board, a LoRa
  radio and a GPS module. Flash it with the [web flasher](https://flasher.meshtastic.org).
- **Radios without GPS** (many Heltec boards, for example) still appear in MeshAnd, but they have
  no position to put on the map.

All radios in a team must use the same **band** (433 / 868 / 915 MHz hardware), **region**,
**LoRa preset** and **channel**.

## Region

Region tells the radio which country's radio rules to follow: frequencies, transmit power, and
on some bands a limit on how much of each hour it may transmit (for example, 10% on EU_433,
UA_433 and EU_868). **Pick the region for the country you actually use it in.** Using another
country's region to get around a limit means breaking the rules where you are.

## A private team channel (most important)

On the default public channel (LongFast with the default key), the firmware sends positions at
most **once an hour**, and smart updates at most **every 5 minutes**, whatever you configure.
A private channel removes that limit and keeps your positions private.

1. On one radio, make a channel with its own name and a random key, and make it the **primary**
   channel (slot 0) with location sharing set to **precise**.
2. Share it with the rest of the team via the QR code / channel link in the Meshtastic app.

Good to know:
- A radio sends its position on **one channel only**: the first slot (0, 1, 2 …) that has
  location sharing enabled. Put the team channel first.
- The primary channel's **name decides the radio frequency**, unless *Frequency Slot* is set by
  hand in the LoRa settings. A private primary channel therefore usually means your team is on
  its own frequency and won't hear the public mesh. That's simplest and most private. If you
  want public nodes to help relay your packets, note your public frequency slot first and set it
  explicitly after switching channels, on every radio.

## Position settings

| Setting | Firmware default | Suggested for a moving team |
|---|---|---|
| Smart position | on | on |
| GPS update interval | 2 min | 30–60 s |
| Broadcast interval | 60 min | 5–10 min (a heartbeat while standing still) |
| Smart minimum distance | 100 m | 25–30 m (below ~10 m, GPS jitter sends extra updates) |
| Smart minimum interval | 5 min | 60 s |
| Position flags: Timestamp | off | **on** (lets MeshAnd show exactly when the GPS got each position) |

With these settings, a walking teammate updates about every minute, and someone standing still
at least every 5–10 minutes. Then MeshAnd's "not heard" alerts can be set to 15–30 minutes.

**Why the Timestamp flag:** when a radio loses its GPS fix, it keeps broadcasting its *last*
position. Without the timestamp, MeshAnd can only notice this because the coordinates stop
changing. With it, MeshAnd shows the real GPS fix time on the map and in the team list. It costs
4 bytes per position.

**Role:** keep people on **Client**. *Tracker* sleeps between broadcasts and can't receive or
relay, so it only suits things you just want to locate (a car, a dog), not people who need to
see each other.

**Airtime:** every position is a radio transmission. A handful of people on LongFast is fine.
Big groups, or regions with an hourly transmit limit, may want a faster preset such as **Medium
Fast**: less airtime per packet, somewhat less range. Change it on every radio at once.

## Getting a good GPS fix

- Carry the radio where it can see the sky: the top of a backpack or a shoulder strap, not deep
  in a pocket.
- The first fix after a long time off can take a few minutes outdoors. Leave the radio on and
  later fixes come quickly.

## Pairing with the phone

- **PIN:** a radio with a screen shows a random **6-digit PIN** the first time a phone connects.
  Type it into Android's pairing dialog. Radios without a screen usually use the fixed PIN
  `123456`.
- **One phone at a time:** a radio talks to only one app over Bluetooth at a time. Disconnect
  the official Meshtastic app, or the web client, before connecting MeshAnd.
