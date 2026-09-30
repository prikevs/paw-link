# PawLink Capture for Android

当前版本：**0.7.3**。同步录制手机视频和项圈 50 Hz 六轴 IMU，支持手持测试、时间校准、活动候选、人工区间标注及版本化导出。

## 构建与安装

需要 JDK 17、Android SDK 36，最低系统版本为 Android 12（API 31）。项目使用 Gradle 8.11.1、CameraX 1.4.2 和 MediaPipe Tasks Vision 1.0.0。

配置 `JAVA_HOME` 与 `ANDROID_HOME` 后，在本目录执行：

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

设备广播名称为 `PawLink-Test`。新版同步需要支持 BLE `0006` 通道的项圈固件；旧固件可保留原始采集，但不能通过新版训练同步检查。

## 采集与标注

1. 连接项圈，选择猫模式或手持测试。猫模式记录猫 ID 与佩戴方式；手持测试关闭猫检测，使用静置、平移、旋转、轻晃和未知标签。
2. 连续录制视频和 IMU。猫模式使用 EfficientDet-Lite2 int8，通过 MediaPipe 生成视觉候选；视觉与 IMU 候选都不会自动成为训练真值。
3. 打开记录，自动进入第一段未处理 IMU 候选并播放前后背景。调整蓝色起止点或逐帧设定边界，选择行为并确认；先保存，再进入下一段。
4. 顶部直接查看片段列表和已标注区间。当前状态显示待标注、已标注、已排除、已跳过、待重新确认或修改尚未保存；编辑已有区间时使用「保存修改」。
5. 重叠提示列出已有行为、原始范围和实际交叉区间。点击冲突项可进入原标注修改，不自动覆盖其他标注。

播放选段在人工起止点之间循环；播放前后使用可扩展的预览范围；播放全片从头开始，到结尾停止。前后余量不会写入标签。底部操作条可左右滑动，直接提供时间调整、排除、撤销、拆分、合并、同步、保存和导出等功能，没有「更多」入口。

持续行为标记完整区间，例如 20 秒进食只需标注一次。跳跃或打滚标记完整动作，并核对前后背景。拆分、合并或改变校准后需要重新确认。

## 时间对齐与导出条件

设备连接后执行往返时钟同步，之后每 30 秒重复。原始设备时间和同步观测均保留；分析时按 epoch 映射设备采样时间，接收时间不作为已校准采样时间。

设备时钟同步不等于视频对齐。用时间轴和逐帧控件定位同一物理事件，通过「同步」添加至少三个视频 / IMU 对应点，跨度至少 10 秒。另用未参与拟合的事件验证对齐。

训练导出要求猫模式、完整设备时钟映射、合格的视频校准、合计估计误差不超过 100 ms、人工确认且未排除的有效行为，以及连续 IMU 覆盖。只导出校准点覆盖范围内的窗口。阈值是保守筛选规则，不是实测精度保证。

持续行为采用两秒窗口、一秒步长，并按估计同步误差内缩边界。短事件保留完整动作和背景，较长事件允许变长窗口；未标背景不会获得事件的逐样本标签。手持测试只能导出原始资料，不进入猫训练集。

## 保存文件

每次采集保存在应用专属目录：

```text
Android/data/com.pawlink.capture/files/sessions/session-YYYYMMDD-HHMMSS-SSS/
├── video.mp4
├── imu.csv
├── frames.csv
├── labels.csv
├── vision.csv
├── evidence.csv
├── sync.csv
├── manifest.json
├── review-v3.json
└── reviews/revision-UUID/
    ├── review.json
    ├── segments.csv
    ├── windows.csv
    ├── imu_training.csv
    └── summary.json
```

采集文件保持原始内容；`review-v3.json` 是原子保存的工作草稿，显式保存生成独立复核版本。原始 ZIP 包含录像、原始数据、同步观测和复核版本；训练 ZIP 包含窗口、样本、边界、摘要和来源信息。旧 v2 草稿可迁移浏览，区间需重新确认。

## 检查

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
```

纯 Java 检查不需要手机：

```sh
CHECK_DIR=$(mktemp -d)
javac -d "$CHECK_DIR" app/src/main/java/com/pawlink/capture/{ClockSync,VideoAlignment,ImuData,ReviewQueue}.java tests/{CaptureAnalysisCheck,CaptureFixtureCheck,ReviewQueueCheck}.java
java -cp "$CHECK_DIR" com.pawlink.capture.CaptureAnalysisCheck
java -cp "$CHECK_DIR" com.pawlink.capture.CaptureFixtureCheck
java -cp "$CHECK_DIR" com.pawlink.capture.ReviewQueueCheck
```

手机解锁且开启 USB 调试后，可使用独立的彩色图案视频验证标注流程：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e mode review com.pawlink.capture.test/com.pawlink.capture.ModelSmokeInstrumentation
```

测试创建唯一的 `session-ui-check-*` 合成记录，不编辑已有记录；检查后仅清理本次创建的测试记录。默认模式的检测器检查需另外准备外部模型测试图片，详见[模型验证记录](../docs/model-provenance/VALIDATION.md)。

0.7.3 的构建、静态检查和 Pixel 真机合成录像流程检查已通过。真实猫行为、硬件采样年龄、实际同步误差、长录像和断线恢复仍需相应设备测试。

## 版本说明

- [0.7.3：直接操作、已有标注状态和具体重叠提示](RELEASE-v0.7.3.md)
- [0.7.2：选段与全片播放、异步定位修复](RELEASE-v0.7.2.md)
- [0.7.1：逐段标注与进度恢复](RELEASE-v0.7.1.md)
- [0.7.0：设备同步、视频校准与训练语义](RELEASE-v0.7.0.md)
- [0.6.1：记录删除](RELEASE-v0.6.1.md)

协议见 [BLE 文档](../docs/ble-protocol.md)，模型来源和许可见[验证记录](../docs/model-provenance/VALIDATION.md)。
