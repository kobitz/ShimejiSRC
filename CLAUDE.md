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

**Normal build flow:**
1. Run `C:\Users\ko\Desktop\Mario Install Testing\Build.bat` → [1] Release, [2] Test, [3] Ant only
2. Bat calls `ant jar` in source folder, then wraps with Launch4j

**Launch4j configs:** `D:\Downloads\Shimeji Workspace\exe builder launch4j\`
- `configTest.xml` → `ShimejiTest.exe`, `config.xml` → `Shimeji.exe`

**Run:** `ShimejiTest.exe` / `Shimeji.exe` from install folder, or `Debug.bat` for console output.

**Ant:** `ant jar` (fat JAR), `ant zip` (distributions), `ant clean`

## Architecture

**Behavior → Action → Animation → Environment** (40ms tick loop)

- **Behavior** (`behavior/`): Long-term decision-making. `UserBehavior` is the standard implementation.
- **Action** (`action/`): Short-term execution, 41 types, all extend `ActionBase`. Runs N ticks then returns to Behavior.
- **Animation** (`animation/`): Sequence of `Pose` objects. Actions own and drive animations.
- **`Manager`**: Tick loop. `WindowsEnvironment.beginTick()` runs once per tick (not per mascot) for performance.
- **`Mascot`**: Individual agent (~3500 lines). `userData` HashMap for per-mascot cross-tick state.
- **`NativeFactory`**: Abstract factory; `Platform` selects Windows/Mac/Generic/Virtual impl.

### Environment Abstraction

- `WindowsEnvironment` (src_win): JNA → Win32. LRU cache (max 256 entries).
- `MacEnvironment` (src_mac): JNA → Carbon/Cocoa.
- `GenericEnvironment` / `VirtualEnvironment`: Fallback / Swing panel mode.

### Configuration System

XML-driven: `conf/actions.xml` + `conf/behaviors.xml` (per-mascot overrides in `img/[name]/conf/`). Supports English and Japanese tag names. `${CONSTANT}` substitution.

- **`<AnimationTemplate>`**: Reusable frame list. Declared in `<ActionList>`; actions reference with `<Animation Template="X" ImageAnchor="48,128" Velocity="-2,0" />` — per-action attributes override template values for every frame. Used by Paimon (~40 float action variants from one template). Schema keys: `AnimationTemplate`, `Template`.

### Scripting

XML conditions/durations/velocities accept Nashorn JS expressions. **`ScriptFilter`** sandboxes — no `java.lang`, no I/O. `VariableMap` exposes `mascot.*` and `environment.*`.

### Notable Engine Features

- **Hotkey System** (`HotkeyManager`): JNativeHook. `conf/hotkeys.properties` (global: `ImageSetName:BehaviorName`) or per-mascot. `!hold` suffix for hold-to-loop.
- **Affordance System**: `AffordanceStay` broadcasts a tag; `Manager.getNearestAffordance()` finds nearby mascots with that tag (emergent inter-mascot interactions, no direct refs).
- **Synchronized Animation**: `SyncedStay` locks to `Manager.globalSyncTick`.
- **Scale Action**: Runtime resizing, scriptable `Target`. Batch pre-warms all `scalables` on scale step to prevent size jitter. `renderCX`/`renderCY` derived from source dimensions to stay pinned to anchor during async cache lag.
- **Audio Level in XML**: `mascot.environment.audioLevel` exposes real-time system speaker RMS (~0-32767). Backed by `AudioTranscriptBuffer.currentSysRms`.
- **Hardware Sensors** (`CpuTempMonitor`): `cpuLoad` (OperatingSystemMXBean), `gpuTemp`/`gpuLoad` (nvidia-smi, NVIDIA only), `batteryLevel` (JNA). `TempSensor.exe` streams `cpuTemp=XX.X` every 1s (LHM, requires admin). `ramLoad` live in `MascotEnvironment`.
- **Video Area Server** (`VideoAreaServer`): HTTP on localhost:41221. Browser extensions send video bounding boxes; mascots treat them as windows.
- **Tint Action** (`action/Tint.java`): Alpha-blended color overlay (`AlphaComposite.SrcAtop`) composited per-frame in `apply()`. Both `Target` (opacity) and `Color` accept script expressions — `Tint.init()` detects scripts vs constants and stores them on the mascot via `setTintExpr()`; `Mascot.tick()` re-evaluates both every 40ms regardless of what behavior is running. Color uses separate lerped R/G/B channels (`tintCurrentR/G/B` → `tintTargetR/G/B`) so sensor-driven color shifts are smooth, not stepped. `Duration` controls action run time only (safe to override via ActionRef). `ClearOnExpiry="true"` to snap-clear on exit. Channels clamped to [0,255] — safe when `gpuLoad` returns -1 (no NVIDIA). Schema keys: `Color`, `Opacity`, `Target`, `LerpFactor`, `ClearOnExpiry`.
  ```xml
  <!-- Register once (e.g. prepended to StandUp); tint tracks sensors live after action exits -->
  <Action Name="TintWithLoad" Type="Embedded" Class="com.group_finity.mascot.action.Tint"
      Color="#{(function(){ var g=Math.max(0,Math.min(255,Math.round(mascot.environment.gpuLoad/100*255))); var h=('0'+g.toString(16)).slice(-2); return 'FF'+h+h; })()}"
      Target="#{(mascot.environment.ramLoad + mascot.environment.gpuLoad - 50) / 150}"
      LerpFactor="0.08" />

  <!-- Flash: static color+opacity for N ticks then snap-clear -->
  <Action Name="FlashRed" Type="Embedded" Class="com.group_finity.mascot.action.Tint"
      Color="FF0000" Opacity="0.4" Duration="25" ClearOnExpiry="true" />

  <!-- Clear tint -->
  <Action Name="ClearTint" Type="Embedded" Class="com.group_finity.mascot.action.Tint"
      Color="000000" Opacity="0" Duration="1" ClearOnExpiry="true" />
  ```

## AI Assistant Layer

All in `src/com/group_finity/mascot/assistant/`. Activates if `<Personality>` block is non-empty.

### Assistant Package Files

- **`AssistantBubble.java`** — Floating bubble UI. `Path2D` shape, per-message fade, click-to-reply, lerp movement.
- **`AssistantInputDialog.java`** — Typed input (`JDialog` for focus on Windows).
- **`OllamaClient.java`** — HTTP client for Ollama. Single queue-worker, depth 5, 2s floor; queue-full drops oldest. `generate()` non-blocking. Vision (`generateWithImage`) bypasses queue on own daemon thread. Sends `"keep_alive":0` (unload immediately) and `"think":false` — required for Gemma 4 and thinking-mode models; without it they burn all tokens on reasoning and return empty `response`. 90s timeout for cold loads.
- **`MascotMemory.java`** — `img/[ImageSet]/conf/memory.json`. Summarizes every 20 interactions. `buildLightMemoryBlock(peerName)` (facts+tone only, no exchange history) used by peer reactions to reduce prefill cost. Migrates legacy `recentExchanges` key on load.
- **`AudioTranscriptBuffer.java`** — 15s rolling audio capture. WASAPI loopback first; falls back to VB-Cable/Stereo Mix.
- **`WasapiLoopbackCapture.java`** — JNA COM → WASAPI loopback. Converts to 16 kHz mono int16 for Whisper.
- **`AudioSessionUtil.java`** — Snapshots active audio sources (`IAudioSessionManager2`). Called just before Whisper runs so source is captured while audio is still playing.
- **`MascotSpeechRegistry.java`** — Inter-mascot speech. Listeners held as `WeakReference` — callers must keep a strong ref. `pendingPairs` prevents duplicate reactions for a speaker-listener pair until the first fires.
- **`ChatLog.java`** — `chat.log` in install folder. `say()` actions intentionally omitted. Missing behavior tags log to ShimejieeLog (WARNING), not chat.log.
- **`VoiceCommandListener.java`** — Polls Whisper every 6s (`VoicePollMs`). Behavior dispatch (keyword→`tryRunBehavior`) runs before transcription — hotword commands are instant; transcription skipped entirely when a behavior matched. Fan noise robustness: relative silence (RMS drops below 70% of utterance peak) + variance check (max/min < 1.4 → absorb as ambient).
- **`WhisperProcess.java`** — Singleton `whisper_server.py` manager. Must be next to JAR.
- **`WeatherTool.java`** — Open-Meteo, no API key. `"auto"` uses `http://ip-api.com` (plain HTTP) as primary — the HTTPS fallback's TLS handshake can hang indefinitely on some JREs. 10-min cache.

### Mascot.java AI Wiring

- **`buildSystemPrompt()`** — Personality + memory + peer speech context + envCtx (time/location/weather) + rules.
- **`isEphemeralQuery(text)`** — True for time, weather, timer/alarm, screen queries. Gates `recordUserExchange()`. Timers included because recording them causes the model to re-set the reminder on every subsequent interaction.
- **`handleScreenQuery()`** — Screen capture on daemon thread → `generateWithImage()`. Appends audio context if available. Not recorded to memory.
- **`audioSnapshotContext()`** — Reads `AudioTranscriptBuffer.lastSysTranscript` (volatile). Zero latency. Used by `fireSpontaneousComment` and `handleScreenQuery`.
- **`fireSpontaneousComment()`** — 45–90s cadence, reacts to active window title. Title fetch on dedicated daemon thread to prevent busy foreground apps from stalling animations. Gated by `globalSpontaneousLastFiredMs`.
- **`fireAudioReaction()`** — 45–90s cadence, transcribes audio buffer. Skips if >70% word overlap with last transcript (guards against Whisper hallucination loops on rolling buffer). Gated by `globalAudioLastFiredMs`.
- **Global Reaction Cooldowns** — Three `AtomicLong`s: `globalSpontaneousLastFiredMs` (2 min), `globalAudioLastFiredMs` (90s), `globalVisionLastFiredMs` (3 min). Shared across all mascots; each type independent. Peer reactions excluded (own chain throttle).
- **`firePeerReaction()`** — Triggered by `MascotSpeechRegistry`. Uses `buildLightMemoryBlock()` to reduce prefill cost.
- **`fireActionFromResponse(raw)`** — Extracts `[ACTION:...]`, `[TIMER:...]`, `[REMEMBER:...]`. Bare `[word]` tags attempt behavior lookup (as-is then title-cased); logs WARNING if neither matches.
- **`stripActionTag(text)`** — Strips all bracket tags including hallucinated ones (e.g. `[blink]`, `[OBSERVATION: Disdain]`).
- **`TIMER_TAG`** — `[TIMER:VALUE:reminder]`. Integer minutes capped at 1440 (rejects LLM math errors > 24h). Wall-clock form rolls to next day if already past.
- **`maybeSummarizeMemory()`** — Every 20 interactions. Counter resets only on successful response.
- **`say(String)`** — Does NOT call `MascotSpeechRegistry.record()` — XML utterances intentionally don't trigger peer reactions.
- **`activeBubbles`** — Manager iterates every tick unconditionally (not gated on `assistantMode`) so Say-action bubbles track the mascot even when AI is off.

### Personality System

```xml
<Information>
    <Name>Hornet</Name>
    <Personality>You are Hornet...</Personality>
</Information>
```

Persisted under `AssistantMode.<imageSet>` in `settings.properties` (keyed by name, not runtime int — int resets each launch).

- **`<SpeechRule>`** — Hard speech constraint injected as FIRST rule + appended after `---` as `Final reminder:` (start+end recency reinforcement). Most effective with WRONG→RIGHT examples and a self-check line.
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
- Max 12 facts, 6 exchange pairs injected into prompt
- `emotionalTone` updates only at summarization
- Ephemeral exchanges (time, weather, timer, screen) never recorded
- Permanent memories (`[REMEMBER:kw1,kw2|content]`) injected only when a keyword matches; capped at 100; never overwritten by summarization

### Audio Pipeline

```
System audio → WASAPI loopback → WasapiLoopbackCapture → AudioTranscriptBuffer
Microphone → VoiceCommandListener (separate channel)
Both → WhisperProcess (faster-whisper, CUDA) → Ollama (gemma3:4b) → bubble
```
VAD on, `no_speech_threshold=0.7`, `temperature=0.0`, `compression_ratio_threshold=1.8`.

### AssistantBubble Key Details

- **Movement:** Lerp (`LERP=0.2`). `setLocation()` called before AND after `setVisible(true)` — OS peer-creation centers the window otherwise.
- **Resize jump fix:** After `window.setSize()` on a visible window, immediately call `window.setLocation()` — without this, any size change causes a 1-frame jump.
- **`getTargetBounds()`** — Fresh `Rectangle` allocation each call. A shared `cachedBounds` field had a Manager/EDT race (4 non-atomic writes) causing extreme Y jumps in stacking.
- **`SHARED_MOVER`** — Single static 16ms `Timer` shared by all bubbles. Self-stops when all at rest.
- **Say messages:** Replace-not-accumulate; always renders at bottom (inserted before first Say in list).
- **Chronological Y stacking:** Oldest-at-top / newest-at-bottom. `bubbleAbove=true`: pushed above newer sibling. `bubbleAbove=false`: pushed below older sibling.
- **Timer countdown:** `repaint()` only per second (no `layout()`) — avoids per-second repositioning.
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
| `WeatherLocation` | "auto" | "auto" = IP geolocation; or city name |
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
| `src/.../action/Say.java` | Shows a bubble; `Text=` attribute |
| `src/.../assistant/AssistantBubble.java` | Floating chat bubble UI |
| `src_win/.../WindowsEnvironment.java` | Window enumeration, multi-monitor |
| `conf/actions.xml` / `conf/behaviors.xml` | Action/behavior definitions |
| `whisper_server.py` | Persistent faster-whisper server (must be next to JAR). `beam_size=1`, `_is_repetitive()` filter. Runs from install folder — copy edits over. |
| `TempSensor.exe` | CPU temp streamer (next to JAR, requires admin). Built from `SensorReader/Program.cs`. |
| `src/.../assistant/MascotSpeechRegistry.java` | Peer reaction scheduling |
| `src/.../assistant/ChatLog.java` | `chat.log` writer |
| `Setup.bat` (install folder) | Installs Ollama, Python, faster-whisper, gemma3:4b |

## Adding New Action Types

**Embedded (class-based, e.g. Tint, Scale):** Use `Type="Embedded" Class="com.group_finity.mascot.action.YourAction"` in XML. No ActionBuilder registration needed — instantiated via reflection.
1. Create class in `src/.../action/` extending `ActionBase`.
2. Add schema entries in `conf/schema.properties` + `conf/schema_ja.properties` for any new XML attributes.

**Built-in type keyword (e.g. Move, Stay, Animate):** Requires registration in the `ActionBuilder.buildAction()` switch.
1. Create class, register type string in `ActionBuilder.java`, add schema entries.

## XML Behavior/Action Authoring Notes

- `Condition`, `Duration`, velocity accept JS expressions (`mascot.anchor.x`, `environment.activeIE`, etc.).
- Per-mascot overrides: `img/[MascotName]/conf/`.
- `Say` usage: `<Action Name="..." Type="Embedded" Class="com.group_finity.mascot.action.Say" Text="Found one." />`
- **Per-pose ImageAnchor (decoupled):** `ImagePair` cache key is path-only. `Pose.next()` calls `mascot.setRenderAnchor()` each frame. One `ScalableNativeImage` per image file — no duplicate pixel data.
- **Dragged `FaceDirection`:** Accepts `FaceDirection="true"` (default false). Updates only on cursor X change.
- **Anchor-snap on surface cling (anchor-cancel pattern):** Cling actions need `ImageAnchor` AT the surface (ceiling `48,0`, wall `16,128`). For uncontrolled floor landings after `Fall`/`Thrown`: insert `<ActionReference Name="Offset" X="${(mascot.lookRight ? -1 : 1) * 32 * scaling * mascot.currentScale}" />` after `BouncingWall`. `scaling` is critical — `ImagePairLoader` multiplies anchor values by `Scaling`.

## Patching Notes

- Always read the target file before patching.
- Java 8: no static methods in non-static inner classes, no `var`, no multi-line string blocks, no non-ASCII in `/** */` javadoc.
- `SettingsWindow.java` is NetBeans-generated — `initComponents()` is GEN-BEGIN/END guarded. Add new tabs in `init()`. Both `init()` and `display()` return `boolean`.

## Current Mascots

- **Hornet** (Hollow Knight) — cold not because she doesn't care, but because she does and has learned care costs things. Silence is load-bearing: her default is restraint, not indifference. Hard truths stated plainly then followed by a task, never comfort. Skepticism framed as a question, not a jab. Feelings surface as reluctant disclosure — briefly acknowledged, then closed off. Personality updated from source dialog analysis (June 2026).
- **Holo** (Spice and Wolf) — ancient wolf deity, formal archaic speech, no contractions. peerTone toward Hornet: "wary respect" (not dismissive — prior dismissiveness created toxic feedback loops).
- **Paimon** (Genshin Impact) — bubbly, blunt, third-person speech, NOT Emergency Food. `FaceDirection="true"` on Pinched. `<ThirdPersonRewrite>true</ThirdPersonRewrite>` handles pronouns mechanically. "Oh Paimon's goodness/gosh" is intentional — leave it. Peer reactions tuned to vary: pushback, teasing, tangents, not just compliments.
- **CampfireON** — CPU load monitor. Scale lerps to `cpuLoad/100 * 2 + 1` (1x–3x). Hotspot: touch fire → add branch; touch logs → transform to CampfireOFF. Can transform to CampfireON_blue.
- **CampfireON_blue** — Blue flame variant; same scale behavior. Transforms back to CampfireON.
- **CampfireOFF** — Unlit; scales to CPU load (Frequency=200, 21-tick one-shots). Transforms to CampfireON when `cpuTemp >= 75`.

## Known Issues / Backburner

- **Voice command latency** — Free-form queries ~6s Whisper polling latency. Hotword→behavior dispatch instant. Mitigate by lowering `VoicePollMs`.
- **Fan control unimplemented** — GE76 EC blocks all software paths (WinRing0, WMI, SendInput). Only physical Fn+F8 works.
- **Timer LLM math** — Model sometimes miscalculates wall-clock minutes. Wall-clock format reliable when used correctly; integers > 1440 min rejected.
- **Paimon third-person (backburner)** — Rewrite working. Gaps: uncovered conjugations produce uninflected verb, "you" → "Traveler" not rewritten. "Oh Paimon's goodness" intentional — leave it.
- **Memory poisoning** — peerExchanges reinforce model patterns quickly. Wipe when personality degrades. Ongoing maintenance.
- **Peer reaction formula repetition** — 4B model ceiling. Prompt-level bans + memory hygiene reduce frequency. Gemma 4 E4B would help.

## Development Rules
- After any significant change, update CLAUDE.md to reflect the new state.
- Remove completed backburner items and add new ones as they arise.
