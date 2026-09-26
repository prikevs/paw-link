#!/usr/bin/env python3
"""Read Paw Link self-test output without third-party Python packages."""

from __future__ import annotations

import math
import os
import select
import sys
import time


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: serial_check.py /dev/cu.usbmodem...", file=sys.stderr)
        return 2

    port = sys.argv[1]
    deadline = time.monotonic() + 12
    samples = 0
    plausible_samples = 0
    status_ok = False
    battery_ok = False
    power_ok = False
    prediction_ok = False
    buffer = b""

    fd = os.open(port, os.O_RDONLY | os.O_NONBLOCK)
    try:
        while time.monotonic() < deadline:
            readable, _, _ = select.select([fd], [], [], 0.5)
            if not readable:
                continue
            chunk = os.read(fd, 4096)
            if not chunk:
                continue
            buffer += chunk
            while b"\n" in buffer:
                raw, buffer = buffer.split(b"\n", 1)
                line = raw.decode("utf-8", errors="replace").strip()
                if line:
                    print(line)
                if line.startswith("STATUS,"):
                    status_ok = all(
                        field in line
                        for field in ("USB=PASS", "IMU=PASS", "BLE=PASS")
                    )
                if line.startswith("BATTERY,"):
                    fields = line.split(",")
                    if len(fields) == 4:
                        try:
                            timestamp_ms = int(fields[1])
                            millivolts = int(fields[2])
                            percent = None if fields[3] == "NA" else int(fields[3])
                        except ValueError:
                            pass
                        else:
                            battery_ok = (
                                timestamp_ms >= 0
                                and 0 <= millivolts <= 65535
                                and (percent is None or 0 <= percent <= 100)
                            )
                if line.startswith("POWER,"):
                    fields = line.split(",")
                    if len(fields) == 4:
                        try:
                            timestamp_ms = int(fields[1])
                            charge_active = int(
                                fields[3].removeprefix("CHARGE_ACTIVE=")
                            )
                        except ValueError:
                            pass
                        else:
                            power_ok = (
                                timestamp_ms >= 0
                                and fields[2]
                                in {"NOT_CHARGING", "CHARGING"}
                                and charge_active in {0, 1}
                            )
                if line.startswith("DATA,"):
                    fields = line.split(",")
                    if len(fields) != 9:
                        continue
                    try:
                        ax, ay, az = map(float, fields[3:6])
                        gx, gy, gz = map(float, fields[6:9])
                    except ValueError:
                        continue
                    values = (ax, ay, az, gx, gy, gz)
                    if not all(math.isfinite(value) for value in values):
                        continue
                    samples += 1
                    magnitude = math.sqrt(ax * ax + ay * ay + az * az)
                    if 0.2 <= magnitude <= 2.5:
                        plausible_samples += 1
                if line.startswith("PREDICT,"):
                    fields = line.split(",")
                    if len(fields) == 13:
                        try:
                            first_sequence = int(fields[2])
                            last_sequence = int(fields[3])
                            margin = float(fields[6])
                            latency_us = int(fields[7])
                            scores = tuple(map(float, fields[8:13]))
                        except ValueError:
                            pass
                        else:
                            prediction_ok = (
                                last_sequence - first_sequence + 1 == 100
                                and fields[4]
                                in {"feed", "jump", "groom", "wash", "roll", "walk", "sleep"}
                                and fields[5]
                                in {"rest", "locomotion", "feed", "groom", "collar_shake"}
                                and math.isfinite(margin)
                                and latency_us >= 0
                                and all(math.isfinite(value) for value in scores)
                                and abs(sum(scores) - 1.0) <= 0.01
                            )
                if (
                    status_ok
                    and battery_ok
                    and power_ok
                    and prediction_ok
                    and samples >= 20
                    and plausible_samples >= 20
                ):
                    print("HOST_CHECK:PASS")
                    return 0
    finally:
        os.close(fd)

    print(
        f"HOST_CHECK:FAIL status_ok={status_ok} battery_ok={battery_ok} "
        f"power_ok={power_ok} "
        f"prediction_ok={prediction_ok} samples={samples} "
        f"plausible_samples={plausible_samples}",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
