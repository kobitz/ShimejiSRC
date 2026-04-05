# Shimeji-ee — Changes Summary & Update Guide
## shimejieesrcOG → ShimejiSRC_CleanTest

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

### Summary of What's New (User-Facing)

- **Global hotkeys** — Control mascots with keyboard shortcuts from anywhere on the desktop.
- **Scale action** — Mascots can grow and shrink as part of their behaviour sequences.
- **CPU temperature reactions** — Mascots can behave differently based on system heat (requires SensorReader2 running).
- **Improved Windows environment** — `WindowsEnvironment.java` has been substantially rewritten, likely improving multi-monitor support and window interaction accuracy.
- **Improved jump/IE interaction physics** — `Jump.java` and `ComplexJump.java` have been significantly reworked.
- **Java 15+ compatibility** — Nashorn JS engine is now bundled, fixing script execution on modern JVMs where it was removed from the standard library.
