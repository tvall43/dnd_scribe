from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Chunk:
    kind: str
    chunk_index: int
    text: str


def chunk_text(text: str, kind: str, target_words: int = 375, overlap_words: int = 50) -> list[Chunk]:
    cleaned = text.strip()
    if not cleaned:
        return []

    paragraphs = [paragraph.strip() for paragraph in cleaned.split("\n\n") if paragraph.strip()]
    chunks: list[str] = []
    current_parts: list[str] = []
    current_words = 0

    def flush() -> None:
        nonlocal current_parts, current_words
        if current_parts:
            chunks.append("\n\n".join(current_parts).strip())
        current_parts = []
        current_words = 0

    for paragraph in paragraphs:
        words = paragraph.split()
        if len(words) > target_words:
            flush()
            chunks.extend(_chunk_words(words, target_words, overlap_words))
            continue

        if current_words and current_words + len(words) > target_words:
            flush()

        current_parts.append(paragraph)
        current_words += len(words)

    flush()
    return [Chunk(kind=kind, chunk_index=index, text=chunk) for index, chunk in enumerate(chunks)]


def _chunk_words(words: list[str], target_words: int, overlap_words: int) -> list[str]:
    if not words:
        return []

    chunks: list[str] = []
    start = 0
    step = max(1, target_words - overlap_words)
    while start < len(words):
        end = min(len(words), start + target_words)
        chunks.append(" ".join(words[start:end]).strip())
        if end >= len(words):
            break
        start += step
    return [chunk for chunk in chunks if chunk]
