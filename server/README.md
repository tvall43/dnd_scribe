# DnD Scribe Server

Small FastAPI backend for storing and querying session archives.

## Run

```bash
export DND_SCRIBE_AUTH_TOKEN="change-me"
export DND_SCRIBE_BASE_PATH="/scribe"
export DND_SCRIBE_DB_PATH="data/sessions.sqlite"
export DND_SCRIBE_EMBEDDING_URL="http://your-litellm-host:4000/v1"
export DND_SCRIBE_EMBEDDING_API_KEY="optional-key"
export DND_SCRIBE_EMBEDDING_MODEL="embeddinggemma"
uvicorn dnd_scribe_server.main:app --app-dir src --host 0.0.0.0 --port 8000
```

`DND_SCRIBE_EMBEDDING_URL` should point at the OpenAI-compatible embedding API root for your provider. For LiteLLM and similar servers that follow the OpenAI layout, that is usually the `/v1` base path shown above; if your provider exposes embeddings somewhere else, set the URL to match that server's route structure.

## Endpoints

- `GET /health`
- `GET /`
- `GET /ui/sessions/{id}`
- `POST /sessions`
- `POST /sync`
- `GET /sessions`
- `GET /sessions/latest`
- `GET /sessions/{id}`
- `GET /sessions/latest/chunks`
- `GET /sessions/{id}/chunks`
- `GET /sessions/{id}/summary`
- `GET /sessions/{id}/notes`
- `GET /sessions/{id}/transcript`
- `DELETE /sessions/{id}`
- `GET /sessions/search?q=...`
- `GET /search/semantic?q=...`

## HTML UI

Open `/` in a browser to browse recent sessions and view details.
If `DND_SCRIBE_AUTH_TOKEN` is set, the browser UI requires a login cookie at `/ui/login`.
The browser UI can browse and delete after login; API scripts still use bearer auth.

If you host behind a prefix like `/scribe/`, set `DND_SCRIBE_BASE_PATH=/scribe`.

## Auth

If `DND_SCRIBE_AUTH_TOKEN` is set, requests must send:

```http
Authorization: Bearer <token>
```
