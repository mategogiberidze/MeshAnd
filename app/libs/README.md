# Vendored libraries

## osmand-aidl-lib-5.4.aar
OsmAnd's client library for its AIDL V2 API (`net.osmand.aidlapi`). It is used to put Meshtastic nodes on the OsmAnd map.

- Source: `https://builder.osmand.net/ivy/net.osmand/android-aidl-lib/5.4/android-aidl-lib-5.4.aar`
  - This is OsmAnd's Ivy repo; the library isn't on Maven Central.
  - The code is in `OsmAnd-api/` of https://github.com/osmandapp/OsmAnd.
- Downloaded: 2026-10-01
- minSdk: 24
- SHA-256: `9f5419af2d4b8133460af370e9ed0ddaa648bf672c76ce82e04d41fe313afdec`

**Why it's vendored:** OsmAnd rebuilds its revision folders in place, so downloading at build time wouldn't give reproducible builds.

**Licensing:** OsmAnd documents the API as usable for any purpose.

**To update:** download a newer revision from the same path, replace the file, and update `app/build.gradle.kts`.
