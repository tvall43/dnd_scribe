# DnD Scribe Todo

This is the running list of follow-up work from the code reviews. Items are ordered roughly by priority and dependency.

## Immediate

- [x] Remove the unauthenticated HTML form delete path and use the authenticated API for UI deletes.
- [x] Delete `session_chunks` when a session is deleted, with SQLite foreign-key cascade as a safety net.
- [x] Split network timeouts so Whisper stays at a shorter window and the LLM gets a longer one.
- [x] Align Whisper model defaults across settings sources.
- [x] Document the embedding API base URL expectation in `server/README.md`.

## Server Stability

- [ ] Make semantic backfill resilient to partial failures so one bad batch does not stop the rest of the index build.
- [ ] Preserve partial embedding progress during batch failures instead of discarding earlier successful batches.
- [ ] Add logging or status reporting for embedding backfill failures so missing vectors are visible.
- [ ] Decide whether semantic search should remain in-process Python cosine similarity or move toward a real vector index as the corpus grows.

## Android Reliability

- [x] Fix `RecordingService` restart logic to handle null intents from `START_STICKY` (ensure service either resumes recording or shuts down).
- [x] Move to unique filenames for all recording segments immediately to prevent the `recording.webm` overwrite risk during rotation crashes.
- [ ] Consider a `WorkManager` fallback or other resiliency path for periodic cloud sync when the foreground service is throttled.

## Sync & Data Flow

- [ ] Add a pull/sync-down path from server to Android so server-side changes and deletes are reflected locally.
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
- [ ] Add any needed server-side audit or access logging for destructive operations.

## Future Web Client

- [ ] Build a proper web client for archive management.
- [ ] Reimplement the app’s flow in the web client once the archive and sync model is finalized.
- [ ] Replace the current debug-oriented HTML UI when the real client is ready.
