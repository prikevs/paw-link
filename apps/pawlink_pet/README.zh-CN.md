# PawLink Pet — 正在开发中

[English](README.md)

**Flutter 版本正在开发中，尚非稳定版本。**

该原型为 Android、macOS、Windows 和 Linux 共享宠物动画与状态逻辑，使用矢量猫和旧版六动作模型：`sleep`、`idle`、`walk`、`eat`、`groom`、`shake`。

当前界面支持手动动作预览。`PetStateSource` 是扩展接口，尚未包含完整 BLE 适配器，也不提供 macOS 原生桌宠的 HTTP 服务。所列平台是开发目标，不表示全部通过实机验证。

在本目录执行：

```sh
flutter pub get
flutter devices
flutter run -d macos
flutter analyze
flutter test
```

2026-09-26 静态检查和现有 3 项测试通过，但不代表整套设备链路已完成。详见[项目说明](../../README.zh-CN.md)与[第三方说明](../../THIRD_PARTY_NOTICES.md)。
