from __future__ import annotations

import os
from dataclasses import dataclass

import httpx


@dataclass(frozen=True)
class EmbeddingConfig:
    base_url: str
    api_key: str
    model: str

    @property
    def enabled(self) -> bool:
        return bool(self.base_url.strip()) and bool(self.model.strip())


def get_embedding_config() -> EmbeddingConfig:
    return EmbeddingConfig(
        base_url=os.environ.get("DND_SCRIBE_EMBEDDING_URL", "").strip(),
        api_key=os.environ.get("DND_SCRIBE_EMBEDDING_API_KEY", "").strip(),
        model=os.environ.get("DND_SCRIBE_EMBEDDING_MODEL", "embeddinggemma").strip(),
    )


def embed_texts(texts: list[str]) -> list[list[float]]:
    config = get_embedding_config()
    if not config.enabled:
        raise RuntimeError("Embedding service is not configured")

    payload = {"model": config.model, "input": texts}
    headers = {"Content-Type": "application/json"}
    if config.api_key:
        headers["Authorization"] = f"Bearer {config.api_key}"

    with httpx.Client(timeout=120.0) as client:
        response = client.post(f"{config.base_url.rstrip('/')}/embeddings", json=payload, headers=headers)
        response.raise_for_status()
        data = response.json()

    embeddings = [item["embedding"] for item in sorted(data["data"], key=lambda item: item["index"])]
    return embeddings


def embed_text(text: str) -> list[float]:
    return embed_texts([text])[0]
