# 项圈动作 → 桌面宠物 API v1

启动新版 **PawLink Pet.app** 后，内置服务地址为 `http://127.0.0.1:8766`。右键菜单底部显示服务状态。退出应用后服务停止；端口被占用时菜单会显示启动失败。

## 数据链路

项圈通过 BLE/USB 把数据送到 Mac 上的接收程序；接收程序完成动作识别后，调用本 API。API 接收的是**已识别的动作标签**，不直接识别加速度/陀螺仪数据，也不负责连接项圈。

服务只绑定本机回环地址。手机或项圈不能直接请求这里的 127.0.0.1；需要 Mac 接收/中转程序。当前没有开放局域网访问，也不支持网页跨域调用。

## 更新动作

`POST /v1/state`，必须使用 `Content-Type: application/json`。

```sh
curl --fail-with-body http://127.0.0.1:8766/v1/state \
  -H 'Content-Type: application/json' \
  -d '{"action":"walk"}'
```

返回 HTTP 200，例如：

```json
{"action":"walk","label":"走路","paused":false,"source":"collar","updated_at":"2026-09-26T01:00:00Z"}
```

| action | 动作 |
|---|---|
| `feed` | 吃饱喝足，保留食物碗；进食/喝水识别结果均可映射到此动作 |
| `jump` | 跳起来玩 |
| `groom` | 舔毛 |
| `wash` | 洗脸 |
| `roll` | 左右打滚 |
| `walk` | 走路（原地循环） |
| `sleep` | 睡觉 |

切换到不同动作时从首帧播放；重复报告同一动作时继续当前动画。更新会解除暂停。右键操作与 API 都立即生效，后到的操作优先；`source` 为 `manual` 或 `collar`。`updated_at` 是 Mac 接受操作的 UTC 时间，不是项圈采样时间。

连接断开或停止上报时保留最后动作。固件 v5 的 BLE 预测值格式为
`action,margin,latency_us,window,source_label`。其中 `action` 已经是本 API 的七种公开动作名，
接收程序无需再把 `rest`、`locomotion` 等内部分类标签映射一次；`source_label` 仅用于调试。
识别程序应自行过滤低置信度、短暂抖动和过期事件；建议只在稳定动作变化时发送。不能判断时不要发送未知标签。网络失败后重发同一动作是安全的。

## 查询

- `GET /health`：服务健康，返回 `{"api_version":1,"status":"ok"}`。
- `GET /v1/actions`：可用动作及中文名称。
- `GET /v1/state`：当前动作、暂停状态、来源、更新时间。

请求体最大 4096 字节，使用 Content-Length，不支持分块传输。每个连接处理一次请求，5 秒超时。

错误：400（JSON/动作无效）、403（非本机 Host 或带 Origin 的网页请求）、404（路径不存在）、405（方法不支持）、411（缺少 Content-Length）、413（请求过大/长度无效）、415（不是 JSON）。失败请求不改变动作。

## Python 接收程序示例

标准库即可使用。把 `report_action()` 放在 BLE/USB 接收程序的识别结果回调中：

```python
import json
from urllib.request import Request, urlopen

def report_action(action):
    req = Request(
        'http://127.0.0.1:8766/v1/state',
        data=json.dumps({'action': action}).encode('utf-8'),
        headers={'Content-Type': 'application/json'},
        method='POST',
    )
    with urlopen(req, timeout=3) as response:
        return json.load(response)

# 举例：分类器稳定识别到走路
report_action('walk')
```

集成测试（会临时轮播动作并恢复原动作）：`python3 test_api.py`。实现使用 [Apple Network 的 NWListener](https://developer.apple.com/documentation/network/nwlistener)。
