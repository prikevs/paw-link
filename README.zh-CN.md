# PawLink

[English](README.md)

**状态：实验性源码版本。Flutter 版本正在开发中。**

PawLink 是一个实验性宠物项圈项目。它采集运动数据，在项圈上运行轻量行为模型，并提供训练数据的录制与复核工具。

## 组件

| 目录 | 用途 |
| --- | --- |
| `firmware/self_test/` | Seeed Studio XIAO nRF52840 Sense 固件，通过 USB 和 BLE 输出 50 Hz IMU 数据与行为预测。 |
| `collector/` | Python 数据采集、特征提取、训练与实时分类工具。 |
| `models/` | 随机森林模型及旧版质心模型。 |
| `android/` | PawLink Capture 0.6.1，支持视频和 IMU 录制、人工复核及训练数据导出。 |
| `apps/pawlink_pet/` | **正在开发中。** 面向 Android、macOS、Windows 和 Linux 的 Flutter 宠物原型。 |
| `scripts/` | 工具安装、固件烧录、模型导出、串口检查及 BLE 桌宠桥接。 |
| `docs/` | 硬件、BLE 协议、模型结果与发布检查文档。 |

macOS 原生桌宠、图片素材和外壳设计目前是独立的本地组件，尚未纳入本仓库。BLE 桥接需要原生桌宠提供的 HTTP 服务；Flutter 原型不提供该服务。

## 数据流程

- 实时显示：项圈 IMU → 固件模型 → BLE 桥接 → macOS 原生桌宠 API。
- 数据采集：项圈 IMU + 手机视频 → Android 录制 → 人工复核 → 导出训练数据。

Android 新导出格式尚未与现有 Python 训练脚本完成兼容验证，目前不是自动训练流水线。

## 硬件与工具

- Seeed Studio XIAO nRF52840 **Sense**，使用板载 LSM6DS3TR-C IMU。
- Python 3.11 或更高版本，以及 `uv`。
- Arduino CLI 1.5.1、Seeed nRF52 mbed core 2.9.3、Seeed Arduino LSM6DS3 2.0.7、ArduinoBLE 2.1.0。
- Android：JDK 17、Android SDK 36、Gradle 8.11.1；手机需 Android 12 或更高版本。
- Flutter：使用 Dart 版本满足 `^3.13.4` 的 Flutter SDK。

固件安装脚本下载的是 **macOS ARM64** 版 Arduino CLI，串口自动查找也面向 macOS。其他系统需要匹配的 CLI 并显式设置 `PORT`，当前安装脚本尚不支持这些系统。

接线与安装方向见[硬件说明](docs/hardware.md)。

## 构建与烧录固件

在 Apple Silicon Mac 上，从仓库根目录执行：

```sh
make setup
make compile
make upload
make check
```

设备广播名称为 `PawLink-Test`。连接多个串口设备时，可显式指定端口：

```sh
PORT=/dev/cu.usbmodemXXXX make upload
```

`make check` 读取连接的设备，检查初始化、IMU 数据帧、电池数据和预测结果，需要真实硬件。

## 采集与分类

```sh
uv sync --locked
make collect LABEL=walking DURATION=60
make classify
```

采集数据保存在 Git 忽略的 `data/` 目录中。数据帧格式见 [BLE 协议](docs/ble-protocol.md)。

如需用公开猫数据集训练，从 [Dryad](https://doi.org/10.5061/dryad.q2bvq83sx) 下载压缩包后执行：

```sh
make import-open-data ARCHIVE=/path/to/dryad-cat.zip
make train-open-cat
```

`make train-open-cat` 训练模型并导出固件头文件；随后执行 `make compile` 和 `make upload` 部署。`make export-model` 仅导出现有模型。`make train-local` 使用本地采集数据训练旧版质心模型。

## 构建 Android Capture

将 `JAVA_HOME` 指向 JDK 17，将 `ANDROID_HOME` 指向 Android SDK，然后执行：

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

应用使用 CameraX，并通过 MediaPipe 运行 EfficientDet-Lite2 模型。它保存视频、IMU、帧时间戳、视觉候选和设备证据。人工复核生成版本化结果，并可导出训练文件。0.6.1 增加了会话删除功能。

视频同步基于接收时间戳和人工偏移校正，不提供已测定的时间精度保证。多猫场景会标记身份不确定，不支持稳定的单猫身份跟踪。详见 [0.6.0 说明](android/RELEASE-v0.6.0.md)和 [0.6.1 说明](android/RELEASE-v0.6.1.md)。

## 运行 Flutter 原型

```sh
cd apps/pawlink_pet
flutter pub get
flutter run -d macos
```

通过 `flutter devices` 查看其他运行目标。原型使用矢量动画和旧版六动作状态模型；`PetStateSource` 是后续适配接口，目前不包含完整 BLE 适配器。

## 使用原生桌宠桥接

先启动独立的 macOS 原生桌宠，使其在 `http://127.0.0.1:8766` 提供服务，再执行：

```sh
make pet-bridge
```

桥接只在公开动作变化时发送更新，并在 BLE 或 API 故障后重试。API 仅供 Mac 本机访问。

公开动作名为 `feed`、`jump`、`groom`、`wash`、`roll`、`walk`、`sleep`。当前模型仅映射输出 `feed`、`groom`、`walk`、`sleep`，另外三种为预留动作。`collar_shake` 仅供诊断，保持上一动画。

## 模型边界

模型仅使用加速度，包含 32 棵树，窗口长度为两秒。来源数据集包含九只猫。已有留一猫验证记录的平衡准确率为 **0.361**，macro F1 为 **0.350**；稀有行为未能泛化到未见猫。这些是本项目基线结果，不是来源论文的结果。

详见[模型说明](docs/open-data-model.md)。项目尚不具备可靠的七类行为识别能力。电量百分比是电压估计，软件关机也不等于物理断电。

Android 检测器已替换为明确采用 Apache-2.0 的 Kaggle 模型版本。来源、APK 授权声明及 Pixel 9 Pro 测试结果见[模型验证记录](docs/model-provenance/VALIDATION.md)。

## 检查

```sh
make test-pet-bridge
cd apps/pawlink_pet
flutter analyze
flutter test
```

Android 存储检查见 [0.6.1 说明](android/RELEASE-v0.6.1.md)。硬件、摄像头、同步精度及跨平台行为仍需分别进行设备测试。

## 隐私与许可证

不要提交采集录像、会话导出、私人照片、签名密钥或本机配置。详见[发布检查](docs/open-source-review.md)。

PawLink 原创软件和文档采用 [Apache-2.0](LICENSE)，依其条款允许商业使用，并提供贡献者专利许可。第三方代码、模型与数据保留各自许可证，详见[第三方说明](THIRD_PARTY_NOTICES.md)。尚在仓库外的照片衍生素材与外壳文件，需在导入时明确授权，不自动适用本仓库许可证。
