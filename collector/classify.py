#!/usr/bin/env python3
"""Classify live Paw Link BLE IMU windows."""

from __future__ import annotations

import argparse
import asyncio
import json
import struct
import time
from pathlib import Path

from bleak import BleakClient, BleakScanner

from cat_features import extract_features as extract_cat_features
from features import extract_features as extract_centroid_features
from features import predict as predict_centroid
from forest_model import predict as predict_forest


SERVICE_UUID = "7e400001-b5a3-f393-e0a9-e50e24dcca9e"
STATUS_UUID = "7e400002-b5a3-f393-e0a9-e50e24dcca9e"
IMU_DATA_UUID = "7e400003-b5a3-f393-e0a9-e50e24dcca9e"
FRAME = struct.Struct("<IIhhhhhh")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Classify Paw Link motion in real time.")
    parser.add_argument("--model", type=Path, default=Path("models/cat-behavior-rf.json"))
    parser.add_argument("--name", default="PawLink-Test")
    parser.add_argument("--scan-timeout", type=float, default=15.0)
    parser.add_argument("--windows", type=int, default=0, help="Stop after N windows; 0 runs until Ctrl-C")
    return parser.parse_args()


async def run(args: argparse.Namespace) -> None:
    model = json.loads(args.model.read_text(encoding="utf-8"))
    is_forest = model.get("model_type") == "random_forest"
    window_size = int(model["window_samples"])
    print(f"Scanning for {args.name!r}...")

    def matches(device, advertisement) -> bool:
        local_name = advertisement.local_name or device.name or ""
        return local_name == args.name or SERVICE_UUID in {uuid.lower() for uuid in advertisement.service_uuids}

    device = await BleakScanner.find_device_by_filter(
        matches, timeout=args.scan_timeout, service_uuids=[SERVICE_UUID]
    )
    if device is None:
        raise RuntimeError(f"could not find {args.name!r}")

    samples: list[list[float]] = []
    sequences: list[int] = []
    device_times: list[int] = []
    completed = 0
    stop = asyncio.Event()

    async with BleakClient(device) as client:
        status = (await client.read_gatt_char(STATUS_UUID)).decode("utf-8", errors="replace")
        print(f"Connected. Device status: {status}")

        def on_frame(_characteristic, payload: bytearray) -> None:
            nonlocal completed
            if len(payload) != FRAME.size:
                return
            sequence, device_ms, ax, ay, az, gx, gy, gz = FRAME.unpack(payload)
            samples.append([ax / 1000, ay / 1000, az / 1000, gx / 100, gy / 100, gz / 100])
            sequences.append(sequence)
            device_times.append(device_ms)
            if len(samples) < window_size:
                return

            if is_forest:
                vector = extract_cat_features(samples[:window_size])
                label, distances, margin = predict_forest(model, vector)
            else:
                vector = extract_centroid_features(samples[:window_size])
                label, distances, margin = predict_centroid(model, vector)
            dropped = sum(max(0, right - left - 1) for left, right in zip(sequences, sequences[1:]))
            elapsed = (device_times[-1] - device_times[0]) / 1000
            rate = (window_size - 1) / elapsed if elapsed > 0 else 0
            distance_text = " ".join(f"{name}={value:.2f}" for name, value in sorted(distances.items()))
            print(
                f"{time.strftime('%H:%M:%S')}  {label:<9} "
                f"margin={margin:.2f} rate={rate:.1f}Hz dropped={dropped}  {distance_text}"
            )
            del samples[:window_size]
            del sequences[:window_size]
            del device_times[:window_size]
            completed += 1
            if args.windows and completed >= args.windows:
                stop.set()

        await client.start_notify(IMU_DATA_UUID, on_frame)
        print(f"Classifying {window_size}-sample windows. Press Ctrl-C to stop.")
        if args.windows:
            await stop.wait()
        else:
            while True:
                await asyncio.sleep(3600)
        await client.stop_notify(IMU_DATA_UUID)


def main() -> None:
    args = parse_args()
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        print("Stopped.")
    except Exception as exc:
        raise SystemExit(f"error: {exc}") from exc


if __name__ == "__main__":
    main()
