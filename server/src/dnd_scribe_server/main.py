from __future__ import annotations

import html
import json
import os
import time

from fastapi import Depends, FastAPI, Form, HTTPException, Query, Request
from fastapi.responses import HTMLResponse, RedirectResponse

from .auth import UI_AUTH_COOKIE, require_bearer_token
from .chunking import chunk_text
from .embeddings import embed_texts, get_embedding_config
from .schemas import (
    SemanticMatchResponse,
    SessionChunkResponse,
    SessionCreate,
    SessionFieldResponse,
    SessionListItem,
    SessionRecord,
    SyncRequest,
    SyncResult,
)
from .semantic import SemanticMatch, cosine_similarity, decode_embedding
from .storage import db, initialize_database

app = FastAPI(title="DnD Scribe Server", version="0.1.0")


def _base_path() -> str:
    raw = os.environ.get("DND_SCRIBE_BASE_PATH", "").strip()
    if not raw:
        return ""
    return "/" + raw.strip("/")


BASE_PATH = _base_path()


@app.on_event("startup")
def on_startup() -> None:
    initialize_database()
    _backfill_session_chunks()
    _backfill_chunk_embeddings()


def _row_to_record(row) -> SessionRecord:
    return SessionRecord(
        id=row["id"],
        device_id=row["device_id"],
        name=row["name"],
        date=row["session_date"],
        full_transcript=row["full_transcript"],
        notes=row["notes"],
        final_summary=row["final_summary"],
        created_at=row["created_at"],
        updated_at=row["updated_at"],
    )


def _row_to_field_response(row, field: str, content: str) -> SessionFieldResponse:
    return SessionFieldResponse(
        id=row["id"],
        name=row["name"],
        field=field,
        content=content,
    )


def _session_text_chunks(row) -> list[tuple[str, int, str]]:
    pieces: list[tuple[str, int, str]] = []
    for kind, text in (("summary", row["final_summary"]), ("notes", row["notes"]), ("transcript", row["full_transcript"])):
        for chunk in chunk_text(text or "", kind=kind):
            pieces.append((kind, chunk.chunk_index, chunk.text))
    return pieces


def _session_chunk_texts(row) -> list[str]:
    return [text for _, _, text in _session_text_chunks(row)]


def _serialize_embedding(value: list[float] | None) -> str | None:
    return json.dumps(value) if value is not None else None


def _replace_session_chunks(conn, session_row) -> None:
    session_id = session_row["id"]
    conn.execute("DELETE FROM session_chunks WHERE session_id = ?", (session_id,))
    created_at = int(time.time() * 1000)
    chunks = _session_text_chunks(session_row)
    if not chunks:
        return

    embeddings: list[list[float]] | None = None
    if get_embedding_config().enabled:
        try:
            embeddings = _embed_in_batches([text for _, _, text in chunks])
        except Exception:
            embeddings = None

    for chunk, embedding in zip(chunks, embeddings or [None] * len(chunks)):
        kind, chunk_index, text = chunk
        conn.execute(
            """
            INSERT INTO session_chunks (
                session_id, chunk_index, kind, text, embedding, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(session_id, kind, chunk_index) DO UPDATE SET
                text = excluded.text,
                embedding = excluded.embedding,
                updated_at = excluded.updated_at
            """,
            (
                session_id,
                chunk_index,
                kind,
                text,
                _serialize_embedding(embedding),
                created_at,
                created_at,
            ),
        )


def _chunk_row_to_dict(row) -> dict[str, object]:
    return {
        "id": row["id"],
        "session_id": row["session_id"],
        "chunk_index": row["chunk_index"],
        "kind": row["kind"],
        "text": row["text"],
        "embedding": decode_embedding(row["embedding"]),
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


def _embed_in_batches(texts: list[str]) -> list[list[float]]:
    if not texts:
        return []
    # Keep batches modest for lightweight OpenAI-compatible embedding servers.
    batch_size = 16
    vectors: list[list[float]] = []
    for start in range(0, len(texts), batch_size):
        vectors.extend(embed_texts(texts[start:start + batch_size]))
    return vectors


def _backfill_session_chunks() -> None:
    with db() as conn:
        sessions = conn.execute("SELECT * FROM sessions ORDER BY session_date ASC, updated_at ASC").fetchall()
        for row in sessions:
            existing = conn.execute("SELECT 1 FROM session_chunks WHERE session_id = ? LIMIT 1", (row["id"],)).fetchone()
            if existing is None:
                _replace_session_chunks(conn, row)


def _backfill_chunk_embeddings() -> None:
    if not get_embedding_config().enabled:
        return

    try:
        with db() as conn:
            rows = conn.execute(
                "SELECT id, text FROM session_chunks WHERE embedding IS NULL OR embedding = '' ORDER BY id ASC"
            ).fetchall()
            if not rows:
                return

            vectors = _embed_in_batches([row["text"] for row in rows])
            for row, vector in zip(rows, vectors):
                conn.execute(
                    "UPDATE session_chunks SET embedding = ?, updated_at = ? WHERE id = ?",
                    (_serialize_embedding(vector), int(time.time() * 1000), row["id"]),
                )
    except Exception:
        return


def _preview(text: str, limit: int = 180) -> str:
    clean = " ".join(text.split())
    return clean[:limit]


def _html_escape(text: str | None) -> str:
    return html.escape(text or "")


def _ui_path(path: str) -> str:
    if not path.startswith("/"):
        path = "/" + path
    return f"{BASE_PATH}{path}" if BASE_PATH else path


def _ui_expected_token() -> str:
    return os.environ.get("DND_SCRIBE_AUTH_TOKEN", "")


def _ui_is_authenticated(request: Request) -> bool:
    expected = _ui_expected_token()
    if not expected:
        return True
    return request.cookies.get(UI_AUTH_COOKIE) == expected


def _ui_login_page(next_path: str = "/", message: str = "") -> HTMLResponse:
    body = f'''
<h1>DnD Scribe</h1>
<div class="card">
  <form method="post" action="{_ui_path('/ui/login')}">
    <input type="hidden" name="next" value="{_html_escape(next_path)}" />
    <label>Access token</label>
    <input name="token" type="password" autofocus placeholder="Bearer token" />
    <button type="submit">Log in</button>
  </form>
  {'<p class="danger">' + _html_escape(message) + '</p>' if message else ''}
</div>
'''
    return _render_page("DnD Scribe Login", body)


def _fetch_session_by_id(session_id: str):
    with db() as conn:
        return conn.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()


def _fetch_latest_session():
    with db() as conn:
        return conn.execute(
            "SELECT * FROM sessions ORDER BY session_date DESC, updated_at DESC LIMIT 1"
        ).fetchone()


def _render_page(title: str, body: str) -> HTMLResponse:
    return HTMLResponse(
        f"""<!doctype html>
<html>
<head>
  <meta charset=\"utf-8\" />
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
  <title>{_html_escape(title)}</title>
  <style>
    body {{ font-family: system-ui, sans-serif; margin: 24px; max-width: 1100px; }}
    .row {{ display: flex; gap: 12px; flex-wrap: wrap; }}
    .card {{ border: 1px solid #ccc; border-radius: 8px; padding: 12px; margin: 12px 0; }}
    input, textarea {{ width: 100%; box-sizing: border-box; padding: 8px; margin: 4px 0 12px; }}
    textarea {{ min-height: 180px; }}
    a {{ text-decoration: none; }}
    .muted {{ color: #666; }}
    .danger {{ color: #900; }}
    .list-item {{ padding: 10px 0; border-bottom: 1px solid #eee; }}
    .small {{ font-size: 0.9rem; }}
    button {{ padding: 8px 12px; }}
    .toolbar {{ display: flex; gap: 8px; align-items: end; flex-wrap: wrap; }}
    .toolbar > div {{ flex: 1; min-width: 220px; }}
  </style>
</head>
<body>
{body}
<script>
const tokenKey = 'dnd_scribe_token';
const basePath = {BASE_PATH!r};

function uiPath(path) {{
  if (!path.startsWith('/')) path = '/' + path;
  return basePath ? `${{basePath}}${{path}}` : path;
}}

function getToken() {{
  return localStorage.getItem(tokenKey) || '';
}}

function setToken(value) {{
  localStorage.setItem(tokenKey, value || '');
  const fields = document.querySelectorAll('[data-token-input]');
  fields.forEach(field => {{ field.value = value || ''; }});
}}

async function apiFetch(url, options = {{}}) {{
  const headers = new Headers(options.headers || {{}});
  const token = getToken();
  if (token) headers.set('Authorization', `Bearer ${{token}}`);
  if (options.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  return fetch(url, {{ ...options, headers }});
}}

async function deleteSession(sessionId) {{
  if (!confirm('Delete this session?')) return;
  const response = await apiFetch(uiPath(`/sessions/${{encodeURIComponent(sessionId)}}`), {{ method: 'DELETE' }});
  if (!response.ok) {{
    alert(await response.text());
    return;
  }}
  window.location.href = uiPath('/');
}}

function installTokenInputs() {{
  document.querySelectorAll('[data-token-input]').forEach(field => {{
    field.value = getToken();
    field.addEventListener('change', () => setToken(field.value));
  }});
}}

document.addEventListener('DOMContentLoaded', installTokenInputs);
</script>
</body>
</html>"""
    )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/ui/login", response_class=HTMLResponse)
def ui_login_page(request: Request, next: str = "/") -> HTMLResponse:
    if _ui_is_authenticated(request):
        return RedirectResponse(url=_ui_path(next), status_code=303)
    return _ui_login_page(next_path=next)


@app.post("/ui/login")
def ui_login(token: str = Form(...), next: str = Form("/")) -> RedirectResponse:
    expected = _ui_expected_token()
    if expected and token != expected:
        return _ui_login_page(next_path=next, message="Invalid token")

    response = RedirectResponse(url=_ui_path(next), status_code=303)
    if expected:
        response.set_cookie(
            UI_AUTH_COOKIE,
            expected,
            httponly=True,
            samesite="lax",
            secure=False,
            path=BASE_PATH or "/",
        )
    return response


@app.get("/", response_class=HTMLResponse)
def index(request: Request, q: str = "") -> HTMLResponse:
    if not _ui_is_authenticated(request):
        return _ui_login_page(next_path=_ui_path("/"))

    semantic_mode = bool(q.strip()) and get_embedding_config().enabled
    items = []
    if semantic_mode:
        try:
            results = _semantic_matches(q.strip(), limit=20)
            for match in results:
                items.append(
                    f'''<div class="list-item">
  <div><a href="{_ui_path(f'/ui/sessions/{match.session_id}')}" ><strong>{_html_escape(match.session_name)}</strong></a></div>
  <div class="muted small">{_html_escape(match.kind)} · score {match.score:.3f} · chunk {match.chunk_index}</div>
  <div class="small">{_html_escape(_preview(match.text, 240))}</div>
</div>'''
                )
        except HTTPException:
            semantic_mode = False
    else:
        with db() as conn:
            if q.strip():
                pattern = f"%{q}%"
                rows = conn.execute(
                    """
                    SELECT * FROM sessions
                    WHERE name LIKE ? OR notes LIKE ? OR final_summary LIKE ? OR full_transcript LIKE ?
                    ORDER BY session_date DESC, updated_at DESC
                    LIMIT 100
                    """,
                    (pattern, pattern, pattern, pattern),
                ).fetchall()
            else:
                rows = conn.execute(
                    "SELECT * FROM sessions ORDER BY session_date DESC, updated_at DESC LIMIT 100"
                ).fetchall()

        for row in rows:
            items.append(
                f'''<div class="list-item">
  <div><a href="{_ui_path(f'/ui/sessions/{row["id"]}')}" ><strong>{_html_escape(row["name"])}</strong></a></div>
  <div class="muted small">{row["id"]} · {row["session_date"]}</div>
  <div class="small">{_html_escape(_preview(row["final_summary"] or row["notes"] or row["full_transcript"]))}</div>
</div>'''
            )

    body = f'''
<h1>DnD Scribe</h1>
<div class="card">
  <div class="toolbar">
    <div>
      <label>Auth token</label>
      <input data-token-input type="password" placeholder="Bearer token for API calls" />
    </div>
    <div class="small muted">Stored in your browser only. Used for delete and any authenticated API calls from this UI.</div>
  </div>
</div>
<div class="card">
  <form method="get" action="{_ui_path('/')}">
    <label>Search</label>
    <input name="q" value="{_html_escape(q)}" placeholder="Search sessions" />
    <button type="submit">{'Semantic Search' if semantic_mode else 'Search'}</button>
  </form>
</div>
<div class="card">
  <div class="muted">{'Semantic matches' if semantic_mode else 'Recent sessions'}</div>
  {''.join(items) if items else '<p class="muted">No sessions yet.</p>'}
</div>
'''
    return _render_page("DnD Scribe", body)


@app.get("/ui/sessions/{session_id}", response_class=HTMLResponse)
def session_page(request: Request, session_id: str) -> HTMLResponse:
    if not _ui_is_authenticated(request):
        return _ui_login_page(next_path=_ui_path(f"/ui/sessions/{session_id}"))

    with db() as conn:
        row = conn.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Session not found")

    body = f'''
<p><a href="{_ui_path('/')}">&larr; Back</a></p>
<h1>{_html_escape(row["name"])}</h1>
<div class="card">
  <label>Auth token</label>
  <input data-token-input type="password" placeholder="Bearer token for API calls" />
</div>
<div class="card">
  <div><strong>ID:</strong> {_html_escape(row["id"])}</div>
  <div><strong>Device:</strong> {_html_escape(row["device_id"])} </div>
  <div><strong>Date:</strong> {row["session_date"]}</div>
  <div><strong>Created:</strong> {row["created_at"]}</div>
  <div><strong>Updated:</strong> {row["updated_at"]}</div>
</div>
<div class="card">
  <button class="danger" type="button" onclick="deleteSession('{_html_escape(row['id'])}')">Delete</button>
</div>
<div class="card"><h3>Summary</h3><pre>{_html_escape(row["final_summary"])}</pre></div>
<div class="card"><h3>Notes</h3><pre>{_html_escape(row["notes"])}</pre></div>
<div class="card"><h3>Transcript</h3><pre>{_html_escape(row["full_transcript"])}</pre></div>
'''
    return _render_page(row["name"], body)


@app.post("/sessions", response_model=SessionRecord, dependencies=[Depends(require_bearer_token)])
def upsert_session(session: SessionCreate) -> SessionRecord:
    now = int(time.time() * 1000)
    with db() as conn:
        existing = conn.execute("SELECT created_at FROM sessions WHERE id = ?", (session.id,)).fetchone()
        created_at = int(existing["created_at"]) if existing else now
        updated_at = session.updated_at or now
        conn.execute(
            """
            INSERT INTO sessions (
                id, device_id, name, session_date, full_transcript, notes, final_summary, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                device_id = excluded.device_id,
                name = excluded.name,
                session_date = excluded.session_date,
                full_transcript = excluded.full_transcript,
                notes = excluded.notes,
                final_summary = excluded.final_summary,
                updated_at = excluded.updated_at
            """,
            (
                session.id,
                session.device_id,
                session.name,
                session.date,
                session.full_transcript,
                session.notes,
                session.final_summary,
                created_at,
                updated_at,
            ),
        )
        row = conn.execute("SELECT * FROM sessions WHERE id = ?", (session.id,)).fetchone()
        _replace_session_chunks(conn, row)
    return _row_to_record(row)


@app.post("/sync", response_model=SyncResult, dependencies=[Depends(require_bearer_token)])
def sync_sessions(payload: SyncRequest) -> SyncResult:
    upserted: list[SessionRecord] = []
    for session in payload.sessions:
        upserted.append(upsert_session(session))
    return SyncResult(upserted=len(upserted), sessions=upserted)


@app.get("/sessions", response_model=list[SessionListItem], dependencies=[Depends(require_bearer_token)])
def list_sessions(limit: int = Query(default=50, ge=1, le=500), offset: int = Query(default=0, ge=0)) -> list[SessionListItem]:
    with db() as conn:
        rows = conn.execute(
            """
            SELECT * FROM sessions
            ORDER BY session_date DESC, updated_at DESC
            LIMIT ? OFFSET ?
            """,
            (limit, offset),
        ).fetchall()

    return [
        SessionListItem(
            id=row["id"],
            device_id=row["device_id"],
            name=row["name"],
            date=row["session_date"],
            created_at=row["created_at"],
            updated_at=row["updated_at"],
            summary_preview=_preview(row["final_summary"] or row["notes"] or row["full_transcript"]),
            notes_preview=_preview(row["notes"]),
        )
        for row in rows
    ]


@app.get("/sessions/latest", response_model=SessionRecord, dependencies=[Depends(require_bearer_token)])
def get_latest_session() -> SessionRecord:
    row = _fetch_latest_session()
    if row is None:
        raise HTTPException(status_code=404, detail="Session not found")
    return _row_to_record(row)


@app.get("/sessions/latest/summary", response_model=SessionFieldResponse, dependencies=[Depends(require_bearer_token)])
def get_latest_summary() -> SessionFieldResponse:
    row = _fetch_latest_session()
    if row is None:
        raise HTTPException(status_code=404, detail="Session not found")
    return _row_to_field_response(row, "summary", row["final_summary"])


@app.get("/sessions/latest/notes", response_model=SessionFieldResponse, dependencies=[Depends(require_bearer_token)])
def get_latest_notes() -> SessionFieldResponse:
    row = _fetch_latest_session()
    if row is None:
        raise HTTPException(status_code=404, detail="Session not found")
    return _row_to_field_response(row, "notes", row["notes"])


@app.get("/sessions/latest/transcript", response_model=SessionFieldResponse, dependencies=[Depends(require_bearer_token)])
def get_latest_transcript() -> SessionFieldResponse:
    row = _fetch_latest_session()
    if row is None:
        raise HTTPException(status_code=404, detail="Session not found")
    return _row_to_field_response(row, "transcript", row["full_transcript"])


@app.get("/sessions/search", response_model=list[SessionListItem], dependencies=[Depends(require_bearer_token)])
def search_sessions(q: str, limit: int = Query(default=50, ge=1, le=500)) -> list[SessionListItem]:
    pattern = f"%{q}%"
    with db() as conn:
        rows = conn.execute(
            """
            SELECT * FROM sessions
            WHERE name LIKE ? OR notes LIKE ? OR final_summary LIKE ? OR full_transcript LIKE ?
            ORDER BY session_date DESC, updated_at DESC
            LIMIT ?
            """,
            (pattern, pattern, pattern, pattern, limit),
        ).fetchall()

    return [
        SessionListItem(
            id=row["id"],
            device_id=row["device_id"],
            name=row["name"],
            date=row["session_date"],
            created_at=row["created_at"],
            updated_at=row["updated_at"],
            summary_preview=_preview(row["final_summary"] or row["notes"] or row["full_transcript"]),
            notes_preview=_preview(row["notes"]),
        )
        for row in rows
    ]


def _chunk_row_to_response(row) -> SessionChunkResponse:
    return SessionChunkResponse(
        id=row["id"],
        session_id=row["session_id"],
        session_name=row["session_name"],
        chunk_index=row["chunk_index"],
        kind=row["kind"],
        text=row["text"],
    )


def _semantic_matches(q: str, limit: int = 10) -> list[SemanticMatchResponse]:
    _ensure_embeddings_available()
    try:
        query_embedding = embed_texts([q])[0]
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"Embedding query failed: {exc}") from exc

    _backfill_chunk_embeddings()

    matches: list[SemanticMatch] = []
    with db() as conn:
        rows = conn.execute(
            """
            SELECT sc.id AS chunk_id, sc.session_id, s.name AS session_name, sc.chunk_index, sc.kind, sc.text, sc.embedding
            FROM session_chunks sc
            JOIN sessions s ON s.id = sc.session_id
            WHERE sc.embedding IS NOT NULL AND sc.embedding != ''
            """,
        ).fetchall()

    for row in rows:
        embedding = decode_embedding(row["embedding"])
        if embedding is None:
            continue
        score = cosine_similarity(query_embedding, embedding)
        matches.append(
            SemanticMatch(
                session_id=row["session_id"],
                session_name=row["session_name"],
                chunk_id=row["chunk_id"],
                chunk_index=row["chunk_index"],
                kind=row["kind"],
                score=score,
                text=row["text"],
            )
        )

    matches.sort(key=lambda match: match.score, reverse=True)
    return [
        SemanticMatchResponse(
            session_id=match.session_id,
            session_name=match.session_name,
            chunk_id=match.chunk_id,
            chunk_index=match.chunk_index,
            kind=match.kind,
            score=match.score,
            text=match.text,
        )
        for match in matches[:limit]
    ]


@app.get("/sessions/latest/chunks", response_model=list[SessionChunkResponse], dependencies=[Depends(require_bearer_token)])
def get_latest_chunks() -> list[SessionChunkResponse]:
    row = _fetch_latest_session()
    if row is None:
        raise HTTPException(status_code=404, detail="Session not found")
    return get_session_chunks(row["id"])


@app.get("/sessions/{session_id}/chunks", response_model=list[SessionChunkResponse], dependencies=[Depends(require_bearer_token)])
def get_session_chunks(session_id: str) -> list[SessionChunkResponse]:
    with db() as conn:
        rows = conn.execute(
            """
            SELECT sc.id, sc.session_id, s.name AS session_name, sc.chunk_index, sc.kind, sc.text
            FROM session_chunks sc
            JOIN sessions s ON s.id = sc.session_id
            WHERE sc.session_id = ?
            ORDER BY sc.kind, sc.chunk_index
            """,
            (session_id,),
        ).fetchall()
    return [_chunk_row_to_response(row) for row in rows]


def _ensure_embeddings_available() -> None:
    if not get_embedding_config().enabled:
        raise HTTPException(status_code=400, detail="Embedding service is not configured")


@app.get("/search/semantic", response_model=list[SemanticMatchResponse], dependencies=[Depends(require_bearer_token)])
def search_semantic(q: str, limit: int = Query(default=10, ge=1, le=50)) -> list[SemanticMatchResponse]:
    return _semantic_matches(q, limit=limit)


@app.get("/sessions/{session_id}/summary", response_model=SessionFieldResponse, dependencies=[Depends(require_bearer_token)])
def get_session_summary(session_id: str) -> SessionFieldResponse:
    row = _fetch_session_by_id(session_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Session not found")
    return _row_to_field_response(row, "summary", row["final_summary"])


@app.get("/sessions/{session_id}/notes", response_model=SessionFieldResponse, dependencies=[Depends(require_bearer_token)])
def get_session_notes(session_id: str) -> SessionFieldResponse:
    row = _fetch_session_by_id(session_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Session not found")
    return _row_to_field_response(row, "notes", row["notes"])


@app.get("/sessions/{session_id}/transcript", response_model=SessionFieldResponse, dependencies=[Depends(require_bearer_token)])
def get_session_transcript(session_id: str) -> SessionFieldResponse:
    row = _fetch_session_by_id(session_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Session not found")
    return _row_to_field_response(row, "transcript", row["full_transcript"])


@app.get("/sessions/{session_id}", response_model=SessionRecord, dependencies=[Depends(require_bearer_token)])
def get_session(session_id: str) -> SessionRecord:
    with db() as conn:
        row = conn.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Session not found")
    return _row_to_record(row)


@app.delete("/sessions/{session_id}", dependencies=[Depends(require_bearer_token)])
def delete_session(session_id: str) -> dict[str, str]:
    with db() as conn:
        conn.execute("DELETE FROM session_chunks WHERE session_id = ?", (session_id,))
        result = conn.execute("DELETE FROM sessions WHERE id = ?", (session_id,))
        if result.rowcount == 0:
            raise HTTPException(status_code=404, detail="Session not found")
    return {"status": "deleted", "id": session_id}
