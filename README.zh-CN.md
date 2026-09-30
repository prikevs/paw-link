# PawLink

[English](README.md)

PawLink 是一个实验性宠物项圈项目，连接运动数据采集、项圈端行为推理、视频辅助标注和宠物动画。

## 项目概览

| 组件 | 目录 | 用途 |
| --- | --- | --- |
| 项圈固件 | `firmware/self_test/` | XIAO nRF52840 Sense 固件，通过 BLE 输出 50 Hz 六轴 IMU、预测、电池读数和时钟同步信息。 |
| Android Capture | `android/` | 视频与 IMU 录制、活动候选、人工区间标注和版本化导出。当前版本：**0.7.3**。 |
| 数据与模型 | `collector/`、`models/` | Python 采集、特征提取、训练、分类和模型文件。 |
| macOS 原生桌宠 | `apps/pawlink_macos/` | AppKit 桌面宠物，提供七种动画和本机 HTTP 动作接口。 |
| Flutter 宠物 | `apps/pawlink_pet/` | 面向 Android、macOS、Windows 和 Linux 的宠物动画原型。 |
| 工具与文档 | `scripts/`、`docs/` | 环境安装、固件烧录、模型导出、BLE 桥接、硬件和协议说明。 |

采集流程：项圈 IMU + 手机视频 → Android 录制 → 时间对齐 → 人工标注 → 训练导出。实时展示流程：项圈预测 → BLE 桥接 → 独立的 macOS 原生桌宠 API。

## 使用 Android 采集与标注

需要 Android 12 或更高版本、JDK 17 和 Android SDK 36。配置 `JAVA_HOME` 与 `ANDROID_HOME` 后构建并安装：

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 连接项圈，选择猫采集或手持测试。手持测试关闭猫检测，数据独立保存，不进入猫训练集。
2. 连续录制；验证对齐时，在开始、中间、结束保留视频可见的同步动作。
3. 查看 IMU 活动候选，调整真实动作起止点，选择行为，确认后自动保存并进入下一段。
4. 直接播放选定区间、前后余量或完整视频；播放背景与人工标签边界相互独立。
5. 查看已有标注和重叠提示；完成视频与 IMU 对应点校准后再导出训练资料。

应用分别保存原始视频、IMU、帧时间戳、时钟观测和设备证据，以及人工草稿与复核版本。视频使用 CameraX；猫模式通过 MediaPipe 运行 EfficientDet-Lite2 生成视觉候选。候选仅供辅助，不是行为真值。

操作、对齐条件、文件与验证方法见 [Android 使用说明](android/README.md)，当前界面变化见 [0.7.3 说明](android/RELEASE-v0.7.3.md)。

## 配置项圈

硬件采用 Seeed Studio XIAO nRF52840 **Sense** 和板载 LSM6DS3TR-C IMU。接线与安装方向见[硬件说明](docs/hardware.md)。

在 Apple Silicon Mac 上，从仓库根目录执行：

```sh
make setup
make compile
make upload
make check
```

设备广播名称为 `PawLink-Test`。需要指定串口时：

```sh
PORT=/dev/cu.usbmodemXXXX make upload
```

环境使用 Arduino CLI 1.5.1、Seeed nRF52 mbed core 2.9.3、Seeed Arduino LSM6DS3 2.0.7 和 ArduinoBLE 2.1.0。安装脚本下载 macOS ARM64 CLI，并查找 macOS 串口；其他系统需自行配置 CLI 并显式指定 `PORT`。`make check` 需要真实硬件，用于检查初始化、IMU、电池和预测输出。

BLE `0006` 通道用于时钟同步，原有 20 字节 IMU 帧保持不变。设备时钟映射与视频物理对应点校准解决不同的对齐问题，详见 [BLE 协议](docs/ble-protocol.md)。

## 使用 Python 采集与训练

需要 Python 3.11 或更高版本及 `uv`：

```sh
uv sync --locked
make collect LABEL=walking DURATION=60
make classify
```

采集数据保存在 Git 忽略的 `data/`。使用公开猫数据集训练时，从 [Dryad](https://doi.org/10.5061/dryad.q2bvq83sx) 下载压缩包：

```sh
make import-open-data ARCHIVE=/path/to/dryad-cat.zip
make train-open-cat
```

训练会同时导出固件模型头文件，随后执行 `make compile` 和 `make upload` 部署。`make export-model` 仅导出现有模型；`make train-local` 使用本地数据训练旧版质心模型。

Android 导出格式尚未与现有 Python 训练脚本完成兼容验证。事件窗口可能变长，需要适当预处理，目前不是自动贯通的训练流水线。

## 宠物应用

### Flutter 宠物

Flutter 应用正在开发中。目前使用矢量动画、旧版六动作状态模型和 `PetStateSource` 适配接口，尚未包含完整 BLE 适配器。

使用 Dart 版本满足 `^3.13.4` 的 Flutter SDK：

```sh
cd apps/pawlink_pet
flutter pub get
flutter run -d macos
```

通过 `flutter devices` 选择其他运行目标，详见 [Flutter 应用说明](apps/pawlink_pet/README.zh-CN.md)。

### macOS 原生桌宠桥接

从仓库构建并启动原生桌宠：

```sh
cd apps/pawlink_macos
./build.sh
open "PawLink Pet.app"
```

需要 macOS 13 或更高版本与 Apple Swift 工具。详见[原生桌宠说明](apps/pawlink_macos/README.md)和 [HTTP API](apps/pawlink_macos/API.md)。服务启动在 `http://127.0.0.1:8766` 后，从仓库根目录执行：

```sh
make pet-bridge
```

桥接在公开动作变化时更新动画，并在 BLE 或 API 故障后重试。公开动作是 `feed`、`jump`、`groom`、`wash`、`roll`、`walk`、`sleep`。当前模型映射输出 `feed`、`groom`、`walk`、`sleep`，其他三种为预留动作；诊断动作 `collar_shake` 保持上一动画。

## 验证与当前边界

- 固件模型仅使用加速度，包含 32 棵树，窗口长度为两秒。九只猫的留一猫基线验证记录：平衡准确率 **0.361**、macro F1 **0.350**。这些是本项目结果，不是来源论文结果，尚未建立可靠的七类行为识别能力。
- 视频对齐需要物理对应点。误差阈值是筛选规则，不是实测精度保证。多猫场景仍有身份不确定性，未实现稳定的单猫跟踪。
- Android 0.7.3 的构建、静态检查和 Pixel 真机合成录像检查已通过；不代表真实猫行为候选准确度或硬件同步精度已获验证。
- 电量百分比是电压估计，软件关机不等于物理断电。

桥接与 Flutter 检查：

```sh
make test-pet-bridge
cd apps/pawlink_pet
flutter analyze
flutter test
```

Android 检查方法见其[使用说明](android/README.md)。

## 文档导航

- [硬件与接线](docs/hardware.md)
- [BLE 数据帧和同步协议](docs/ble-protocol.md)
- [模型训练与评估](docs/open-data-model.md)
- [Android 对齐与训练导出](android/RELEASE-v0.7.0.md)
- [检测器来源与验证](docs/model-provenance/VALIDATION.md)
- [发布检查](docs/open-source-review.md)

## 隐私与许可证

采集录像、会话导出、私人照片、签名密钥和本机配置不提交 Git。标注测试视频是生成的彩色图案，不是实际录像。

PawLink 原创软件和文档采用 [Apache-2.0](LICENSE)，依条款允许商业使用并提供贡献者专利许可。第三方代码、模型和数据保留各自许可证，见[第三方说明](THIRD_PARTY_NOTICES.md)。原生桌宠的生成动画资源有单独的[素材范围说明](apps/pawlink_macos/ASSETS.md)。原始参考照片和外壳设计仍在仓库外。
