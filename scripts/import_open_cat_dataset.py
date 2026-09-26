#!/usr/bin/env python3
"""Safely import the CC0 Dryad cat accelerometer dataset from its zip."""

from __future__ import annotations

import argparse
import hashlib
import zipfile
from pathlib import Path


CSV_NAME = "Dunford_et_al._Cats_calibrated_data.csv"
README_NAME = "README.md"
EXPECTED_CSV_SHA256 = "0e06dc05dd177c84e1abc9363fb9b7b0e095edd1b0de2c852236b954ce822615"


def sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Import the Dryad domestic-cat dataset.")
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output-dir", type=Path, default=Path("data/external/dryad-cat"))
    args = parser.parse_args()

    with zipfile.ZipFile(args.archive) as archive:
        names = set(archive.namelist())
        required = {CSV_NAME, README_NAME}
        if not required.issubset(names):
            raise SystemExit(f"archive is missing: {', '.join(sorted(required - names))}")
        csv_payload = archive.read(CSV_NAME)
        readme_payload = archive.read(README_NAME)

    digest = sha256(csv_payload)
    if digest != EXPECTED_CSV_SHA256:
        raise SystemExit(f"unexpected CSV sha256: {digest}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / CSV_NAME).write_bytes(csv_payload)
    (args.output_dir / README_NAME).write_bytes(readme_payload)
    print(f"Imported {len(csv_payload):,} bytes to {args.output_dir / CSV_NAME}")
    print(f"CSV sha256: {digest}")


if __name__ == "__main__":
    main()
