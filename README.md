# PawLink

[简体中文](README.zh-CN.md)

**Status: Experimental source release. The Flutter version is under development.**

PawLink is an experimental pet collar project. It collects motion data, runs a small behavior model on the collar, and provides tools to record and review training data.

## Components

| Directory | Purpose |
| --- | --- |
| `firmware/self_test/` | Firmware for Seeed Studio XIAO nRF52840 Sense. Sends 50 Hz IMU data and behavior predictions over USB and BLE. |
| `collector/` | Python tools for data collection, feature extraction, training, and live classification. |
| `models/` | Random forest model and an older centroid model. |
| `android/` | PawLink Capture 0.6.1. Records video and IMU data. Supports manual review and training-data export. |
| `apps/pawlink_pet/` | **Under development.** Flutter pet prototype targeting Android, macOS, Windows, and Linux. |
| `scripts/` | Tool setup, firmware upload, model export, serial checks, and BLE-to-pet bridge. |
| `docs/` | Hardware, BLE protocol, model results, and release checks. |

The native macOS pet, its image assets, and enclosure designs are separate local components. They are not included in this repository yet. The BLE bridge needs the native pet's HTTP service. The Flutter prototype does not provide that service.

## Data flow

- Live display: collar IMU → firmware model → BLE bridge → native macOS pet API.
- Data collection: collar IMU + phone video → Android recording → manual review → exported training data.

The new Android export format has not been verified with the existing Python training scripts. This is not yet an automatic training pipeline.

## Hardware and tools

- Seeed Studio XIAO nRF52840 **Sense**, with its LSM6DS3TR-C IMU.
- Python 3.11 or later and `uv` for Python tools.
- Arduino CLI 1.5.1, Seeed nRF52 mbed core 2.9.3, Seeed Arduino LSM6DS3 2.0.7, ArduinoBLE 2.1.0.
- Android: JDK 17, Android SDK 36, Gradle 8.11.1. The app requires Android 12 or later.
- Flutter: use a Flutter SDK with Dart compatible with `^3.13.4`.

The firmware setup script downloads the **macOS ARM64** Arduino CLI. Its automatic serial-port search also targets macOS. Other hosts need a suitable CLI and an explicit `PORT`; they are not supported by the current setup script.

See [hardware details](docs/hardware.md) for wiring and mounting direction.

## Build and upload firmware

Run these commands from the repository root on an Apple Silicon Mac:

```sh
make setup
make compile
make upload
make check
```

The device advertises as `PawLink-Test`. Set `PORT` if more than one serial device is connected:

```sh
PORT=/dev/cu.usbmodemXXXX make upload
```

`make check` reads the connected device. It checks initialization, IMU frames, battery data, and predictions. It requires real hardware.

## Collect and classify data

```sh
uv sync --locked
make collect LABEL=walking DURATION=60
make classify
```

Recordings are stored in `data/`, which Git ignores. See the [BLE protocol](docs/ble-protocol.md) for packet formats.

To train from the public cat dataset, download its archive from [Dryad](https://doi.org/10.5061/dryad.q2bvq83sx), then run:

```sh
make import-open-data ARCHIVE=/path/to/dryad-cat.zip
make train-open-cat
```

`make train-open-cat` trains the model and exports its firmware header. Run `make compile` and `make upload` to deploy it. `make export-model` exports the saved model without retraining. `make train-local` trains the older centroid model from local recordings.

## Build Android Capture

Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to your Android SDK directory. Then run:

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app uses CameraX and an EfficientDet-Lite2 model through MediaPipe. It saves video, IMU samples, frame timestamps, visual candidates, and device evidence. Manual review creates versioned results and can export training files. Version 0.6.1 adds session deletion.

Video alignment uses receive timestamps and a manual offset. It does not provide a measured timing guarantee. Multi-cat scenes are marked as uncertain; the app does not track a stable identity for each cat. See [0.6.0 notes](android/RELEASE-v0.6.0.md) and [0.6.1 notes](android/RELEASE-v0.6.1.md).

## Run the Flutter prototype

```sh
cd apps/pawlink_pet
flutter pub get
flutter run -d macos
```

Use `flutter devices` to select another target. This prototype uses vector animation and the older six-action state model. Its `PetStateSource` interface supports future adapters; a complete BLE adapter is not included.

## Use the native pet bridge

With the separate native macOS pet running on `http://127.0.0.1:8766`:

```sh
make pet-bridge
```

The bridge sends an update when the public action changes. It retries after BLE or API failures. The API is local to the Mac.

The public action names are `feed`, `jump`, `groom`, `wash`, `roll`, `walk`, and `sleep`. The current model maps only to `feed`, `groom`, `walk`, and `sleep`. The other three actions are reserved. `collar_shake` is diagnostic and keeps the previous animation.

## Model limits

The model uses acceleration only. It has 32 trees and uses two-second windows. Its source dataset contains nine cats. The recorded leave-one-cat-out evaluation reports balanced accuracy **0.361** and macro F1 **0.350**. Rare behaviors did not generalize to unseen cats. These are project baseline results, not the results of the source paper.

See [model details](docs/open-data-model.md). The project does not yet provide reliable seven-behavior recognition. Battery percentage is a voltage estimate. Software shutdown does not physically disconnect the battery.

The Android detector uses the Apache-2.0 Kaggle model variation. Its source, APK notices, and Pixel 9 Pro checks are recorded in [model validation](docs/model-provenance/VALIDATION.md).

## Checks

```sh
make test-pet-bridge
cd apps/pawlink_pet
flutter analyze
flutter test
```

Android storage checks are described in the [0.6.1 notes](android/RELEASE-v0.6.1.md). Hardware, camera, timing, and multi-platform behavior need separate device tests.

## Privacy and license

Do not commit recordings, session exports, private photos, signing keys, or local configuration. See [release review](docs/open-source-review.md).

Original PawLink software and documentation are licensed under [Apache-2.0](LICENSE). This permits commercial use and includes a contributor patent license, subject to its terms. Third-party code, models, and data retain their own licenses. See [third-party notices](THIRD_PARTY_NOTICES.md). Separate photo-derived assets and enclosure files are outside this repository's license scope until they are imported with explicit notices.
