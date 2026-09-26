"""Bridge PawLink BLE predictions to the PawLink desktop pet API."""

import asyncio
import json
import subprocess
import time
from dataclasses import dataclass
from datetime import datetime
from urllib.error import URLError
from urllib.request import Request, urlopen

from bleak import BleakClient, BleakScanner


SERVICE = "7e400001-b5a3-f393-e0a9-e50e24dcca9e"
PREDICT = "7e400004-b5a3-f393-e0a9-e50e24dcca9e"
PET_API = "http://127.0.0.1:8766/v1/state"

ACTION_NAMES = {
    "feed": "吃饱喝足",
    "jump": "跳起来玩",
    "groom": "舔毛",
    "wash": "洗脸",
    "roll": "左右打滚",
    "walk": "走路",
    "sleep": "睡觉",
}

SOURCE_NAMES = {
    "rest": "静止",
    "locomotion": "移动",
    "feed": "进食",
    "groom": "理毛",
    "collar_shake": "项圈抖动",
}


def log(message):
    print(f"{datetime.now():%H:%M:%S}  {message}", flush=True)


@dataclass(frozen=True)
class Prediction:
    action: str
    margin: float
    latency_us: int
    window: int
    source_label: str


def parse_prediction(payload):
    """Parse firmware v5: action,margin,latency_us,window,source_label."""
    raw = payload.decode("utf-8", errors="strict")
    parts = raw.split(",")
    if len(parts) != 5:
        raise ValueError(f"expected 5 fields, got {len(parts)}: {raw}")

    action, margin, latency, window, source_label = parts
    if action not in ACTION_NAMES:
        raise ValueError(f"unknown public action: {action}")

    parsed = Prediction(
        action=action,
        margin=float(margin),
        latency_us=int(latency),
        window=int(window),
        source_label=source_label,
    )
    if parsed.latency_us < 0 or parsed.window < 0:
        raise ValueError("latency and window must be non-negative")
    return parsed


def show(payload, prefix=""):
    try:
        prediction = parse_prediction(payload)
    except (UnicodeDecodeError, ValueError) as exc:
        log(f"{prefix}无效预测：{exc}")
        return None

    source = SOURCE_NAMES.get(prediction.source_label, prediction.source_label)
    log(
        f"{prefix}{ACTION_NAMES[prediction.action]} ({prediction.action})  "
        f"来源={source}  领先票差={prediction.margin:.4f}  "
        f"推理={prediction.latency_us}μs  窗口={prediction.window}"
    )
    return prediction


def post_pet_action(action):
    request = Request(
        PET_API,
        data=json.dumps({"action": action}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=3) as response:
        state = json.load(response)
    if state.get("action") != action:
        raise RuntimeError(f"desktop pet returned unexpected state: {state}")
    return state


class DesktopPetBridge:
    """Keep the pet on the newest action and retry transient API failures."""

    def __init__(self):
        self.desired_action = None
        self.reported_action = None
        self.changed = asyncio.Event()
        self.stopping = False

    def submit(self, action):
        if action != self.desired_action:
            self.desired_action = action
            self.changed.set()

    async def run(self):
        while not self.stopping:
            await self.changed.wait()
            self.changed.clear()
            while (
                not self.stopping
                and self.desired_action is not None
                and self.desired_action != self.reported_action
            ):
                action = self.desired_action
                try:
                    state = await asyncio.to_thread(post_pet_action, action)
                    if action == self.desired_action:
                        self.reported_action = action
                    log(f"桌面宠物已切换：{ACTION_NAMES[action]} ({state['action']})")
                except (OSError, URLError, ValueError, RuntimeError) as exc:
                    log(f"桌面宠物 API 暂不可用：{exc}；3 秒后重试。")
                    try:
                        await asyncio.wait_for(self.changed.wait(), timeout=3)
                        self.changed.clear()
                    except asyncio.TimeoutError:
                        pass

    def stop(self):
        self.stopping = True
        self.changed.set()


class MotionSound:
    def __init__(self):
        self.last_sound = float("-inf")
        self.process = None

    def notify(self, action):
        if action == "sleep":
            return
        now = time.monotonic()
        if now - self.last_sound < 10:
            return
        if self.process is not None and self.process.poll() is None:
            return
        try:
            self.process = subprocess.Popen(
                ["/usr/bin/afplay", "/System/Library/Sounds/Ping.aiff"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            self.last_sound = now
        except OSError as exc:
            log(f"提示音播放失败：{exc}")

    def close(self):
        if self.process is not None:
            if self.process.poll() is None:
                self.process.terminate()
            self.process.wait()


async def monitor(sound, bridge):
    while True:
        try:
            log("寻找项圈……（Ctrl+C 停止）")
            device = await BleakScanner.find_device_by_filter(
                lambda d, a: (a.local_name or d.name) == "PawLink-Test"
                or SERVICE in [uuid.lower() for uuid in a.service_uuids],
                timeout=30,
                service_uuids=[SERVICE],
            )
            if device is None:
                log("未找到项圈，5 秒后重试。")
            else:
                disconnected = asyncio.Event()
                async with BleakClient(
                    device, disconnected_callback=lambda _: disconnected.set()
                ) as client:
                    if client.services.get_characteristic(PREDICT) is None:
                        log("当前固件没有动作预测接口，停止监听。")
                        return

                    log("已连接；动作变化将自动同步到 PawLink 桌面宠物。")
                    initial = show(
                        await client.read_gatt_char(PREDICT),
                        "最近一次（可能为缓存）：",
                    )
                    if initial is not None:
                        bridge.submit(initial.action)

                    last_update = asyncio.get_running_loop().time()

                    def on_prediction(_, payload):
                        nonlocal last_update
                        last_update = asyncio.get_running_loop().time()
                        prediction = show(payload)
                        if prediction is not None:
                            sound.notify(prediction.action)
                            bridge.submit(prediction.action)

                    await client.start_notify(PREDICT, on_prediction)
                    while not disconnected.is_set():
                        try:
                            await asyncio.wait_for(disconnected.wait(), timeout=10)
                        except asyncio.TimeoutError:
                            if asyncio.get_running_loop().time() - last_update >= 10:
                                log("超过 10 秒未收到新预测，保留桌面宠物当前动作。")
                    log("连接已断开，5 秒后重新扫描。")
        except Exception as exc:
            log(f"连接或读取失败：{exc}；5 秒后重试。")
        await asyncio.sleep(5)


async def main(sound):
    bridge = DesktopPetBridge()
    bridge_task = asyncio.create_task(bridge.run())
    try:
        await monitor(sound, bridge)
    finally:
        bridge.stop()
        await bridge_task


if __name__ == "__main__":
    sound = MotionSound()
    try:
        asyncio.run(main(sound))
    except KeyboardInterrupt:
        log("已停止监听。")
    finally:
        sound.close()
