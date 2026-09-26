#!/usr/bin/env python3
"""Train an MCU-sized forest from the Dryad domestic-cat dataset."""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter
from pathlib import Path

import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import balanced_accuracy_score, confusion_matrix, f1_score
from sklearn.model_selection import LeaveOneGroupOut

from cat_features import FEATURE_NAMES, extract_features
from forest_model import predict as predict_portable


SOURCE_RATE_HZ = 40
TARGET_RATE_HZ = 50
LABEL_MAP = {
    "Rest": "rest",
    "Walk": "locomotion",
    "Trot": "locomotion",
    "Run": "locomotion",
    "Feed": "feed",
    "Groom": "groom",
    "Shake": "collar_shake",
}
LABELS = ("rest", "locomotion", "feed", "groom", "collar_shake")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train the open-data cat behaviour forest.")
    parser.add_argument(
        "--input",
        type=Path,
        default=Path("data/external/dryad-cat/Dunford_et_al._Cats_calibrated_data.csv"),
    )
    parser.add_argument("--output", type=Path, default=Path("models/cat-behavior-rf.json"))
    parser.add_argument("--window-samples", type=int, default=100)
    parser.add_argument("--stride-samples", type=int, default=25)
    parser.add_argument("--trees", type=int, default=32)
    parser.add_argument("--max-depth", type=int, default=8)
    parser.add_argument("--min-samples-leaf", type=int, default=4)
    return parser.parse_args()


def load_segments(path: Path) -> list[tuple[str, str, np.ndarray]]:
    segments: list[tuple[str, str, list[list[float]]]] = []
    current_key: tuple[str, str] | None = None
    current_samples: list[list[float]] = []
    previous_second: int | None = None
    with path.open(newline="", encoding="utf-8-sig") as handle:
        for row in csv.DictReader(handle):
            label = LABEL_MAP.get(row["Behaviour"])
            if label is None:
                continue
            key = (row["ID"], label)
            hours, minutes, seconds = map(int, row["Time"].split(":"))
            current_second = hours * 3600 + minutes * 60 + seconds
            time_gap = previous_second is not None and not 0 <= current_second - previous_second <= 1
            if current_key is not None and (key != current_key or time_gap):
                segments.append((*current_key, current_samples))
                current_samples = []
            current_key = key
            previous_second = current_second
            current_samples.append([float(row["AccX"]), float(row["AccY"]), float(row["AccZ"])])
    if current_key is not None:
        segments.append((*current_key, current_samples))
    return [(cat, label, np.asarray(samples)) for cat, label, samples in segments]


def resample(samples: np.ndarray) -> np.ndarray:
    target_count = round(len(samples) * TARGET_RATE_HZ / SOURCE_RATE_HZ)
    source_positions = np.arange(len(samples), dtype=float)
    target_positions = np.linspace(0, len(samples) - 1, target_count)
    return np.column_stack(
        [np.interp(target_positions, source_positions, samples[:, axis]) for axis in range(3)]
    )


def build_windows(
    segments: list[tuple[str, str, np.ndarray]], window_samples: int, stride_samples: int
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    vectors: list[list[float]] = []
    labels: list[str] = []
    groups: list[str] = []
    for cat, label, source_samples in segments:
        samples = resample(source_samples)
        for start in range(0, len(samples) - window_samples + 1, stride_samples):
            vectors.append(extract_features(samples[start : start + window_samples]))
            labels.append(label)
            groups.append(cat)
    return np.asarray(vectors), np.asarray(labels), np.asarray(groups)


def make_forest(args: argparse.Namespace) -> RandomForestClassifier:
    return RandomForestClassifier(
        n_estimators=args.trees,
        max_depth=args.max_depth,
        min_samples_leaf=args.min_samples_leaf,
        max_features="sqrt",
        class_weight="balanced_subsample",
        n_jobs=-1,
        random_state=20260920,
    )


def majority_vote(forest: RandomForestClassifier, vectors: np.ndarray) -> np.ndarray:
    class_votes = np.stack(
        [forest.classes_[tree.predict(vectors).astype(int)] for tree in forest.estimators_]
    )
    predictions = []
    for column in class_votes.T:
        counts = Counter(column)
        predictions.append(max(LABELS, key=lambda label: counts[label]))
    return np.asarray(predictions)


def export_trees(forest: RandomForestClassifier, labels: tuple[str, ...]) -> list[list[list[float | int]]]:
    label_indexes = {label: index for index, label in enumerate(labels)}
    trees = []
    for estimator in forest.estimators_:
        tree = estimator.tree_
        nodes = []
        for index in range(tree.node_count):
            if tree.children_left[index] == tree.children_right[index]:
                class_index = int(np.argmax(tree.value[index][0]))
                sklearn_class = forest.classes_[class_index]
                nodes.append([-1, 0.0, 0, 0, label_indexes[str(sklearn_class)]])
            else:
                nodes.append(
                    [
                        int(tree.feature[index]),
                        float(tree.threshold[index]),
                        int(tree.children_left[index]),
                        int(tree.children_right[index]),
                        0,
                    ]
                )
        trees.append(nodes)
    return trees


def main() -> None:
    args = parse_args()
    segments = load_segments(args.input)
    vectors, labels, groups = build_windows(segments, args.window_samples, args.stride_samples)
    print(f"Built {len(vectors)} windows from {len(set(groups))} cats: {dict(Counter(labels))}")

    logo = LeaveOneGroupOut()
    actual_all: list[str] = []
    predicted_all: list[str] = []
    per_cat = {}
    for train_indexes, test_indexes in logo.split(vectors, labels, groups):
        held_out = str(groups[test_indexes[0]])
        forest = make_forest(args)
        forest.fit(vectors[train_indexes], labels[train_indexes])
        predicted = majority_vote(forest, vectors[test_indexes])
        actual = labels[test_indexes]
        present_labels = sorted(set(actual))
        recalls = [
            float(np.mean(predicted[actual == label] == label)) for label in present_labels
        ]
        actual_all.extend(actual.tolist())
        predicted_all.extend(predicted.tolist())
        per_cat[held_out] = {
            "windows": len(test_indexes),
            "balanced_accuracy": float(np.mean(recalls)),
            "macro_f1_present_labels": float(
                f1_score(actual, predicted, labels=present_labels, average="macro", zero_division=0)
            ),
        }
        print(
            f"  held-out {held_out}: windows={len(test_indexes)} "
            f"balanced_accuracy={per_cat[held_out]['balanced_accuracy']:.3f} "
            f"macro_f1={per_cat[held_out]['macro_f1_present_labels']:.3f}"
        )

    confusion = confusion_matrix(actual_all, predicted_all, labels=LABELS)
    macro_f1 = f1_score(actual_all, predicted_all, labels=LABELS, average="macro", zero_division=0)
    balanced = balanced_accuracy_score(actual_all, predicted_all)
    print(f"Leave-one-cat-out balanced accuracy: {balanced:.3f}")
    print(f"Leave-one-cat-out macro F1: {macro_f1:.3f}")
    print("Confusion rows=actual, columns=predicted")
    print("  " + " ".join(f"{label:>13}" for label in LABELS))
    for label, row in zip(LABELS, confusion, strict=True):
        print(f"  {label:>13} " + " ".join(f"{value:13d}" for value in row))

    forest = make_forest(args)
    forest.fit(vectors, labels)
    model = {
        "schema_version": 2,
        "model_type": "random_forest",
        "labels": list(LABELS),
        "feature_names": list(FEATURE_NAMES),
        "window_samples": args.window_samples,
        "stride_samples": args.stride_samples,
        "nominal_sample_rate_hz": TARGET_RATE_HZ,
        "trees": export_trees(forest, LABELS),
        "training": {
            "source": "https://doi.org/10.5061/dryad.q2bvq83sx",
            "source_license": "CC0-1.0",
            "source_sample_rate_hz": SOURCE_RATE_HZ,
            "cat_count": len(set(groups)),
            "window_count": len(vectors),
            "windows_per_label": dict(Counter(labels)),
            "validation": "leave-one-cat-out",
            "balanced_accuracy": float(balanced),
            "macro_f1": float(macro_f1),
            "confusion_labels": list(LABELS),
            "confusion": confusion.tolist(),
            "per_cat": per_cat,
            "forest": {
                "trees": args.trees,
                "max_depth": args.max_depth,
                "min_samples_leaf": args.min_samples_leaf,
            },
        },
    }
    reference_predictions = majority_vote(forest, vectors)
    portable_predictions = np.asarray(
        [predict_portable(model, vector)[0] for vector in vectors]
    )
    mismatches = int(np.count_nonzero(reference_predictions != portable_predictions))
    if mismatches:
        raise RuntimeError(f"portable forest disagrees with scikit-learn on {mismatches} windows")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(model, separators=(",", ":")) + "\n", encoding="utf-8")
    node_count = sum(len(tree) for tree in model["trees"])
    print(f"Saved {args.output}: trees={len(model['trees'])}, nodes={node_count}")
    print(f"Portable inference parity: PASS ({len(vectors)} windows)")


if __name__ == "__main__":
    main()
