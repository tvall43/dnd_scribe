from pydantic import BaseModel, Field


class SessionBase(BaseModel):
    id: str = Field(min_length=1)
    device_id: str | None = None
    name: str = Field(min_length=1)
    date: int
    full_transcript: str = ""
    notes: str = ""
    final_summary: str = ""


class SessionCreate(SessionBase):
    updated_at: int | None = None


class SessionRecord(SessionBase):
    created_at: int
    updated_at: int


class SessionListItem(BaseModel):
    id: str
    device_id: str | None = None
    name: str
    date: int
    created_at: int
    updated_at: int
    summary_preview: str = ""
    notes_preview: str = ""


class SyncResult(BaseModel):
    upserted: int
    sessions: list[SessionRecord]


class SyncRequest(BaseModel):
    sessions: list[SessionCreate]
