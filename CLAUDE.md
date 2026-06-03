# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A heavily modified fork of Shimeji-ee (Java/Windows desktop mascot app) with an AI assistant layer built on top. The goal is character-accurate desktop mascots that perceive and react to the user's environment in real time.

**Vision:** A sci-fi AI desktop companion — the kind seen in anime or sci-fi where a character has a personal AI as their right-hand man (think J.A.R.V.I.S., or holographic AI companions in anime). Not a novelty — a genuinely useful, context-aware presence. Proactive behavior (spontaneous reactions, environment awareness, peer interactions, persistent memory) is the core loop, not just responding to queries. Polish and smoothness matter because jank breaks immersion.

All behaviors, animations, and interactions are defined via XML, not hard-coded.

**Owner:** Ko (Kahului, Hawaii)
**Entry point:** `com.group_finity.mascot.Main`
**Java target:** 8 — no `var`, no multi-line string blocks, no static methods in non-static inner classes, no non-ASCII chars in `/** */` javadoc (causes Java 8 compile errors — use plain hyphens).

## Directory Structure

- **Source folder (here):** `D:\Downloads\Shimeji Workspace\Stable - Copy` — all Java source, build files, assets under development.
- **Install folder:** `C:\Users\ko\Desktop\Mario Install Testing` — the deployed/running instance. Built JAR and assets are copied here to test. Mascot image sets (`img/Hornet/`, `img/Holo/`), `conf/`, `memory.json` files, and runtime logs all live here, NOT in the source folder.

## Build Commands

The real build pipeline uses `Build.bat` in the install folder — do NOT just run `ant jar` directly.

**Normal build flow:**
1. Run `C:\Users\ko\Desktop\Mario Install Testing\Build.bat`
2. Choose [1] Release, [2] Test, or [3] Ant only
3. The bat calls `ant jar` in this source folder, then wraps with Launch4j

**Launch4j config locations:**
- `D:\Downloads\Shimeji Workspace\exe builder launch4j\launch4jc.exe` — the wrapper tool
- `D:\Downloads\Shimeji Workspace\exe builder launch4j\configTest.xml` — test build → outputs `ShimejiTest.exe` to install folder
- `D:\Downloads\Shimeji Workspace\exe builder launch4j\config.xml` — release build → outputs `Shimeji.exe` to install folder

**To run after build:**
- Run `ShimejiTest.exe` (or `Shimeji.exe`) from the install folder, OR
- Run `Debug.bat` in the install folder for console output (`java -jar Shimeji-ee.jar`)

**Ant commands (used internally by Build.bat):**
```bash
ant jar    # Compile and build fat JAR → target/Shimeji-ee.jar
ant zip    # Full build + three distribution ZIPs
ant clean  # Clean build directory
```

The three editions differ only in which `conf/*Behavior.xml` is packaged as `conf/behaviors.xml`.

## Architecture

The system is layered: **Behavior → Action → Animation → Environment**

### Behavior-Action-Animation Pipeline

- **Behavior** (`behavior/`): Long-term decision-making. `UserBehavior` is the standard implementation. Behaviors select the next Action and chain them together.
- **Action** (`action/`): Short-term execution (Stay, Move, Jump, Fall, Breed, Transform, Scale, etc. — 41 types). All extend `ActionBase`. Each action runs for some number of 40ms ticks, then completes and returns control to the Behavior.
- **Animation** (`animation/`): Sequence of `Pose` objects (frame + duration + optional sound). Actions own and drive animations.

### Core Components

- **`Manager`**: The tick loop (40ms). Maintains the mascot list, drives `mascot.tick()` + `mascot.apply()` for each mascot, and increments `globalSyncTick` for synchronized animations. Shared `WindowsEnvironment.beginTick()` runs once per tick (not once per mascot) for performance.
- **`Mascot`**: Individual agent (~3500 lines). Owns position, velocity, current behavior/action, image, scale, hotspots, and a `userData` HashMap for per-mascot cross-tick state.
- **`NativeFactory`**: Abstract factory. `Platform` detects the OS at startup and selects the appropriate `NativeFactoryImpl` (Windows / Mac / Generic / Virtual).

### Environment Abstraction

`Environment` is the abstract base; platform-specific implementations handle window enumeration and boundary detection:
- `WindowsEnvironment` (src_win): JNA → Win32 EnumWindows, GetMonitorInfo, DWM APIs. LRU cache (max 256 entries) avoids re-checking interactive windows every tick.
- `MacEnvironment` (src_mac): JNA → Carbon/Cocoa Accessibility APIs.
- `GenericEnvironment` (src_generic): Fallback with no window detection.
- `VirtualEnvironment` (src_virtual): Swing panel mode; mascots rendered inside a window instead of on the desktop.

### Configuration System

XML-driven via `conf/actions.xml` and `conf/behaviors.xml` (or per-mascot overrides in `img/[name]/conf/`).

- **`Entry`**: XML DOM wrapper with attribute/child access
- **`Configuration`**: Parses and caches `ActionBuilder` and `BehaviorBuilder` instances. Supports both English and Japanese XML tag names (detected automatically via `conf/schema.properties` vs `conf/schema_ja.properties`). XML supports `${CONSTANT}` substitution.
- **`ActionBuilder` / `BehaviorBuilder` / `AnimationBuilder`**: Builder pattern; instantiated once per configuration load, then `build()` creates new instances per mascot.
- **`<AnimationTemplate>`**: Reusable animation definition to avoid copy-pasting identical frame lists across actions. Declared inside `<ActionList>` as `<AnimationTemplate Name="X"><Pose Image=... Duration=.../></AnimationTemplate>` (frame list only — no per-action anchor/velocity). An action references it with `<Animation Template="X" ImageAnchor="48,128" Velocity="-2,0" />`; the `ImageAnchor`/`Velocity`/`Duration` on the `<Animation>` element override the template's pose values for every frame. `Configuration.load()` parses templates into the `animationTemplates` map (`getAnimationTemplates()`) BEFORE the action loop; `ActionBuilder` passes the map to `AnimationBuilder`'s 4-arg constructor, which expands the template (applying overrides) when `Template=` is present, else falls back to inline `<Pose>` children. Schema keys `AnimationTemplate` and `Template`. Used by Paimon (one float animation reused across ~40 actions at different anchors/velocities — cut actions.xml roughly in half). The same template can serve different surfaces by overriding only the anchor (floor `48,128`, ceiling `48,0`, wall `16,128`); the anchor is also the collision point, so a single template + per-action anchor keeps surface detection correct.

### Scripting

Conditions, durations, velocities, and targets in XML can be JavaScript expressions evaluated via Nashorn (bundled as `nashorn-core-15.4.jar` for Java 15+ compatibility). **`ScriptFilter`** sandboxes scripts — no `java.lang`, no I/O, no reflection. `VariableMap` exposes `mascot.*` and `environment.*` variables to expressions.

### New/Notable Engine Features

- **Hotkey System** (`HotkeyManager`): Uses JNativeHook for global keyboard/mouse hooks. Config: `conf/hotkeys.properties` (global, format `ImageSetName:BehaviorName`) or `img/[ImageSet]/conf/hotkeys.properties` (per-mascot, format `BehaviorName` only). `!hold` suffix enables hold-to-loop mode.
- **Affordance System**: `AffordanceStay` broadcasts a string tag (e.g. `"BlockHittable"`). `Manager.getNearestAffordance()` (callable from scripts) finds nearby mascots with that tag, enabling emergent inter-mascot interactions without direct references.
- **Synchronized Animation**: `SyncedStay` locks animation phase to `Manager.globalSyncTick` so all mascots show the same frame simultaneously.
- **Scale Action**: Runtime mascot resizing. `Target` can be a script expression (e.g. tied to CPU load). Scale persists in `settings.properties`. Mascot.`apply()` tracks `lastDisplayScale` (rounded to 1dp); when it steps to a new value, all entries in the `scalables` map are pre-warmed simultaneously so every animation frame rebuilds at the new scale in one batch — prevents multi-frame animations from cycling through frames at different scales during a transition (size jitter). `renderCX`/`renderCY` derive anchor pixel offsets from `lastBaseImageWidth`/`lastBaseImageHeight` (source image dimensions at globalScaling) rather than raw `currentScale`, so the displayed position stays pinned to the anchor even when the async scale cache is showing a stale rounded-scale frame.
- **Audio Level in XML**: `mascot.environment.audioLevel` exposes real-time system speaker RMS (~0-32767) to XML behavior conditions. Backed by `AudioTranscriptBuffer.currentSysRms`. Use to trigger behaviors when music is playing, volume spikes, etc.
- **Hardware Sensors** (`CpuTempMonitor`): Two sensor threads. Fast loop (1s): `cpuLoad` via `OperatingSystemMXBean.getSystemCpuLoad()` (pure Java); `gpuTemp`/`gpuLoad` via `nvidia-smi` (~80ms spawn, NVIDIA only, no admin); `batteryLevel` via JNA `GetSystemPowerStatus` (instant). Persistent process: `TempSensor.exe` spawned once at startup, streams `cpuTemp=XX.X` to stdout every 1s — LHM driver loaded once (~700ms), then each read is instant; requires admin/UAC; auto-restarts on crash; killed via shutdown hook on JVM exit. `ramLoad` computed live in `MascotEnvironment.getRamLoad()` (instant, real-time). `TempSensor.exe` is built from `SensorReader/Program.cs` via `dotnet publish` — single-file, no DLL needed alongside it.
- **Video Area Server** (`VideoAreaServer`): HTTP server on localhost:41221. Browser extensions send video player bounding boxes; mascots treat them as interactive windows. `getSiteIdForRect(Rectangle)` looks up the site name (e.g. `"youtube"`) for a given synthetic area rect by scanning the `areas` map keys and stripping the `_top/_bottom/_left/_right` suffix. `WindowsEnvironment.getActiveIETitle()` returns this site name (or `"Video Area"` as fallback) when the active window is a synthetic sentinel.

## AI Assistant Layer

All in `src/com/group_finity/mascot/assistant/`. Assistant mode activates automatically if the mascot's `<Personality>` block (in `behaviors.xml`) is non-empty.

### Assistant Package Files

- **`AssistantBubble.java`** — Floating text bubble UI. `Path2D` for seamless bubble+tail shape, per-message fade timers, click-to-reply, lerp movement, settings-driven appearance.
- **`AssistantInputDialog.java`** — Typed input dialog (`JDialog` for focus on Windows).
- **`OllamaClient.java`** — HTTP client for Ollama `/api/generate`. Model/endpoint read from `settings.properties`. Single queue-worker thread; `ArrayBlockingQueue` max depth 5; 2-second floor between dispatches; queue-full drops the oldest pending request (not newest). `generate()` is non-blocking (offers to queue, returns immediately). Overload `generate(system, user, maxTokens, callback)` used by summarization (300-token budget vs default 100). `generateWithImage(system, user, imageBase64, visionModel, callback)` bypasses the queue (own daemon thread), includes `"images":[...]` in the JSON body, and uses `modelOverride` so the vision model can differ from the chat model. `buildJson` sends `"keep_alive":0` (both text and vision) so Ollama unloads models immediately after each request. `buildJson` also sends `"think":false` — required for Gemma 4 and other thinking-mode models; without it those models burn all tokens on internal reasoning and return an empty `response` field. Non-thinking models ignore the flag. Read/connect timeout is 90s to accommodate cold model loads.
- **`MascotMemory.java`** — Persistent per-mascot memory stored at `img/[ImageSet]/conf/memory.json`. Summarizes every 20 interactions via Ollama. Exchanges split into `userExchanges` (human-mascot) and `peerExchanges` (shimeji-shimeji). `peerTones` map keyed by peer name. Key methods: `recordUserExchange()`, `recordPeerExchange()`, `setPeerTone()`, `getPeerTone()`, `getKnownPeers()`, `buildAllPeerMemoryBlocks()`, `buildLightMemoryBlock()` (facts+tone only, no exchange history), `buildMemoryBlock(peerName)` overload (peer-specific tone + exchange history). Migrates old `recentExchanges` key on load for back-compat.
- **`AudioTranscriptBuffer.java`** — Continuous audio capture into 15s rolling buffer. Tries WASAPI loopback first (no virtual device needed); falls back to VB-Cable/Stereo Mix via javax.sound.sampled. Uses `WhisperProcess` for transcription.
- **`WasapiLoopbackCapture.java`** — JNA COM calls into Windows Core Audio (WASAPI). Captures the default render endpoint in loopback mode, converts native format (float32/int16, any rate, any channels) to 16 kHz mono int16 for Whisper.
- **`AudioSessionUtil.java`** — JNA COM calls into `IAudioSessionManager2`. `getActiveAudioSources()` snapshots which apps are actively playing audio (returns e.g. `"Spotify, Chrome"` or null). Called by `AudioTranscriptBuffer.transcribeWithSource()` just before Whisper runs so the source is captured while audio is still playing.
- **`MascotSpeechRegistry.java`** — Shared static registry for inter-mascot speech awareness. `record()` stores a mascot's utterance and schedules peer reactions (REACTION_PROB = {0.35, 0.25, 0.15, 0.05} by chain depth, MAX_CHAIN_DEPTH=4, 10s per-pair cooldown). `buildContext()` returns recent utterances for injection into any mascot's prompt. Listeners held as `WeakReference` — callers must keep a strong reference (field on `Mascot`). The probability array naturally throttles chain length — the cooldown only prevents immediate re-triggering of the same pair after a chain ends. `pendingPairs` (`ConcurrentHashMap.newKeySet()`) prevents duplicate reactions: once a reaction is scheduled for a speaker-listener pair, no further reactions for that pair can be scheduled until the first one fires and removes itself from the set.
- **`ChatLog.java`** — Appends every mascot/user exchange to `chat.log` in the install folder (working directory). Format per line: `yyyy-MM-dd:HH:mm:ss:Source:Message`. Source examples: `User`, `User(voice)`, `Hornet`, `Hornet(to: Task Manager)`, `Hornet(heard from: Spotify)`, `Hornet(screen glance)`, `Hornet(to: Holo)`. Audio reactions log a source transcript entry immediately before the mascot response entry. All AI response paths write to this log; `say()` actions are intentionally omitted. Useful for debugging reaction timing, personality drift, and peer chain behavior. Missing behavior tags (bare `[word]` with no matching XML behavior) are logged at WARNING in ShimejieeLog, not chat.log.
- **`VoiceCommandListener.java`** — Mic capture, polls Whisper every 6s (configurable via `VoicePollMs`), detects mascot name triggers, dispatches voice commands. Includes two-pronged fan noise robustness: relative silence detection (RMS drops below 70% of utterance peak = silence even if above global threshold) and variance check at cap-flush (max/min ratio >= 1.4 = contains speech, send to Whisper; otherwise absorb as ambient noise). `handleNameTrigger`: behavior dispatch (keyword match → `tryRunBehavior`) runs before `buf.transcribe()` so hotword commands execute instantly; transcription is skipped entirely when a behavior matched.
- **`WhisperProcess.java`** — Singleton persistent `whisper_server.py` subprocess manager. Serializes transcription requests via `ReentrantLock`. Passes model name as first CLI arg and thread count as second. `whisper_server.py` must be next to `Shimeji-ee.jar`. `getModel()` reads `WhisperModel` from `settings.properties`; `getThreads()` reads `WhisperThreads`.
- **`WeatherTool.java`** — Fetches current weather from Open-Meteo (no API key). Location from `WeatherLocation` setting: `"auto"` uses `http://ip-api.com` (plain HTTP, no TLS) as primary, ipapi.co HTTPS as fallback only — the HTTPS endpoint's TLS handshake can hang indefinitely on some JREs. Named city uses Open-Meteo geocoding. 10-min result cache. `getCachedPlaceName()` returns the resolved city+region; `getCachedResult()` returns the full weather string. Both used by `buildSystemPrompt()` and `handleNameTrigger()` to inject `[Current time:]`, `[User location:]`, `[Current weather:]` labeled blocks into the system prompt. All log entries at WARNING. `°F` escape used for degree symbol; `build.xml` specifies `encoding="UTF-8"` on javac.

### Mascot.java AI Wiring

Key methods added to `Mascot.java` for the AI layer:

- **`buildSystemPrompt()`** — Combines personality + memory block + peer speech context + envCtx + rules. `envCtx` injects three labeled lines before the RULES block: `[Current time: ...]`, `[User location: ...]` (omitted until first weather fetch), `[Current weather: ...]` (omitted until first weather fetch). Called by typed input, voice command, and reply paths.
- **`isEphemeralQuery(text)`** — Returns true for time, weather, timer/reminder/alarm, and screen-content queries. Gates `recordUserExchange()` — ephemeral Q&A is never written to memory. Timer requests are included because recording them causes the model to set the same reminder on every subsequent interaction. Screen queries are included because captured screen state is ephemeral by nature.
- **`handleScreenQuery(userText, system, client)`** — Captures screen on a daemon thread, then calls `ollamaClient.generateWithImage()` with the vision model. Appends `[Audio context: ...]` to the user text if `AudioTranscriptBuffer.lastSysTranscript` is non-null. Response shown in bubble; not recorded to memory.
- **`audioSnapshotContext()`** — Static helper. Reads `AudioTranscriptBuffer.lastSysTranscript` (populated by `fireAudioReaction`, `public static volatile`) and returns `"[Audio context: ...]"` or empty string. Zero latency, no Whisper call. Used by `fireSpontaneousComment` (injected into the window-title prompt) and `handleScreenQuery` (appended to user text). Silently omitted when the field is null or blank.
- **`fireSpontaneousComment()`** — Random cadence (45–90s), reacts to active window title. Injects `[Audio context: ...]` into the prompt via `audioSnapshotContext()` if audio is available. Records as `[Observed]` fact. Also calls `MascotSpeechRegistry.record()` at chain depth 0 to notify peers. The `getActiveWindowTitle()` call runs on a dedicated daemon thread ("spontaneous-title-fetch") — this prevents a busy foreground app from stalling animations. `lastSpontaneousTitle` is `volatile` for safe cross-thread access. Gated by `globalSpontaneousLastFiredMs`. Uses `getImageSet()` as the mascot-name fallback.
- **`fireAudioReaction()`** — Random cadence (45–90s, independent), transcribes audio buffer via `transcribeWithSource()`, includes source app in prompt and memory fact. Records as `[Observed]` fact. Gated by `globalAudioLastFiredMs`. Skips reacting when the new transcript is a near-duplicate of the last one (`isAudioTranscriptDuplicate()`, >70% word overlap — guards against Whisper hallucination loops and static rolling-buffer content). Uses `getImageSet()` name fallback.
- **Global Reaction Cooldowns** — Three static `AtomicLong`s (`globalSpontaneousLastFiredMs` 2 min, `globalAudioLastFiredMs` 90 s, `globalVisionLastFiredMs` 3 min) shared across all mascots. When any mascot fires a reaction type, all others skip that type until the cooldown elapses. Each type is independent. Peer reactions excluded — they have their own diminishing-probability chain throttle.
- **`firePeerReaction(speakerName, speakerText, chainDepth)`** — Triggered by `MascotSpeechRegistry` when another mascot speaks. Builds a prompt directing this mascot to address the speaker by name; includes per-peer tone from memory. Uses `memory.buildLightMemoryBlock(speakerName)` (facts+tone only) to reduce prefill cost. Calls `ensureFreshBubble()` then `showThinking()`. On response records via `recordPeerExchange()` and calls `MascotSpeechRegistry.record()` at chainDepth+1.
- **`fireActionFromResponse(raw)`** — Extracts `[ACTION:BehaviorName]`, `[TIMER:VALUE:reminder]`, and `[REMEMBER:kw1,kw2|content]` tags from an LLM response, validates/dispatches each. REMEMBER tags call `MascotMemory.addPermanentMemory()`. Also scans for bare `[word]` tags (no colon) and attempts behavior lookup as shorthand — tries the word as-is then title-cased; logs a WARNING to ShimejieeLog if neither matches. Called from every `onResponse` callback before text is stripped for display.
- **`stripActionTag(text)`** — Strips all known and unknown bracket tags before display. Removes `[ACTION:...]`, `[TIMER:...]`, `[REMEMBER:...]`, `[OBSERVATION:...]`, any `[WORD:...]` pattern, and bare `[word]` tags. The catch-all prevents model-hallucinated tags (e.g. `[blink]`, `[OBSERVATION: Disdain]`) from leaking into bubbles or memory.
- **`REMEMBER_TAG`** — Matches `[REMEMBER:kw1,kw2|content]`. Pipe separates keywords from content (colon-safe). Keywords are comma/space split, lowercased, stored in `MascotMemory.permanentMemories`.
- **`TIMER_TAG` / `WALL_CLOCK`** — `TIMER_TAG` matches `[TIMER:VALUE:reminder]` where VALUE is digits-only (minutes) or `H:MMam/pm` (wall-clock). `parseTimerDurationMs()` converts VALUE to ms: integer → minutes × 60000 (capped at 1440 min = 24h; larger values rejected as LLM math errors); wall-clock form → `Calendar` delta to next occurrence (rolls to next day if already past).
- **`setMascotTimer(timeValue, reminder)`** — Creates or reuses `timerBubble` (a dedicated `AssistantBubble`). `timerBubble` lives in `activeBubbles` at `TIMER_TIMESTAMP = Long.MAX_VALUE - 1` (always nearest the mascot in the stack).
- **`maybeSummarizeMemory()`** — Every 20 interactions, asks Ollama to distill exchanges into facts + tone. Counter reset moved to inside `onResponse` (only resets on success). Peer exchanges also trigger summarization. Summary prompt requests `PEER_TONE:<name>:<tone>` lines; `isValidTone()` validator rejects pronouns/short words/sentences. Prompt instructs "EXACTLY 5 facts under 12 words."
- **`say(String)`** — Called by `Say` action class. Shows bubble with text as context for replies. Does NOT call `MascotSpeechRegistry.record()` — XML-scripted utterances intentionally do not trigger peer reactions.
- **`ensureFreshBubble()`** — Must be called on EDT before every `showThinking()`. Creates a new `AssistantBubble` and adds it to `activeBubbles` when the current bubble already has a visible AI response; otherwise reuses the current one. This gives each AI exchange its own JWindow and chronological timestamp for inter-mascot stacking.
- **`activeBubbles`** — `CopyOnWriteArrayList<AssistantBubble>` on Mascot. Every bubble ever created for this mascot lives here. Manager thread iterates it each tick for `reposition()` (unconditionally — not gated on `assistantMode` so Say-action bubbles track the mascot even when assistant mode is off). `assistantBubble` always points to the most recently created entry.

### Personality System

Each mascot has a `<Personality>` block in `img/[ImageSet]/conf/behaviors.xml`, inside `<Information>`:

```xml
<Information>
    <Name>Hornet</Name>
    <Personality>You are Hornet...
        Personality: ...
        Speech style: Extremely terse. One or two sentences maximum...
    </Personality>
</Information>
```

Assistant mode defaults ON if `Personality` is non-empty. Persisted in `settings.properties` under key `AssistantMode.<imageSet>` (keyed by image set name, not the runtime integer `id` — the integer resets each launch and would lose the saved value). All instances of the same image set share one setting.

**`<SpeechRule>`** — Optional sibling of `<Personality>` in the `<Information>` block. A hard speech constraint (e.g. forced third person) that the model follows more reliably than personality prose, because it is injected directly into the RULES section. `Mascot.getSpeechRule(cfg)` reads it; it is injected as the FIRST rule in all six prompt RULES blocks (labeled `CRITICAL SPEECH CONSTRAINT`) and appended again after the closing `---` via `withSpeechReminder()` as a `Final reminder:` (recency reinforcement — LLMs weight both the start and end of context). Registered in `schema.properties` and read by `Configuration.loadInformation()` like `Personality`/`VoiceTrigger`. Most effective with concrete WRONG→RIGHT examples and a self-check line; a third-person personality block (write "Paimon is..." not "You are...") aligns the generative voice with the constraint instead of fighting it. Even so, an 8B model will not hit 100% adherence.

**`<ThirdPersonRewrite>`** — Optional boolean field in `<Information>`. When `true`, `Mascot.applyPersonaRewrites()` runs a post-generation regex rewrite (`rewriteFirstPerson()`) that replaces first-person pronouns (I/me/my/mine/myself + contractions + ~25 common verb conjugations) with the mascot's image-set name. Applied to all response text before display and memory recording. Currently set for Paimon only.

### Memory System

`img/[ImageSet]/conf/memory.json` — human-editable plain JSON:
```json
{
  "interactionCount": 42,
  "sinceLastSummary": 5,
  "emotionalTone": "warm",
  "facts": ["[Observed] Window: YouTube | Reaction: ...", "User likes anime"],
  "userExchanges": [
    {"role": "user", "text": "..."},
    {"role": "mascot", "text": "..."}
  ],
  "peerExchanges": [
    {"role": "Hornet", "text": "..."},
    {"role": "Holo", "text": "..."}
  ],
  "peerTones": {"Hornet": "respectful", "Holo": "fond"},
  "permanentMemories": [
    {"keywords": ["pizza", "food"], "content": "User loves spicy food, especially Thai cuisine"}
  ]
}
```
- Max 12 facts, max 6 exchange pairs injected into prompt
- Summarizes every 20 interactions (user OR peer exchanges count)
- Passive observations stored as `[Observed]` facts (lower weight than real exchanges)
- Time, weather, timer/reminder/alarm, and screen-content exchanges are **never recorded** (`isEphemeralQuery()` gate) — stale answers act as poisoned few-shot examples
- `emotionalTone` is a free-form validated adjective/phrase (e.g. "warm", "cautiously fond") — only updates at summarization
- `buildLightMemoryBlock(peerName)` — facts+tone only (used by `firePeerReaction` to reduce prefill cost)
- Migrates legacy `recentExchanges` key to `userExchanges` on load
- **Permanent memories** — stored under `permanentMemories`; never overwritten by summarization; capped at 100. Only injected into the prompt when a keyword matches the user's message (word-boundary, case-insensitive). The LLM creates them with `[REMEMBER:kw1,kw2|content]` in any response.

### Audio Pipeline

```
System audio → WASAPI loopback (default render endpoint) → WasapiLoopbackCapture → AudioTranscriptBuffer
Microphone → VoiceCommandListener (separate channel)
Both → WhisperProcess (faster-whisper, CUDA, configurable model) → transcript
Transcript → Ollama (gemma3:4b) → bubble response
```
VAD filter on, `no_speech_threshold=0.7`, `temperature=0.0`, `compression_ratio_threshold=1.8`. CUDA via `nvidia-cublas-cu12` + `nvidia-cudnn-cu12`.
VB-Cable is no longer required. WASAPI loopback captures whatever Windows is playing on the default output device. Fallback to VB-Cable/Stereo Mix still works if WASAPI fails.

### AssistantBubble Key Details

- **Bubble shape:** Single `Path2D` (no seams at opacity). `buildBubblePath()` traces outline with tail inline. `bubbleAbove=true` → tail at bottom; `bubbleAbove=false` → tail at top.
- **Fade:** Per-message `Timer`. Delay = `max(5000ms, charCount * 250ms)`. Then 30s fade to `MIN_ALPHA=0.01`. Despawns at `MIN_ALPHA`. Repaint suppressed during hold phase.
- **Movement:** Lerp-based (`LERP=0.2`, `SNAP_PX=1.0`). Teleports on first show — `setLocation()` called before AND after `setVisible(true)` to override OS peer-creation centering.
- **Resize jump fix:** After `window.setSize()` on a visible window, immediately call `window.setLocation((int)curX, (int)curY)` to re-anchor before the OS repositions the native handle. Without this, any size change causes a 1-frame jump.
- **Shared move timer:** `SHARED_MOVER` is a single static `Timer` (16ms) shared by all bubbles. Stops itself when all bubbles are at rest; restarted via `invokeLater(MOVER_STARTER)` when any bubble moves.
- **`getTargetBounds()`** — Returns `new Rectangle(targetX, targetY, lastLayoutW, lastLayoutH)` (a fresh allocation each call). A shared `cachedBounds` field had a Manager/EDT race: 4 non-atomic writes could be observed half-written, causing stacking to compute an extreme `winY` jump.
- **X button:** Separate `JWindow` overlay (`xWindow`). `positionXWindow()` called from `reposition()` whenever `xWindow.isVisible()` — not just when reply dialog is open. Dismisses selected message (or `clearTimerState()` for timer messages).
- **Reply dialog:** `JDialog` (not JWindow — needs focus). Timer messages (both active countdown and fired reminders) never open the reply dialog.
- **Screen change:** Debounced 300ms hide/setBackground/show cycle to fix Windows alpha compositing reset.
- **Performance:** `reposition()` has a fast-path that skips full `layout()` when window size is stable. `window.setSize()` guarded to only call on actual size change. Font metrics via `Toolkit.getFontMetrics()` (no scratch `BufferedImage`).
- **Say messages:** `Message` has a `fromSay` flag. `showSay()` removes any existing Say messages before adding a new one (replace-not-accumulate). `addMessage()` inserts non-Say messages before the first Say so Say always renders at the bottom.
- **Per-message windows:** Each AI response gets its own `AssistantBubble` JWindow (via `ensureFreshBubble()`). A back-and-forth produces one window per exchange across all mascots, each with its own chronological timestamp.
- **Chronological Y stacking:** Ordering is always oldest-at-top / newest-at-bottom in screen Y, regardless of `bubbleAbove`. `bubbleAbove=true`: each bubble finds its immediately newer sibling and is pushed above it. `bubbleAbove=false`: each bubble finds its immediately older sibling and is pushed below it. Collision push direction follows the same rule.
- **Stacking participants:** `!thinking && (hasAiMessages || hasSayMessage)`. Thinking bubbles act as fixed obstacles. Timer countdown bubbles use `TIMER_TIMESTAMP = Long.MAX_VALUE - 1` — always closest to the mascot.
- **Timer countdown bubble:** `showCountdown(endMs, reminder, mascotBounds)` adds a `Message` with `fromTimer=true`. Fades to 20% alpha after 3s. `startCountdownTicker()` updates `msg.displayText` every second and calls `panel.repaint()` only (no `layout()` — avoids per-second repositioning). `fireTimerAlarm()` replaces countdown text with the reminder, resets alpha to 1.0.
- **Timer pause/resume:** Click active countdown → `pauseCountdown()` stops ticker, stores `timerPausedRemainingMs`, shows X window (no reply dialog). Click again → `resumeCountdown()` recomputes `timerEndMs`, resets alpha to 1.0, starts fresh fade and ticker.

### Assistant Settings Properties

Added to `settings.properties`:
- `BubbleWidth` (default 180)
- `BubbleFontSize` (default 14)
- `BubbleFontName` (default "" = Ubuntu R)
- `BubbleBackground` (default false) — enables `Path2D` bubble with tail
- `OllamaModel` (default "gemma3:4b")
- `OllamaEndpoint` (default "http://localhost:11434/api/generate")
- `WhisperModel` (default "tiny") — combobox: tiny / base / small / medium. Takes effect on restart.
- `WhisperThreads` (default = availableProcessors/2) — slider (range 1..availableProcessors, snap-to-ticks). Takes effect on restart.
- `VoicePollMs` (default 6000) — slider (range 1000–10000ms, snap 40ms). Voice command poll interval. Takes effect on restart.
- `WeatherLocation` (default `"auto"`) — `"auto"` uses IP geolocation; any city name geocodes via Open-Meteo. Cache invalidates when this changes.
- `VisionModel` (default `"gemma3:4b"`) — Ollama model used for screen-content queries. Must support multimodal input. gemma3:4b handles both text and vision so no second model needed. Fallback choices: `moondream`, `llava`, `llava-phi3`. Setup.bat pulls moondream automatically.

Chat Bubbles tab is built in `init()` (not `initComponents()`) so properties are loaded first. Model dropdown fetches from `GET /api/tags` on open.

## Key Files

| File | Purpose |
|------|---------|
| `src/.../Main.java` | Entry point, system tray, settings persistence, GUI setup |
| `src/.../Manager.java` | Tick loop, mascot lifecycle, affordance/sync APIs |
| `src/.../Mascot.java` | Individual mascot agent (~3500 lines), all AI wiring |
| `src/.../HotkeyManager.java` | Global hotkey registration and dispatch |
| `src/.../config/Configuration.java` | XML parsing and caching |
| `src/.../action/Say.java` | Instant action that shows a bubble; `Text=` attribute |
| `src/.../assistant/AssistantBubble.java` | Floating chat bubble UI |
| `src_win/.../WindowsEnvironment.java` | Window enumeration, multi-monitor, fullscreen detection |
| `conf/actions.xml` | All action type definitions |
| `conf/behaviors.xml` | Behavior decision trees (the active edition's file) |
| `conf/hotkeys.properties` | Global hotkey bindings |
| `whisper_server.py` | Persistent faster-whisper STT server (must be next to JAR). Args: `model` (argv[1]), `thread_count` (argv[2]). `beam_size=1` for mic/voice-command files (greedy, fast). `_is_repetitive()` discards transcripts where a 2–4 word phrase fills >40% of the n-grams. NOTE: this file runs from the install folder — edits to the source-folder copy do not take effect until copied over. |
| `TempSensor.exe` | Persistent CPU temp reader (must be next to JAR). Streams `cpuTemp=XX.X` once per second. Requires admin. Built from `SensorReader/Program.cs` via `dotnet publish`. |
| `src/.../environment/FanController.java` | Stub — sends fan_on/fan_off to TempSensor stdin, which currently no-ops them (EC blocks all software fan control paths on GE76). |
| `SensorReader/Program.cs` | Source for `TempSensor.exe`. Uses LibreHardwareMonitor (bundled). Loop-based; exits when stdin closes. |
| `src/.../assistant/MascotSpeechRegistry.java` | Inter-mascot speech registry; peer reaction scheduling |
| `src/.../assistant/ChatLog.java` | Appends all AI exchanges to `chat.log` in the install folder |
| `Setup.bat` (install folder) | One-shot dependency installer — Ollama, Python, faster-whisper, gemma3:4b |

## Adding New Action Types

1. Create class in `src/.../action/` extending `ActionBase` (or another abstract action).
2. Register the type string in `config/ActionBuilder.java` (the `build` switch/map).
3. Add XML attributes in `conf/actions.xml` as needed.
4. Add schema entries to `conf/schema.properties` (English) and `conf/schema_ja.properties` (Japanese) if new XML tags are introduced.

## XML Behavior/Action Authoring Notes

- Behaviors reference Actions by name; Actions reference Animations by image filename.
- `Condition` attributes are JS expressions evaluated against `VariableMap` (`mascot.anchor.x`, `environment.activeIE`, etc.).
- `Duration` and velocity fields also accept JS expressions.
- Nested `<Action Type="Sequence">` blocks are supported inline without top-level definitions.
- Per-mascot overrides: place `actions.xml` / `behaviors.xml` in `img/[MascotName]/conf/`.
- `Say` action usage: `<Action Name="..." Type="Embedded" Class="com.group_finity.mascot.action.Say" Text="Found one." />`
- **Per-pose ImageAnchor (decoupled):** `ImagePair` cache key is now path-only (no anchor). `MascotImage` stores `center=(0,0)`. `Pose.next()` calls `mascot.setRenderAnchor(anchorX, anchorY)` each frame. `Mascot.apply()` and `getBounds()` compute `cx/cy` from `renderAnchorX/Y * globalScaling * currentScale` via `renderCX(imageWidth)` / `renderCY()` helpers. The `scalables` map now has one `ScalableNativeImage` per image file — no duplicate pixel data in memory.
- **Dragged `FaceDirection`:** The `Dragged` action (Pinched) accepts `FaceDirection="true"` (default false). When true the mascot faces the drag direction, updated only when the cursor pixel X actually changes (preserves last facing when cursor is still). `FaceDirection` is a real schema key; `Dragged.isFaceDirection()` has a `MissingResourceException` fallback for un-rebuilt JARs.
- **Anchor-snap on surface cling (anchor-cancel pattern):** Cling actions need their `ImageAnchor` AT the surface (ceiling `48,0`, wall `16,128`) while floor/air use `48,128`. When the cling is reached via a *controlled* approach, the snap can be cancelled: put an `Offset` one frame before a dedicated approach action that already uses the cling anchor, so the start-snap and the anchor-switch-snap negate across one 40 ms tick. For *uncontrolled* floor landings after `Fall`/`Thrown`, insert `<ActionReference Name="Offset" X="${(mascot.lookRight ? -1 : 1) * 32 * scaling * mascot.currentScale}" />` after `BouncingWall` and before `Stand`. **`scaling` is critical**: `ImagePairLoader` multiplies anchor values by `Scaling` (e.g. at `Scaling=0.5` the actual pixel delta is 16, not 32). `mascot.currentScale` handles the Scale-action runtime scale.

## Patching Notes

- Always read the target file before patching.
- Java 8: no static methods in non-static inner classes, no `var`, no multi-line string blocks.
- Non-ASCII chars in `/** */` javadoc cause Java 8 compile errors — use plain hyphens.
- `SettingsWindow.java` is NetBeans-generated — `initComponents()` is GEN-BEGIN/END guarded. Add new tab code in `init()` instead. `init()` returns `boolean`; `display()` returns `boolean`.

## Current Mascots

- **Hornet** (Hollow Knight) — cold not because she doesn't care, but because she does and has learned care costs things. Silence is load-bearing: her default is restraint, not indifference. Hard truths stated plainly then followed by a task, never comfort. Skepticism framed as a question, not a jab. When feeling surfaces it reads as reluctant disclosure — briefly acknowledged, then closed off. Never explains herself unless she's decided to. Personality updated from source dialog analysis (June 2026).
- **Holo** (Spice and Wolf) — ancient wolf deity, formal archaic speech, no contractions. peerTone toward Hornet is "wary respect" (not dismissive — prior dismissiveness created toxic feedback loops).
- **Paimon** (Genshin Impact) — bubbly, blunt, third-person speech ("Paimon thinks..."), NOT Emergency Food. Pinched action uses `FaceDirection="true"` so she faces the cursor while dragged. No `<SpeechRule>` — `<ThirdPersonRewrite>true</ThirdPersonRewrite>` handles first-person mechanically as a post-generation rewrite. "Oh Paimon's goodness/gosh" is an intentional side-effect of the rewrite on exclamations — charming, leave it. Peer reactions tuned to vary: pushback, teasing, tangents, not just compliments.
- **CampfireON** — CPU load monitor; Scale action lerps size to `cpuLoad/100 * 2 + 1` (range 1x–3x). `StandUpWithScale` runs `ScaleWithLoad` continuously (500–1500 ticks) so lerp is every tick. Hotspot: touch fire → add branch; touch logs → fire off (transforms to CampfireOFF). Can transform to CampfireON_blue.
- **CampfireON_blue** — Blue flame variant of CampfireON; same scale/lerp behavior. Transforms back to CampfireON via its own hotspot.
- **CampfireOFF** — Unlit campfire; also scales to CPU load via `StandUpWithScale` (21-tick one-shot runs, Frequency=200), unlike CampfireON which runs the scaler continuously. Transforms to CampfireON via a heat-triggered behavior (`cpuTemp >= 75`).

## Known Issues / Backburner

- **Voice command latency** — Free-form queries (non-hotword paths) have ~6s Whisper polling latency. Hotword→behavior dispatch is instant; latency only affects the transcription path. Mitigated by lowering `VoicePollMs` in settings at the cost of more frequent Whisper calls.
- **Fan control unimplemented** — the GE76 EC blocks all software fan control paths: WinRing0 port I/O blocked by ACPI.sys; WMI `Set_Fan` writes immediately reverted by firmware; SendInput with the Cooler Boost scan code doesn't reach the EC. `FanController.java` and `TempSensor.exe` are wired up but fan commands are no-ops. Only physical Fn+F8 toggles Cooler Boost.
- **Timer LLM math** — Model sometimes computes wrong minute counts for wall-clock requests despite instruction. Mitigated by: (1) RULES say "copy the time exactly, never convert to minutes"; (2) integer inputs > 1440 min rejected by `parseTimerDurationMs()`. Wall-clock format is reliable when the model uses it correctly.
- **Paimon third-person (backburner)** — Regex rewrite working. Remaining: (1) stilted phrasing for uncovered conjugation patterns; (2) "you" → "Traveler"/peer name not yet rewritten (context-dependent); (3) uncovered verb conjugations produce `Paimon + uninflected verb` (grammatically wrong but still third-person). "my" → "Paimon's" in exclamations ("Oh my goodness" → "Oh Paimon's goodness") is intentional and charming — do not fix.
- **Memory poisoning** — peerExchanges accumulate the model's habitual patterns quickly and feed them back as few-shot examples, reinforcing repetition. Wipe peerExchanges (and facts if they contain old-voice reactions) when personality quality degrades. This is ongoing maintenance, not a one-time fix. The chat.log makes drift easy to spot.
- **Peer reaction formula repetition** — 4B model ceiling. Small models fall into structural patterns under repetitive conditions (same audio source, same peers). Prompt-level bans on specific phrases (e.g. "preoccupied with") and memory hygiene reduce frequency but don't eliminate it. Gemma 4 E4B would reduce this significantly at the cost of heavier resource use.

## Development Rules
- After any significant change, update CLAUDE.md to reflect the new state.
- Remove completed backburner items and add new ones as they arise.
