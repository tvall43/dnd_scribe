from __future__ import annotations

import json
import math
from dataclasses import dataclass


@dataclass(frozen=True)
class SemanticMatch:
    session_id: str
    session_name: str
    chunk_id: int
    chunk_index: int
    kind: str
    score: float
    text: str


def cosine_similarity(left: list[float], right: list[float]) -> float:
    if not left or not right or len(left) != len(right):
        return 0.0
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(a * a for a in left))
    right_norm = math.sqrt(sum(b * b for b in right))
    if left_norm == 0 or right_norm == 0:
        return 0.0
    return dot / (left_norm * right_norm)


def decode_embedding(raw: str | None) -> list[float] | None:
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None

