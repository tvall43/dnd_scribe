# DnD Scribe Server

Small FastAPI backend for storing and querying session archives.

## Run

```bash
export DND_SCRIBE_AUTH_TOKEN="change-me"
export DND_SCRIBE_DB_PATH="data/sessions.sqlite"
uvicorn dnd_scribe_server.main:app --app-dir src --host 0.0.0.0 --port 8000
```

## Endpoints

- `GET /health`
- `GET /`
- `GET /ui/sessions/{id}`
- `POST /sessions`
- `POST /sync`
- `GET /sessions`
- `GET /sessions/{id}`
- `DELETE /sessions/{id}`
- `GET /sessions/search?q=...`

## HTML UI

Open `/` in a browser to browse recent sessions and view details.
The token field is stored in `localStorage` and used for browser-side API calls like delete.

## Auth

If `DND_SCRIBE_AUTH_TOKEN` is set, requests must send:

```http
Authorization: Bearer <token>
```
