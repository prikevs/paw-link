# PawLink native macOS pet

使用 AppKit 透明窗口和原生菜单的桌面宠物，支持进食、跳跃、舔毛、洗脸、打滚、走路和睡觉七种动画，内置本机 HTTP 动作接口。

## 构建与启动

需要 macOS 13 或更高版本及 Apple Swift 编译工具（Xcode Command Line Tools）。从仓库根目录执行：

```sh
cd apps/pawlink_macos
./build.sh
open "PawLink Pet.app"
```

脚本将 `assets/` 中的七张图打包到应用，并编译 `PawLink.swift` 与 `API.swift`。输出为本地临时签名的 `PawLink Pet.app`，未做 Apple 公证。应用包和 `.build/` 不提交 Git；可从仓库源码重新构建，无需原工作目录或 Flutter。

## 操作

- 左键拖动猫咪调整位置。
- 右键或 Control + 单击选择动作、暂停、调整大小或退出。
- 屏幕顶部爪印菜单提供相同操作。
- 走路为原地循环，睡觉为轻微呼吸。

程序仅移除与图片边缘相连的浅色背景，保留腹部浅色毛发。毛发边缘可能存在少量白边。

## 项圈桥接与 API

启动时服务绑定 `http://127.0.0.1:8766`；右键菜单显示服务状态。API 接收已识别动作，不直接连接项圈或识别 IMU。

在仓库根目录运行：

```sh
make pet-bridge
```

桥接监听项圈 BLE 预测，并在公开动作变化时调用 `POST /v1/state`。接口、请求限制和示例见 [API.md](API.md)。本机调用示例：

```sh
curl --fail-with-body http://127.0.0.1:8766/v1/state \
  -H 'Content-Type: application/json' \
  -d '{"action":"walk"}'
```

应用启动后，在本目录执行 `python3 test_api.py`，验证七种动作、状态回读、错误请求隔离、请求限制与并发上报。测试会临时切换动作并恢复原动作。

## 素材与许可范围

运行所需图像已放入 `assets/`，原始参考照片、网页预览和旧版素材不纳入本组件。图像来源、校验值与许可范围见 [ASSETS.md](ASSETS.md)。软件与文档沿用仓库 [Apache-2.0](../../LICENSE)；图像不自动适用该软件许可证。
