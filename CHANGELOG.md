# Changelog

Per-version release history for the Shimeji AI fork. The runnable app ships as a GitHub Release asset (`Shimeji-Install-Windows.zip`) at **github.com/kobitz/ShimejiSRC**; the README is intentionally version-agnostic, so this file + the GitHub release notes are the changelog of record. Release *mechanics* (how to cut one) live in `CLAUDE.md` → Distribution / Releases.

## Unreleased

- **Collision respects covering windows** — a mascot that isn't always-on-top (global and per-image-set) and is completely hidden behind another window stops acting as a surface until it is uncovered. Always-on-top mascots keep their collision.
- **Settings window taller** — the tray Settings dialog is 720 px tall (was 640, scaled by menu DPI, capped to the screen) so Done/Cancel are fully visible.
- **Freeze fix (likely)** — the first watchdog dump caught the Manager thread inside `Window.setBounds` (a synchronous native call that holds the AWT tree lock) while the Swing EDT was blocked waiting on that lock; with ~30 mascots a slow AWT toolkit thread turned into multi-second tick stalls. Steady-state frames no longer call `setBounds` (the layered-window update already moves/resizes the window); it is only used when a mascot is first shown.
- **Occlusion check no longer stalls the tick** — the covering-window check looked up each mascot's HWND via JAWT every frame, which needs the AWT lock the Swing EDT holds while repainting a translucent window (4.6 s stall caught by the watchdog). The HWND is now looked up once per window and cached. Watchdog GC figures corrected (they were cumulative).
- **Right-click menu diagnostics** — failures opening the menu are now logged (`[Menu] failed to open...`, with stack) instead of vanishing into the event thread's default handler, a slow event thread is logged (`[Menu] ... waited Nms`), and a stale "open" popup reference no longer blocks a new menu.
- **Deadlock fix (the 30-minute freeze)** — the watchdog dump showed a real ABBA deadlock: the tick thread held the mascot-list lock while entering `UserBehavior.next()`, while the Swing thread (a voice name-trigger running a behavior) held that `UserBehavior`'s monitor and asked for the mascot-list lock. Everything, including the right-click menu, died with the Swing thread. The per-mascot tick/apply loop now runs over a snapshot copy outside the list lock.
- **Second half of the deadlock** — `setBehaviorAll`, `setBehaviorAllSafe` (both) and `cancelHeldActions` (menu/hotkey paths) held the mascot-list lock while calling into behaviors, the reverse of what the tick thread does. They now work from a snapshot (`Manager.mascotsSnapshot()`), so no thread takes a behavior monitor while holding the list lock.
- **Cursor read off the tick** — the next watchdog dump caught the Manager thread in `MouseInfo.getPointerInfo()` (called once per mascot per tick; native AWT, waits on the AWT lock) while the Swing thread was inside a multi-second native `Window.setSize` resizing a chat bubble: a 5.5 s stall. The pointer position is now polled by a `CursorPoller` daemon thread (15 ms) and the tick just reads the cached value.
- **Watchdog samples earlier** — a 5.6 s tick stall was only noticed as it ended, so its dump showed an idle Manager. The watchdog now polls every 200 ms, dumps at 1.5 s (and again at 4 s if still stuck), and lists only the Manager/Swing threads plus running or blocked threads.
- **Freeze diagnostics** — new `StallWatchdog`: if a Manager tick runs, or the Swing event thread stops answering, for 4+ seconds it writes one `[StallWatchdog]` log record with every thread's stack, any deadlock, and GC time. `[TickWatch]` lines now include `gc=` (ms spent in GC during the tick).
- **Stale-schema safety** — the XML schema files are read from the install folder, so an install whose `conf/schema.properties` predates the jar made loading fail (`MissingResourceException ... SplashImages`) or broke `Jump`/`ScanInteract` actions (`Arc`/`Look`). `config/SchemaBundle` now supplies the new keys (`Look`, `Arc`, `SplashImages`) from the jar when the installed file lacks them; any other missing key still errors. Copying the new `conf/schema*.properties` into the install folder is still the right thing to do.
- **Kilkakon 1.0.23 parity** — ported the 1.0.23 features we were missing (we were on 1.0.22): new actions **`BringToFront`**, **`MinimizeIE`** (needs Throwing enabled), **`BreedFall`** and **`JumpWithIE`**; **`Arc`** attribute on `Jump`/`ScanJump`/`ComplexJump` (`false` = straight line); **`Look`** attribute on `ScanInteract` (`false` = don't turn toward the target); **multipage splash screens** (`<SplashImages><SplashImage>..` in info.xml, prev/next buttons in the Information window); **Reveal Statistics** now has *Behaviours* (history, newest first, capped at 200) and *Variables* tabs in place of the single Behaviour line; the out-of-memory dialog now resets `Scaling` to 1.0 and shows the heap available to Shimeji (both handlers merged into `Main.showMemoryError`); toggleable behaviours marked `Hidden` now appear in Personal Behaviours; selecting an image set whose child sets link back to it no longer loops forever. Already covered by existing features: per-Shimeji overrides of global toggles (our Universal Behaviours submenu + `*.mascotN` keys). **Not ported:** 1.0.23's under-the-hood image-storage rewrite (our image pipeline is heavily customised). Also `schema.properties` gains `Look`, `Arc`, `SplashImages`.
- **Animated thinking indicator** — the chat bubble's "Thinking..." text is replaced by dots that cycle `.` -> `..` -> `...` (400 ms per step), anchored so they don't jitter as the count changes.
- **Mascot Collision (universal behavior)** — new toggle: while on for a mascot's image set, other mascots treat it like a window (land/stand on it, climb it, etc.). The collision box is the smallest rectangle around the sprite's non-transparent pixels (alpha >= ~10%), so a 128x128 question block with a solid 64x64 middle collides as just that square. Right-click -> Universal Behaviours -> **Mascot Collision** (per image set, `Collision.imageset.<name>`) and tray -> Allowed Behaviours -> **Mascot Collision** (all; clears per-set overrides; `Collision`). Off by default. Windows only. Boxes are published each tick (`environment/CollisionRegistry`), so mascots standing on a moving one ride it.
- **Script sandbox: `java.awt.Point` allowed** — mascot scripts that build a `java.awt.Point` were being blocked by the tightened class filter; that inert value class is now allowed by default (everything else is still blocked unless listed in `ScriptAllowedClasses`).
- **Build cleanup** — removed the unused 25 MB `vosk` jar (it was being merged into the fat jar); `build.xml` now defaults to `jar`, drops the broken upstream `zip`/`zip_package` targets, declares UTF-8, and is versioned 1.9; `MANIFEST.MF` Class-Path case fixed (`jnativehook-2.2.2.jar`) and newline-terminated; `Build.bat` reads machine-specific paths from a gitignored `Build.local.bat`; `.gitattributes` keeps `*.bat` CRLF.
- **VideoAreaServer hardening** — web origins are now allowlisted (`VideoAreaAllowedHosts`, default `youtube.com,twitch.tv`; extension origins and Origin-less local requests still work) instead of `Access-Control-Allow-Origin: *`; request bodies capped at 2 KB; registered ids capped (64 entries / 64 chars). **If you extend the browser extension to more sites, add them to `VideoAreaAllowedHosts`.**
- **TempSensor.exe launched by absolute path** — `CpuTempMonitor` and `FanController` resolve it next to the jar/exe (new `InstallFiles`) instead of by bare name, closing an exe-planting path under the elevated `Admin.bat`.
- **Weather privacy** — new `WeatherEnabled` setting (default true; `false` = no outside lookups at all). README no longer claims "no cloud APIs": it now states that weather questions contact open-meteo (+ `ip-api.com`/`ipapi.co` for `WeatherLocation=auto`) and how to avoid it.
- **Mascot-script sandbox tightened** — mascot XML scripts can no longer name any Java class (`ScriptFilter` used to expose all of `com.group_finity.mascot.*`, which let a script from an untrusted mascot pack rewrite settings like `OllamaEndpoint` or reach `FanController`/`WhisperProcess`), and Nashorn's `load`/`loadWithNewGlobal`/`exit`/`quit` built-ins are removed (they could read files/URLs and kill the app). New `ScriptAllowedClasses` setting (exact class names) for a trusted script that really needs one; blocked attempts log a `[Script] … blocked` warning naming the class. **If a custom mascot stops behaving after updating, check ShimejieeLog for that warning.**

- **`Mascot.java` split, step 1** — 16 pure static text helpers (reply cleanup, timer/action-tag parsing, persona pronoun rewrite, query classifiers) moved to `assistant/ReplyText`; `Mascot.java` 5085 -> ~4700 lines. No behavior change (old vs new outputs byte-identical over ~30k test inputs).
- **`Mascot.java` split, step 2** — the ~700-line right-click menu (`showPopup`) and its two menu-item classes moved to `MascotPopupMenu`; `Mascot.java` ~4700 -> ~4000 lines. Pure move: the extracted method text is identical to the original apart from `Mascot.this`/field/method references now going through the mascot instance.
- **Fullscreen video: mascots no longer hop onto the taskbar** — the fullscreen-monitor set was published and then modified by the scanner thread (browser-reported fullscreen merged in afterwards), so the tick thread could briefly see the monitor as not fullscreen and the floor jumped to the taskbar. It is now built completely, published once as an immutable set, and held 1.5 s after the last sighting to absorb one-scan blips.
- **Chat bubble offset** — bubbles are now placed relative to the mascot's anchor (stable across frames) instead of the center of its current sprite rectangle, so they no longer shift when an action changes the sprite width. The bubble sits in front of the mascot by a configurable fraction of its height (new **Horizontal Offset** slider in Settings -> Chat Bubbles, `BubbleOffset`, default 50%); the tail points at the mascot's anchor. Screen-edge clamping and bubble-collision pushing are unchanged.
- **Sanitizer + Whisper launch hardening** — `ReplyText.sanitizeResponse` no longer passes a reply through raw when it *opens* with injected content (`##` / `u003c`): it now yields `(no response)` instead. `WhisperProcess` launches Python by absolute path from `PATH` (new `environment/ExecutableLocator`, skips the working directory and relative entries) instead of the bare name `python`, closing the planted-exe path under the elevated `Admin.bat`; new optional `WhisperPython` setting overrides the path. If Whisper stops starting after updating, check ShimejieeLog for `[Whisper] Python not found`.
- **ReplyText tests + data-file ignores** — `test/.../ReplyTextTest.java` (~100 checks over the pure text helpers); `.gitignore` now also covers `relationship_journal.txt`, `memory.json`, `driveindex.txt`, `driveembed.txt` (they stay plain, user-editable text by design; `chat.log` was already covered by `*.log`).
- **Assistant classes moved into `assistant/`** — `AssistantInput/Memory/Prompts/Reactions/Replies/Support` now live in the `assistant` package and depend on `AssistantHost` (grown to cover bubble/dialog/window-title/bubble-list/reply access) instead of `Mascot`; they call each other directly. Shared singletons (Ollama client, audio buffer + lock, last transcript, vision-model default, style rules) moved to `assistant/AssistantShared`. Same logic; `Mascot.java` ~2330 -> ~2325 lines (its remaining size is the mascot itself).
- **`AssistantHost` + first real decoupling** — new `assistant/AssistantHost` interface (what assistant classes may ask of a mascot); `Mascot` implements it. `AssistantActions` moved into the `assistant/` package and now depends only on that interface, with a fake-host test in `test/` (run instructions in CLAUDE.md -> Tests). `REMEMBER_TAG` moved to `ReplyText`. Behavior unchanged.
- **`Mascot.java` split, step 9** — screen capture, Ollama client factory and the fresh-bubble guard moved to `AssistantSupport`; `Mascot.java` ~2390 -> ~2330 lines. Identical method text; delegates left in `Mascot`.
- **`Mascot.java` split, step 8** — prompt construction (system/user prompt, personality, speech rule, weather/audio/situation context, persona rewrite; ~180 lines) moved to `AssistantPrompts`; `Mascot.java` ~2570 -> ~2390 lines. Identical method text; delegates left in `Mascot`.
- **`Mascot.java` split, step 7** — memory summarization (`AssistantMemory`) and action/timer tag handling (`AssistantActions`), ~330 lines; `Mascot.java` ~2900 -> ~2570 lines. Same pattern (identical method text, delegates left in `Mascot`).
- **`Mascot.java` split, step 6** — the typed-input dialog, text-input handler and bubble-reply handler (~270 lines) moved to `AssistantInput`; `Mascot.java` ~3200 -> ~2900 lines. Same pattern as steps 4-5 (identical method text, one-line delegates left in `Mascot`).
- **`Mascot.java` split, step 5** — voice name-trigger reply, peer reaction and screen query (~330 lines) moved to `AssistantReplies`; `Mascot.java` ~3500 -> ~3200 lines. Method text identical apart from `m.`/`Mascot.` member references; `Mascot` keeps one-line delegates.
- **Popup dismiss** — the 150 ms outside-click timer that force-closes the right-click menu now ignores the `IllegalComponentStateException` the NimROD look-and-feel throws when the menu was already closing (it printed a stack trace to the console; no functional effect).
- **`Mascot.java` split, step 4** — the four unprompted reactions (spontaneous comment, audio, vision glance, overheard user speech; ~500 lines) moved to `AssistantReactions`; `Mascot.java` ~4000 -> ~3500 lines. Method text identical apart from `m.`/`Mascot.` member references; `Mascot` keeps one-line delegates.
- **`Mascot.java` split, step 3** — the 4 global reaction-cooldown timers and their durations moved to `assistant/ReactionCooldowns` (static-imported, names and values unchanged). Prep for moving the `fire*Reaction` methods out next.
- **Script sandbox follow-up** — mascot scripts that fired behaviors on other mascots via `com.group_finity.mascot.Main` (Mario/Luigi stomp, block hit, mushroom) now use `mascot.manager.triggerBehavior(o, 'Name')`; see CLAUDE.md -> Scripting.

## v1.9 — June 26 2026

The deepest companion update since the AI layer, plus two architectural performance fixes.

- **Cross-signal fusion completed** — the shared `SituationModel` read is now injected into **every** unprompted reaction (peer, audio, vision, user-speech), not just direct replies + spontaneous. One shared `Mascot.situationBackground()` helper appends a labeled background-only clause so each reaction is informed by the whole picture of what the user is doing, not just its own trigger.
- **Relationship layer (first cut)** — (1) a shared, persisted **daily-activity journal** (`relationship_journal.txt`, 14 days) in `SituationModel`, with a recent-days digest injected into direct replies so the companion can reference the arc of the week ("you've been deep in that all week"); (2) per-mascot **relationship age** (`MascotMemory.firstSeenEpochDay`) — the memory block now carries a "you and this user go back N days (M exchanges)" line so depth accumulates. New setting `RelationshipJournalEnabled` (default true).
- **Performance — render dirty-check** — `Mascot.apply()` skips the layered-window composite when a mascot's frame + position are unchanged, so still mascots stop re-compositing 25×/sec. Removes the per-tick render pile-up that ballooned under GPU/CPU contention (e.g. an AI generation burst).
- **Performance — decoupled window scan** — the per-tick `EnumWindows` walk now runs on its own `WindowScanner` daemon (immutable snapshot via one `volatile`), so a slow scan under a CPU spike + window churn (loading a wall of browser tabs) can never stall the animation tick. Mascots read ≤~40ms-stale window geometry; the scan is off the critical path.
- **ConsoleReadout / ThirdPersonRewrite de-bespoked** — the 2B console readout is now an opt-in `<ConsoleReadout>` `<Information>` flag (2B carries it in its own XML; new `ConsoleReadout` schema key + whitelist entry); the Paimon third-person rewrite likewise reads its own `<ThirdPersonRewrite>` tag. No mascot name is hardcoded in Java for either.
- **Mario-family hotkeys** moved from the global `conf/hotkeys.properties` into per-mascot `img/<name>/conf/hotkeys.properties` (Mario, Luigi, Big Mario, Big Luigi).
- **Claude mascot** removed from the README cast (it was never in the shipped bundle); marked local-only.
- **Dev tooling:** `memory_auditor.pyw` (repo-only) — audits `chat.log` convergence + `memory.json` health, and can prune memory / repair persona `<SpeechRule>`s. Not shipped in the release bundle.

## v1.8 — June 25 2026

- **Configurable campfire transform temps** — `CampfireLitTemp` (75) / `CampfireBlueTemp` (80) in `settings.properties`, via a new generic `mascot.environment.setting('Key', default)` XML accessor any mascot can use.
- **2B `ConsoleReadout` click-through** (`WS_EX_TRANSPARENT`) — the readout window no longer catches the mouse.
- Peer-tone multi-line parse fix (one peer no longer absorbs a comma-joined `PEER_TONE` line).
- **Second release asset: `mascot_patcher.exe`** — standalone XML batch-editor, PyInstaller-built from `mascot_patcher.pyw`.
- First release since v1.3 with conf/img changes (swapped the 3 `img/Campfire*/conf/behaviors.xml` + added the two `settings.properties` keys). ~286 MB zipped.

## v1.7 — June 24 2026

- **Per-mascot hotkey files** — `img/<name>/conf/hotkeys.properties` (bare behavior names scoped to that image set, overriding the global `conf/hotkeys.properties` per combo).

## v1.6 — June 24 2026

- **CPU-load dispatch gate** — unprompted reactions + summarize/synth defer while `cpuLoad ≥ OllamaLoadGate` (60), dropped after 25s; keeps light-use temps under the campfire's 80 °C cooler-boost trigger (load leads temperature). Direct user replies never gated.
- **CoolerBoost 2 s turn-on debounce** — `FanController` requires blue fire to persist 2 s before the fans engage, so a self-resolving spike never triggers boost.

## v1.5 — June 20 2026

- **Dynamic-scale re-rasterize-from-source** — `ScalableNativeImage` draws from the full-res source PNG, not the global-baked bitmap, so global<1 + dynamic upscale rasterizes in one step instead of double-resampling (sharper resize).
- Right-click per-image-set SFX volume slider/label alignment cleanup.

## v1.4 — June 19 2026

- **Live global-Scaling fix** — changing the universal scale in Settings now invalidates the two static `Scaling` caches (`Mascot.cachedGlobalScaling`, `ActionBase.scalingConstant`), so anchors no longer keep the old scale (sprites had clipped through the floor / teleported on turn until restart).

## v1.3 — June 18 2026

- Behavior/action **transition (tween) system** + 2B sit-tween.
- Per-tick movement-delta fix (deltaX/Y now correct for fall/jump/throw).
- `ConsoleReadout` smoothDamp follow & WARNING/TickWatch/SEVERE token styling.

## v1.2 — June 16 2026

- Default model → `gemma4:e2b-it-qat`.
- 2B `ConsoleReadout` (bundles `conf/UbuntuMono-R.ttf`).
- peerTone validation + situational salience fix.

## v1.1 — June 14 2026

- Drive-context retrieval + gemma4 crawl mitigation.
- MIT `LICENSE` added.

## v1.0 — June 2026

- First public release.
