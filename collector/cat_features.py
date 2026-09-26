"""Acceleration-only features shared by open-data training and live inference."""

from __future__ import annotations

import math
from collections.abc import Sequence


CHANNEL_NAMES = ("ax_g", "ay_g", "az_g", "accel_mag")
STAT_NAMES = ("mean", "std", "rms", "min", "max", "range", "mean_abs_diff", "rms_diff")
FEATURE_NAMES = tuple(f"{channel}_{stat}" for stat in STAT_NAMES for channel in CHANNEL_NAMES)


def extract_features(samples: Sequence[Sequence[float]]) -> list[float]:
    if len(samples) < 2:
        raise ValueError("a cat-behaviour window needs at least two samples")
    channels = [[], [], [], []]
    for sample in samples:
        if len(sample) < 3:
            raise ValueError("each sample must contain at least three acceleration channels")
        ax, ay, az = map(float, sample[:3])
        values = (ax, ay, az, math.sqrt(ax * ax + ay * ay + az * az))
        for channel, value in zip(channels, values, strict=True):
            channel.append(value)

    features: list[float] = []
    means = [sum(values) / len(values) for values in channels]
    mean_squares = [sum(value * value for value in values) / len(values) for values in channels]
    features.extend(means)
    features.extend(
        math.sqrt(max(0.0, mean_square - mean * mean))
        for mean, mean_square in zip(means, mean_squares, strict=True)
    )
    features.extend(math.sqrt(value) for value in mean_squares)
    minima = [min(values) for values in channels]
    maxima = [max(values) for values in channels]
    features.extend(minima)
    features.extend(maxima)
    features.extend(high - low for low, high in zip(minima, maxima, strict=True))

    differences = [
        [right - left for left, right in zip(values, values[1:])]
        for values in channels
    ]
    features.extend(sum(abs(value) for value in values) / len(values) for values in differences)
    features.extend(
        math.sqrt(sum(value * value for value in values) / len(values))
        for values in differences
    )
    return features
