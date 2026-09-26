# PawLink Pet — under development

[简体中文](README.zh-CN.md)

**The Flutter version is under development. It is not a stable release.**

This prototype shares pet animation and state handling across Android, macOS,
Windows, and Linux. It uses a vector-drawn cat and the older six-action state
model: `sleep`, `idle`, `walk`, `eat`, `groom`, and `shake`.

The current UI supports manual action previews. `PetStateSource` is an extension
interface; a complete BLE adapter and native macOS pet HTTP service are not
included. Platform targets are development targets, not a claim that every
platform has passed device testing.

From this directory:

```sh
flutter pub get
flutter devices
flutter run -d macos
flutter analyze
flutter test
```

Analysis and the three existing tests passed on 2026-09-26. These checks do not
establish full device integration. See the [project README](../../README.md)
and [third-party notices](../../THIRD_PARTY_NOTICES.md).
