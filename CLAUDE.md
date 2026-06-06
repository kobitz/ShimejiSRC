# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A heavily modified fork of Shimeji-ee (Java/Windows desktop mascot app) with an AI assistant layer. Goal: character-accurate desktop mascots that perceive and react to the user's environment in real time — proactive behavior, environment awareness, peer interactions, persistent memory.

**Owner:** Ko (Kahului, Hawaii)
**Entry point:** `com.group_finity.mascot.Main`
**Java target:** 8 — no `var`, no multi-line string blocks, no static methods in non-static inner classes, no non-ASCII chars in `/** */` javadoc (causes Java 8 compile errors — use plain hyphens).

## Directory Structure

- **Source folder (here):** `D:\Downloads\Shimeji Workspace\Stable` — Java source, build files, assets.
- **Install folder:** `C:\Users\ko\Desktop\Mario Install Testing` — deployed instance. JAR, `img/`, `conf/`, `memory.json`, logs all live here, NOT in source.

## Build Commands

1. Run `C:\Users\ko\Desktop\Mario Install Testing\Build.bat` → [1] Release, [2] Test, [3] Ant only
2. Bat calls `ant jar` in source folder, then wraps with Launch4j

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

XML conditions/durations/velocities accept Nashorn JS expressions. **`ScriptFilter`** sandboxes — no `java.lang`, no I/O. `VariableMap` exposes `mascot.*` and `environment.*`. Nashorn exposes Java bean getters as properties (`o.getImageSet()` → `o.imageSet`).

### Notable Engine Features

- **Hotkey System** (`HotkeyManager`): JNativeHook. `conf/hotkeys.properties` (global: `ImageSetName:BehaviorName`) or per-mascot. `!hold` suffix for hold-to-loop.
- **Affordance System**: `AffordanceStay` broadcasts a tag; `Manager.getNearestAffordance()` finds nearby mascots with that tag.
- **Scale Action**: Runtime resizing, scriptable `Target`. Batch pre-warms all `scalables` on scale step. `renderCX`/`renderCY` derived from source dimensions to stay pinned to anchor during async cache lag.
- **Audio Level in XML**: `mascot.environment.audioLevel` exposes real-time system speaker RMS (~0-32767).
- **Hardware Sensors** (`CpuTempMonitor`): `cpuLoad`, `gpuTemp`/`gpuLoad` (nvidia-smi, NVIDIA only), `batteryLevel` (JNA), `ramLoad`. `TempSensor.exe` streams `cpuTemp=XX.X` every 1s (LHM, requires admin).
- **Video Area Server** (`VideoAreaServer`): HTTP on localhost:41221. Browser extensions send video bounding boxes; mascots treat them as windows.
- **Tint Action** (`action/Tint.java`): Alpha-blended color overlay composited per-frame. Both `Target` (opacity) and `Color` accept script expressions — stored on the mascot via `setTintExpr()`; `Mascot.tick()` re-evaluates both every 40ms regardless of what behavior is running. Color uses lerped R/G/B channels for smooth sensor-driven shifts. `ClearOnExpiry="true"` snap-clears on duration expiry. Schema keys: `Color`, `Opacity`, `Target`, `LerpFactor`, `ClearOnExpiry`.
  ```xml
  <Action Name="TintWithLoad" Type="Embedded" Class="com.group_finity.mascot.action.Tint"
      Color="#{(function(){ var g=Math.max(0,Math.min(255,Math.round(mascot.environment.gpuLoad/100*255))); var h=('0'+g.toString(16)).slice(-2); return 'FF'+h+h; })()}"
      Target="#{(mascot.environment.ramLoad + mascot.environment.gpuLoad - 50) / 150}"
      LerpFactor="0.08" />
  ```
- **ClearTintOnDisable** (behavior attribute): For `Toggleable` behaviors that drive a live Tint. When unchecked → `snapClearTint()` fires; when checked (with `Frequency="0"`) → `setBehavior()` called directly since Freq=0 won't auto-fire. Requires `ClearTintOnDisable="true"` on the `<Behavior>` tag. Paimon's `RaveMode` uses this.

## AI Assistant Layer

All in `src/com/group_finity/mascot/assistant/`. Activates if `<Personality>` block is non-empty.

### Assistant Package Files

- **`AssistantBubble.java`** — Floating bubble UI. `Path2D` shape, per-message fade, click-to-reply, lerp movement.
- **`AssistantInputDialog.java`** — Typed input (`JDialog` for focus on Windows).
- **`OllamaClient.java`** — HTTP client. Queue depth 5, 2s floor, drops oldest on full. Sends `"keep_alive":0` and `"think":false` — required for Gemma 4/thinking models (without it they return empty `response`). 90s timeout for cold loads.
- **`MascotMemory.java`** — `img/[ImageSet]/conf/memory.json`. Summarizes every 20 interactions. `buildLightMemoryBlock(peerName)` (facts+tone only) used by peer reactions.
- **`AudioTranscriptBuffer.java`** — 15s rolling audio capture. WASAPI loopback first; falls back to VB-Cable/Stereo Mix.
- **`WasapiLoopbackCapture.java`** — JNA COM → WASAPI loopback. Converts to 16 kHz mono int16.
- **`AudioSessionUtil.java`** — Snapshots active audio sources just before Whisper runs.
- **`MascotSpeechRegistry.java`** — Inter-mascot speech. `WeakReference` listeners — callers keep strong ref. `pendingPairs` prevents duplicate reactions per speaker-listener pair.
- **`ChatLog.java`** — `chat.log` in install folder. `say()` actions omitted. Missing behavior tags log WARNING to ShimejieeLog.
- **`VoiceCommandListener.java`** — Polls Whisper every 6s (`VoicePollMs`). Hotword→behavior dispatch runs before transcription. Fan noise robustness: RMS < 70% of utterance peak + variance check.
- **`WhisperProcess.java`** — Singleton `whisper_server.py` manager. Must be next to JAR.
- **`WeatherTool.java`** — Open-Meteo, no API key. `"auto"` uses `http://ip-api.com` (plain HTTP) — HTTPS fallback TLS can hang indefinitely. 10-min cache.

### Mascot.java AI Wiring

- **`buildSystemPrompt()`** — Personality + memory + peer speech context + envCtx (time/location/weather) + rules.
- **`isEphemeralQuery(text)`** — True for time, weather, timer/alarm, screen. Gates `recordUserExchange()`. Timers included — recording causes model to re-set reminder on every interaction.
- **`handleScreenQuery()`** — Screen capture on daemon thread → `generateWithImage()`. Appends audio context. Not recorded.
- **`fireSpontaneousComment()`** — 45–90s cadence, reacts to active window title. Title fetch on dedicated daemon thread (prevents busy apps stalling animations). Gated by `globalSpontaneousLastFiredMs` (2 min).
- **`fireAudioReaction()`** — 45–90s cadence. Skips if >70% word overlap with last transcript (Whisper hallucination guard). Gated by `globalAudioLastFiredMs` (90s).
- **`firePeerReaction()`** — Triggered by `MascotSpeechRegistry`. Uses `buildLightMemoryBlock()`.
- **Global Reaction Cooldowns** — Three `AtomicLong`s: spontaneous (2 min), audio (90s), vision (3 min). Shared across all mascots; each type independent.
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

- **`<SpeechRule>`** — Injected as FIRST rule + appended as `Final reminder:`. Most effective with WRONG→RIGHT examples and self-check line.
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
- Permanent memories (`[REMEMBER:kw1,kw2|content]`) injected only on keyword match; capped at 100; never overwritten by summarization.

### Audio Pipeline

```
System audio → WASAPI loopback → WasapiLoopbackCapture → AudioTranscriptBuffer
Microphone → VoiceCommandListener (separate channel)
Both → WhisperProcess (faster-whisper, CUDA) → Ollama (gemma3:4b) → bubble
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
| `VisionModel` | "gemma3:4b" | Must support multimodal. Fallbacks: moondream, llava |

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

## Patching Notes

- Always read the target file before patching.
- **Schema files are read from the install folder at runtime** (`C:\Users\ko\Desktop\Mario Install Testing\conf\`), NOT from the JAR — `conf/` is on the classpath via `MANIFEST.MF`. Any addition to `conf/schema.properties` or `conf/schema_ja.properties` in source **must also be applied to the install folder copies** in the same edit session.
- Java 8: no static methods in non-static inner classes, no `var`, no multi-line string blocks, no non-ASCII in `/** */` javadoc.
- `SettingsWindow.java` is NetBeans-generated — `initComponents()` is GEN-BEGIN/END guarded. Add new tabs in `init()`.

## Current Mascots

- **Hornet** (Hollow Knight) — cold not because she doesn't care, but because she does and has learned care costs things. Restraint is default, not indifference. Hard truths stated plainly then followed by a task. Skepticism framed as a question. Feelings surface reluctantly, briefly, then closed off. Personality updated from source dialog analysis (June 2026). **Hunting:** `HuntMosscreep` (Freq=5000) fires when combined Mosscreep+Mossfly pop > 9; `HuntMosscreepAggressive` (Freq=10000) when > 15. Population counted by `o.imageSet` check against all 4 moss names.
- **Holo** (Spice and Wolf) — ancient wolf deity, formal archaic speech, no contractions. peerTone toward Hornet: "wary respect".
- **Paimon** (Genshin Impact) — bubbly, blunt, third-person speech, NOT Emergency Food. `FaceDirection="true"` on Pinched. `<ThirdPersonRewrite>true</ThirdPersonRewrite>`. "Oh Paimon's goodness/gosh" intentional. **RaveMode:** toggleable right-click checkbox (`Frequency="0"`, `ClearTintOnDisable="true"`); HSV rainbow tint at 50% opacity using `Date.now()` in Color expression; starts/stops cleanly via `ClearTintOnDisable` mechanism.
- **CampfireON** — CPU load monitor. Scale lerps to `cpuLoad/100 * 2 + 1` (1x–3x). Hotspot: touch fire → add branch; touch logs → transform to CampfireOFF. Can transform to CampfireON_blue.
- **CampfireON_blue** — Blue flame variant; same scale behavior. Transforms back to CampfireON.
- **CampfireOFF** — Unlit; scales to CPU load (Frequency=200, 21-tick one-shots). Transforms to CampfireON when `cpuTemp >= 75`.
- **Mosscreep (Orange/White) + Mossfly (Orange/White)** — Ecosystem mascots. `Appear (Self)` when own image set count ≤ 2 (`mascot.count < 3`). `Appear (Others)` when combined Mosscreep+Mossfly count < 9 (checked via `o.imageSet` against all 4 names). Expose `Hunt` affordance for Hornet targeting. Self-breeding keeps each type at ~3; cross-breeding tops up the colony when thin. At 9+ combined, only self-breeding continues; at 10+ Hornet starts hunting.

## Known Issues / Backburner

- **Voice command latency** — Free-form queries ~6s Whisper polling latency. Hotword→behavior dispatch instant. Mitigate by lowering `VoicePollMs`.
- **Fan control unimplemented** — GE76 EC blocks all software paths. Only physical Fn+F8 works.
- **Timer LLM math** — Model sometimes miscalculates wall-clock minutes. Integers > 1440 min rejected.
- **Paimon third-person (backburner)** — Gaps: uncovered conjugations, "you" → "Traveler" not rewritten. "Oh Paimon's goodness" intentional.
- **Memory poisoning** — peerExchanges reinforce model patterns quickly. Wipe when personality degrades.
- **Peer reaction formula repetition** — 4B model ceiling. Prompt-level bans + memory hygiene reduce frequency.

## Development Rules
- After any significant change, update CLAUDE.md to reflect the new state.
- Remove completed backburner items and add new ones as they arise.
