# EfficientDet-Lite2 source and license

Checked on 2026-09-26. The bundled model now comes from TensorFlow's
[EfficientDet-Lite2 detection metadata, TFLite, version 1](https://www.kaggle.com/models/tensorflow/efficientdet/tfLite/lite2-detection-metadata/1?tfhub-redirect=true).
The publisher lists **Apache 2.0** as this variation's license. This is a
model-specific declaration, not an inference from the runtime's license.

## Exact artifact

- [Versioned download](https://www.kaggle.com/api/v1/models/tensorflow/efficientdet/tfLite/lite2-detection-metadata/1/download).
- Archive member: `1.tflite`.
- Bundled filename: `android/app/src/main/assets/efficientdet_lite2.tflite`.
- Size: 7,557,887 bytes.
- SHA-256: `6fd32c84ab1eb0f7e7f3a7a20a20d7df1530daa8378728f7c79571096286bd52`.
- Modification: filename only. The model bytes are unchanged.

The downloaded member, repository asset, and built debug APK asset have the
same SHA-256. The source and redistribution-license review is resolved for
this artifact. Commercial use and redistribution are permitted subject to
[Apache-2.0](../../LICENSE), including retaining the license and applicable
notices. The model's training dataset is not redistributed by this project.

The APK includes `assets/licenses/efficientdet-lite2-APACHE-2.0.txt` and
`assets/licenses/efficientdet-lite2-NOTICE.txt`. The notice records attribution
and provenance and is identified as a PawLink-prepared notice. The downloaded
archive contained only the model; no separate upstream NOTICE was present.

See [machine-readable provenance](efficientdet-lite2.json) and
[device validation](VALIDATION.md).

## Previous artifact

The previous MediaPipe int8/1 file matched Google's official download, but its
exact-artifact license evidence was incomplete. It was replaced with user
approval, without changing application inference code. Its historical hashes
and findings are retained in [the previous review](previous-mediapipe-review.json).
The old model is not included in the application or release source; a local,
ignored copy was used for the on-device comparison.
