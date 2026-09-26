#!/usr/bin/env python3
"""Train and validate Paw Link's lightweight motion classifier."""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path

from features import FEATURE_NAMES, extract_features, fit_model, predict


DEFAULT_LABELS = ("still", "shake_lr", "circle")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train a nearest-centroid motion classifier.")
    parser.add_argument("--data-dir", type=Path, default=Path("data"))
    parser.add_argument("--output", type=Path, default=Path("models/motion-centroids.json"))
    parser.add_argument("--window-samples", type=int, default=100)
    parser.add_argument("--trim-samples", type=int, default=50)
    parser.add_argument("--labels", nargs="+", default=list(DEFAULT_LABELS))
    return parser.parse_args()


def load_recording(path: Path) -> tuple[str, list[list[float]]]:
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        raise ValueError(f"empty recording: {path}")
    label = rows[0]["label"]
    samples = [
        [float(row[name]) for name in ("ax_g", "ay_g", "az_g", "gx_dps", "gy_dps", "gz_dps")]
        for row in rows
    ]
    return label, samples


def main() -> None:
    args = parse_args()
    recordings: dict[str, list[tuple[Path, list[list[float]]]]] = defaultdict(list)
    for path in sorted(args.data_dir.glob("*.csv")):
        label, samples = load_recording(path)
        if label in args.labels:
            recordings[label].append((path, samples))

    missing = [label for label in args.labels if len(recordings[label]) < 2]
    if missing:
        raise SystemExit(f"need at least two recordings for each label; missing: {', '.join(missing)}")

    fold_windows: dict[int, list[tuple[list[float], str]]] = defaultdict(list)
    file_counts = {}
    for label in args.labels:
        file_counts[label] = len(recordings[label])
        for repetition, (_path, samples) in enumerate(recordings[label]):
            trimmed = samples[args.trim_samples : len(samples) - args.trim_samples]
            for start in range(0, len(trimmed) - args.window_samples + 1, args.window_samples):
                vector = extract_features(trimmed[start : start + args.window_samples])
                fold_windows[repetition].append((vector, label))

    confusion: Counter[tuple[str, str]] = Counter()
    for held_out in sorted(fold_windows):
        training = [item for fold, items in fold_windows.items() if fold != held_out for item in items]
        model = fit_model(training)
        for vector, actual in fold_windows[held_out]:
            predicted, _distances, _margin = predict(model, vector)
            confusion[(actual, predicted)] += 1

    total = sum(confusion.values())
    correct = sum(count for (actual, predicted), count in confusion.items() if actual == predicted)
    all_windows = [item for items in fold_windows.values() for item in items]
    model = fit_model(all_windows)
    model.update(
        {
            "schema_version": 1,
            "window_samples": args.window_samples,
            "nominal_sample_rate_hz": 50,
            "feature_names": list(FEATURE_NAMES),
            "training": {
                "recordings_per_label": file_counts,
                "window_count": len(all_windows),
                "trim_samples_per_edge": args.trim_samples,
                "validation_accuracy": correct / total,
                "validation_confusion": {
                    actual: {predicted: confusion[(actual, predicted)] for predicted in args.labels}
                    for actual in args.labels
                },
            },
        }
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")

    print(f"Saved model to {args.output}")
    print(f"Validation: {correct}/{total} windows correct ({100 * correct / total:.2f}%)")
    for actual in args.labels:
        results = ", ".join(f"{predicted}={confusion[(actual, predicted)]}" for predicted in args.labels)
        print(f"  {actual}: {results}")


if __name__ == "__main__":
    main()
