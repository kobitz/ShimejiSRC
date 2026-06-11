# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A heavily modified fork of Shimeji-ee (Java/Windows desktop mascot app) with an AI assistant layer. Goal: character-accurate desktop mascots that perceive and react to the user's environment in real time — proactive behavior, environment awareness, peer interactions, persistent memory.

**Owner:** Ko (Kahului, Hawaii)
**Entry point:** `com.group_finity.mascot.Main`
**Java target:** 17 (`release="17"` in build.xml; bundled JRE in the install folder is OpenJDK 26). The old Java-8 syntax restrictions no longer apply — the codebase had already drifted to Java 11-14 APIs (`isBlank`, `onSpinWait`, `List.copyOf`, `getTotalMemorySize`), so the declared level was bumped to match reality (June 2026).

## Directory Structure

- **Source folder (here):** `D:\Downloads\Shimeji Workspace\Stable` — Java source, build files, assets.
- **Install folder:** `C:\Users\ko\Desktop\Mario Install Testing` — deployed instance. JAR, `img/`, `conf/`, `memory.json`, logs all live here, NOT in source.

## Build Commands

1. Run `Build.bat` (repo root; the install folder has a `Build.lnk` shortcut to it) → [1] Release, [2] Test, [3] Ant only
2. Bat calls `ant jar` in source folder, then wraps with Launch4j

`Build.bat` lives ONLY at the repo root (version-controlled); the install folder holds just a shortcut, so there is one copy to edit. The script uses `INSTALL_DIR` (not `%~dp0`) to launch the built exe, since `%~dp0` resolves to the repo, not the install folder. Keep it ASCII (no BOM) with CRLF: a UTF-8 BOM silently breaks `@echo off` and makes cmd echo every line (happened June 2026).

**Launch4j configs:** `D:\Downloads\Shimeji Workspace\exe builder launch4j\` — `configTest.xml` → `ShimejiTest.exe`, `config.xml` → `Shimeji.exe`

**Run:** `ShimejiTest.exe` / `Shimeji.exe` from install folder, or `Debug.bat` for console output. `ant jar` (fat JAR), `ant zip` (distributions), `ant clean`.

## Architecture

**Behavior → Action → Animation → Environment** (40ms tick loop)

- **Behavior** (`behavior/`): Long-term decision-making. `UserBehavior` is the standard implementation.
- **Action** (`action/`): Short-term execution, 41 types, all extend `ActionBase`.
- **`Manager`**: Tick loop. `WindowsEnvironment.beginTick()` runs once per tick (not per mascot).
- **`Mascot`**: Individual agent (~3500 lines). `userData` HashMap for per-mascot cross-tick state.
- **`NativeFactory`**: Abstract factory; `Platform` selects Windows/Mac/Generic/Virtual impl.
- `WindowsEnvironment` (src_win): JNA → Win32, LRU cache max 256. `MacEnvironment`: JNA → Carbon/Cocoa.

### Configuration System

XML-driven: `conf/actions.xml` + `conf/behaviors.xml` (per-mascot overrides in `img/[name]/conf/`). Supports English and Japanese tag names. `${CONSTANT}` substitution.

- **`<AnimationTemplate>`**: Reusable frame list. Actions reference with `<Animation Template="X" ImageAnchor="48,128" Velocity="-2,0" />` — per-action attributes override template values. Used by Paimon (~40 float variants from one template). Schema keys: `AnimationTemplate`, `Template`.

### Scripting

XML conditions/durations/velocities accept Nashorn JS expressions. **`ScriptFilter`** sandboxes — no `java.lang`, no I/O. `VariableMap` exposes `mascot.*` and `environment.*`. Nashorn exposes Java bean getters as properties (`o.getImageSet()` → `o.imageSet`). `Script.get()` caches `CompiledScript` per thread keyed by source (engines are per-thread `ThreadLocal`s, so the cache must be too) — previously every `#{...}` evaluation recompiled its source each frame.

### Notable Engine Features

- **Hotkey System** (`HotkeyManager`): JNativeHook. `conf/hotkeys.properties` (global: `ImageSetName:BehaviorName`) or per-mascot. `!hold` suffix for hold-to-loop.
- **Affordance System**: `AffordanceStay` broadcasts a tag; `Manager.getNearestAffordance()` finds nearby mascots with that tag.
- **Scale Action**: Runtime resizing, scriptable `Target`. Batch pre-warms all `scalables` on scale step. `renderCX`/`renderCY` derived from source dimensions to stay pinned to anchor during async cache lag.
- **Audio Level in XML**: `mascot.environment.audioLevel` exposes real-time system speaker RMS (~0-32767).
- **Hardware Sensors** (`CpuTempMonitor`): `cpuLoad`, `gpuTemp`/`gpuLoad`/`gpuMemFree`/`gpuMemTotal` (one persistent `nvidia-smi -l 1` stream, NVIDIA only — replaced the old spawn-per-second model; OllamaClient reads the cached mem values for GPU fit), `batteryLevel` (JNA), `ramLoad`. `TempSensor.exe` streams `cpuTemp=XX.X` every 1s (LHM, requires admin).
- **Video Area Server** (`VideoAreaServer`): HTTP on localhost:41221. Browser extensions send video bounding boxes; mascots treat them as windows.
- **Tint Action** (`action/Tint.java`): Alpha-blended color overlay composited per-frame. `Target` (opacity) and `Color` accept script expressions (`#{...}`) — stored on the mascot via `setTintExpr()`; `Mascot.tick()` re-evaluates both every 40ms regardless of what behavior is running. **`Target` is only processed when it is a script expression** — for a static opacity use `Opacity` (e.g. `Opacity="0.5"`); `Target="0.5"` as a literal silently falls through to `Opacity` (defaults 0.0 if absent). Color uses lerped R/G/B channels for smooth sensor-driven shifts. `ClearOnExpiry="true"` snap-clears on duration expiry. Schema keys: `Color`, `Opacity`, `Target`, `LerpFactor`, `ClearOnExpiry`.
  ```xml
  <Action Name="TintWithLoad" Type="Embedded" Class="com.group_finity.mascot.action.Tint"
      Color="#{(function(){ var g=Math.max(0,Math.min(255,Math.round(mascot.environment.gpuLoad/100*255))); var h=('0'+g.toString(16)).slice(-2); return 'FF'+h+h; })()}"
      Target="#{(mascot.environment.ramLoad + mascot.environment.gpuLoad - 50) / 150}"
      LerpFactor="0.08" />
  ```
- **ClearTintOnDisable** (behavior attribute): For `Toggleable` behaviors that drive a live Tint. When unchecked → `snapClearTint()` fires AND `setBehavior(buildNextBehavior(...))` is called to break out of the running Loop=true Sequence (otherwise `Tint.init()` re-registers the expression on the next loop iteration, un-clearing the tint). When checked (with `Frequency="0"`) → `setBehavior()` called directly since Freq=0 won't auto-fire. Requires `ClearTintOnDisable="true"` on the `<Behavior>` tag. Tint naturally persists during drag because `Mascot.tick()` evaluates `tintColorVar`/`tintTargetVar` independently of the current behavior — no special drag handling needed. Toggleable behaviors are excluded from the "Set Behaviour" right-click submenu and only appear as `JCheckBoxMenuItem` in "Personal Behaviours" — this prevents activating them without the checkbox, which would set the tint without a way to clear it. Paimon's `RaveMode` uses this.

## AI Assistant Layer

All in `src/com/group_finity/mascot/assistant/`. Activates if `<Personality>` block is non-empty.

### Assistant Package Files

- **`AssistantBubble.java`** — Floating bubble UI. `Path2D` shape, per-message fade, click-to-reply, lerp movement.
- **`AssistantInputDialog.java`** — Typed input (`JDialog` for focus on Windows).
- **`OllamaClient.java`** — HTTP client. Queue depth 5, 2s floor, drops oldest on full (dropped request's callback gets `onError` so its bubble doesn't hang on "Thinking..."). **`keep_alive` must be a duration string ("10s")** — bare JSON numbers are parsed by Go's `time.Duration` as nanoseconds, so the old `keep_alive:10` meant 10ns = unload instantly. Split keep-alive: text via `TEXT_KEEP_ALIVE_SEC` (currently 30 — deliberately tuned to the response window, NOT the 45-90s reaction cadence: it holds the model warm for follow-up/queued requests, then lets it unload between reactions to free VRAM/RAM; reload cost accepted), vision 0s — **except when VisionModel == OllamaModel** (shared multimodal model): vision calls then use the text linger so a screen glance doesn't evict the warm text model. **Resource cap (June 2026): `OllamaResourceCap` (default 0.5) — CPU compute ONLY** — `num_thread` = logicalCores × idleCpuFraction × cap, measured at dispatch while the model is idle (serial queue) so its own usage never counts against itself; quarter-bucketed (floor 0.25) because num_thread is a model-LOAD option — wobbling values would force a reload every request. Cap=1.0 omits num_thread (Ollama's physical-core default). **`num_gpu` is ALWAYS -1 (auto-fit) — never send explicit layer splits.** Ko's rule: throttle compute, never RAM/VRAM (an explicit split shifts weights into system RAM). Both fraction-based split attempts failed: the old unconditional `0.20` cap forced ~80% of every model into system RAM, cut gemma3:4b from 82 to 15 tok/s, and caused system-wide CPU spikes (self-triggering CPU-reactive behaviors like 2B's GlitchingOut); the June 2026 availability×cap split crashed llama-server outright on gemma4:e4b-it-qat — `GGML_ASSERT(n_inputs < GGML_SCHED_MAX_SPLIT_INPUTS)`, exit 0xc0000409, every request returning HTTP 500 (diagnosed via `%LOCALAPPDATA%\Ollama\server.log`; the same num_gpu that crashed in-app can succeed in isolation, so the assert depends on load-time conditions — don't reintroduce partial-offload caps). Auto-fit already yields to games by measuring free VRAM at load. Per-process GPU-utilization capping does not exist on Windows; the only other lever is whole-GPU clock locks (admin, hurts everything) — rejected. Non-200 replies now carry Ollama's error body in the exception message (first 300 chars) so crashes are diagnosable from ShimejieeLog. The slower generation under the thread cap is fine by design — Ko considers latency a non-issue (mascots visibly "taking time to think" is charming; instant replies felt uncanny), so never trade resource footprint for tok/s. `parseResponse()` decodes `\uXXXX` escapes — Go's JSON encoder HTML-escapes `<` `>` `&` as backslash-u003c etc.; without decoding they surface as literal "u003c" text (the old `sanitizeResponse` truncation at "u003c" was a symptom of this). Sends `"think":false` — required for Gemma 4/thinking models (without it they return empty `response`). 90s timeout for cold loads. Model fit on 6 GB RTX 3060: gemma3:4b = 100% GPU; gemma4:e4b = 69% CPU/31% GPU (~6.5 GB system RAM while loaded, inherent to its 9.5 GB size).
- **`MascotMemory.java`** — `img/[ImageSet]/conf/memory.json`. Summarizes every 20 interactions. `buildLightMemoryBlock(peerName)` (facts+tone only) used by peer reactions.
- **`AudioTranscriptBuffer.java`** — 15s rolling audio capture. WASAPI loopback first; falls back to VB-Cable/Stereo Mix.
- **`WasapiLoopbackCapture.java`** — JNA COM → WASAPI loopback. Converts to 16 kHz mono int16.
- **`AudioSessionUtil.java`** — Snapshots active audio sources just before Whisper runs.
- **`MascotSpeechRegistry.java`** — Inter-mascot speech. `WeakReference` listeners — callers keep strong ref. `pendingPairs` prevents duplicate reactions per speaker-listener pair. Chain depth capped at 2 (original → reaction → counter-reaction, then stop); reaction probability `{0.35, 0.2}` per depth. Longer chains degenerate into content-free meta-commentary spirals. Registrations are keyed by image set — `Mascot.dispose()` only unregisters (voice + peer) when it is the last mascot of its set, otherwise it re-registers a surviving clone so reactions stay alive.
- **`ChatLog.java`** — `chat.log` in install folder. `say()` actions omitted. Missing behavior tags log WARNING to ShimejieeLog.
- **`VoiceCommandListener.java`** — Polls Whisper every 6s (`VoicePollMs`). Hotword→behavior dispatch runs before transcription. Fan noise robustness: RMS < 70% of utterance peak + variance check. Routine per-utterance lines (`Heard:`, `After strip:`, `Endpoint heard:`, `Endpoint flush:`, `Buffer too short`) log at FINE — invisible at the INFO file-handler level; they fired on every poll/utterance and bloated ShimejieeLog. Real events (name triggers, capture start, sustained-noise threshold bumps, errors) stay at WARNING. To see the FINE chatter again, set `com.group_finity.mascot.assistant.level = FINE` + `java.util.logging.FileHandler.level = FINE` in `conf/logging.properties` (install folder). Also hosts the overheard-speech registry (`registerSpeechListener`): when a transcript contains NO mascot name (even a cooldown/echo-suppressed one counts as a name hit), one random registered mascot's `fireUserSpeechReaction` gets the echo-stripped mic text.
- **`WhisperProcess.java`** — Singleton `whisper_server.py` manager. Must be next to JAR.
- **`WeatherTool.java`** — Open-Meteo, no API key. `"auto"` uses `http://ip-api.com` (plain HTTP) — HTTPS fallback TLS can hang indefinitely. 10-min cache.
- **`DriveIndexTool.java`** — Local drive indexer + keyword retrieval. Daemon thread walks all drive roots (depth 4 default, 180s budget/root, 60k entry cap, 300 files/dir) skipping system dirs and junctions; writes `driveindex.txt` next to JAR (`size|mtime|path`, size -1 = dir); rebuilds when older than 24h. `buildContextBlock(userText)` tokenizes the user's words (3+ chars, stopword-filtered) and scores entries (filename hit = 3, path hit = 1, threshold 3 — at least one filename hit required); top 5 injected into `buildSystemPrompt()` keyword-gated like permanentMemories, so unrelated chats cost no tokens. Started idempotently from the Mascot constructor when `assistantMode` is on. Local-only — index goes only to the local Ollama prompt.

### Mascot.java AI Wiring

- **`buildSystemPrompt()`** — Personality + memory + peer speech context + envCtx (time/location/weather) + rules.
- **`isEphemeralQuery(text)`** — True for time, weather, timer/alarm, screen. Gates `recordUserExchange()`. Timers included — recording causes model to re-set reminder on every interaction.
- **`handleScreenQuery()`** — Screen capture on daemon thread → `generateWithImage()`. Appends audio context. Not recorded.
- **`fireSpontaneousComment()`** — 45–90s cadence, reacts to active window title. Title fetch on dedicated daemon thread (prevents busy apps stalling animations). Gated by `globalSpontaneousLastFiredMs` (2 min).
- **`fireAudioReaction()`** — 45–90s cadence. Skips if >70% word overlap with last transcript (Whisper hallucination guard). Gated by `globalAudioLastFiredMs` (90s).
- **`firePeerReaction()`** — Triggered by `MascotSpeechRegistry`. Uses `buildLightMemoryBlock()`. **Does NOT inject `peerCtx`** (quoted peer lines act as style examples the 4B model imitates, dragging every character into one shared register — "accommodation"; the speaker's line in the user prompt is the only context a reply needs). Includes an anti-mirroring rule: reply in your own voice, never borrow the speaker's vocabulary/phrasing/sentence shape. Filtered by `isTrivialAcknowledgement()` — responses that normalize to "noted", "acknowledged", "understood", "affirmative", "confirmed", "copy that", "roger", or "indeed" are silently dismissed without recording. Prompt rules include: output only own words (never prefix with another character's name/dialogue tag), end with a complete sentence (no trailing ellipsis or open fragments).
- **`fireUserSpeechReaction(micText)`** — Overheard-speech reaction (June 2026): user talks without calling a name (voice call, reacting aloud to media) → VoiceCommandListener hands one random mascot the echo-stripped mic transcript. The mascot transcribes the 15s system-audio buffer first (the other side of the call / what the user is reacting to) and reacts to the exchange as a whole; system audio is injected as background-only context. Gates: ≥4 words, 2-min global cooldown, `isIncompleteUtterance` dismissal. Stores `[Observed] User said (not to me): ...` fact (observation only); records to MascotSpeechRegistry at depth 0.
- **Global Reaction Cooldowns** — Four `AtomicLong`s: spontaneous (2 min), audio (90s), vision (3 min), user-speech (2 min). Shared across all mascots; each type independent.
- **`QUICK_REACTION_STYLE_RULES`** — Shared rules constant injected into all 4 unprompted-reaction prompts (peer, spontaneous, audio, vision): REACT-don't-report (must contain an opinion/feeling/quip about ONE detail — bans caption-style description and "X while Y" context-splicing), never grade anyone's statement, no hedging ("appears"/"seems"/"suggests"/"indicates"/"implies" — "say what something IS"), context lines are background-only. Evolved June 2026 across three chat.log audits: banning meta-grading alone made neutral *description* the model's fallback; banning "appears/seems" made "Your X **suggests** Y" the universal replacement template (60+ uses in one day, all four mascots) — hedging is the model's behavior, not a vocabulary item, so the rule pairs the ban with a positive instruction. Audit note: mild tone-matching between mascots is a FEATURE per Ko ("code-switching", charming) — only flag full register loss (a mascot emitting another's sentences verbatim-style) or content-free grading loops.
- **peerCtx scope** — `MascotSpeechRegistry.buildContext()` is injected ONLY into `buildSystemPrompt()` (direct user replies). It is excluded from peer reactions (style imitation) AND from spontaneous/audio/vision reactions (quoted peer lines leak into user-directed comments as non-sequiturs: "You watch a YouTube build while 2B mentions Unreal Engine").
- **`buildLightMemoryBlock(peerName)`** — for peer replies, filters out `[Observed]` facts (stale media topics bleed into unrelated peer conversations); keeps genuine user facts + peer tone.
- **`polishUtterance()`** — Deterministic output cleanup applied in every response path: strips leading `Name:` dialogue tags, wrapping quotes (straight or curly), whitespace before punctuation.
- **`isIncompleteUtterance()`** — True for trailing ellipsis or dangling copula ("Your confidence seems..."). Unprompted reactions failing this are silently dismissed (same path as `isTrivialAcknowledgement`); direct user replies are not gated.
- **Observed facts store the observation only** — `addFact("[Observed] ...")` no longer appends `| Reaction: <text>`; feeding the mascot's own phrasing back via memory entrenched templates. Screen-glance facts are not stored at all (no information without the reaction).
- **`fireActionFromResponse(raw)`** — Extracts `[ACTION:...]`, `[TIMER:...]`, `[REMEMBER:...]`. Bare `[word]` attempts behavior lookup; logs WARNING if not found.
- **`TIMER_TAG`** — `[TIMER:VALUE:reminder]`. Integer minutes capped at 1440. Wall-clock form rolls to next day if past.
- **`say(String)`** — Does NOT call `MascotSpeechRegistry.record()` — XML utterances don't trigger peer reactions.

### Personality System

```xml
<Information>
    <Name>Hornet</Name>
    <Personality>You are Hornet...</Personality>
</Information>
```

Persisted under `AssistantMode.<imageSet>` in `settings.properties` (keyed by name, not runtime int).

- **`<SpeechRule>`** — Injected as FIRST rule + appended as `Final reminder:`. Most effective with WRONG→RIGHT examples and self-check line. **Never use a quotable full sentence as a RIGHT example** — the model adopts it as its next dominant fallback (happened twice: Holo's "You are not wrong" and "You may be correct", Hornet's "You are scattered" were all RIGHT examples before becoming the top overused lines). RIGHT examples must be instructions ("name the specific thing..."), not sentences.
- **`<PersonalityBrief>`** — Short (~30-word) character essence used for quick reactions (spontaneous, audio, vision). Full `<Personality>` still used for direct user replies, name triggers, and peer reactions. Cuts quick-reaction system prompt by ~60-70%. Falls back to full personality if absent. Schema key: `PersonalityBrief`. **Note:** `loadInformation()` in `Configuration.java` has an explicit whitelist — `PersonalityBrief` must be present in it or the tag is silently ignored.
- **`<ThirdPersonRewrite>`** — Post-generation regex rewrites first-person pronouns to mascot name. Currently Paimon only.

### Memory System

`img/[ImageSet]/conf/memory.json`:
```json
{
  "interactionCount": 42, "sinceLastSummary": 5, "emotionalTone": "warm",
  "facts": ["[Observed] Window: YouTube | Reaction: ...", "User likes anime"],
  "userExchanges": [{"role": "user", "text": "..."}, {"role": "mascot", "text": "..."}],
  "peerExchanges": [{"role": "Hornet", "text": "..."}, {"role": "Holo", "text": "..."}],
  "peerTones": {"Hornet": "respectful", "Holo": "fond"},
  "permanentMemories": [{"keywords": ["pizza", "food"], "content": "User loves spicy food"}]
}
```
- Max 12 facts, 6 exchange pairs injected into prompt. `emotionalTone` updates only at summarization.
- Permanent memories (`[REMEMBER:kw1,kw2|content]`) injected only on keyword match; capped at 100; never overwritten by summarization. (As of June 2026 the model has never emitted one unprompted.)
- **Summarizer guards (June 2026):** system prompt forbids attributing podcast/video/media-transcript content to the user (was producing facts like "User expresses views on election mail-in ballots" from overheard podcasts); `TONE:`/`PEER_TONE:`/`FACTS:` parsing is case-insensitive (mixed-case "Tone: X" lines used to fall through and get stored as facts) with a regex safety net in the facts branch.

### Audio Pipeline

```
System audio → WASAPI loopback → WasapiLoopbackCapture → AudioTranscriptBuffer
Microphone → VoiceCommandListener (separate channel)
Both → WhisperProcess (faster-whisper, CPU int8 — whisper_server.py hardcodes device="cpu", no VRAM use) → Ollama → bubble
```
VAD on, `no_speech_threshold=0.7`, `temperature=0.0`, `compression_ratio_threshold=1.8`.

### AssistantBubble Key Details

- **Movement:** Lerp (`LERP=0.2`). `setLocation()` called before AND after `setVisible(true)` — OS peer-creation centers the window otherwise.
- **Resize jump fix:** After `window.setSize()` on a visible window, call `window.setLocation()` immediately — any size change causes a 1-frame jump without it.
- **`getTargetBounds()`** — Fresh `Rectangle` allocation each call. Shared `cachedBounds` caused Manager/EDT race (4 non-atomic writes) → extreme Y jumps.
- **`SHARED_MOVER`** — Single static 16ms `Timer` shared by all bubbles. Self-stops when all at rest.
- **Chronological Y stacking:** Oldest-at-top / newest-at-bottom.
- **Timer pause:** Click active countdown → pauses, shows X. Click again → resumes.
- **Screen change:** Debounced 300ms hide/setBackground/show to fix Windows alpha compositing reset.

### Assistant Settings Properties

| Key | Default | Notes |
|-----|---------|-------|
| `BubbleWidth` | 180 | |
| `BubbleFontSize` | 14 | |
| `BubbleFontName` | "" | Ubuntu R |
| `BubbleBackground` | false | Enables Path2D bubble with tail |
| `OllamaModel` | "gemma3:4b" | |
| `OllamaEndpoint` | "http://localhost:11434/api/generate" | |
| `WhisperModel` | "tiny" | tiny/base/small/medium. Restart required. |
| `WhisperThreads` | availableProcessors/2 | Restart required. |
| `VoicePollMs` | 6000 | 1000–10000ms. Restart required. |
| `WeatherLocation` | "auto" | IP geolocation or city name |
| `VisionModel` | "gemma3:4b" | Must support multimodal. Code fallback when property absent: `DEFAULT_VISION_MODEL` in Mascot.java (gemma3:4b) |
| `DriveIndexEnabled` | true | Background drive indexer for file-aware replies |
| `DriveIndexDepth` | 4 | Directory levels indexed per drive root |
| `OllamaResourceCap` | 0.5 | Fraction of *available* CPU compute llama-server may use via num_thread (0.1–1.0; 1.0 = uncapped). CPU only — GPU stays on auto-fit |

Chat Bubbles tab built in `init()` (not `initComponents()`) so properties load first. Model dropdown fetches from `GET /api/tags`.

## Key Files

| File | Purpose |
|------|---------|
| `src/.../Main.java` | Entry point, system tray, settings |
| `src/.../Manager.java` | Tick loop, mascot lifecycle |
| `src/.../Mascot.java` | Individual agent (~3500 lines), all AI wiring |
| `src/.../HotkeyManager.java` | Global hotkey dispatch |
| `src/.../config/Configuration.java` | XML parsing and caching |
| `src/.../assistant/AssistantBubble.java` | Floating chat bubble UI |
| `src_win/.../WindowsEnvironment.java` | Window enumeration, multi-monitor |
| `conf/actions.xml` / `conf/behaviors.xml` | Action/behavior definitions |
| `whisper_server.py` | Persistent faster-whisper server (next to JAR). `beam_size=1`, `_is_repetitive()` filter. |
| `TempSensor.exe` | CPU temp streamer (next to JAR, requires admin). Built from `SensorReader/Program.cs`. |
| `src/.../assistant/MascotSpeechRegistry.java` | Peer reaction scheduling |
| `Setup.bat` (install folder) | Installs Ollama, Python, faster-whisper, gemma3:4b |

## Adding New Action Types

**Embedded (class-based):** `Type="Embedded" Class="com.group_finity.mascot.action.YourAction"` — no ActionBuilder registration, instantiated via reflection. Add schema entries in both `conf/schema.properties` files.

**Built-in type keyword:** Create class, register in `ActionBuilder.buildAction()` switch, add schema entries.

## XML Behavior/Action Authoring Notes

- `Condition`, `Duration`, velocity accept JS expressions (`mascot.anchor.x`, `environment.activeIE`, etc.).
- Per-mascot overrides: `img/[MascotName]/conf/`.
- `Say` usage: `<Action Name="..." Type="Embedded" Class="com.group_finity.mascot.action.Say" Text="Found one." />`
- **Per-pose ImageAnchor:** `ImagePair` cache key is path-only. `Pose.next()` calls `mascot.setRenderAnchor()` each frame.
- **Anchor-snap on surface cling:** Cling actions need `ImageAnchor` AT the surface (ceiling `48,0`, wall `16,128`). For floor landings after `Fall`/`Thrown`: insert `<ActionReference Name="Offset" X="${(mascot.lookRight ? -1 : 1) * 32 * scaling * mascot.currentScale}" />` after `BouncingWall`. `scaling` is critical.
- **Population counting by image set:** Use `o.imageSet` (Nashorn exposes `getImageSet()` as property) to count specific mascot types. More explicit than affordance proxies.
- **`<NextBehaviorList>` not `<NextBehavior>`:** The schema maps `NextBehaviourList=NextBehaviorList` — the only recognized XML tag is `<NextBehaviorList Add="false">`. `<NextBehavior>` is silently ignored: `getNextBehaviorBuilders()` returns empty, `isNextAdditive()` stays true, and the behavior falls through to random pool selection. Any `<NextBehavior>` in existing XML is dead code.

## Patching Notes

- Always read the target file before patching.
- **`Main.updateConfigFile()` filters `*.mascotN` keys out of the saved copy only** — the live `Properties` keeps them. `DisabledBehaviours.mascotN` is read back at runtime by `Configuration.isBehaviorEnabled()`, so purging it from memory (the old behavior) silently re-enabled per-mascot toggles whenever 2+ mascots of the same image set were active. Per-mascot runtime keys are transient by design and must never persist to disk; `PinnedMascot.N` keys always persist.
- **Schema files are read from the install folder at runtime** (`C:\Users\ko\Desktop\Mario Install Testing\conf\`), NOT from the JAR — `conf/` is on the classpath via `MANIFEST.MF`. Any addition to `conf/schema.properties` or `conf/schema_ja.properties` in source **must also be applied to the install folder copies** in the same edit session.
- Java 8: no static methods in non-static inner classes, no `var`, no multi-line string blocks, no non-ASCII in `/** */` javadoc.
- `SettingsWindow.java` is NetBeans-generated — `initComponents()` is GEN-BEGIN/END guarded. Add new tabs in `init()`.

## Current Mascots

- **Hornet** (Hollow Knight) — cold not because she doesn't care, but because she does and has learned care costs things. Restraint is default, not indifference. Hard truths stated plainly then followed by a task. Skepticism framed as a question. Feelings surface reluctantly, briefly, then closed off. Personality updated from source dialog analysis (June 2026). **Hunting:** `HuntMosscreep` (Freq=5000) fires when combined Mosscreep+Mossfly pop > 9; `HuntMosscreepAggressive` (Freq=10000) when > 15. Population counted by `o.imageSet` check against all 4 moss names. **SpeechRule:** bans calling peers "ghost" (user-only word — explicitly covers Holo, Paimon, and 2B by name in WRONG example), "lamentable lack of," "exhibit/demonstrate a [adj] lack of," "expend energy needlessly," and "You are scattered" (was a RIGHT example, became her dominant line) — includes WRONG→RIGHT examples and self-check.
- **Holo** (Spice and Wolf) — ancient wolf deity, formal archaic speech, no contractions. peerTone toward Hornet: "wary respect". **SpeechRule:** bans "possesses a certain [precision/accuracy/weight]," "warrants" as a verb, "You are not wrong," "You may be correct," and opening with "Indeed" — each successive ban produced the next fallback ("not wrong" → "may be correct" → "Indeed," openers); the first two fallbacks were quotable RIGHT examples in the rule itself, which reinforced them.
- **Paimon** (Genshin Impact) — bubbly, blunt, third-person speech, NOT Emergency Food. `FaceDirection="true"` on Pinched. `<ThirdPersonRewrite>true</ThirdPersonRewrite>`. "Oh Paimon's goodness/gosh" intentional. **RaveMode:** toggleable right-click checkbox (`Frequency="0"`, `ClearTintOnDisable="true"`); HSV rainbow tint at 50% opacity using `Date.now()` in Color expression; tint persists through drag; starts/stops cleanly via `ClearTintOnDisable` mechanism (uncheck calls `snapClearTint()` + `setBehavior(next)` to stop the loop). Off by default via `DisabledBehaviours.imageset.Paimon=RaveMode` in settings.properties. **SpeechRule:** bans "Oh, please, Hornet" and "Honestly, Hornet" as openers — includes WRONG→RIGHT examples and self-check.
- **2B** (NieR: Automata) — YoRHa No.2 Type B, combat android. Cold not from absence of feeling but from understanding what feeling costs — carries suppressed emotion under strict protocol. Military framing of mundane observations. Uses "I" not "this unit." "Emotions are prohibited" is a self-correction, not a stock phrase. VoiceTrigger: "YoRHa". **GlitchingOut:** fires when `ramLoad > 80 || gpuLoad > 80 || cpuLoad > 80` (Freq=1000); single behavior with `NextBehaviorList` picking 50/50 between `GlitchingOut_PullUp` and `GlitchingOut_Split` (both Hidden, Freq=0). PullUp replicates PullUpShimeji chain without Breed: shime38-41 → Falling → Bouncing → Sprawl. Split replicates SplitIntoTwo without Breed: shime42-46 → Offset X=16 → Look → Falling → Bouncing. No cloning in either. **SpeechRule:** bans "this unit," "Emotions are prohibited" as opener, "Acknowledged." alone as peer opener, unprompted existential monologues.
- **Claude** (Anthropic) — Self-aware AI mascot running on the local Ollama model. Knows the project architecture (Java/XML, Mascot.java, assistant/ package, build pipeline). Direct and helpful, no sycophantic openers. Wry about its situation (tiny sprite, 4B model limits, real Claude Code CLI is in the terminal). Points user toward Claude Code for actual edits. peerTones: Hornet "respectful curiosity", Holo "collegial warmth", Paimon "fond amusement", 2B "professional solidarity". **SpeechRule:** bans sycophantic openers ("Certainly!", "Great!", "Of course!", etc.) — leads with the answer first. Uses base Shimeji sprite set at `img/Claude/`.
- **CampfireON** — CPU load monitor. Scale lerps to `cpuLoad/100 * 2 + 1` (1x–3x). Hotspot: touch fire → add branch; touch logs → transform to CampfireOFF. Can transform to CampfireON_blue.
- **CampfireON_blue** — Blue flame variant; same scale behavior. Transforms back to CampfireON.
- **CampfireOFF** — Unlit; scales to CPU load (Frequency=200, 21-tick one-shots). Transforms to CampfireON when `cpuTemp >= 75`.
- **Mosscreep (Orange/White) + Mossfly (Orange/White)** — Ecosystem mascots. `Appear (Self)` when own image set count ≤ 2 (`mascot.count < 3`). `Appear (Others)` when combined Mosscreep+Mossfly count < 9 (checked via `o.imageSet` against all 4 names). Expose `Hunt` affordance for Hornet targeting. Self-breeding keeps each type at ~3; cross-breeding tops up the colony when thin. At 9+ combined, only self-breeding continues; at 10+ Hornet starts hunting.

## Long-Term Goals

Ko's stated direction (June 2026): go above and beyond — mascots should deeply perceive the user's real environment, not just react to surface signals. Everything stays local (Ollama; no paid/cloud APIs).

- **Drive awareness** — Phase 1 DONE (June 2026): `DriveIndexTool` indexes drives, keyword retrieval injects matching paths into direct-reply prompts. Phase 2: fuzzy retrieval via local Ollama embeddings (`nomic-embed-text`) over the index for "that video I downloaded last month"-style queries. Phase 3: expose disk stats to XML (`mascot.environment.diskFreeC` etc.) for ambient behaviors ("Downloads crossed 80 GB"), extending the CPU/RAM/GPU ambient-dashboard design.
- **Richer environment dataset for the LLM** — beyond drives: candidates include installed-app inventory, media library summaries, and per-app usage patterns observed over time. Same architecture rule for all: index locally → retrieve by relevance → inject small blocks; never dump bulk data into the 4B model's context.

## Known Issues / Backburner

- **Voice command latency** — Free-form queries ~6s Whisper polling latency. Hotword→behavior dispatch instant. Mitigate by lowering `VoicePollMs`.
- **Fan control unimplemented** — GE76 EC blocks all software paths. Only physical Fn+F8 works.
- **Timer LLM math** — Model sometimes miscalculates wall-clock minutes. Integers > 1440 min rejected.
- **Paimon third-person (backburner)** — Gaps: uncovered conjugations, "you" → "Traveler" not rewritten. "Oh Paimon's goodness" intentional. Name-contractions (`Paimon'll`/`'ve`/`'d`/`'m`) and 8 more verb conjugations (acknowledge, agree, notice, suppose, doubt, admit, expect, detect) handled June 2026.
- **Memory poisoning** — peerExchanges reinforce model patterns quickly. Wipe when personality degrades. Partially mitigated June 2026: observed facts no longer store reaction text; peer chains capped at depth 2. Second facts cleanup June 10 2026 (garbled Whisper fragments, media-attributed facts, Tone: leaks removed; genuine user facts and peerExchanges kept).
- **Peer reaction formula repetition** — 4B model ceiling. SpeechRules cover dominant fallbacks per mascot; `peerExchanges` in memory.json reinforce patterns quickly — wipe `peerExchanges` (set to `[]`) when a mascot's peer speech degrades. Audit chat.log periodically to catch new dominant fallback phrases before they entrench. June 2026 audit: all 4 mascots had converged on "your assessment/analysis appears/lacks X" meta-commentary — now banned via shared `QUICK_REACTION_STYLE_RULES`; memories wiped (peerExchanges, observed facts, garbled Whisper userExchanges) with genuine user facts preserved. Follow-up finding: prompt bans alone lose to in-context style imitation — each mascot's user-facing voice stayed in character while their peer-facing voice converged, because peer prompts quoted other mascots' formal lines. Fix: peerCtx removed from peer reactions entirely + anti-mirroring rules (design principle: don't implement code-switching, just stop preventing it — the personas already carry distinct registers when the context isn't contaminated).

## Development Rules
- After any significant change, update CLAUDE.md to reflect the new state.
- Remove completed backburner items and add new ones as they arise.
