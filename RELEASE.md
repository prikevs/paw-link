# Initial experimental source release

[中文说明](#中文说明)

This release contains collar firmware source, Python collection/training tools,
Android Capture source and its licensed model, the BLE bridge, and the Flutter
pet prototype. **The Flutter version is under development.**

The native macOS pet, its animation assets, and enclosure designs are not part
of this source release. The BLE bridge needs the separate native pet service.
Raw recordings, personal photos, local test fixtures, backups, APKs and compiled
firmware are excluded. No compiled distribution is being released in this step.

The model's limits and platform limits are documented in the bilingual README.
APK or firmware releases need their full compiled-dependency notice bundle and
appropriate signing/device checks. Those are separate from this source release.

Validation: Flutter analysis and 3 tests; Python bridge 4 tests; the previous
Android build and Pixel 9 Pro model check. Firmware was not rebuilt in this
final documentation pass. See `docs/source-release-check.md`.

## 中文说明

首版为实验性源码版本，包括项圈固件源码、Python 采集与训练工具、Android
采集端及已核实授权的模型、BLE 桥接和 Flutter 宠物原型。
**Flutter 版本正在开发中。**

Mac 原生桌宠、动画素材与外壳设计暂不纳入本次发布。原始录像、私人照片、
本地测试素材、备份、APK 和编译后的固件均不发布。模型和平台限制已写入双语
README；后续二进制发布需另外补齐依赖声明、签名与设备检查。
