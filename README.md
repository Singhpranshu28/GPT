# Flow Notes

Flow Notes is an Android app that works like Wispr Flow, but it saves what you say as notes. Long-press the side button anywhere, talk naturally, and get clean, well-written text instead of a raw transcript.

```
long-press side button ─▶ overlay pops up, recording starts
talk ("um, so the meeting is at 5, actually make that 6…")
long-press again / volume key / tap ✓
        │
        ▼
speech-to-text: Whisper on your phone (free, offline), or a cloud API
        │  raw: "um so the meeting is at 5 actually make that 6 and uh bring the deck"
        ▼
clean-up: free offline rules, or Claude for the full rewrite
        │  note: "The meeting is at 6pm. Bring the deck."
        ▼
saved as a note (title + tags), copied to the clipboard
```

## What it borrows from Wispr Flow

| Wispr Flow | Flow Notes |
|---|---|
| Removes "um", "uh", "like", stutters and false starts | Same |
| Backtracking: "5… actually 6pm" → "6pm" | Same, with Claude: only the final intent is kept |
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

**It works for free, with no API keys.** By default, speech is transcribed **on your phone** by [Whisper](https://github.com/openai/whisper), using [whisper.cpp](https://github.com/ggml-org/whisper.cpp). Nothing is uploaded and nothing costs money. To set it up, go to **Settings → Speech to text → On this phone (free)** and download a model once. Use Wi-Fi for the bigger ones.

| Model | Size | Best for |
|---|---|---|
| Tiny | 31 MB | Older phones, quick notes |
| Base (default) | 57 MB | Most phones: good balance of speed and accuracy |
| Small | 181 MB | Best accuracy, especially for Hindi and Hinglish; about 3–4× slower than Base |

All models are multilingual. Setting a **Language** (e.g. `hi`, `en`) makes recognition faster and more accurate than auto-detect. On a recent phone, Base transcribes a 1-minute note in roughly 5–15 seconds. Older phones take longer.

**Organising notes is free too.** Under **Settings → Organising notes**, pick one of three engines:

| Engine | Cost | What you get |
|---|---|---|
| **On-device AI** (default) | Free, offline | A small LLM ([Qwen2.5](https://huggingface.co/Qwen), Apache-2.0) runs on the phone through [llama.cpp](https://github.com/ggml-org/llama.cpp). It removes fillers, applies self-corrections ("5… actually 6" → "6"), organises the note into paragraphs and bullet lists, and adds a title, tags and **reminders**. Download **Qwen2.5 1.5B** (1.0 GB, recommended) or **0.5B** (0.4 GB, faster) once. A 1-minute note takes roughly 10–40 seconds to organise. |
| Claude | Paid per use | Best-quality rewrite through the Anthropic API (`claude-opus-5-5` by default). Needs an API key. |
| Basic rules | Free, instant | Removes "um/uh", stutters and fillers, drops the sentence before "scratch that", and applies spoken "new paragraph" / "bullet point". No reorganising and no reminders. |

If the AI isn't available or fails, the note still saves, using the basic rules.

### Reminders

Say it while you dictate, for example *"…and remind me to call mom tomorrow at 6"* or *"don't let me forget the rent on Friday"*. The AI fills in the reminder and you get a notification at that time:

- The overlay shows the reminder right away, so you can check the time it picked.
- On the note, you can **Change** the date and time, **Remove** the reminder, or **Add reminder** yourself.
- If you name a day but no time, it uses 9:00. If you give no time at all, the note gets a reminder with **Set time**.
- Reminders survive restarts and are included in the Drive backup.
- On Android 12+, tap **Settings → Allow on-time reminders**. Without it, Android may deliver reminders a few minutes late.

**Optional paid alternatives:**
- **Cloud speech-to-text:** if your phone is slow, switch to **Cloud API** and use OpenAI (`gpt-4o-transcribe`), Groq (`whisper-large-v3-turbo`), or any OpenAI-compatible endpoint.

Keys are stored in the app's private storage on the device.

### Google Drive backup (optional)

To back up notes, go to **Settings → Sign in with Google**. For each note, Drive then holds:

- a **Google Doc** in a **"Flow Notes"** folder, readable and searchable anywhere, and updated whenever you edit the note;
- the **recording** in **"Flow Notes/Audio"** as `.m4a`. You can turn this off with *Back up recordings*;
- a full JSON copy of the note (raw transcript, tags, timestamps, links to the Doc and audio) in the app's **hidden app-data folder** on Drive. Restore reads this copy.

**Restore:** sign in on a new phone (or after reinstalling) with no notes, and everything comes back automatically, recordings included. You can also tap **Restore** at any time to pull in notes that aren't on the phone. Notes already on the phone are left alone.

Deleting a note moves its Doc and recording to the Drive trash and removes its restore copy, so it won't come back on a restore.

The app asks only for `drive.file` and `drive.appdata`. It can see the files it created and nothing else in your Drive.

Google needs a one-time setup before sign-in works, because Google identifies Android apps by package name and signing key:

1. Go to [Google Cloud Console](https://console.cloud.google.com/) and create a project, for example "Flow Notes".
2. In **APIs & Services → Library**, enable the **Google Drive API**.
3. Open **Google Auth Platform**, or **OAuth consent screen** in the older layout:
   - Set the app type to **External** and fill in the app name and your email.
   - Under **Audience → Test users**, add the Gmail address you'll sign in with.
   - Under **Data access**, add the scopes `.../auth/drive.file` and `.../auth/drive.appdata`.
4. Go to **Clients → Create client → Android** and enter:
   - Package name: `com.flownotes`
   - SHA-1: `96:B3:DA:25:F6:33:17:37:1A:54:36:4A:EC:14:58:9B:6E:4C:AF:D7` (the fingerprint of `app/debug.keystore`, which signs every build)

You don't need to copy any client ID into the app. Google matches the app by package name and SHA-1. While the consent screen is in **Testing** mode, Google may ask you to sign in again about once a week, and the app shows a **Sign in again** button when that happens. Publishing the consent screen avoids this, and neither scope requires Google's app review. Audio goes only to the speech-to-text provider you configure, and the transcript goes only to Anthropic.

## Install on your phone

1. **Download the APK:** on your phone, open **https://github.com/Singhpranshu28/GPT/releases/latest/download/flow-notes.apk**. This link always points to the newest build of `main`. All builds are listed on the [Releases page](https://github.com/Singhpranshu28/GPT/releases).
2. **Allow installs from your browser or file manager:** open the APK. Android asks you to allow "Install unknown apps" for the browser or Files app you opened it from. Turn that on and go back.
3. **Install:** tap **Install**. If Play Protect warns that the app is unknown, tap **More details → Install anyway**. That warning is normal for apps that don't come from the Play Store.
4. Open **Flow Notes** and allow the microphone and notifications.
5. In **Settings**, add your API keys, then tap **Set as digital assistant** to use the side button.

Updates install over the previous version and keep your notes, because every build is signed with the same key in `app/debug.keystore`.

If you'd rather install from a computer:

To build locally with Android Studio or the Android SDK:

```bash
# phone: Settings → About → tap "Build number" 7× → Developer options → USB debugging
./gradlew assembleDebug            # or use the APK downloaded from Actions
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Code map

```
app/src/main/java/com/flownotes/
  assist/      FlowVoiceInteractionService + session (side button), RecordTileService (QS tile)
  ui/          RecordActivity (overlay), MainActivity, NotesScreen, NoteDetailScreen, SettingsScreen
  recording/   RecordingService (mic + pipeline in the foreground), NoteProcessor, RecordingBus
  audio/       AudioRecorder (16 kHz mono AAC, ~0.5 MB/min)
  stt/         WhisperEngine + WhisperNative (on-device whisper.cpp via JNI), WhisperModels (download), AudioDecoder
  ../cpp/      CMake build of whisper.cpp (pinned) + whisper_jni.cpp
llm/           Android library: CMake build of llama.cpp (pinned) + llama_jni.cpp (grammar-constrained generation)
  ai/          LocalLlm (on-device Qwen via llama.cpp), Polisher (Claude), Prompts (editing rules + JSON grammar),
               LocalCleaner (basic offline rules), Transcriber (cloud STT)
  reminders/   Reminders (AlarmManager), ReminderReceiver (notification), RescheduleReceiver (after reboot)
  data/        Note, NotesRepository (JSON file), SettingsStore
  drive/       GoogleAuth (sign-in + consent), DriveClient (REST), DriveSync (backup: Doc + audio + JSON, and restore)
```

The editing behaviour lives in `ai/Prompts.kt`. Tweak it there to change how notes are written.
