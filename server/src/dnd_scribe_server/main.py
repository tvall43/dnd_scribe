from __future__ import annotations

import html
import time

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.responses import HTMLResponse, RedirectResponse

from .auth import require_bearer_token
from .schemas import SessionCreate, SessionListItem, SessionRecord, SyncRequest, SyncResult
from .storage import db, initialize_database

app = FastAPI(title="DnD Scribe Server", version="0.1.0")


@app.on_event("startup")
def on_startup() -> None:
    initialize_database()


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


def _preview(text: str, limit: int = 180) -> str:
    clean = " ".join(text.split())
    return clean[:limit]


def _html_escape(text: str | None) -> str:
    return html.escape(text or "")


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
  const response = await apiFetch(`/sessions/${{encodeURIComponent(sessionId)}}`, {{ method: 'DELETE' }});
  if (!response.ok) {{
    alert(await response.text());
    return;
  }}
  window.location.href = '/';
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


@app.get("/", response_class=HTMLResponse)
def index(request: Request, q: str = "") -> HTMLResponse:
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

    items = []
    for row in rows:
        items.append(
            f'''<div class="list-item">
  <div><a href="/ui/sessions/{row["id"]}"><strong>{_html_escape(row["name"])}</strong></a></div>
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
  <form method="get" action="/">
    <label>Search</label>
    <input name="q" value="{_html_escape(q)}" placeholder="Search sessions" />
    <button type="submit">Search</button>
  </form>
</div>
<div class="card">
  <div class="muted">Recent sessions</div>
  {''.join(items) if items else '<p class="muted">No sessions yet.</p>'}
</div>
'''
    return _render_page("DnD Scribe", body)


@app.get("/ui/sessions/{session_id}", response_class=HTMLResponse)
def session_page(session_id: str) -> HTMLResponse:
    with db() as conn:
        row = conn.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Session not found")

    body = f'''
<p><a href="/">&larr; Back</a></p>
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
        result = conn.execute("DELETE FROM sessions WHERE id = ?", (session_id,))
        if result.rowcount == 0:
            raise HTTPException(status_code=404, detail="Session not found")
    return {"status": "deleted", "id": session_id}


@app.post("/sessions/{session_id}")
def delete_via_form(session_id: str, request: Request) -> RedirectResponse:
    method = request.query_params.get("_method", "").lower()
    if method != "delete":
        raise HTTPException(status_code=405, detail="Method not allowed")

    with db() as conn:
        result = conn.execute("DELETE FROM sessions WHERE id = ?", (session_id,))
        if result.rowcount == 0:
            raise HTTPException(status_code=404, detail="Session not found")
    return RedirectResponse(url="/", status_code=303)
