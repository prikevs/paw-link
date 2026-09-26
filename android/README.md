# PawLink Capture for Android

当前版本：**0.6.1**。完整项目介绍见 [English](../README.md) / [简体中文](../README.zh-CN.md)。新增功能与边界见 [0.6.0](RELEASE-v0.6.0.md) 和 [0.6.1](RELEASE-v0.6.1.md)。以下保留早期版本沿革。

同步录制手机摄像头画面和 XIAO nRF52840 Sense 的 50 Hz BLE IMU 数据。

## 当前功能

- CameraX 后置摄像头预览和 720p 视频录制
- 扫描并连接 `PawLink-Test`
- 解析 PawLink 20 字节 BLE IMU 帧
- 保存视频、IMU、摄像头帧时间戳和人工标签事件
- EfficientDet-Lite2 端侧猫咪检测和视觉运动建议
- `rest`、`locomotion`、`feed`、`groom`、`collar_shake`、`unknown` 快捷标签
- 使用 `sequence` 统计 IMU 丢包

每次采集写入 App 专属目录下的独立会话：

```text
Android/data/com.pawlink.capture/files/sessions/session-YYYYMMDD-HHMMSS/
├── video.mp4
├── imu.csv
├── frames.csv
├── labels.csv
├── vision.csv
└── manifest.json
```

`frames.csv` 同时记录 CameraX 图像时间戳和手机单调时钟。`vision.csv` 保存猫咪可见性、检测置信度、运动强度与 `rest`/`locomotion` 建议。建议不会直接成为训练标签；在相机画面上点击建议后，标签来源会记录为 `vision_accepted`。

`feed`、`groom` 和 `collar_shake` 需要后续使用已复核的视频片段训练时序动作模型，当前仍由人工标记。

为便于诊断屏幕图片、反光和远距离目标，0.2.1 会在未检测到猫时显示模型置信度最高的其他 COCO 类别。检测器保留分数不低于 20% 的前五个候选，但只有 `cat` 类会进入动作建议逻辑。

0.2.2 修复了部分 Android 设备 RGBA 相机帧包含行填充时，模型输入逐行错位的问题。相机帧现在通过 CameraX 的标准转换接口生成紧凑位图后再送入 MediaPipe。

0.3.0 在实时预览中绘制检测框、对象类别和置信度。猫、人、狗和其他候选对象使用不同颜色显示；动作建议仍只依据置信度最高的猫。

0.3.1 让预览、录像和图像分析共用 CameraX 视口，保证检测框坐标与竖屏预览的裁切区域一致。

0.3.2 修复目标框延伸到画面外时标签被裁掉的问题，将标签自动放到目标框的可见边缘。

0.3.3 按 MediaPipe 官方 Android 示例在推理前裁切并旋转相机位图，检测框直接按旋转后图像尺寸映射到共享视口，避免边界框发生 90° 坐标错位。

0.4.0 将检测模型升级为 448×448 的 EfficientDet-Lite2 int8，并将最多返回结果从 5 个提高到 10 个，以改善多猫与小目标场景的检测精度。

0.4.1 将对象检测输出限制为 `cat` 和 `person`。其他 COCO 类别不会显示或写入视觉候选结果，动作建议仍只使用置信度最高的猫。

0.5.0 在采集过程中自动写入 `rest`、`locomotion` 和猫离开画面后的 `unknown` 粗标签；新增“采集记录 / 人工复核”入口，可逐段播放、保留或修改粗标签。复核结果保存在 `labels_reviewed.csv`，并生成可直接训练使用的 `imu_reviewed.csv`，原始文件保持不变。

## 构建

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

项目使用 Android 36、JDK 17、Gradle 8.11.1、CameraX 1.4.2 和 MediaPipe Tasks Vision 1.0.0。最低系统版本为 Android 12（API 31）。

当前模型使用 TensorFlow 在 Kaggle 发布的 EfficientDet-Lite2 detection metadata / TFLite / version 1，明确采用 Apache-2.0。文件保持原样，仅改名为应用使用的文件名。来源、校验值及 APK 内授权声明见[第三方说明](../THIRD_PARTY_NOTICES.md)，真机测试与速度对比见[验证记录](../docs/model-provenance/VALIDATION.md)。
