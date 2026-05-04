# AGENTS.md

## Purpose

This repo contains an Android app plus a small FastAPI backend. Use the app for live session capture and the server for archive, search, and cloud sync.

## Working Rules

- Inspect current state before editing: `git status`, `git diff`, `git log -5 --oneline`.
- Prefer the smallest correct change.
- Use `apply_patch` for file edits.
- Do not commit unless the user explicitly asks.
- Do not overwrite unrelated user changes.

## Android App

- Build with `./gradlew assembleDebug` from the repo root.
- Keep recording logic reliable; background recording is a requirement.
- Preserve existing local-first behavior when cloud sync is disabled.

## Server

- Server code lives in `server/`.
- Run syntax checks with `python -m compileall server/src`.
- The backend is FastAPI + SQLite.
- HTML UI is behind cookie login when `DND_SCRIBE_AUTH_TOKEN` is set.
- API endpoints use bearer auth.

## Search / Embeddings

- Transcripts are chunked before indexing.
- Prefer semantic search for human queries when embeddings are configured.
- Embedding service settings come from env vars:
  - `DND_SCRIBE_EMBEDDING_URL`
  - `DND_SCRIBE_EMBEDDING_API_KEY`
  - `DND_SCRIBE_EMBEDDING_MODEL`

## Useful Commands

- Android build: `./gradlew assembleDebug`
- Server syntax check: `python -m compileall server/src`
