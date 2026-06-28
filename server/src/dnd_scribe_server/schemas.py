from pydantic import BaseModel, Field, ConfigDict


class SessionBase(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str = Field(min_length=1, max_length=255)
    device_id: str | None = Field(default=None, max_length=255)
    name: str = Field(min_length=1, max_length=255)
    date: int = Field(ge=0)
    full_transcript: str = Field(default="", max_length=10000000)
    notes: str = Field(default="", max_length=5000000)
    final_summary: str = Field(default="", max_length=5000000)



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


class SessionFieldResponse(BaseModel):
    id: str
    name: str
    field: str
    content: str


class SessionChunkResponse(BaseModel):
    id: int
    session_id: str
    session_name: str
    chunk_index: int
    kind: str
    text: str


class SemanticMatchResponse(BaseModel):
    session_id: str
    session_name: str
    chunk_id: int
    chunk_index: int
    kind: str
    score: float
    text: str
