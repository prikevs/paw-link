"""Portable random-forest inference used to verify the exported MCU model."""

from __future__ import annotations

from collections.abc import Sequence


def predict(model: dict, vector: Sequence[float]) -> tuple[str, dict[str, float], float]:
    labels = model["labels"]
    votes = [0] * len(labels)
    for tree in model["trees"]:
        node_index = 0
        while True:
            node = tree[node_index]
            if node[0] < 0:
                votes[node[4]] += 1
                break
            node_index = node[2] if vector[node[0]] <= node[1] else node[3]

    tree_count = len(model["trees"])
    scores = {label: votes[index] / tree_count for index, label in enumerate(labels)}
    order = sorted(range(len(labels)), key=lambda index: votes[index], reverse=True)
    best = order[0]
    second = order[1] if len(order) > 1 else best
    margin = (votes[best] - votes[second]) / tree_count
    return labels[best], scores, margin
