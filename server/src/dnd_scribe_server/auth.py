import os

from fastapi import Header, HTTPException, Request, status

UI_AUTH_COOKIE = "dnd_scribe_ui_token"


def require_bearer_token(request: Request, authorization: str | None = Header(default=None)) -> None:
    expected = os.environ.get("DND_SCRIBE_AUTH_TOKEN", "")
    if not expected:
        return

    if not authorization or not authorization.startswith("Bearer "):
        cookie_token = request.cookies.get(UI_AUTH_COOKIE)
        if cookie_token == expected:
            return
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")

    token = authorization.removeprefix("Bearer ").strip()
    if token != expected:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid bearer token")
