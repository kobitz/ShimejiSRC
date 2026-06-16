# Shimeji-ee — Changes Summary & Update Guide
## shimejieesrcOG → ShimejiSRC_CleanTest

> **Note (June 2026):** Parts 1 and 2 below are the **historical record** of the original
> engine migration (the first batch of changes off base Shimeji-ee: hotkeys, scaling, CPU
> temperature, affordances). The byte counts and file lists are accurate for *that* snapshot
> and are kept as-is. The project has since grown a full local **AI assistant layer** and
> many more engine features — for the **current, complete feature inventory see Part 3 at
> the bottom of this file**, and **CLAUDE.md** for the authoritative architecture reference.

---

## Part 1: Complete Changes Summary

### ➕ New Files Added

**New Java source files:**

| File | Purpose |
|------|---------|
| `src/.../mascot/HotkeyManager.java` | Global keyboard shortcut management system |
| `src/.../mascot/action/Scale.java` | New action: dynamically resize/scale a mascot |
| `src/.../mascot/environment/CpuTempMonitor.java` | Reads CPU temperature to expose it to mascot behaviour scripts |
| `src/.../mascot/image/ScalableNativeImage.java` | Native image type supporting the new Scale action |

**New C# companion project:**

| File | Purpose |
|------|---------|
| `SensorReader2/SensorReader/Program.cs` | C# app that feeds hardware sensor data to the Java side |
| `SensorReader2/SensorReader/SensorReader.csproj` | Project file for building SensorReader |
| `SensorReader2/SensorReader/LibreHardwareMonitorLib.dll` | Hardware monitoring library (CPU temp, etc.) |

**New libraries in `lib/`:**

| JAR | Purpose |
|-----|---------|
| `lib/asm-9.6.jar` | ASM bytecode library (core) |
| `lib/asm-commons-9.6.jar` | ASM commons utilities |
| `lib/asm-tree-9.6.jar` | ASM tree API |
| `lib/asm-util-9.6.jar` | ASM utilities |
| `lib/jnativehook-2.2.2.jar` | Global keyboard/mouse hook — required by HotkeyManager |
| `lib/nashorn-core-15.4.jar` | Standalone Nashorn JS engine for Java 15+ compatibility |

---

### ➖ Files Removed

**Platform/shortcut files:**
- `Shimeji-ee.exe` — Pre-built Windows executable wrapper removed from the source package
- `Shimeji-eeHomePage.url`, `ShimejiHomePage.url`, `page.url` — Homepage shortcut files removed

**Images:**
- `img/banner.bmp` — Root-level banner image removed
- `img/KuroShimeji/` — **Entire KuroShimeji image set removed** (46 sprites + banner, shime1–46.png)

**Configuration:**
- `conf/schema_en.properties` — Separate English schema file removed; content consolidated into `conf/schema.properties`

**Misc:**
- `devnotes.txt` — Internal developer notes removed
- `img/FixPngImage.jar` — PNG fix utility removed

---

### ✏️ Modified Files (same path, different content)

**Build & config:**

| File | Change |
|------|--------|
| `MANIFEST.MF` | Updated (167 → 311 bytes) — reflects new classpath entries for added JARs |
| `build.xml` | Updated (2492 → 2599 bytes) — adjusted to compile new source files |
| `conf/schema.properties` | Expanded (1781 → 1897 bytes) — new keys for Scale and CpuTemp features |
| `conf/schema_ja.properties` | Minor update (1437 → 1464 bytes) |
| `conf/logging.properties` | Minor trim (1982 → 1940 bytes) |

**Core mascot logic:**

| File | Change |
|------|--------|
| `src/.../mascot/Main.java` | Updated (+322 bytes) — hotkey and sensor integration wired in |
| `src/.../mascot/Manager.java` | Significantly expanded (10811 → 13766 bytes) — new mascot lifecycle management for scaling/hotkeys |
| `src/.../mascot/Mascot.java` | Significantly expanded (24645 → 27882 bytes) — scale state, personal space logic |
| `src/.../mascot/DebugWindow.java` | Expanded (14112 → 19457 bytes) — new debug/diagnostic UI panels |

**Actions:**

| File | Change |
|------|--------|
| `src/.../action/Jump.java` | Major rewrite (3825 → 6071 bytes) |
| `src/.../action/ComplexJump.java` | Expanded (9546 → 11638 bytes) |
| `src/.../action/Dragged.java` | Updated (4916 → 5067 bytes) |
| `src/.../action/FallWithIE.java` | Updated (3795 → 4040 bytes) |
| `src/.../action/ThrowIE.java` | Updated (3058 → 3123 bytes) |
| `src/.../action/WalkWithIE.java` | Updated (3800 → 4132 bytes) |

**Environment & image:**

| File | Change |
|------|--------|
| `src/.../environment/Environment.java` | Expanded (4676 → 5018 bytes) — exposes new sensor/temp data |
| `src/.../environment/MascotEnvironment.java` | Expanded (5952 → 6696 bytes) — CPU temp access for scripts |
| `src/.../image/MascotImage.java` | Updated (1003 → 1173 bytes) — scale support |
| `src/.../image/NativeImage.java` | Significantly updated (224 → 473 bytes) — extended interface for scaling |

**Windows platform layer (most changed area):**

| File | Change |
|------|--------|
| `src_win/.../WindowsEnvironment.java` | Major rewrite (12553 → 21178 bytes) — substantial Windows-specific environment improvements |
| `src_win/.../WindowsNativeImage.java` | Expanded (4857 → 5681 bytes) — scaling support in Windows renderer |
| `src_win/.../jna/MONITORINFO.java` | Expanded (310 → 458 bytes) — more monitor info fields |
| `src_win/.../jna/POINT.java` | Expanded (298 → 365 bytes) |

**Script engine:**

| File | Change |
|------|--------|
| `src/.../script/Script.java` | Minor trim (2361 → 2277 bytes) |
| `src/.../script/ScriptFilter.java` | Tiny trim (294 → 290 bytes) |
| `src/.../config/Configuration.java` | Slight reduction (14747 → 14504 bytes) — cleanup/refactor |

---

---

## Part 2: Update Guide — Upgrading from shimejieesrcOG

This guide is for anyone migrating from the original source (`shimejieesrcOG`) to the new `ShimejiSRC_CleanTest` version.

---

### Step 1: Back Up Your Customisations

Before touching anything, back up:
- `conf/actions.xml` and `conf/behaviors.xml` if you have edited mascot behaviour
- Any custom image sets under `img/`
- `conf/settings.properties` (your active shimeji selections)
- Any custom schema properties files

---

### Step 2: Replace the Source Tree

Copy the new source files over your existing directory. The folder structure (`src/`, `src_win/`, `src_mac/`, `src_generic/`, `src_virtual/`) is unchanged, so a full replace works cleanly.

---

### Step 3: Update Your Library Classpath

The `lib/` folder has new additions. Add the following JARs to your project's classpath:

| JAR | Why needed |
|-----|-----------|
| `lib/asm-9.6.jar` | Bytecode manipulation (new scripting features) |
| `lib/asm-commons-9.6.jar` | ASM commons |
| `lib/asm-tree-9.6.jar` | ASM tree API |
| `lib/asm-util-9.6.jar` | ASM utilities |
| `lib/jnativehook-2.2.2.jar` | Global hotkeys (HotkeyManager) |
| `lib/nashorn-core-15.4.jar` | JS scripting on Java 15+ |

All existing libraries (`jna.jar`, `examples.jar`, `AbsoluteLayout.jar`, `nimrodlf.jar`) are **unchanged and still required**.

---

### Step 4: Replace build.xml and MANIFEST.MF

Both have been updated to include the new JARs in classpath declarations. Replace both files with the versions from the new zip. If you have local modifications to `build.xml`, diff carefully before replacing.

---

### Step 5: Update conf/ Files

Replace the following conf files with the new versions:

- **`conf/schema.properties`** — New entries for Scale and CPU temperature features.
- **`conf/schema_ja.properties`** — Minor update.
- **`conf/logging.properties`** — Minor trim.

**Note on `conf/schema_en.properties`:** This file has been **removed**. Its content is now part of `conf/schema.properties`. Delete `conf/schema_en.properties` from your working copy.

All other `conf/` files (`actions.xml`, `behaviors.xml`, all language files, `settings.properties`, `theme.properties`, etc.) are **unchanged** — no action needed unless you have customisations.

---

### Step 6: New Feature — Global Hotkeys

`HotkeyManager.java` is wired into `Main.java` automatically. It uses `jnativehook` to register global keyboard shortcuts for controlling mascots without the tray icon. No manual configuration is needed — just ensure `jnativehook-2.2.2.jar` is on the classpath (covered in Step 3).

---

### Step 7: New Feature — Scale Action

The new `Scale.java` action allows mascots to grow or shrink dynamically during behaviour sequences. To use it in a mascot's `actions.xml`, reference it as you would any other action type. The supporting classes (`ScalableNativeImage.java`, updated `NativeImage.java` and `MascotImage.java`) are already in the source tree.

---

### Step 8: New Feature — CPU Temperature Awareness (Optional)

Mascot behaviours can now react to CPU temperature via `CpuTempMonitor.java`. This requires the companion **SensorReader2** C# project to be running alongside Shimeji-ee:

1. Open `SensorReader2/SensorReader/SensorReader.csproj` in Visual Studio, or build with `dotnet build`.
2. `LibreHardwareMonitorLib.dll` is already included — no separate download needed.
3. Run the resulting `SensorReader.exe` in the background while Shimeji-ee is running.

This is entirely **optional**. Shimeji-ee works fine without it — behaviours referencing CPU temperature simply won't trigger.

---

### Step 9: Remove Deleted Files (Optional Cleanup)

These files exist in the OG but are gone in the new version. Delete them from your working copy to stay clean:

- `Shimeji-ee.exe`
- `Shimeji-eeHomePage.url`, `ShimejiHomePage.url`, `page.url`
- `img/banner.bmp` (root-level)
- `img/FixPngImage.jar`
- `img/KuroShimeji/` (entire folder — 46 sprites)
- `conf/schema_en.properties`
- `devnotes.txt`

---

### Step 10: Rebuild

Clean and rebuild:

```bash
ant clean
ant
```

Or use your IDE's clean/build. All new source files will be picked up automatically.

---

### Summary of What's New (User-Facing) — original migration

- **Global hotkeys** — Control mascots with keyboard shortcuts from anywhere on the desktop.
- **Scale action** — Mascots can grow and shrink as part of their behaviour sequences.
- **CPU temperature reactions** — Mascots can behave differently based on system heat (requires SensorReader2 running).
- **Improved Windows environment** — `WindowsEnvironment.java` has been substantially rewritten, likely improving multi-monitor support and window interaction accuracy.
- **Improved jump/IE interaction physics** — `Jump.java` and `ComplexJump.java` have been significantly reworked.
- **Java 15+ compatibility** — Nashorn JS engine is now bundled, fixing script execution on modern JVMs where it was removed from the standard library.

---

---

## Part 3: Current Feature Inventory (June 2026)

Everything below was added **after** the original migration documented in Parts 1–2. This is
a feature overview; **CLAUDE.md** is the authoritative architecture reference, and the
recipient-facing **readme.txt** documents usage for end users.

> The big shift since Parts 1–2: this fork now carries a **fully local AI assistant layer**
> (LLM inference via Ollama, speech-to-text via faster-whisper, all on-machine — no cloud
> APIs, no telemetry). Mascots given a `<Personality>` become environment-aware companions.

### AI Assistant Layer (`src/.../assistant/`)

- **Chat** — click-to-reply speech bubbles, typed input, persistent per-character memory
  (`img/<name>/conf/memory.json`) with automatic summarization every 20 interactions,
  keyword-gated permanent memories (`[REMEMBER:]`), and timers/reminders (`[TIMER:]`).
- **Screen awareness** — periodic glances at the active window plus on-demand screen captures
  ("what am I looking at?") fed to a local vision model.
- **Audio awareness** — a 15s WASAPI loopback buffer transcribed by Whisper (auto-translated
  to English), so mascots react to videos, music, and calls. Transcript-level echo removal
  keeps speaker bleed from being mistaken for the user.
- **Voice** — name triggers ("Hornet, …") answer directly; hotword→behavior dispatch fires
  animations instantly; overheard speech (you talking on a call / reacting aloud) draws
  occasional in-character remarks.
- **Peer conversations** — mascots overhear and reply to each other, capped at depth 2,
  each in its own voice (anti-mirroring rules), with per-peer relationship tones that drift
  over time.
- **Situational model** (`SituationModel.java`) — a shared, always-running, rule-based daemon
  that fuses app sessions + activity tempo + audio + system load into one read of your state
  (focused / multitasking / winding-down / idle). Mascots react to this fused state, and
  spontaneous comments are triggered by genuine changes (salience), not a blind timer. An
  optional small periodic LLM pass adds a one-line narrative + conservative mood.
- **Drive awareness** (`DriveIndexTool.java`) — a local, never-uploaded drive index behind a
  query router: keyword (exact), semantic (vague topical recall via local `nomic-embed-text`
  embeddings, CPU-only), recency ("last thing I downloaded"), aggregate (counts/sizes/"what
  can I delete"), and folder listing. Ordinary chat never triggers a lookup.
- **Weather & time context** — current time + local weather via Open-Meteo (no API key);
  location auto-detected from IP or set by city name.
- **Resource discipline** — the engine is tuned to stay out of the way on modest hardware
  (6 GB VRAM / 16 GB RAM reference): process-priority separation (Shimeji raised, Ollama/
  Whisper demoted), request spacing, a CPU-only `num_thread` cap (`OllamaResourceCap`), split
  text/vision model keep-alive, and GPU placement always on auto-fit (never forced into RAM).
- **Persona system** — `<Personality>`, short `<PersonalityBrief>` for cheap quick-reactions,
  `<SpeechRule>` hard constraints, `<ThirdPersonRewrite>`, and `<VoiceTrigger>`.
- **Live console readout** (`ConsoleReadout.java` / `ConsoleTap.java`, 2B only) — a faint
  Ubuntu-Mono stream of the app's live console/log output (TickWatch, peer/memory/situation/
  drive events, raw stdout/stderr) projected ahead of 2B in her facing direction — a YoRHa
  unit jacked into the machine. ~30 fps independent window, last 5 lines with a recency
  fade, created/disposed with her assistant mode; each clone gets its own.

### Engine features added since the migration

- **Tint action** — per-frame alpha-blended colour overlay; colour and opacity drivable by
  live JS expressions (sensor-reactive), with lerped channels and `ClearOnExpiry`.
- **Audio level sensor** — `mascot.environment.audioLevel` exposes real-time speaker RMS to
  behaviour scripts.
- **Expanded hardware sensors** — `cpuLoad`/`cpuTemp`, `gpuTemp`/`gpuLoad`/`gpuMemFree`/
  `gpuMemTotal` (NVIDIA via a persistent `nvidia-smi` stream), `ramLoad`, `batteryLevel`.
  CPU temperature now streams from **TempSensor.exe** (LibreHardwareMonitor, requires admin);
  the old per-tick SensorReader spawn is gone.
- **MSI Cooler Boost** — fans ramp to max while a mascot broadcasts the `CoolerBoost`
  affordance (e.g. the blue campfire), via the `MSI_ACPI` WMI firmware path.
- **Animation templates**, **per-pose `ImageAnchor`**, **looped & nested `Sequence`s**,
  **`SyncedStay`** (phase-locked shared animation), **affordance system**, **`FaceDirection`
  on Dragged**, **always-on-top controls**, and **per-mascot behaviour toggles**.
- **Performance** — single shared `EnumWindows` scan per tick; mascot list snapshot rebuilt
  only on change; combined tick/apply pass; a per-tick shared population + affordance index
  (O(N²)→O(N), holds at 130+ mascots); and a `[TickWatch]` slow-tick watchdog that logs a
  phase breakdown (envScan / per-mascot env-tick-apply split / lock-wait) for any tick ≥250ms.
- **Browser extension** — reports YouTube/Twitch video-player bounds to a local HTTP server
  so mascots can walk the edges of the video as if it were a window.

### Build & distribution

- `Build.bat` (repo root) → `ant jar` + Launch4j wrap into `Shimeji.exe`/`ShimejiTest.exe`.
- The runnable app ships as a **GitHub Release** install zip; the public repo
  (`github.com/kobitz/ShimejiSRC`) is **code-only** (MIT) — image/sound/JRE assets are not
  tracked in git and ride along in the release. `Setup.bat` installs Ollama + Python +
  faster-whisper and pulls the default models (`gemma4:e2b-it-qat` + `nomic-embed-text`).
