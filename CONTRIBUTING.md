# Contributing to MeshAnd

Thanks for helping! MeshAnd is a small hobby project, so contributions of any size are welcome.

## Useful ways to help

- **Test it on your hardware.** So far it has been tested on one Android 7 phone and one Android 16
  tablet, with T-Beam Supreme radios. A report saying "works on my Pixel 8 with a Heltec V3 and
  OsmAnd 5.5" is very valuable, even if nothing broke.
- **Share a field report.** Tell us how MeshAnd did on a real trip in
  [Discussions → Show and tell](https://github.com/mategogiberidze/MeshAnd/discussions/categories/show-and-tell).
- **Ask and answer questions** in [Discussions → Q&A](https://github.com/mategogiberidze/MeshAnd/discussions/categories/q-a).
- **Report bugs.** Open an issue using the bug report template, which asks for the details that
  matter: phone, Android version, radio, firmware and OsmAnd version.
- **Improve the docs.** Especially [docs/radio-setup.md](docs/radio-setup.md), if you know
  Meshtastic well.
- **Send code.** For anything bigger than a small fix, start an
  [Ideas discussion](https://github.com/mategogiberidze/MeshAnd/discussions/categories/ideas) or
  an issue first, so we can agree on the approach.

## Ground rules for code

- **Android only.** MeshAnd needs OsmAnd's Android API, so there will be no iOS version.
- **Almost read-only toward the radio.** The only thing MeshAnd sends to the mesh is a pin, and only
  when the user taps Send. It never changes radio settings. Anything else that transmits needs a
  discussion first.
- **Keep it simple.** No dependency-injection framework, no database, no extra architecture
  layers. Match the style of the code around your change.
- **Fill in the pull request template.** It asks how you tested and what you changed.
- **Test before you push.** Tests must pass (`./gradlew :app:testDebugUnitTest`). Bluetooth
  changes must be tried on a real phone with a real radio, because the emulator has no Bluetooth.
- **Never log secrets.** Don't log channel keys or radio configuration.

[docs/development.md](docs/development.md) explains how to build and how the code is organised.

## About the AI-written code

This project was written entirely with an AI coding agent. Contributions written with AI tools
are welcome too, as long as you've tested them and understand what they change. The project
facts and rules the agent works from are in [AGENTS.md](AGENTS.md). Please keep them up to date
when your change makes one of them wrong.

## License

By contributing, you agree that your contribution is licensed under the
[GPL-3.0](LICENSE), like the rest of the project.
