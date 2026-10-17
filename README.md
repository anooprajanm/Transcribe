# Transcribe

Personal Android app that records face-to-face meetings and produces a speaker-labelled
transcript and action items, entirely on the phone. Built for English + Malayalam meetings,
including meetings that switch between the two.

## How it works

| Step | Engine (all on-device) |
|---|---|
| Record | 16 kHz mono WAV, foreground service (screen can be off) |
| Who spoke when | sherpa-onnx speaker diarization: pyannote segmentation 3.0 + 3D-Speaker embeddings |
| Speech to text | Per speaker turn. **Hybrid mode:** Whisper detects the turn's language; English stays with Whisper, Malayalam/mixed turns go to Meta Omnilingual ASR 300M |
| Action items | On-device LLM via MediaPipe (Gemma `.task` file you import once) |

Nothing is uploaded. Internet is used only once, to download the speech models (~750 MB on
disk with the defaults).

## Install

1. Every push builds a debug APK in GitHub Actions. Download `transcribe-debug.apk` from the
   **Dev build** pre-release (or the workflow run's artifact) on the phone and install it.
2. Open the app → Settings → **Download models** (use Wi-Fi).
3. Optional, for action items: download a Gemma `.task` model on the phone (e.g. Gemma 3 1B IT
   int4, or Gemma 3n E2B for better Malayalam understanding, from the `litert-community`
   organisation on Hugging Face; requires accepting the Gemma licence), then Settings →
   **Import .task model**.

## Usage tips

- Put the phone flat in the middle of the table.
- If you know how many people are in the meeting, set the speaker count in Settings. It is
  the single biggest improvement to speaker separation.
- Rename "Speaker 1/2/…" on the meeting screen; names are used in the transcript, share
  text and action items (use **Redo action items** after renaming).
- Tap any transcript line to hear that part of the audio.

## Known limits

- Malayalam accuracy is usable, not perfect; Omnilingual output has no punctuation.
  Intra-sentence code-mixing (English words inside a Malayalam sentence) is the weakest case.
- Processing runs at roughly 0.5–2x the meeting length depending on phone and Whisper size.
- The whole recording is held in memory for diarization; keep single recordings under ~90 min.

## Building locally

```
./scripts/fetch-native-libs.sh   # sherpa-onnx v1.13.8 JNI libs -> app/src/main/jniLibs
./gradlew assembleDebug
```

The Kotlin API in `app/src/main/java/com/k2fsa/sherpa/onnx` is vendored from the same
sherpa-onnx release; upgrade both together.
