# PawLink

[简体中文](README.zh-CN.md)

PawLink is an experimental pet collar project that connects motion sensing, on-collar behavior inference, video-assisted data annotation, and pet animation.

## Project overview

| Component | Location | Purpose |
| --- | --- | --- |
| Collar firmware | `firmware/self_test/` | XIAO nRF52840 Sense firmware; 50 Hz six-axis IMU, predictions, battery readings, and clock synchronization over BLE. |
| Android Capture | `android/` | Video and IMU recording, activity candidates, manual interval annotation, and versioned exports. Current version: **0.7.3**. |
| Data and models | `collector/`, `models/` | Python collection, feature extraction, training, classification, and model artifacts. |
| Flutter pet | `apps/pawlink_pet/` | Pet animation prototype targeting Android, macOS, Windows, and Linux. |
| Tools and documentation | `scripts/`, `docs/` | Setup, firmware upload, model export, BLE bridge, hardware and protocol references. |

The collection workflow is collar IMU + phone video → Android recording → time alignment → human annotation → training export. Live display uses collar predictions → BLE bridge → the separate native macOS pet API.

## Record and annotate with Android

Requires Android 12 or later, JDK 17, and Android SDK 36. Set `JAVA_HOME` and `ANDROID_HOME`, then build and install:

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Connect the collar and choose cat recording or hand testing. Hand testing disables cat detection and stays separate from cat training data.
2. Record continuously. Keep visible synchronization events near the beginning, middle, and end when testing alignment.
3. Review the IMU activity candidates. Adjust the actual action boundaries, choose a behavior, and confirm to save and advance.
4. Use the direct controls to play the selection, its surrounding context, or the full video. The selected label interval remains separate from playback context.
5. Check saved annotations and any overlap warnings. Calibrate video / IMU correspondence before exporting training data.

Capture stores the original video, IMU samples, frame timestamps, clock observations, and device evidence separately from review drafts and saved versions. CameraX provides video capture; MediaPipe runs EfficientDet-Lite2 for visual candidates in cat mode. Candidates are suggestions, not behavior ground truth.

See the [Android guide](android/README.md) for controls, alignment requirements, files, and checks. The [0.7.3 notes](android/RELEASE-v0.7.3.md) describe the current interface.

## Set up the collar

Hardware: Seeed Studio XIAO nRF52840 **Sense**, with its onboard LSM6DS3TR-C IMU. See [hardware details](docs/hardware.md) for wiring and mounting direction.

From the repository root on an Apple Silicon Mac:

```sh
make setup
make compile
make upload
make check
```

The device advertises as `PawLink-Test`. To select a serial port explicitly:

```sh
PORT=/dev/cu.usbmodemXXXX make upload
```

The setup uses Arduino CLI 1.5.1, Seeed nRF52 mbed core 2.9.3, Seeed Arduino LSM6DS3 2.0.7, and ArduinoBLE 2.1.0. The setup script downloads a macOS ARM64 CLI and searches macOS serial ports; other hosts require their own CLI setup and an explicit `PORT`. `make check` requires connected hardware and checks initialization, IMU frames, battery readings, and predictions.

Clock synchronization uses BLE characteristic `0006`; the original 20-byte IMU packet remains unchanged. Device clock mapping and physical video correspondence points serve different purposes. See the [BLE protocol](docs/ble-protocol.md).

## Collect and train with Python

Requires Python 3.11 or later and `uv`:

```sh
uv sync --locked
make collect LABEL=walking DURATION=60
make classify
```

Recordings are stored in Git-ignored `data/`. To train from the public cat dataset, download its archive from [Dryad](https://doi.org/10.5061/dryad.q2bvq83sx):

```sh
make import-open-data ARCHIVE=/path/to/dryad-cat.zip
make train-open-cat
```

Training also exports the firmware model header. Run `make compile` and `make upload` to deploy it. `make export-model` exports an existing model without retraining; `make train-local` trains the older centroid model from local recordings.

The Android export format has not been verified with the existing Python training scripts. Event windows may have variable lengths and need appropriate preprocessing; this is not yet an automatic end-to-end training pipeline.

## Pet applications

### Flutter pet

The Flutter application is under development. It currently uses vector animation, the older six-action state model, and a `PetStateSource` adapter interface; a complete BLE adapter is not included.

Use a Flutter SDK with Dart compatible with `^3.13.4`:

```sh
cd apps/pawlink_pet
flutter pub get
flutter run -d macos
```

Use `flutter devices` to select another target. See the [Flutter app guide](apps/pawlink_pet/README.md).

### Native macOS pet bridge

The native pet and its image assets are separate local components, outside this repository. With its HTTP service running at `http://127.0.0.1:8766`:

```sh
make pet-bridge
```

The bridge updates the animation when the public action changes and retries after BLE or API failures. The public actions are `feed`, `jump`, `groom`, `wash`, `roll`, `walk`, and `sleep`. The current model maps to `feed`, `groom`, `walk`, and `sleep`; the other three are reserved. Diagnostic `collar_shake` keeps the previous animation.

## Validation and current limits

- The firmware model uses acceleration only, 32 trees, and two-second windows. Its nine-cat leave-one-cat-out baseline reports balanced accuracy **0.361** and macro F1 **0.350**. These are project results, not the source paper's results; reliable seven-behavior recognition is not established.
- Video alignment requires physical correspondence points. Timing thresholds are screening rules, not measured accuracy guarantees. Multi-cat scenes remain identity-uncertain; stable individual tracking is not implemented.
- Android 0.7.3 build, static checks, and synthetic recording tests passed on a Pixel device. This does not establish real-cat candidate accuracy or hardware timing accuracy.
- Battery percentage is a voltage estimate; software shutdown does not physically disconnect the battery.

Checks for the bridge and Flutter app:

```sh
make test-pet-bridge
cd apps/pawlink_pet
flutter analyze
flutter test
```

Android checks are documented in its [guide](android/README.md).

## Documentation

- [Hardware and wiring](docs/hardware.md)
- [BLE packet and synchronization protocol](docs/ble-protocol.md)
- [Model training and evaluation](docs/open-data-model.md)
- [Android alignment and training export](android/RELEASE-v0.7.0.md)
- [Detector provenance and validation](docs/model-provenance/VALIDATION.md)
- [Release review](docs/open-source-review.md)

## Privacy and license

Keep recordings, session exports, private photos, signing keys, and local configuration out of Git. The annotation test video is a generated color-pattern fixture, not a recording.

Original PawLink software and documentation use [Apache-2.0](LICENSE), which permits commercial use and includes a contributor patent license under its terms. Third-party code, models, and data retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md). Separate photo-derived assets and enclosure designs are outside this repository's license scope until imported with explicit notices.
