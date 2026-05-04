from __future__ import annotations

import os
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator


def _db_path() -> Path:
    raw = os.environ.get("DND_SCRIBE_DB_PATH", "data/sessions.sqlite")
    return Path(raw)


def initialize_database() -> None:
    path = _db_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(path) as conn:
        conn.execute("PRAGMA foreign_keys = ON;")
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT PRIMARY KEY,
                device_id TEXT,
                name TEXT NOT NULL,
                session_date INTEGER NOT NULL,
                full_transcript TEXT NOT NULL,
                notes TEXT NOT NULL,
                final_summary TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_sessions_date ON sessions(session_date DESC)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_sessions_name ON sessions(name)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_sessions_updated ON sessions(updated_at DESC)")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS session_chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                kind TEXT NOT NULL,
                text TEXT NOT NULL,
                embedding TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE,
                UNIQUE(session_id, kind, chunk_index)
            )
            """
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_session_chunks_session_id ON session_chunks(session_id)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_session_chunks_kind ON session_chunks(kind)")
        conn.commit()


def get_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(_db_path())
    conn.execute("PRAGMA foreign_keys = ON;")
    conn.row_factory = sqlite3.Row
    return conn


@contextmanager
def db() -> Iterator[sqlite3.Connection]:
    conn = get_connection()
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()
