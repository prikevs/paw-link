"""Shared motion-window features and nearest-centroid inference."""

from __future__ import annotations

import math
import statistics
from collections.abc import Sequence


CHANNEL_NAMES = ("ax_g", "ay_g", "az_g", "gx_dps", "gy_dps", "gz_dps")
FEATURE_NAMES = tuple(
    [f"{channel}_mean" for channel in CHANNEL_NAMES]
    + [f"{channel}_std" for channel in CHANNEL_NAMES]
    + [f"{channel}_rms" for channel in CHANNEL_NAMES]
    + ["accel_mag_mean", "accel_mag_std", "gyro_mag_mean", "gyro_mag_std"]
)


def _mean_std(values: Sequence[float]) -> tuple[float, float]:
    return statistics.fmean(values), statistics.pstdev(values)


def extract_features(samples: Sequence[Sequence[float]]) -> list[float]:
    if len(samples) < 2:
        raise ValueError("a motion window needs at least two samples")
    if any(len(sample) != 6 for sample in samples):
        raise ValueError("each sample must contain six IMU channels")

    columns = list(zip(*samples, strict=True))
    means = [statistics.fmean(column) for column in columns]
    standard_deviations = [statistics.pstdev(column) for column in columns]
    rms = [math.sqrt(statistics.fmean(value * value for value in column)) for column in columns]
    accel_magnitude = [math.sqrt(sum(value * value for value in sample[:3])) for sample in samples]
    gyro_magnitude = [math.sqrt(sum(value * value for value in sample[3:])) for sample in samples]
    accel_mean, accel_std = _mean_std(accel_magnitude)
    gyro_mean, gyro_std = _mean_std(gyro_magnitude)
    return means + standard_deviations + rms + [accel_mean, accel_std, gyro_mean, gyro_std]


def fit_model(windows: Sequence[tuple[list[float], str]]) -> dict:
    if not windows:
        raise ValueError("no training windows")
    labels = sorted({label for _, label in windows})
    width = len(windows[0][0])
    means = [statistics.fmean(vector[index] for vector, _ in windows) for index in range(width)]
    scales = [statistics.pstdev(vector[index] for vector, _ in windows) for index in range(width)]
    scales = [scale if scale >= 1e-9 else 1.0 for scale in scales]

    centroids = {}
    for label in labels:
        members = [vector for vector, member_label in windows if member_label == label]
        centroids[label] = [
            statistics.fmean((vector[index] - means[index]) / scales[index] for vector in members)
            for index in range(width)
        ]
    return {"labels": labels, "feature_mean": means, "feature_scale": scales, "centroids": centroids}


def predict(model: dict, vector: Sequence[float]) -> tuple[str, dict[str, float], float]:
    mean = model["feature_mean"]
    scale = model["feature_scale"]
    normalized = [(value - center) / spread for value, center, spread in zip(vector, mean, scale, strict=True)]
    distances = {
        label: math.sqrt(sum((value - target) ** 2 for value, target in zip(normalized, centroid, strict=True)))
        for label, centroid in model["centroids"].items()
    }
    ordered = sorted(distances.items(), key=lambda item: item[1])
    label = ordered[0][0]
    margin = 1.0 if len(ordered) == 1 else max(0.0, (ordered[1][1] - ordered[0][1]) / max(ordered[1][1], 1e-9))
    return label, distances, margin
