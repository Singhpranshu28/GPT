# Flow Notes

Flow Notes is an Android app that works like Wispr Flow, but it saves what you say as notes. Long-press the side button anywhere, talk naturally, and get clean, well-written text instead of a raw transcript.

```
long-press side button ─▶ overlay pops up, recording starts
talk ("um, so the meeting is at 5, actually make that 6…")
long-press again / volume key / tap ✓
        │
        ▼
speech-to-text (OpenAI gpt-4o-transcribe, Groq Whisper, any OpenAI-compatible API)
        │  raw: "um so the meeting is at 5 actually make that 6 and uh bring the deck"
        ▼
Claude clean-up
        │  note: "The meeting is at 6pm. Bring the deck."
        ▼
saved as a note (title + tags), copied to the clipboard
```

## What it borrows from Wispr Flow

| Wispr Flow | Flow Notes |
|---|---|
| Removes "um", "uh", "like", stutters and false starts | Same |
| Backtracking: "5… actually 6pm" → "6pm" | Same: only the final intent is kept |
| Auto-formats lists, paragraphs and emails | Same, plus spoken commands ("new paragraph", "bullet point") |
| Personal dictionary | One word per line. It is sent to both the speech-to-text model (as a spelling hint) and Claude |
| Auto Cleanup levels (Light / Medium / High) | Same three levels |
| Styles per app | Note, Bullets, Message, Email |
| Hotkey / hold Fn to talk | **Long-press the side button** (also: Quick Settings tile, launcher shortcut, in-app button) |
| Pastes into the current text box | Saves a note **and** copies it to the clipboard, so you can paste it anywhere |

It also keeps the original audio and the raw transcript. From any note you can view "what you actually said", re-polish it with different settings, or re-transcribe it.

## Using the side button

On Android, the long press of the side/power button opens the **default digital assistant**. Flow Notes registers itself as an assistant (a `VoiceInteractionService`), so:

1. Open Flow Notes → **Settings → Set as digital assistant**. Or go to *Settings → Apps → Default apps → Digital assistant app* and pick **Flow Notes**.
2. Make sure the side-button long press is set to the assistant:
   - **Pixel:** *Settings → System → Gestures → Press & hold power button → Digital assistant*.
   - **Samsung:** *Settings → Advanced features → Side button → Press and hold → Wake Bixby*. One UI then opens the default digital assistant instead of Bixby. On some versions, you can also set *Double press → Open app → Flow Notes*.
   - **OnePlus / Xiaomi / Motorola:** look for *Power button → Hold for assistant*.
3. Long-press the side button. The overlay appears over any app, including the lock screen, and recording starts right away.
4. To finish, **long-press the side button again**, press **either volume key**, or tap **✓**. Tap **✕** to discard.

Recording and processing run in a foreground service. You can switch apps or lock the phone while talking, and the note still gets saved.

> **iPhone:** iOS doesn't let third-party apps take over the side button. The equivalent there is the **Action Button** on an iPhone 15 Pro or later, set to run a Shortcut or App Intent. That would need a separate iOS app. This repo is Android only.

## Setup

You need two API keys, added in the app's Settings:

- **Speech to text** (required): an OpenAI key (default model `gpt-4o-transcribe`), or a Groq key (`whisper-large-v3-turbo`, faster and cheaper). Any OpenAI-compatible `/audio/transcriptions` endpoint works, including a self-hosted Whisper server.
- **Anthropic** (for the clean-up): uses `claude-opus-5-5` by default, and you can change the model in Settings. Without this key, notes still save, but as raw transcripts.

Keys are stored in the app's private storage on the device. Audio goes only to the speech-to-text provider you configure, and the transcript goes only to Anthropic.

## Build and install

To get a ready-built APK, open the repo's **Actions** tab, then the latest *Android build* run, and download the **flow-notes-debug-apk** artifact.

To build locally with Android Studio or the Android SDK:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Code map

```
app/src/main/java/com/flownotes/
  assist/      FlowVoiceInteractionService + session (side button), RecordTileService (QS tile)
  ui/          RecordActivity (overlay), MainActivity, NotesScreen, NoteDetailScreen, SettingsScreen
  recording/   RecordingService (mic + pipeline in the foreground), NoteProcessor, RecordingBus
  audio/       AudioRecorder (16 kHz mono AAC, ~0.5 MB/min)
  ai/          Transcriber (OpenAI-compatible STT), Polisher (Claude), Prompts (the "flow" editing rules)
  data/        Note, NotesRepository (JSON file), SettingsStore
```

The editing behaviour lives in `ai/Prompts.kt`. Tweak it there to change how notes are written.
