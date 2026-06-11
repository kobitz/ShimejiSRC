# Shimeji AI — Environment-Aware Desktop Mascots

A heavily modified fork of [Shimeji-ee](https://kilkakon.com/shimeji/) (Java desktop mascots for Windows) with a fully local AI assistant layer. Mascots now perceive the user's real environment and react to it in character: what's on screen, what's playing through the speakers, what the user says (to them or near them), system load, the weather, even the contents of local drives.

**Everything runs locally.** No cloud APIs, no subscriptions, no telemetry. LLM inference via [Ollama](https://ollama.com), speech-to-text via [faster-whisper](https://github.com/SYSTRAN/faster-whisper) on CPU.

## What the mascots can do

- **Chat** — click-to-reply bubbles, typed input, persistent per-character memory (`memory.json`) with automatic summarization, keyword-gated permanent memories, and timers/reminders.
- **React to your screen** — periodic glances at the active window title and screen captures fed to a local vision model.
- **React to system audio** — a 15s WASAPI loopback buffer is transcribed (and auto-translated to English, e.g. for Japanese shows) so mascots can comment on videos, music, and calls.
- **React to your voice** — name triggers ("Hornet, ...") answer directly; hotword→behavior dispatch fires animations instantly; overheard speech (talking on a call, reacting aloud) draws occasional in-character remarks, with transcript-level echo removal so speaker bleed isn't mistaken for the user.
- **Talk to each other** — peer reactions with per-character relationship tones, anti-style-contamination rules, and chain-depth caps.
- **Reflect the machine** — CPU/GPU/RAM/temperature exposed to behavior scripts: campfires that scale with CPU load, glitch animations under heavy load, sensor-driven tint overlays.
- **Know your files** — a local drive index supports file-aware replies (retrieval is keyword-gated; the index never leaves the machine).

## Current cast

Hornet (Hollow Knight), Holo (Spice and Wolf), Paimon (Genshin Impact), 2B (NieR: Automata), Claude (Anthropic), plus an ambient ecosystem (Mosscreep/Mossfly colonies that Hornet hunts, CPU-reactive campfires). Each has a hand-tuned personality, speech rules, and voice trigger.

## Requirements

- Windows 10/11 (the AI layer is Windows-only: WASAPI loopback, JNA Win32)
- Java 17+ (a JRE is bundled in release builds)
- [Ollama](https://ollama.com) with a small instruct model (tuned for ~4B class, e.g. `gemma3:4b`; multimodal model needed for vision features)
- Python with `faster-whisper` for voice/audio features (`whisper_server.py` runs beside the JAR)
- NVIDIA GPU optional — GPU telemetry uses `nvidia-smi`; everything degrades gracefully without it

A 6 GB VRAM / 16 GB RAM laptop is the reference hardware; the engine is tuned to stay out of the way (process priority separation, request spacing, short model keep-alive) so inference never makes the desktop stutter.

## Building

1. `Build.bat` at the repo root — `[1]` Release, `[2]` Test build + launch, `[3]` Ant only.
2. The script runs `ant jar` and wraps the result with Launch4j into `Shimeji.exe` / `ShimejiTest.exe` in the install folder.

Plain `ant jar` produces the fat JAR if you don't need the exe wrapper.

## Configuration

XML-driven behaviors (`conf/actions.xml`, `conf/behaviors.xml`, per-mascot overrides in `img/<name>/conf/`) with Nashorn JS expressions for conditions and velocities. Assistant settings (models, bubble styling, voice polling, drive indexing, resource caps) live in `conf/settings.properties` and the in-app settings UI.

See **CLAUDE.md** for the full architecture reference: the tick loop, action/behavior system, the assistant package, memory format, audio pipeline, and authoring notes.

## Lineage & license

Fork of Shimeji-ee (Group Finity / Shimeji-ee Group / kilkakon). Original licenses retained — see `licence.txt`, `originallicence.txt`, and `originalreadme.txt`.
