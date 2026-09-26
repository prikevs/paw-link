# Android model replacement validation

Date: 2026-09-26. Device: Pixel 9 Pro, Android 17 preview/userdebug build.
Runtime: MediaPipe Tasks Vision 1.0.0. App: current 0.6.1 debug build with the
approved model replacement. This is not a signed production release.

## Results

- `assembleDebug` and `assembleAndroidTest`: passed.
- APK model hash matches the licensed Kaggle download: passed.
- Full Apache-2.0 text and model attribution included in APK assets: passed.
- Old model and downloaded test image absent from application APK: passed.
- Model input: uint8 RGB `[1,448,448,3]`; embedded `labelmap.txt` includes
  `person`, `cat`, and `dog`. MediaPipe loads the metadata and detection outputs.
- Unfiltered test: cats and dog detected. One dog was labeled bird at 0.258;
  this fixture is not evidence of general model accuracy.
- App's `cat`/`person` allowlist: passed; dog and bird filtered out.
- Finite scores and positive boxes intersecting the input image: passed.
- Box scaling at half-resolution: matched cat boxes have IoU 0.981 and 0.937.
  Objects were matched by geometry, not confidence rank.
- Blank gray image: no detections above the configured threshold.
- Actual `VisionAnalyzer` result conversion: passed. Repeated still-image
  results produce a visible cat and the expected `rest` suggestion.

## Timing

Five warm-up calls, then 20 measured calls per model on the same 1200×600 image.
CPU/default MediaPipe options; VIDEO mode, threshold 0.20, max 10 results,
`cat`/`person` allowlist. Each measured call includes a bitmap copy and image
wrapping, inference and output conversion; it excludes camera capture and UI.
Models were measured sequentially, not in a randomized benchmark.

| Model | Median | p95 | Maximum |
| --- | ---: | ---: | ---: |
| Previous MediaPipe artifact | 411.6 ms | 423.3 ms | 425.7 ms |
| New Kaggle artifact | 235.2 ms | 258.7 ms | 290.8 ms |

The new model was about 43% faster in this run. Its median still exceeds the
application's 200 ms analysis interval. That interval limits how often frames
are selected; it does not guarantee 5 processed frames per second. No timing
settings or behavior thresholds were changed to hide this limitation.

This is a fixed-image compatibility check, not a live-camera, orientation,
long-session, power-use, or real-cat accuracy benchmark. It checks detection
coordinates and result conversion, not a screenshot of the rendered overlay.
The camera processing code was unchanged. Existing sessions were not cleared.

## Reproduce

The test runner is
`android/app/src/androidTest/java/com/pawlink/capture/ModelSmokeInstrumentation.java`.
Download fixtures separately into the ignored directory
`android/app/src/androidTest/assets/model-test/`:

| Filename | Source | SHA-256 |
| --- | --- | --- |
| `cats_and_dogs.jpg` | https://storage.googleapis.com/mediapipe-assets/cats_and_dogs.jpg | `a2eaa7ad3a1aae4e623dd362a5f737e8a88d122597ecd1a02b3e1444db56df9c` |
| `previous.tflite` | https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/1/efficientdet_lite2.tflite | `b3f50554cb0ea559e90328845f7d9ba4d13c8bff372914d24e06bc8bb72fa896` |

With JDK 17 and the Android SDK configured, from `android/`:

```sh
./gradlew assembleDebug assembleAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.pawlink.capture.test/com.pawlink.capture.ModelSmokeInstrumentation
```

This custom instrumentation runner prints `PASS` or `FAIL`; inspect that text,
not only the shell exit status. The fixture image and old model are for local
tests only and are not covered by the new model's redistribution review.
See [captured output](device-test.txt).
