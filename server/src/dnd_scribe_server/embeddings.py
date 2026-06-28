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


import logging
import time

logger = logging.getLogger("dnd_scribe")


def embed_texts(texts: list[str]) -> list[list[float]]:
    config = get_embedding_config()
    if not config.enabled:
        raise RuntimeError("Embedding service is not configured")

    payload = {"model": config.model, "input": texts}
    headers = {"Content-Type": "application/json"}
    if config.api_key:
        headers["Authorization"] = f"Bearer {config.api_key}"

    max_retries = 3
    backoff = 1.0
    for attempt in range(max_retries):
        try:
            with httpx.Client(timeout=15.0) as client:
                response = client.post(
                    f"{config.base_url.rstrip('/')}/embeddings",
                    json=payload,
                    headers=headers
                )
                if response.status_code in (429, 500, 502, 503, 504):
                    response.raise_for_status()
                response.raise_for_status()
                data = response.json()
                
                if not isinstance(data, dict) or "data" not in data:
                    raise ValueError(f"Invalid embedding response format: missing 'data' key. Response: {data}")
                
                items = data["data"]
                if not isinstance(items, list):
                    raise ValueError(f"Invalid embedding response format: 'data' is not a list. Response: {data}")
                
                sorted_items = sorted(items, key=lambda item: item.get("index", 0))
                embeddings = []
                for item in sorted_items:
                    if not isinstance(item, dict) or "embedding" not in item:
                        raise ValueError(f"Invalid embedding response format: missing 'embedding' key in item. Response: {data}")
                    embeddings.append(item["embedding"])
                
                return embeddings
        except (httpx.HTTPError, ValueError, KeyError) as e:
            if attempt == max_retries - 1:
                logger.exception(f"Embedding failed after {max_retries} attempts: {e}")
                raise
            time.sleep(backoff)
            backoff *= 2.0
    raise RuntimeError("Embedding failed due to unexpected flow")


def embed_text(text: str) -> list[float]:
    return embed_texts([text])[0]

