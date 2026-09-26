#!/usr/bin/env python3
"""Collect Paw Link 50 Hz BLE IMU notifications into a labeled CSV file."""

from __future__ import annotations

import argparse
import asyncio
import csv
import struct
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from bleak import BleakClient, BleakScanner


SERVICE_UUID = "7e400001-b5a3-f393-e0a9-e50e24dcca9e"
STATUS_UUID = "7e400002-b5a3-f393-e0a9-e50e24dcca9e"
IMU_DATA_UUID = "7e400003-b5a3-f393-e0a9-e50e24dcca9e"
FRAME = struct.Struct("<IIhhhhhh")


@dataclass
class Stats:
    received: int = 0
    dropped: int = 0
    invalid: int = 0
    first_sequence: int | None = None
    last_sequence: int | None = None
    first_received_at: float | None = None
    last_received_at: float | None = None

    def add(self, sequence: int, received_at: float) -> None:
        if self.last_sequence is not None:
            gap = (sequence - self.last_sequence) & 0xFFFFFFFF
            if 1 < gap < 0x80000000:
                self.dropped += gap - 1
        if self.first_sequence is None:
            self.first_sequence = sequence
            self.first_received_at = received_at
        self.last_sequence = sequence
        self.last_received_at = received_at
        self.received += 1

    @property
    def rate_hz(self) -> float:
        if (
            self.received < 2
            or self.first_received_at is None
            or self.last_received_at is None
        ):
            return 0.0
        elapsed = self.last_received_at - self.first_received_at
        return (self.received - 1) / elapsed if elapsed > 0 else 0.0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Collect labeled IMU data from a Paw Link collar over BLE."
    )
    parser.add_argument("--label", default="unlabeled", help="Activity label")
    parser.add_argument(
        "--duration", type=float, default=10.0, help="Recording duration in seconds"
    )
    parser.add_argument("--name", default="PawLink-Test", help="BLE device name")
    parser.add_argument("--address", help="Connect to a known BLE address/identifier")
    parser.add_argument("--output-dir", type=Path, default=Path("data"))
    parser.add_argument("--scan-timeout", type=float, default=15.0)
    return parser.parse_args()


async def find_device(args: argparse.Namespace):
    if args.address:
        return args.address

    print(f"Scanning for {args.name!r}...")

    def matches(device, advertisement) -> bool:
        local_name = advertisement.local_name or device.name or ""
        advertised = {uuid.lower() for uuid in advertisement.service_uuids}
        return local_name == args.name or SERVICE_UUID in advertised

    device = await BleakScanner.find_device_by_filter(
        matches,
        timeout=args.scan_timeout,
        service_uuids=[SERVICE_UUID],
    )
    if device is None:
        raise RuntimeError(
            f"Could not find {args.name!r} in {args.scan_timeout:.0f} seconds"
        )
    return device


async def collect(args: argparse.Namespace) -> Path:
    if args.duration <= 0:
        raise ValueError("--duration must be greater than zero")

    target = await find_device(args)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    safe_label = "".join(
        char if char.isalnum() or char in "-_" else "_" for char in args.label
    ).strip("_") or "unlabeled"
    timestamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    output = args.output_dir / f"{timestamp}-{safe_label}.csv"
    stats = Stats()

    with output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "host_time_ns",
                "device_time_ms",
                "sequence",
                "label",
                "ax_g",
                "ay_g",
                "az_g",
                "gx_dps",
                "gy_dps",
                "gz_dps",
            ]
        )

        async with BleakClient(target) as client:
            status = (await client.read_gatt_char(STATUS_UUID)).decode(
                "utf-8", errors="replace"
            )
            print(f"Connected. Device status: {status}")
            if "IMU=PASS" not in status or "BLE=PASS" not in status:
                raise RuntimeError(f"Device is not ready: {status}")

            def on_frame(_characteristic, payload: bytearray) -> None:
                received_at = time.monotonic()
                host_time_ns = time.time_ns()
                if len(payload) != FRAME.size:
                    stats.invalid += 1
                    return
                sequence, device_ms, ax, ay, az, gx, gy, gz = FRAME.unpack(payload)
                stats.add(sequence, received_at)
                writer.writerow(
                    [
                        host_time_ns,
                        device_ms,
                        sequence,
                        args.label,
                        ax / 1000.0,
                        ay / 1000.0,
                        az / 1000.0,
                        gx / 100.0,
                        gy / 100.0,
                        gz / 100.0,
                    ]
                )

            await client.start_notify(IMU_DATA_UUID, on_frame)
            print(f"Recording label={args.label!r} for {args.duration:.1f}s...")
            await asyncio.sleep(args.duration)
            await client.stop_notify(IMU_DATA_UUID)

    if stats.received == 0:
        output.unlink(missing_ok=True)
        raise RuntimeError("Connected successfully, but received no IMU notifications")

    expected = stats.received + stats.dropped
    loss_percent = (100.0 * stats.dropped / expected) if expected else 0.0
    print(
        f"Saved {stats.received} samples to {output} | "
        f"rate={stats.rate_hz:.2f} Hz | dropped={stats.dropped} "
        f"({loss_percent:.2f}%) | invalid={stats.invalid}"
    )
    return output


def main() -> None:
    args = parse_args()
    try:
        asyncio.run(collect(args))
    except KeyboardInterrupt:
        print("Recording stopped.")
    except Exception as exc:
        raise SystemExit(f"error: {exc}") from exc


if __name__ == "__main__":
    main()
