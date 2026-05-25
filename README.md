# DnD Scribe

I wanted a thing for recording D&D sessions and getting auto-generated notes because I'm bad at taking them during play. So I asked a bunch of LLMs to write it for me and glued the results together.

The code is heavily AI-generated. I own that. It works.

## What it does

Android app records your session, sends chunks to Whisper for a live transcript, then periodically runs the transcript through an LLM to generate running notes. At the end of a session it does a final summary pass over the full transcript and notes. Timer intervals are all configurable.

There's a companion FastAPI server for cloud sync, archive management, and search. The server README has setup instructions.

## Quick start

1. Open the Android project in Android Studio or build with `./gradlew assembleDebug`
2. Fill in your LLM and Whisper API details in the app settings
3. Hit record

For the cloud backup / search server, see `server/README.md`.

## Stack

- Android app: Kotlin, Jetpack Compose, Room, DataStore, OkHttp/Retrofit
- Server: Python, FastAPI, SQLite
- Audio transcription: OpenAI Whisper API (or any OpenAI-compatible endpoint)
- LLM: any OpenAI-compatible provider
