import os

from fastapi import Header, HTTPException, status


def require_bearer_token(authorization: str | None = Header(default=None)) -> None:
    expected = os.environ.get("DND_SCRIBE_AUTH_TOKEN", "")
    if not expected:
        return

    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")

    token = authorization.removeprefix("Bearer ").strip()
    if token != expected:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid bearer token")
