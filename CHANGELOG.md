# Changelog

Per-version release history for the Shimeji AI fork. The runnable app ships as a GitHub Release asset (`Shimeji-Install-Windows.zip`) at **github.com/kobitz/ShimejiSRC**; the README is intentionally version-agnostic, so this file + the GitHub release notes are the changelog of record. Release *mechanics* (how to cut one) live in `CLAUDE.md` → Distribution / Releases.

## Unreleased (on `main`, ahead of v1.8)

- **Cross-signal fusion completed** — the shared `SituationModel` read is now injected into **every** unprompted reaction (peer, audio, vision, user-speech), not just direct replies + spontaneous. One shared `Mascot.situationBackground()` helper appends a labeled background-only clause so each reaction is informed by the whole picture of what the user is doing, not just its own trigger.
- **Relationship layer (first cut)** — (1) a shared, persisted **daily-activity journal** (`relationship_journal.txt`, 14 days) in `SituationModel`, with a recent-days digest injected into direct replies so the companion can reference the arc of the week ("you've been deep in that all week"); (2) per-mascot **relationship age** (`MascotMemory.firstSeenEpochDay`) — the memory block now carries a "you and this user go back N days (M exchanges)" line so depth accumulates. New setting `RelationshipJournalEnabled` (default true).
- **ConsoleReadout / ThirdPersonRewrite de-bespoked** — the 2B console readout is now an opt-in `<ConsoleReadout>` `<Information>` flag (2B carries it in its own XML); the Paimon third-person rewrite likewise reads its own `<ThirdPersonRewrite>` tag. No mascot name is hardcoded in Java for either.
- **Mario-family hotkeys** moved from the global `conf/hotkeys.properties` into per-mascot `img/<name>/conf/hotkeys.properties` (Mario, Luigi, Big Mario, Big Luigi).

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
