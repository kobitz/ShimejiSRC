---
name: ShimejiSRC Overgrowth Backlog
overview: Close user-facing completion gaps so ShimejiSRC feels like a finished local AI desktop companion — baboon-brain can install, talk, tune, and recover from failure without reading docs or editing XML by hand.
todos:
  - id: persist-assistant-toggle
    content: "Fix Assistant Mode + AlwaysOnTop.imageset persistence (Mascot.java updateConfigFile after toggle)"
    status: pending
  - id: settings-cancel-revert
    content: "Settings Cancel reverts full open-time snapshot, not theme-only (SettingsWindow.java)"
    status: pending
  - id: reaction-type-toggles
    content: "Stream 3 sprint 0 — implement per-reaction-type UI toggles OR rewrite README/readme.txt to match reality (doc rewrite is default if no bandwidth)"
    status: pending
  - id: source-ai-sample
    content: "Add img/DemoAssistant/ with Personality + README source-vs-release section"
    status: pending
  - id: build-bat-portable
    content: "Build.bat paths derive from %~dp0 or env vars — no hardcoded D:/ C:/"
    status: pending
  - id: silent-degraded-modes
    content: "Tray/status hub for Ollama offline, defer quiet, Whisper/mic dead, hotkeys dead, cpuTemp N/A, VideoAreaServer fail"
    status: pending
  - id: settings-ai-fields
    content: "Surface WeatherLocation, OllamaEndpoint, Drive/Situation keys in Settings OR fix readme.txt false Chat Bubbles claims"
    status: pending
  - id: docs-reconcile
    content: "Reconcile README.md vs readme.txt; fix reaction-checkbox, ConsoleReadout, cast list, Setup.bat release-only"
    status: pending
  - id: in-app-memory-reset
    content: "Per-mascot Reset conversation memory menu item (alternative to memory_auditor.pyw)"
    status: pending
  - id: relationship-journal-ux
    content: "Settings to view/disable/clear relationship_journal.txt with privacy note"
    status: pending
  - id: language-pin-restore
    content: "refreshLanguage() must call restorePinnedMascots() after recreate (Main.java)"
    status: pending
  - id: config-save-dialog
    content: "Config save failures → user dialog, not empty catch (Main.java updateConfigFile)"
    status: pending
isProject: false
---

# ShimejiSRC — Product Completion Backlog

## Outcome

A baboon-brain user can: download or clone, get a working AI mascot without hand-editing properties; toggle assistant features from UI; understand when something is broken (Ollama, mic, admin temps, hotkeys); recover without dead-end silent failures; trust that Cancel means cancel and toggles survive restart.

## Scope

| In | Out |
|----|-----|
| Core desktop UX: tray, settings, mascot menu, hotkeys, build/run | Upstream Shimeji-ee parity features unrelated to this fork |
| AI layer: bubbles, voice, memory, Ollama, situational/drive/weather | Cloud APIs, paid models, telemetry |
| Onboarding: Setup.bat path, source-vs-release clarity | Reconstructing full 286 MB release bundle in git |
| Windows-primary surfaces; document Mac/Linux limits | Full AMD GPU / Mac AI port (backburner) |
| Operator tools: memory_auditor discoverability | Shipping memory_auditor in public bundle |
| Privacy UX: drive index, relationship journal | Legal compliance audit beyond user-visible controls |

## Overgrowth summary

| Metric | Partition A (Core) | Partition B (AI) | Merged |
|--------|-------------------|------------------|--------|
| Surfaces cataloged | 52 | 32 | 84 |
| Total gaps | 48 | 48 | 96 |
| ship-blocker | 4 | 3 | **7** |
| major | 22 | 22 | 44 |
| minor | 19 | 20 | 39 |
| nice | 3 | 3 | 6 |

### Top ship-blockers (≤15)

1. **Build.bat hardcoded paths** — `Build.bat:4-8` pins Ko's D:/ and C:/; open-source builders blocked.
2. **Assistant Mode not persisted** — toggle sets in-memory property only; no `updateConfigFile()` (`Mascot.java:1274-1276`).
3. **Always On Top per-mascot not persisted** — same pattern (`Mascot.java:1318-1319`).
4. **Settings Cancel incomplete** — only restores theme (`SettingsWindow.java:2239-2243`).
5. **Reaction-type toggles documented but missing** — README.md:125 promises checkboxes; UI has behavior toggles only.
6. **Source repo cannot demo AI** — `img/` only default Shimeji; no Personality XML; Setup.bat absent from tree.
7. **Silent unprompted/deferred failures** — background reactions dismiss bubble with no user cue (`Mascot.java:2208-2213`, `2727-2732`).

## Gap backlog

### Tier-1 — ship-blocker + major (selected; full count 51)

| # | Part | Surface | Gap | Tag | Severity | Evidence |
|---|------|---------|-----|-----|----------|----------|
| A1 | A | build.bat | Hardcoded workspace/install paths | ops-gap | ship-blocker | `Build.bat:4-8` |
| A2 | A | mascot.assistant-toggle | Assistant Mode lost on restart | incomplete-flow | ship-blocker | `Mascot.java:1274-1276` |
| A3 | A | mascot.right-click-menu | AlwaysOnTop.imageset not flushed to disk | incomplete-flow | ship-blocker | `Mascot.java:1318-1319` |
| A4 | A | settings.window | Cancel reverts theme only | rough-edge | ship-blocker | `SettingsWindow.java:2239-2243` |
| B1 | B | README | Per-reaction checkboxes promised, not built | incomplete-flow | ship-blocker | `README.md:125` |
| B2 | B | onboarding | Setup.bat referenced, absent from source | ops-gap | ship-blocker | docs only; no `Setup.bat` in tree |
| B3 | B | img/ | No sample AI mascot in repo | missing-feature | ship-blocker | `img/` only `Shimeji/` |
| A5 | A | tray.language-picker | Language change drops pinned mascots | incomplete-flow | major | `Main.java:1538-1550` |
| A6 | A | hotkeys.global | JNativeHook fail = log only | permission-hole | major | `HotkeyManager.java:154-157` |
| A7 | A | conf/hotkeys.properties | All examples commented; zero active bindings | missing-feature | major | `conf/hotkeys.properties:12-37` |
| A8 | A | video.browser-extension | Port bind fail silent | ops-gap | major | `VideoAreaServer.java:51-55` |
| A9 | A | hardware.sensors | cpuTemp N/A without Admin.bat; no in-app banner | permission-hole | major | `CpuTempMonitor.java:28-33` |
| A10 | A | error.shimejiee-log | Config save exceptions swallowed | ops-gap | major | `Main.java:1678-1680` |
| A11 | A | mascot.right-click-menu | English-only menu strings | polish | major | `Mascot.java:906,1090,1267` |
| A12 | A | launch.shimeji-exe | Git clone lacks showcase mascots/assets | incomplete-flow | major | `img/` audit |
| A13 | A | xml.schema | Unknown Information tags silently ignored | ops-gap | major | `Configuration.java:348-360` |
| B4 | B | bubble | Unprompted onError = silent dismiss | rough-edge | major | `Mascot.java:2208-2213` |
| B5 | B | OllamaClient | CPU defer drop invisible to user | rough-edge | major | `OllamaClient.java:270-277` |
| B6 | B | settings | WeatherLocation in readme, not in UI | incomplete-flow | major | `readme.txt:591` |
| B7 | B | settings | OllamaEndpoint not user-editable | incomplete-flow | major | `readme.txt:586` |
| B8 | B | settings | Drive/Situation/ResourceCap keys properties-only | missing-feature | major | `readme.txt:593-605` |
| B9 | B | settings.properties | WhisperModel path vs enum mismatch | rough-edge | major | `conf/settings.properties:10` |
| B10 | B | WhisperProcess | Python missing — no guided recovery | incomplete-flow | major | `WhisperProcess.java:73-76` |
| B11 | B | settings | Ollama offline — model dropdown silent fail | rough-edge | major | `SettingsWindow.java:443-445` |
| B12 | B | OllamaClient | Queue full → generic skip message | rough-edge | major | `OllamaClient.java:387-404` |
| B13 | B | Voice | No mic = WARNING log only | incomplete-flow | major | `readme.txt:160-164` |
| B14 | B | Voice | AI audio Windows-only; weak first-run gate | missing-feature | major | `README.md:135` |
| B15 | B | bubble | No onboarding for click-to-talk | polish | major | `Mascot.java:728-738` |
| B16 | B | memory | No in-app reset; external pyw only | ops-gap | major | `memory_auditor.pyw` |
| B17 | B | DriveIndexTool | Full drive paths indexed beside JAR | compliance-gap | major | `DriveIndexTool.java:51` |
| B18 | B | SituationModel | relationship_journal.txt invisible to user | compliance-gap | major | `SituationModel.java:183` |
| B19 | B | README | Cast listed but not in source img/ | ops-gap | major | `README.md:129-131` |

*Tier-2 deferred: 45 minor/nice gaps (localization polish, tray double-click, timer confirmation, AMD GPU doc, etc.) — implement after tier-1 streams complete.*

## Streams

### Stream 1 — Persistence & settings integrity (Core)

- `updateConfigFile()` after Assistant Mode and AlwaysOnTop toggles
- Settings open-time snapshot; Cancel restores all tabs
- Language change preserves pinned mascots via `restorePinnedMascots()`
- Config save failures → dialog, not swallow

**Files:** `Mascot.java`, `SettingsWindow.java`, `Main.java`

### Stream 2 — Build & source/release clarity (Ops)

- Portable `Build.bat` (`%~dp0` or env)
- README section: "Developing from source" vs "Using release bundle"
- Optional `img/DemoAssistant/` minimal Personality sample
- Include or template `Setup.bat` in repo OR explicit release-only callout
- Reconcile `README.md` and `readme.txt`

**Files:** `Build.bat`, `README.md`, `readme.txt`, `img/DemoAssistant/conf/behaviors.xml`

### Stream 3 — AI controls & documentation truth (AI UX)

- **Sprint 0 decision (locked):** per-reaction toggles **OR** doc rewrite — default to doc rewrite if bandwidth tight; either path clears ship-blocker B1
- Settings: WeatherLocation, OllamaEndpoint, advanced AI section (or fix readme false claims)
- WhisperModel canonical format + migration (`tiny` enum, not filesystem path)
- ConsoleReadout readme fix (config-driven opt-in via `<ConsoleReadout>`, not 2B-only)

**Files:** `Mascot.java`, `SettingsWindow.java`, `README.md`, `readme.txt`, `conf/settings.properties`

### Stream 4 — Degraded mode visibility (Trust)

- **New surface required:** tray/status hub or submenu — no aggregation exists today (`Main.java` tray is menu-only)
- Signals: Ollama unreachable, defer quiet, Whisper/mic dead, hotkeys unavailable, cpuTemp requires Admin.bat, VideoAreaServer failed
- Unprompted onError → user-visible cue (not silent dismiss); wire shared `onError` path in `Mascot.java`
- Ollama model dropdown offline state in Settings
- First-run gates: Windows-only AI audio, click-to-talk hint

**Files:** `Main.java`, `Mascot.java`, `OllamaClient.java`, `HotkeyManager.java`, `WhisperProcess.java`, `VideoAreaServer.java`, `CpuTempMonitor.java`

### Stream 5 — Privacy & operator tools (Compliance + power user)

- Drive index opt-out + explanation in Settings (`DriveIndexEnabled` property exists; no UI)
- Relationship journal view/disable/clear (`RelationshipJournalEnabled` property exists; no UI)
- In-app "Reset conversation memory" per mascot (clears `peerExchanges` + optional facts)
- Dedicated operator section documenting `memory_auditor.pyw` (brief mention in README:127 insufficient)

**Files:** `SettingsWindow.java`, `SituationModel.java`, `MascotMemory.java`, `Mascot.java`, `readme.txt`

### Stream 6 — Core polish batch (after blockers)

- Hotkey sample binding or setup wizard
- Global vs per-imageset behavior toggle labels
- XML unknown tag warnings (`Configuration.java`)
- Localize hardcoded mascot menu strings
- Non-tray platform fallback (Linux CI)

## Risks

| Risk | Mitigation |
|------|------------|
| Doc rewrite vs feature build for reaction toggles | Stream 3 sprint 0; default doc rewrite; README lie is ship-blocker either way |
| Source/release split is intentional | Minimal DemoAssistant + clear docs; do not bloat git with full cast |
| Silent defer under CPU load feels "broken companion" | Stream 4 tray signal; keep defer logic, add visibility |
| relationship_journal.txt privacy | Stream 5 disable/clear before public docs claim "local only" |
| Build.bat fix breaks Ko's local workflow | Env var override with `%INSTALL_DIR%` default |
| readme.txt falsely lists OllamaEndpoint/WeatherLocation in Chat Bubbles tab | Stream 3 must fix readme.txt:583-591, not only add Settings fields |

## Verify gate

User-visible acceptance (not compile-only). Each item maps to approved audit gates.

| # | Test | Gates |
|---|------|-------|
| 1 | Enable Assistant Mode → quit → relaunch → still on; `settings.properties` contains `AssistantMode.<imageSet>` | 6 |
| 2 | Settings: change scale/model → Cancel → reopen → unchanged (not theme-only revert) | 8 |
| 3 | Ollama stopped → typed chat shows error; tray indicates AI offline | 15 |
| 4 | CPU load ≥ gate → tray shows "companions quiet"; direct chat still works when Ollama up | 16 |
| 5 | Clone repo → README "Developing from source" explains AI path (DemoAssistant or release bundle); Setup.bat called release-only | 2, 3, 4 |
| 6 | Reaction: if built, disable audio only → no audio reactions; if doc rewrite, README no longer promises per-reaction checkboxes | 11 |
| 7 | Admin.bat vs Shimeji.exe: banner when cpuTemp unavailable | 18 |
| 8 | Reset memory menu → `peerExchanges` cleared | 21 |
| 9 | `Build.bat` runs on machine without D:/ C:/ paths | 1 |
| 10 | Always On Top toggle survives restart | 7 |
| 11 | Language change → pinned mascots restored at saved positions | 9 |
| 12 | Settings exposes drive-index opt-out OR readme accurately marks manual-only | 12, 19 |
| 13 | Relationship journal viewable/disabled from Settings | 20 |
| 14 | `memory_auditor.pyw` documented in operator section of readme | 22 |

## Weedwhacker radius audit (E2E)

**Hop A — Source & build handoff**

| Gate | Status | Evidence | Remediation |
|------|--------|----------|-------------|
| 1 — `Build.bat` portable paths | GAP | `Build.bat:4-8` hardcodes `D:\Downloads\...` and `C:\Users\ko\...` | Stream 2 — `%~dp0` + `%INSTALL_DIR%` env |
| 2 — README source vs release | GAP | `README.md:7` release-only Setup.bat path; `README.md:143-148` Building has no source/release split | Stream 2 — add "Developing from source" section |
| 3 — DemoAssistant or release callout | GAP | `img/` contains only `Shimeji/`; no `<Personality>` in any tracked XML | Stream 2 — add `img/DemoAssistant/` |
| 4 — Setup.bat release-only documented | GAP | `README.md:7` implies Setup.bat for all users; no `Setup.bat` in repo (`find` empty) | Stream 2 — explicit release-only callout |
| 5 — Cast list matches source `img/` | GAP | `README.md:129-131` lists Hornet/Holo/Paimon/2B; source has `img/Shimeji` only | Stream 2 — cast labeled release-bundle or trim list |

**Hop B — Runtime companion trust**

| Gate | Status | Evidence | Remediation |
|------|--------|----------|-------------|
| 6 — Assistant Mode persists | GAP | `Mascot.java:1274-1276` sets property; no `updateConfigFile()`. Contrast `Mascot.java:912-913` ManualOnly flushes | Stream 1 |
| 7 — AlwaysOnTop persists | GAP | `Mascot.java:1317-1319` sets property; no `updateConfigFile()` | Stream 1 |
| 8 — Settings Cancel full revert | GAP | `SettingsWindow.java:2239-2243` restores `oldTheme` only; open snapshot is theme-only (`SettingsWindow.java:224-234`) | Stream 1 |
| 9 — Pinned mascots on language change | GAP | `Main.java:1538-1550` disposes all + recreates without `restorePinnedMascots()`. Contrast startup path `Main.java:358-360` | Stream 1 |
| 10 — Config save errors surfaced | GAP | `Main.java:1678-1680` empty catch swallows write failures | Stream 1 |
| 11 — Reaction toggles OR doc fix | GAP | `README.md:125` promises per-reaction checkboxes; `Mascot.java:929` PersonalBehaviours toggles XML behaviors only | Stream 3 sprint 0 |
| 12 — Advanced AI keys in Settings OR manual-only docs | GAP | `readme.txt:583-591` falsely lists OllamaEndpoint/WeatherLocation in Chat Bubbles tab; `SettingsWindow.java:383-456` exposes model/bubble fields only; `readme.txt:593-605` correctly marks Drive/Situation manual | Stream 3 |
| 13 — WhisperModel canonical format | WRONG BASELINE | `conf/settings.properties:10` = `./whisper/ggml-tiny.en.bin`; `WhisperProcess.java:238` default `"tiny"` enum — path never resolves as model name | Stream 3 — migrate to enum + startup warning |
| 14 — ConsoleReadout docs config-driven | GAP | `README.md:121` says "used by 2B"; Java is config-driven (`Configuration.java:359` whitelist) | Stream 3 — doc fix |
| 15 — Ollama offline UI | GAP | `SettingsWindow.java:443-445` silent catch; `Main.java:528-1347` tray has no status aggregation | Stream 4 |
| 16 — CPU defer visible | GAP | `OllamaClient.java:270-277` logs + `onError("Skipped — system busy.")`; `Mascot.java:2727-2732` dismisses bubble silently | Stream 4 |
| 17 — Unprompted onError user cue | GAP | `Mascot.java:2208-2213`, `2727-2732` — all unprompted/direct error paths dismiss without message | Stream 4 |
| 18 — Degraded subsystem signals | GAP | Hotkeys: `HotkeyManager.java:154-157` log only; Whisper: `WhisperProcess.java:73-76` severe log; cpuTemp: `CpuTempMonitor.java:28-33` admin note, no UI; VideoArea: `VideoAreaServer.java:51-55` stderr/log; mic: `readme.txt:160-164` troubleshooting only | Stream 4 |
| 19 — Drive index opt-out in Settings | GAP | `DriveIndexTool.java:240-242` respects `DriveIndexEnabled` property; no Settings UI; index at `DriveIndexTool.java:51` | Stream 5 |
| 20 — Relationship journal controls | GAP | `SituationModel.java:182-183` writes `relationship_journal.txt`; `SituationModel.java:364-367` property gate only; no view/disable/clear UX | Stream 5 |
| 21 — In-app memory reset | GAP | `MascotMemory.java:527-540` stores `peerExchanges`; no menu hook; external `memory_auditor.pyw` only | Stream 5 |
| 22 — memory_auditor documented | GAP | `README.md:127` one-line mention; no dedicated operator section in readme.txt | Stream 5 |

**E2E trace:** Contributor clones → portable `Build.bat` + README/DemoAssistant yields AI path → user enables Assistant Mode (persists) → Settings Cancel honored → Ollama/degraded states visible in tray → privacy controls govern drive index + journal → in-app memory reset clears poisoned peer speech.

**Handoff risks addressed in plan:** Build.bat env override preserves Ko workflow; Stream 3 sprint 0 locks reaction-toggle vs doc decision; Stream 4 requires new tray status surface (not just log lines); readme.txt Chat Bubbles false claims flagged under gate 12.

## 4-step logic audit

**Step 1 — Premise:** Plan premise valid. All 22 gates scored GAP or WRONG BASELINE against live code at `/home/nackloose/Downloads/ShimejiSRC-main`; zero OK. Ship-blocker count (7) matches evidence. Source/release asset split is intentional — plan correctly scopes minimal DemoAssistant rather than full cast in git.

**Step 2 — Ordering:** Stream 1 (persistence) first — verify gates 1–2 depend on it before trust testing. Stream 2 (build/docs) parallelizable with Stream 1. Stream 3 sprint 0 (reaction toggle decision) blocks doc reconciliation in Stream 2 partial overlap — run decision before README rewrite. Stream 4 (degraded visibility) after Stream 1 so persistence fixes do not mask status signals. Stream 5 (privacy/operator) after Stream 4 — journal/drive UX should not ship while silent failures remain. Stream 6 after all blockers.

**Step 3 — Failure modes:**

- Reaction toggles built late → README lie persists → ship-blocker → **Stream 3 sprint 0** (default doc rewrite)
- Tray status hub scoped too large → Stream 4 stalls → **Stream 4** — start with tray submenu strings, not full dashboard
- DemoAssistant without bundled Ollama guidance → clone still broken → **Stream 2** — pair DemoAssistant with README setup steps
- WhisperModel path left in properties → voice silently broken → **Stream 3** — enum migration + one-time normalize on load
- Language refresh spawns duplicates alongside pins → **Stream 1** — guard `refreshLanguage()` with pin-restore before create loop
- readme.txt Chat Bubbles section left wrong after Settings fields added → **Stream 3** — reconcile both files in same PR

**Step 4 — Locked:** (1) Per-reaction toggles: build **or** doc rewrite — doc rewrite is default fallback. (2) DemoAssistant minimal sample, not full cast assets. (3) Tray status hub is new surface — extend existing tray menu, not separate app. (4) `memory_auditor.pyw` stays dev-only, not shipped in bundle. ETA wall **~6–8 focused sessions** (Streams 1–5 serial; Stream 2+3 partially parallel).

## Outcome

Disk: remediated plan with 22-gate audit; code changes across Streams 1–5 when implemented. Wall: tier-1 blockers first (~1–2 focused sessions per stream). Deliverables: persisted settings, honest docs, visible degraded modes, sample AI mascot in source, baboon can finish primary companion flows without XML hand-editing.