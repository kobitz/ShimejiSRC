# Shimeji AI — Environment-Aware Desktop Mascots

A heavily modified fork of [Shimeji-ee](https://kilkakon.com/shimeji/) (an English edition of Group Finity's original Shimeji) — Java desktop mascots for Windows — with a local-first AI assistant layer added on top. You get the complete classic Shimeji experience *and* mascots that perceive your environment and react in character: what's on screen, what's playing through the speakers, what you say (to them or near them), system load, the weather, even the contents of local drives.

**Your data stays local by default, and once installed it runs fully offline.** No subscriptions, no accounts, no telemetry — LLM inference runs through [Ollama](https://ollama.com) and speech-to-text through [faster-whisper](https://github.com/SYSTRAN/faster-whisper) on CPU, and nothing about your screen, voice, files, or chats leaves the machine. The one exception is the weather lookup: when you ask a mascot about the weather, it contacts `api.open-meteo.com` — and with the default `WeatherLocation=auto`, also `ip-api.com` (falling back to `ipapi.co`), which sees your public IP to guess your city. Set `WeatherLocation` to a city name to skip the IP lookup, or `WeatherEnabled=false` to turn weather off entirely.

**Optional cloud mode.** You can instead point the assistant at an OpenAI-compatible API (see *Using a cloud / OpenAI-compatible API* below). In that mode your chats, screenshots, transcripts and window titles are sent to the provider you choose, so leave it off if that matters to you.

**▶ [Download from the releases page](https://github.com/kobitz/ShimejiSRC/releases)** — grab `ShimejiSRC-Setup-<version>.exe` and run it. No admin rights needed; the setup asks where to install (the default is per-user) and downloads a small Java runtime the first time, so it needs internet once. Tick **Set up AI features** on the last page (or run it later from the Start menu) to install Ollama and the local models. Then launch **ShimejiSRC** from the Start menu, or use the *Administrator - hardware sensors* shortcut for fan control and CPU-temperature features. Your settings, memories and chat history are kept when you reinstall or upgrade; the uninstaller asks before deleting them.

## The basics

Mascots are animated sprites rendered as borderless, always-on-top windows, moved by a physics simulation — gravity, momentum, and edge collision. They walk, sit, climb, sleep, and fall. Run any number at once, mix image sets, and they span multiple monitors.

- **Drag & throw** — grab a mascot with the mouse; releasing imparts velocity, then gravity and collision take over and it bounces off the screen and window edges.
- **Window interaction** — mascots walk along the top edges of open windows, climb their sides, sit on them, and can grab a window to drag or throw it. Which apps are interactive is configurable.
- **Breeding** — a mascot clones itself onto the screen, so one character becomes many.

## Controlling them

**Right-click any mascot** for its menu:

- **Call Another** — spawn another of that character.
- **Follow Cursor** — it chases your mouse.
- **Set Behaviour** — directly trigger any animation/behavior (sit, climb, jump, and so on).
- **Restore Windows** — put back any windows the mascots have moved.
- **Dismiss** — remove that one. Clicking anywhere else closes the menu.
- …plus this fork's extras: a tooltip toggle, a per-character sound-effects volume slider, and the AI **assistant mode** toggle with its personal-behaviour checkboxes.

**The system-tray icon** manages all active mascots: add mascots, choose which image sets are active, *Reduce to One*, *Restore Windows*, *Allowed Behaviours* (globally enable or disable individual behaviors), open the **Mascot Shop**, open *Settings*, and quit (the classic "Bye bye"). Sets that carry a `hidden.txt` marker (see *Making your own*) don't appear in the chooser or get picked by *Call Shimeji*.

**Settings** cover global scale, always-on-top, multi-monitor mode, which apps' windows are interactive, and all of the assistant options below.

## Hotkeys

Global keyboard and mouse shortcuts can trigger any behavior, configured in `conf/hotkeys.properties` (plus optional per-mascot files, below). Each line is `<combo>=<Behavior>`:

```
F1=Sit
ctrl+shift+J=Jump
RIGHT=Mario:MoveRight!hold
```

- **Combo** — modifiers (`ctrl`, `shift`, `alt`, `meta`) plus a key, joined with `+`. Keys use names like `F1`, `A`, `SPACE`, `ENTER`, `HOME`; mouse buttons are `MOUSE1`–`MOUSE5`. Case-insensitive.
- **Behavior** — any behavior name. By default it fires for *every* active mascot; prefix it with an image-set name (`Mario:MoveRight`) to target only that character.
- **`!hold`** — append it to loop the behavior for as long as the key is held (e.g. walk while held, stop on release) instead of firing once on press.

**Two files feed the same system.** The global `conf/hotkeys.properties` applies to every active mascot (or one, via the `ImageSet:Behavior` prefix). A per-mascot `img/<name>/conf/hotkeys.properties` is scoped to that character automatically — write **bare** behavior names, no prefix — and **overrides the global per key**: combos it defines win for that mascot, while combos it leaves out still inherit from the global file. Lines beginning with `#` are comments.

For example, `img/Frieren/conf/hotkeys.properties`:

```
F2=SitDown
```

makes only Frieren sit on `F2`, regardless of what the global file binds `F2` to — and Frieren still picks up every other global combo.

## Web video (browser extension)

A bundled Firefox extension (`shimeji-video-tracker.xpi`) lets mascots treat web videos as physical surfaces. The extension watches for video elements on a page (YouTube, Twitch, and the like) and reports each one's on-screen rectangle to a small HTTP server the app runs locally on `127.0.0.1:41221`. The engine treats those rectangles the same way it treats window edges — so a mascot can sit on, walk along, and climb the borders of a video playing in your browser, and it knows which site the video is on. The extension also reports when a tab enters or exits fullscreen, so mascots yield to fullscreen video.

Everything stays on localhost; no page data leaves the machine. Load `shimeji-video-tracker.xpi` in Firefox to enable it — without the extension there are simply no web-video surfaces, and nothing else changes.

## What the mascots can do (the AI layer)

- **Chat** — click-to-reply bubbles, typed input, persistent per-character memory (`memory.json`) with automatic summarization, keyword-gated permanent memories, and timers/reminders.
- **React to your screen** — periodic glances at the active window title and screen captures fed to a local vision model.
- **React to system audio** — a 15s WASAPI loopback buffer is transcribed (and auto-translated to English, e.g. for Japanese shows) so mascots can comment on videos, music, and calls.
- **React to your voice** — name triggers ("Hornet, ...") answer directly; hotword→behavior dispatch fires animations instantly; overheard speech (talking on a call, reacting aloud) draws occasional in-character remarks, with transcript-level echo removal so speaker bleed isn't mistaken for the user.
- **Talk to each other** — peer reactions with per-character relationship tones, anti-style-contamination rules, and chain-depth caps.
- **Reflect the machine** — CPU/GPU/RAM/temperature exposed to behavior scripts: campfires that scale with CPU load, glitch animations under heavy load, sensor-driven tint overlays. On MSI laptops a lit blue campfire drives the cooling, ramping both fans to max via Cooler Boost.
- **Know your files** — a local drive index answers file-aware questions through a query router: keyword (exact), semantic (vague topical recall via local `nomic-embed-text` embeddings), recency ("last thing I downloaded"), aggregate (counts/sizes/"what can I delete"), and folder listing. The index and embeddings never leave the machine.
- **Sense the situation** — a shared, continuously-sampled situational model fuses what you're doing (app sessions, activity tempo, audio, system load) into one read, so mascots react to your *state* — focused, multitasking, winding down — and speak when something actually changes, not on a blind timer. An optional small periodic LLM pass adds a one-line narrative + conservative mood.

## Agent tools (opt-in)

Off by default. Set `AgentEnabled=true` in `conf/settings.properties` and restart, and a mascot can act on your computer when you ask it to in your own words (typed or spoken to it):

- **Windows** — focus, minimize, maximize, snap left/right/top/bottom, center, send to another monitor, or *carry* a window around on its head for a while.
- **Files** — tidy your Downloads folder by type, or move a named file to a folder, with a preview and a confirmation bubble first. Moves are journalled so you can say "undo", and nothing is ever deleted or overwritten. The mascot can walk a file between Explorer windows (`AgentVisualMoves=false` turns that off).
- **Scheduling** — "play a sound in 20 minutes", reminders with a countdown bubble, "open youtube.com tomorrow at 9am", calendar events (`.ics`), "what's scheduled", cancel. `AgentTypeEnabled=true` additionally allows "type this into the focused window at 8:40pm" (the focus is verified right before typing).
- **System controls** — volume up/down/set/mute and play/pause/next/previous. Anything destructive (shut down, restart, close, delete, install...) is refused with *"This action is not within my parameters."*

Only your own words trigger these: text from the screen, other people's audio, or anything a mascot reads can never give it an order. See `CLAUDE.md` for the safety rules.

## Mascot Shop

Tray menu → **Mascot Shop...** browses a catalog of community mascots. Entries either link to the creator's own page (you download, then use *Import .zip*) or install in place when the creator allows redistribution; installs are checksum-verified and never overwrite an existing mascot. The shop only touches the network while it's open (`ShopThumbnails=false` skips preview images).

## Making your own

Each mascot is a folder under `img/<name>/` containing sprite PNGs and two XML files that define how it moves and speaks. No recompilation required for either.

### Folder structure

```
img/
└── YourMascot/
    ├── conf/
    │   ├── actions.xml       ← animations and physical behaviors
    │   ├── behaviors.xml     ← when and how often each behavior fires
    │   ├── hotkeys.properties  ← optional per-mascot keybinds
    │   └── hidden.txt          ← optional: empty file that hides this set from the menus
    └── *.png                 ← sprite sheets
```

**Helper sets:** drop an empty file named `hidden.txt` into a mascot's `conf` folder and it won't appear in *Choose Shimeji* or be picked by *Call Shimeji* or a tray click. It still loads, and other mascots can still spawn it, which suits sidekick or effect sets (Hornet's Needle uses it).

Sprites are referenced by filename from within `actions.xml`. The engine handles rendering, physics, and window interaction — you only describe what the mascot does and when.

### Movement and behaviors

`actions.xml` defines animations (sequences of frames with velocities and durations) and the actions that play them — walking, climbing, sitting, jumping, and so on. `behaviors.xml` controls the decision layer: which actions run, how frequently, and under what conditions.

Conditions and durations are Nashorn JavaScript expressions, so you can gate behaviors on environment variables like `mascot.environment.ceiling` (is there a ceiling above?) or `mascot.environment.cpuLoad` (current CPU usage). The full variable set is documented in the original `readme.txt` preserved in the repo root.

### Adding the AI layer

Drop a `<Personality>` block inside `<Information>` in `actions.xml`:

```xml
<Information>
    <Name>YourMascot</Name>
    <Personality>You are [character]. [Voice, tone, and behavioral rules.]</Personality>
    <PersonalityBrief>~30-word essence for quick reactions.</PersonalityBrief>
</Information>
```

`<Personality>` is used for direct replies, name-triggered responses, and peer reactions. `<PersonalityBrief>` is used for the lighter spontaneous/audio/vision reactions and cuts the prompt size by ~60–70%; falls back to the full personality if absent.

To suppress unwanted speech patterns, add `<SpeechRule>` tags — injected as the first rule in every prompt and repeated as a final reminder. Write rules as instructions with wrong→right examples, but **never use a full example sentence as the RIGHT case** — the model will adopt it verbatim as a fallback phrase.

```xml
<SpeechRule>Never start with "Interesting." Wrong: "Interesting. That explains it."
Right: open with your reaction to the specific detail.</SpeechRule>
```

Optional tags (all must be present in `loadInformation()`'s whitelist in `Configuration.java` to take effect):

- **`<ThirdPersonRewrite>`** — rewrites first-person pronouns to the mascot's name post-generation (used by Paimon).
- **`<ConsoleReadout>`** — set `true` to display a live console/log stream projected ahead of the mascot (used by 2B).

### Enabling assistant mode

Once your mascot folder is in place, launch Shimeji, right-click the mascot, and toggle **Assistant Mode** on. Per-character checkboxes let you enable or disable individual reaction types (spontaneous comments, audio reactions, peer reactions, screen glances, voice triggers) independently.

Memory is stored per-mascot in `img/<name>/conf/memory.json` and accumulates automatically. If speech quality degrades over time, wipe `peerExchanges` (→ `[]`) in that file — the `memory_auditor.pyw` dev tool in the repo root automates this audit and prune loop.

## Current cast

The original Shimeji, Hornet (Hollow Knight), Holo (Spice and Wolf), Paimon (Genshin Impact), 2B (NieR: Automata), plus an ambient ecosystem (Mosscreep/Mossfly colonies in two colors that Hornet hunts, CPU-reactive campfires). Each has a hand-tuned personality, speech rules, and voice trigger.

## Requirements

- Windows 10/11 (the AI layer is Windows-only: WASAPI loopback, JNA Win32)
- Java 17+ (the installer downloads a Temurin 21 runtime into the install folder when needed; running from source or the portable build uses a Java you already have)
- [Ollama](https://ollama.com) with a small instruct model (tuned for ~4B class, e.g. `gemma4:e2b-it-qat`, which doubles as the multimodal model for vision); `nomic-embed-text` (CPU-only, ~280 MB) powers semantic drive recall and is optional — keyword retrieval continues without it
- *Optional instead of Ollama:* any OpenAI-compatible API plus a base URL and key (billed by the provider)
- Python with `faster-whisper` for voice/audio features (`whisper_server.py` runs beside the JAR; transcription stays local in both modes)
- NVIDIA GPU optional — GPU telemetry uses `nvidia-smi`; everything degrades gracefully without it

A 6 GB VRAM / 16 GB RAM laptop is the reference hardware; the engine is tuned to stay out of the way (process priority separation, request spacing, short model keep-alive, and CPU-load-gated reactions that defer while you're busy) so inference never makes the desktop stutter or overheat.

## Building

`Build.bat` at the repo root (needs a JDK 17+ and Apache Ant) offers:

- **`[4]` Portable build** — works on any machine: builds the jar, finds or downloads Launch4j into `tools\`, and produces a runnable `dist\ShimejiSRC\Shimeji.exe` with `conf\` and `img\` beside it.
- **`[1]` Release / `[2]` Test** — use your own Launch4j configs and install folder, set in a gitignored `Build.local.bat` (copy `Build.local.bat.example`).
- **`[3]` Ant only** — the fat JAR in `target\`.

**Making the installer:** run `Build.bat` → `[1]`, then `installer\build-installer.bat <version>` (needs [Inno Setup 6.3+](https://jrsoftware.org/isinfo.php)). It assembles a clean bundle from this repo's `conf\` and `img\` (the mascot sets listed in `installer\mascots.txt` — put clean copies in `img\`, which is otherwise untracked), scrubs anything personal, and writes `dist\ShimejiSRC-Setup-<version>.exe`. Add `test` (`installer\build-installer.bat 2.0 test`) for a throwaway installer with its own name and a `dist\test-install` target that can't touch a real install.

## Configuration

XML-driven behaviors (`conf/actions.xml`, `conf/behaviors.xml`, per-mascot overrides in `img/<name>/conf/`) with Nashorn JS expressions for conditions and velocities. Assistant and agent settings (models, bubble styling, voice polling, drive indexing, resource caps, `AgentEnabled`) live in `conf/settings.properties` and the in-app settings UI; a fresh install gets the defaults from the repo's `conf/settings.properties`.

### Using a cloud / OpenAI-compatible API

Open **Settings → Chat Bubbles**, set **AI Provider** to *OpenAI-compatible API*, enter the base URL and key, click **Load models**, then pick the text, vision and (optionally) embedding models. Any server that implements `POST /chat/completions` and `GET /models` works.

| Provider | Base URL |
|---|---|
| OpenAI | `https://api.openai.com/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |
| Groq | `https://api.groq.com/openai/v1` |
| Together | `https://api.together.xyz/v1` |
| LM Studio | `http://localhost:1234/v1` |
| llama.cpp server | `http://localhost:8080/v1` |

The first time you save with API mode on, a notice lists what gets sent: what you type and say to a mascot and its memory of you, screenshots when a mascot looks at your screen, microphone and system-audio transcripts, window titles and your activity summary, and file names when you ask about files. Speech-to-text, window and file actions, sensors and fan control stay on your computer.

The key is written to `conf/api.properties` (gitignored), not `conf/settings.properties`. To keep it off disk, set the `SHIMEJI_AI_API_KEY` environment variable, which takes precedence. Settings keys: `AiProvider`, `AiBaseUrl`, `AiTextModel`, `AiVisionModel`, `AiEmbeddingModel`.

In API mode, **semantic drive search is off** (it would send file and folder names to the provider); set `DriveSemanticRemote=true` to allow it. Remote calls skip the local CPU-load deferral and use a 1-second dispatch floor. Prefer `https://` for anything that isn't on your own machine: the key is sent as a Bearer header, and a plain-`http` remote URL logs a warning.

## Lineage & license

Fork of Shimeji-ee (Group Finity / Shimeji-ee Group / kilkakon), itself descended from Group Finity's original Shimeji. Full credit to the original authors - see [`CREDITS.md`](CREDITS.md) for everyone and everything this builds on. The original Shimeji-ee manual is preserved in the repo as `readme.txt` (detailed configuration and customization docs); original licenses are retained in `licence.txt`, `originallicence.txt`, and `originalreadme.txt`.
