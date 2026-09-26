# Initial source release check

Date: 2026-09-26. Scope: the main repository only; see [release scope](../RELEASE.md).

- English and Simplified Chinese project READMEs identify the release as experimental.
- Both project READMEs and both Flutter READMEs say that Flutter is under development.
- The root Apache-2.0 license and model-specific license/notice are present.
- Flutter SDK and template-image BSD license texts are retained.
- All 13 published icons match their official SDK/package source bytes. PNG metadata
  contains only a software name, color-space information and dimensions; no GPS,
  author, personal path or capture timestamp was found. The Windows ICO also matches
  the official package. No image content was modified.
- The Gradle wrapper archive contains its license and expected wrapper classes.
  The TFLite model's embedded ZIP contains only `labelmap.txt`; its model hash matches
  the reviewed Kaggle version. No private archive or recording is included.
- Final source candidates were scanned for common secret formats and home paths,
  including printable strings in binaries and decompressed JAR/TFLite ZIP entries.
  This is a pattern check, not a guarantee against every possible secret format.
- Python bridge: all 4 tests pass. The Makefile test command was corrected to run
  the test script directly, preserving its sibling-module import behavior.
- Flutter: analysis reports no issues; all 3 tests pass. This does not establish
  finished BLE integration or all-platform device support.
- Android: build and Pixel 9 Pro model checks passed in the preceding validation;
  see [model validation](model-provenance/VALIDATION.md). No Android runtime code
  changed in this final source preparation step.
- No raw data, test-fixture images/models, APKs, application bundles, signing keys,
  tool caches, local paths or private backups are intended for this source commit.
- Commit identity uses the authenticated GitHub account's numeric no-reply email.

This release contains source and reviewed model assets, not compiled app or firmware
releases. Complete linked-dependency notices and distribution-specific signing and
device validation before a later binary release. The native Mac pet, its animation
assets and enclosure designs remain outside this release and require their own import review.
