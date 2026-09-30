# Third-party materials

The root Apache-2.0 license applies to original PawLink software and documentation. It does not replace the licenses of dependencies, model files, datasets, generated platform templates, or icons. Preserve upstream notices when redistributing those materials.

## Public behavior dataset

- Author: Carolyn Dunford (2024).
- Title: Data for: Domestic cat accelerometer data calibrated with behaviours.
- Source: https://doi.org/10.5061/dryad.q2bvq83sx
- Data terms: CC0, under [Dryad's data policy](https://datadryad.org/stash/terms).
- Use: training the project random forest in `models/cat-behavior-rf.json` and its generated `firmware/self_test/model_data.h`.

The raw dataset is downloaded separately into the ignored `data/` directory. Cite the source when using the data or model. PawLink's model and evaluation are separate from the source study.

## Android detection model

- Publisher: TensorFlow / Google.
- Variation: EfficientDet-Lite2 detection metadata, TFLite, version 1.
- License: **Apache-2.0**, explicitly listed on the [model variation page](https://www.kaggle.com/models/tensorflow/efficientdet/tfLite/lite2-detection-metadata/1?tfhub-redirect=true).
- [Versioned download](https://www.kaggle.com/api/v1/models/tensorflow/efficientdet/tfLite/lite2-detection-metadata/1/download), archive member `1.tflite`.
- Bundled file: `android/app/src/main/assets/efficientdet_lite2.tflite` (renamed, bytes unchanged).
- SHA-256: `6fd32c84ab1eb0f7e7f3a7a20a20d7df1530daa8378728f7c79571096286bd52`.

Source and license verification are complete for this file. The Apache-2.0 text
and attribution are bundled under `android/app/src/main/assets/licenses/` so
they ship inside the APK. Preserve them when redistributing the model.
See [provenance](docs/model-provenance/README.md) and
[device validation](docs/model-provenance/VALIDATION.md).

## Dependencies and generated files

The dependency manifests and lockfiles are the version records:

- Python: `pyproject.toml` and `uv.lock` (Bleak, NumPy, scikit-learn and transitive dependencies).
- Android: Gradle files (AndroidX, CameraX and MediaPipe); Gradle wrapper files retain upstream notices.
- Flutter: `apps/pawlink_pet/pubspec.yaml` and `pubspec.lock`; generated platform scaffolding and default icons retain applicable upstream terms.
- Firmware: pinned Seeed board core, Seeed LSM6DS3 library and ArduinoBLE versions in `scripts/paw`.

This file is an inventory, not a complete binary dependency notice bundle. Before distributing compiled apps or firmware, collect the license and notice files for the exact linked dependencies, including the Arduino libraries and board core. The project license does not remove any of their redistribution requirements.

## Native macOS app and animation resources

The native macOS software is included under `apps/pawlink_macos/` and follows the root software license. Its seven runtime animation PNG files are existing project ImageGen outputs; their source and license scope are recorded in [ASSETS.md](apps/pawlink_macos/ASSETS.md), with SHA-256 values in the asset manifest. They do not automatically inherit the software license.

Original reference photographs and enclosure designs remain outside this repository. This import does not include compiled macOS application bundles or grant rights to those excluded components.

## Flutter templates and default icons

The platform scaffolding and default icons originate from Flutter SDK templates.
The five Android icons match the installed Flutter SDK templates. The seven
macOS icons and one Windows icon match the official `flutter_template_images`
5.0.0 package byte-for-byte. The original Flutter Authors copyright and
BSD terms are retained in [the Flutter license](licenses/FLUTTER-BSD-3-Clause.txt).
The image package
[license](licenses/FLUTTER-TEMPLATE-IMAGES-BSD-3-Clause.txt) is also retained.
The Gradle wrapper JAR retains its upstream `META-INF/LICENSE`.
