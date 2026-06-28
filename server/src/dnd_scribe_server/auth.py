import hmac
import os
import time

from fastapi import Header, HTTPException, Request, status

UI_AUTH_COOKIE = "dnd_scribe_ui_token"


def require_bearer_token(request: Request, authorization: str | None = Header(default=None)) -> None:
    expected = os.environ.get("DND_SCRIBE_AUTH_TOKEN", "")
    if not expected:
        return

    # 1. Bearer Header (Safe from CSRF)
    if authorization and authorization.lower().startswith("bearer "):
        token = authorization[7:].strip()
        if hmac.compare_digest(token, expected):
            return
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid bearer token")

    # 2. Cookie fallback (Requires CSRF validation for modifying requests)
    session_id = request.cookies.get(UI_AUTH_COOKIE)
    if session_id:
        from .storage import db
        with db() as conn:
            row = conn.execute(
                "SELECT expires_at FROM ui_sessions WHERE session_id = ?",
                (session_id,)
            ).fetchone()
        if row and int(row["expires_at"]) >= int(time.time()):
            if request.method in ("POST", "PUT", "DELETE", "PATCH"):
                csrf_cookie = request.cookies.get("dnd_scribe_csrf_token")
                csrf_header = request.headers.get("x-csrf-token")
                if not csrf_cookie or not csrf_header or not hmac.compare_digest(csrf_header, csrf_cookie):
                    raise HTTPException(status_code=403, detail="CSRF token validation failed")
            return

    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing or invalid authentication")

