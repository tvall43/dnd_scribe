# DnD Scribe Todo

This is the running list of follow-up work from the code reviews. Items are ordered roughly by priority and dependency. New items from the full-codebase review are grouped under "Review Findings" below.

## Immediate

- [x] Remove the unauthenticated HTML form delete path and use the authenticated API for UI deletes.
- [x] Delete `session_chunks` when a session is deleted, with SQLite foreign-key cascade as a safety net.
- [x] Split network timeouts so Whisper stays at a shorter window and the LLM gets a longer one.
- [x] Align Whisper model defaults across settings sources.
- [x] Document the embedding API base URL expectation in `server/README.md`.

## Server Stability

- [x] Make semantic backfill resilient to partial failures so one bad batch does not stop the rest of the index build.
- [x] Preserve partial embedding progress during batch failures instead of discarding earlier successful batches.
- [x] Add logging or status reporting for embedding backfill failures so missing vectors are visible.
- [ ] Decide whether semantic search should remain in-process Python cosine similarity or move toward a real vector index as the corpus grows.

## Android Reliability

- [x] Fix `RecordingService` restart logic to handle null intents from `START_STICKY` (ensure service either resumes recording or shuts down).
- [x] Move to unique filenames for all recording segments immediately to prevent the `recording.webm` overwrite risk during rotation crashes.
- [ ] Consider a `WorkManager` fallback or other resiliency path for periodic cloud sync when the foreground service is throttled.

## Sync & Data Flow

- [x] Add a pull/sync-down path from server to Android so server-side changes and deletes are reflected locally.
- [ ] Define clear conflict handling for local edits versus server updates.
- [ ] Revisit `updated_at` semantics so null or stale values do not cause unnecessary churn.

## Search & Archives

- [ ] Move archive filtering on Android from in-memory filtering to a Room query for better scaling.
- [ ] Decide how semantic results should be presented in the web UI once it grows beyond a debug endpoint.
- [ ] Add clearer distinctions between session-level and chunk-level search results if semantic search stays exposed.
- [ ] Audit app buttons and add clearer labels/tooltips so their purpose is obvious without extra thought.
- [x] Add local settings backup/restore so app configuration can survive reinstall or device changes.

## Security / Hardening

- [ ] Decide whether the browser auth cookie should become `Secure` in production deployments.
- [ ] Revisit browser token storage if the web UI becomes user-facing.
- [x] Add any needed server-side audit or access logging for destructive operations.

## Future Web Client

- [ ] Build a proper web client for archive management.
- [ ] Reimplement the app's flow in the web client once the archive and sync model is finalized.
- [ ] Replace the current debug-oriented HTML UI when the real client is ready.

## Review Findings — Blockers

These came out of the full-codebase review and should land before relying on the system for real sessions or any exposure beyond a personal LAN.

### Android recording correctness

- [ ] Serialize `RecordingService` start/stop with a `Mutex` so concurrent start intents and `START_STICKY` redelivery cannot leak a second `MediaRecorder` or orphan a chunk file.
- [ ] Move `MediaRecorder.prepare/start/stop/release` off `Dispatchers.Main.immediate` to a dedicated IO dispatcher; keep `StateFlow` updates on Main.
- [ ] Gate `toggleRecording` on a runtime `RECORD_AUDIO` check and request `POST_NOTIFICATIONS` on API 33+ before starting the foreground service.
- [ ] Handle `MediaRecorder.stop()` exceptions on too-short / failed chunks: delete the partial file, null out `currentAudioFile`, skip transcription for empty captures.
- [ ] Replace the dead `START_STICKY` redelivery branch with a real resume path that either restarts a recorder cleanly or deletes the orphan `activeAudioFile` and clears state.
- [ ] Quarantine failed audio chunks instead of unconditionally deleting them in `transcribeFile`'s `finally`; add bounded exponential-backoff retry for Whisper.

### Android security

- [ ] Make cleartext HTTP actually opt-in: drop `usesCleartextTraffic="true"` from the manifest and constrain `network_security_config.xml` so HTTPS→HTTP redirects cannot silently downgrade.
- [ ] Move API keys (`whisperApiKey`, `llmApiKey`, `cloudApiKey`) into `EncryptedSharedPreferences` or otherwise off plaintext DataStore.
- [ ] Set `android:allowBackup="false"` (or write extraction rules) so secrets cannot leak via auto-backup or `adb backup`.

### Server auth / security

- [x] Replace the UI cookie-as-API-token model: generate a random opaque session id on login, store `(session_id → expiry)` server-side or sign with a separate `DND_SCRIBE_COOKIE_SECRET`.
- [x] Default the UI auth cookie to `Secure=True` with an explicit `DND_SCRIBE_COOKIE_INSECURE` escape hatch for local dev.
- [x] Replace all token equality checks in `auth.py` and the login handler with `hmac.compare_digest`.
- [x] Add CSRF protection (double-submit cookie or per-request token) on every cookie-authed non-GET route, including `/ui/login`, `POST /sessions`, `POST /sync`, and `DELETE /sessions/{id}`.
- [x] Fix the `/ui/login?next=...` open redirect by rejecting values that contain `:`, start with `//`, or are not strict absolute paths within the app.

### Server scaling primitives

- [x] Move embedding HTTP work and chunk re-indexing out of `POST /sessions` and `POST /sync` request paths into a background task or queue with bounded concurrency.
- [x] Stop running `_backfill_chunk_embeddings` on every `/search/semantic` call; do it on startup and via an explicit admin endpoint or scheduler.
- [x] Embed batches independently with retry on 429/5xx and persist successful batches; stop discarding the whole session's embeddings on a single batch failure.
- [x] Validate the embedding response shape, lock the vector dimension at first write, and refuse mixed-dimension chunks if `DND_SCRIBE_EMBEDDING_MODEL` changes.
- [x] Drop the embed HTTP timeout from 120 s to ~10–15 s with retry, once embedding work is off the request path.
- [ ] Pre-normalize chunk vectors at write time, store as `BLOB` float32, and replace per-request all-pairs cosine with a single NumPy `query @ M.T` over a cached matrix.

## Review Findings — High Priority

### Android data layer

- [ ] Declare a Room migration policy: add `MIGRATION_1_2` or `fallbackToDestructiveMigration*`, turn `exportSchema` on so future schema changes do not crash existing installs.
- [ ] Project a lightweight summary entity for the archives list (id, name, date, snippet) and load full transcripts only on detail open.
- [ ] Move archive search to a Room `LIKE` query instead of in-memory filtering on every keystroke.
- [ ] Move active-session transcript out of DataStore Preferences into a flat file or Room; keep DataStore for small scalars only.
- [ ] Deduplicate cloud sync by the server `id` (persist a `remoteId` column with a migration); use `updated_at` for last-write-wins.
- [ ] Paginate `pullFromCloud` until the response is shorter than `limit` so corpora over 500 sessions sync fully.
- [ ] Track per-session dirty / `updated_at` and only upload changed rows in `syncNow`, ideally in capped batches.
- [ ] Validate / clamp numeric settings (`chunkSec`, `notesIntervalMin`, `finalIntervalMin`, `previousNotesContextCount`) on both `updateConfig` and `importSettings` so a 0 or negative value cannot thrash the recording loop.
- [ ] Catch all non-cancellation exceptions in DataStore Flow `catch` blocks and emit `emptyPreferences()`, instead of letting `SerializationException` kill collectors.
- [ ] Make `getAllSessions()` order deterministic with `ORDER BY date DESC, id DESC`.

### Server data layer

- [x] Set `PRAGMA busy_timeout = 5000` and `BEGIN IMMEDIATE` in `db()`; add an explicit `rollback()` on exception.
- [x] Wrap `/sync` in a single transaction so a partially failing batch does not leave the database half-upserted; skip embedding for sessions whose `(updated_at, transcript_hash)` is unchanged.
- [x] Decide whether `_replace_session_chunks` should keep the `DELETE FROM session_chunks` or rely on the upsert clause; remove whichever is dead and fix `created_at` to reflect the original creation time.
- [x] Catch `json.JSONDecodeError` in `decode_embedding` and return `None` so a malformed row cannot 500 a semantic-search request.
- [x] Add `max_length` constraints to `full_transcript`, `notes`, `final_summary`; bound `date`; set `model_config = ConfigDict(extra="forbid")` on `SessionBase`.
- [x] Escape `%` / `_` / `\` in `LIKE` patterns and add `ESCAPE '\\'` (or migrate text search to FTS5).

### Server deployment / ops

- [x] Configure FastAPI for proxy deployment: pass `root_path=DND_SCRIBE_BASE_PATH`, run uvicorn with `--proxy-headers --forwarded-allow-ips`, add `TrustedHostMiddleware` and an explicit allow-list `CORSMiddleware`.
- [x] Add basic rate limiting (e.g. `slowapi`) on `POST /ui/login` and `GET /search/semantic`, plus a body-size cap on uvicorn for `POST /sync`.
- [x] Harden `server/Dockerfile`: create a non-root user, `USER` switch, declare `VOLUME ["/data"]`, set `DND_SCRIBE_DB_PATH=/data/sessions.sqlite`, add a `HEALTHCHECK`, drop the editable install for production images.
- [ ] Add a lockfile (`uv.lock` / `requirements.lock`) and reference it from the Dockerfile so builds are reproducible.
- [x] Add a `logging` setup with structured `logger.exception` in every existing silent `except` (embedding step in `_replace_session_chunks`, `_backfill_chunk_embeddings`, the index route's HTTPException catch); record login success/failure as an audit log.

### Server HTML UI hardening

- [x] Escape every untrusted value for its destination context: `urllib.parse.quote` for URL pieces, `json.dumps` for JS strings, `html.escape` for body / attribute. Audit `row["id"]`, `row["session_date"]`, and the JS `delete` argument specifically.
- [x] Replace `repr(BASE_PATH)` with `json.dumps(BASE_PATH)` when interpolating into the UI's `<script>` block.

## Review Findings — Cleanup

- [ ] Deduplicate `extractPreviousNotesContext` between `RecordingService.kt` and `MainViewModel.kt` into a shared helper.
- [ ] Move user-facing strings into `strings.xml`.
- [ ] Replace ad-hoc `Toast.makeText` from the ViewModel with a UI-state Snackbar event flow.
- [ ] Replace `Locale.getDefault()` with `Locale.US` for stored timestamps and filename formatting; consider migrating to `java.time.DateTimeFormatter`.
- [ ] Inject `ActiveSessionState` instead of using a top-level `object` so it is testable and not silently mutable from any caller.
- [ ] Replace the `RetrofitClient` placeholder `baseUrl("http://localhost/")` with an obviously invalid sentinel that fails loudly if a future method drops `@Url`.
- [ ] Pick a real `applicationId` (not `com.example.dndscribe`) before any release.
- [x] Migrate server startup hook from deprecated `@app.on_event("startup")` to a `lifespan=` async context manager.
- [x] Delete the unused `_chunk_row_to_dict` helper and have `get_session` reuse `_fetch_session_by_id`.
- [x] Make `auth.py`'s `Bearer` scheme match case-insensitively per RFC 6750.
- [x] Expand `server/.gitignore` with `*.sqlite-wal`, `*.sqlite-shm`, `.venv/`, `*.egg-info/`, `.env`, `.pytest_cache/`, `.mypy_cache/`.
- [ ] Apply tail-overlap when `_chunk_text` flushes accumulated short paragraphs so adjacent chunks do not lose all overlap; consider content-hash chunk ids so embeddings survive chunker tweaks.
- [ ] Update `AGENTS.md` to reference the actual edit tooling instead of `apply_patch`.
- [ ] Expand the top-level `README.md` with a "running it yourself" section once high-severity items are fixed.
- [ ] Add a "Production deployment" section to `server/README.md` covering TLS termination, `DND_SCRIBE_BASE_PATH` + `--root-path`, and rotating the auth token.

## Review Findings — Tests & CI

- [ ] Add unit tests for the recording state machine, chunker, embedding batching, and cloud-sync dedup.
- [ ] Add a minimal CI job (e.g. GitHub Actions) running `python -m compileall server/src` and `./gradlew assembleDebug` on push.
