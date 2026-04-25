Shimeji-ee: Shimeji English Enhanced

Shimeji-ee is a Windows desktop mascot that freely wanders and plays around the screen.  The mascot is very configurable; its actions are defined through xml and its animations/images can be (painstakingly) customized.  Shimeji was originally created by Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/).  This branch of the original Shimeji project not only translates the program/source to English, but adds additional enhancements to Shimeji by Kilkakon and other members of the community.

==== Contents ==== 

1. Homepage
2. Requirements
3. How to Start
4. Basic Configuration
5. Advanced Configuration
6. How to Quit
7. How to Uninstall
8. Source
9. Library
10. Trouble Shooting
11. Changes

==== Homepage ==== 

Homepage: http://kilkakon.com/shimeji

==== Requirements ==== 

1. Windows Vista or higher
2. Java 8

==== How to Start ==== 

Double Click the Shimeji-ee file (Shimeji-ee.jar).

Right click the tray icon for general options.

Right click a Shimeji for options relating to it.

For a tutorial on how to get Shimeji running, watch this video: https://www.youtube.com/watch?v=S7fPCGh5xxo

You can also watch the FAQ if you encounter problems: https://www.youtube.com/watch?v=A1y9C1Vbn6Q

You can also join my Discord group: https://discord.gg/dcJGAn3

==== Basic Configuration ==== 

If you want multiple Shimeji types, you must have multiple image sets.  Basically, you put different folders with the correct Shimeji images under the img directory.

For example, if you want to add, say, a new Batman Shimeji:

1. Create an img/Batman folder.
2. You must have an image set that mimicks the contents of img/Shimeji.  Create and put new versions of shime1.png - shime46.png (with Batman images of course) in the img/Batman folder.  The filenames must be the same as the img/Shimeji files.  Refer to img/Shimeji for the proper character positions.
3. Start Shimeji-ee.  Now Shimeji and Batman will drop.  Right click Batman to perform Batman specific options.  Adding "Call Shimeji" from the tray icon will randomly create add either Shimeji or Batman.

When Shimeji-ee starts, one Shimeji for every image set in the img folder will be created.  If you have too many image sets, a lot of your computer's memory will be used... so be careful.  Shimeji-ee can eat up to 60% of your system's free memory.  

Shimeji-ee will ignore all the image sets that are in the img/unused folder, so you can hide image sets in there.  There is also a tool, Image Set Chooser, that will let you select image sets at run time.  It remembers previous options via the conf/settings.properties file.  Don't choose too many at once.

For more information, read through the configuration files in conf/.  Most options are somewhat complicated, but it's not too hard to limit the total number of Shimeji or to turn off certain behaviors (hint: set frequency to 0.)

==== Advanced Configuration ==== 

All configuration files are located in the in the conf folders.  In general, none of these should need to be touched.

The logging.properties file defines how logging errors is done.
The actions.xml file specifies the different actions Shimeji can do.  When listing images, only include the file name.  More detail on this file will hopefully be added later.
The behaviors.xml file specifies when Shimeji performs each action.  More detail on this file will hopefully be added later.
The settings.properties file details which Shimeji are active as well as the windows with which they can interact. These settings can be changed using the program itself.
The hotkeys.properties file defines global hotkey bindings that trigger specific behaviors.  See the section on Hotkeys below for details.

Each type of Shimeji is configured through:

1. An image set.  This is located in img/[NAME].  The image set must contain all image files specified in the actions file. 
2. An actions file.  Unless img/[NAME]/conf/actions.xml or conf/[NAME]/actions.xml exists, conf/actions.xml will be used.
3. A behaviors file.  Unless img/[NAME]/conf/behaviors.xml or conf/[NAME]/behaviors.xml exists, conf/behaviors.xml will be used.

When Shimeji-ee starts, one Shimeji for every image set in the img folder will be created.  If you have too many image sets, a lot of your computer's memory will be used... so be careful.  Shimeji-ee can eat up to 60% of your system's free memory.  

Shimeji-ee will ignore all the image sets that are in the img/unused folder, so you can hide image sets in there.  There is also a tool, Image Set Chooser, that will let you select image sets at run time.  It remembers previous options via the ActiveShimeji file.  Don't choose too many at once.

The Image Set Chooser looks for the shime1.png image.  If it's not found, no image set preview will be shown.  Even if you're not using an image named shime1.png in your image set, you should include one for the Image Set Chooser's sake.

Editing an existing configuration is fairly straightforward.  But writing a brand new configuration file is very time consuming and requires a lot of trial and error.  Hopefully someone will write a guide for it someday, but until then, you'll have to look at the existing conf files to figure it out.  Basically, for every Behavior, there must be a corresponding action.  Actions and Behaviors can be a sequence of other actions or behaviors.

The following actions must be present for the actions.xml to be valid:

ChaseMouse
Fall
Dragged
Thrown

The following behaviors must be present for the behaviors.xml to be valid:

ChaseMouse
Fall
Dragged
Thrown

The icon used for the system tray is img/icon.png

==== Hotkeys ====

Global hotkeys can be configured in conf/hotkeys.properties.  Each line maps a key combo to a behavior name.

Format: <combo>=<BehaviorName>

Key combo syntax (case-insensitive, tokens joined by +):
  Modifiers : ctrl, shift, alt, meta
  Keys      : F1-F12, A-Z, 0-9, SPACE, ENTER, HOME, END, etc.
  Mouse     : MOUSE1 (left), MOUSE2 (right), MOUSE3 (middle), MOUSE4, MOUSE5

To target a specific mascot image set, prefix the behavior name with the image set name and a colon:
  MOUSE4=Mario:JumpToCursor

To make a key loop the behavior for as long as it is held down, suffix the behavior name with !hold:
  RIGHT=Mario:MoveRight!hold
  LEFT=Mario:MoveLeft!hold

Without !hold, the key fires once on press.  With !hold, the behavior plays through cleanly to the end and loops until the key is released, at which point the mascot returns to normal behavior.

The hold-to-loop system also supports sequence ramp-up via the HoldLoopStep attribute on an action.  When HoldLoopStep is set to the name of a child action reference, the sequence plays through once in full and then loops only that named step for as long as the key is held.  This allows behaviors such as a walk-to-run ramp-up: the mascot walks for the configured distance, then runs on a loop until the key is released, at which point the run's NextBehaviorList fires normally (e.g. DashBrake).

XML usage:
  <Action Name="MoveRight" Type="Sequence" HoldLoopStep="Run" ...>
    <ActionReference Name="Walk" TargetX="..." />
    <ActionReference Name="Run"  TargetX="..." />
    ...
  </Action>

Lines starting with # are comments.  Blank lines are ignored.

==== How to Quit ==== 

Right-click the tray icon of Shimeji, Select "Dismiss All"

==== How to Uninstall ==== 

Delete the unzipped folder.

==== Source ====

Programmers may feel free to use the source.  The Shimeji-ee source is under the New BSD license.
Follow the zlib/libpng licenses.

==== Library ====

lib / jna.jar and lib / examples.jar of the JNA library.
JNA follows the LGPL.
lib / AbsoluteLayout.jar from Netbeans.

==== Browser Extension: Video Player Window Detection ====

A companion browser extension (included in the shimeji folder) allows mascots to treat video players on YouTube and Twitch as interactive windows — walking on top of the player, clinging to its sides, and interacting with its edges exactly like any other window on screen.

The extension sends the video player's screen position to a local HTTP server built into Shimeji (port 41221).  Shimeji injects four thin border strips around the video player into its window detection system, bypassing the normal occlusion checks so the strips are visible even though they sit inside the browser window bounds.  The strips update automatically whenever the player moves, resizes, or the browser zoom level changes.

To install permanently in LibreWolf or Firefox:
1. In about:config, set xpinstall.signatures.required to false.
2. In about:addons, click the gear icon and select Install Add-on From File, then pick the shimeji-video-tracker.xpi.

The extension supports YouTube and Twitch out of the box.  Additional sites can be added by editing the matches list in manifest.json and updating the video element selector in content.js.

==== Trouble Shooting ====

For a tutorial on how to get Shimeji running, watch this video: https://www.youtube.com/watch?v=S7fPCGh5xxo

You can also watch the FAQ if you encounter problems: https://www.youtube.com/watch?v=A1y9C1Vbn6Q

You can also join my Discord group: https://discord.gg/dcJGAn3

Shimeji-ee takes a LOT of time to start if you have a lot of image sets, so give it some time.  Try moving all but one image set from the img folder to the img/unused folder to see if you have a memory problem.  If Shimeji is running out of memory, try editing Shimeji-ee.bat and change "-Xmx1000m" to a higher number.

If the Shimeji-ee icon appears, but no Shimeji appear:

1. Make sure you have the newest version of Shimeji-ee.
2. Make sure you only have image set folders in your img directory.
3. Make sure you have Java on your system.
4. If you're somewhat computer savvy, you can try running Shimeji-ee from the command line.  Navigate to the Shimeji-ee directory and run this command: "C:\Program Files (x86)\Java\jre6\bin\java" -jar Shimeji-ee.jar
5. Try checking the log (ShimejiLogX.log) for errors.  If you find a bug (which is very likely), post it up on the Shimeji-ee homepage in the issues section.

==== Changes ====

The following changes have been made from the base Shimeji-ee source.

---- Multi-Window Support ----

The interactive window detection system has been overhauled.  Previously, each mascot performed its own full EnumWindows scan every tick, meaning 12 mascots would trigger 12 separate scans per tick.  Now, WindowsEnvironment.beginTick() runs a single shared EnumWindows scan once per tick, and all mascots read from that shared snapshot.  This significantly reduces CPU overhead when many mascots are running.

Multi-monitor awareness has also been improved.  The work area is now calculated per-monitor using MonitorFromPoint and GetMonitorInfo, allowing Shimeji to correctly respect taskbar positions and work area boundaries on each individual screen in a multi-monitor setup.

---- Global Hotkeys ----

A new HotkeyManager class provides global hotkey support via the JNativeHook library.  Hotkeys are configured in conf/hotkeys.properties and can trigger any named behavior on all mascots or on a specific image set.  Keys support modifier combinations (ctrl, shift, alt, meta) and mouse buttons (MOUSE1-MOUSE5).

A hold-to-loop mode is available by appending !hold to the behavior name.  When a key is held, the behavior plays through cleanly to completion and loops from the start, rather than restarting on every OS key-repeat event.  Releasing the key returns the mascot to its normal behavior on its next natural action completion.

---- Hold-Loop Last Step (Sequence Ramp-Up) ----

When a behavior is triggered with !hold, the hold-loop system normally replays the entire action sequence on each loop.  The HoldLoopStep attribute on an action tag lets you designate a specific named child action reference as the loop target.  The sequence plays through in full once, then only the designated step loops for as long as the key is held.  When the key is released, that step's NextBehaviorList fires naturally.

This enables walk-to-run ramp-up: a MoveRight action can contain a Walk step followed by a Run step.  With HoldLoopStep="Run", the mascot walks once to build speed, then runs on a loop until the key is released, at which point DashBrake fires via Run's NextBehaviorList.  The loop step is located via deep tree search, so it can be nested inside Select or Sequence children.

The mascot's affordances (e.g. SmallMarioHittable) are preserved correctly while the loop step runs, even though the looped action reference has no Affordance attribute of its own.

---- Directional Air Control ----

When a directional hotkey (!hold) is held while a mascot is falling or thrown, the mascot's horizontal fall velocity is steered directly each tick without interrupting the Fall or Thrown behavior.  This allows mid-air trajectory adjustment: holding left or right while airborne nudges the mascot's fall direction continuously for as long as the key is held.

---- Manual Only Mode ----

Each mascot has a per-mascot Manual Only toggle in its right-click menu.  When enabled, the mascot's autonomous behavior selection is suppressed: it only responds to hotkey-triggered behaviors and transitions between a small set of physics behaviors (Fall, Dragged, Thrown) and standing behaviors.  When no explicit behavior is triggered and no NextBehaviorList applies, the mascot falls back to a stand-in-place behavior.

Manual Only state is persisted per-mascot in settings.properties and restored on next launch.

---- Floor Collision Toggle ----

Each mascot has a per-mascot Floor Collision toggle in its right-click menu.  When disabled, the mascot ignores the workarea bottom border as a floor surface and will fall through the bottom of the screen if no windows are present to land on.  IE window top borders (the tops of application windows) still act as floors normally.  This allows mascots to fall freely off the bottom of the screen without being blocked by the desktop floor.

Floor Collision state is persisted per-mascot in settings.properties.

---- Multi-Screen Border Transparency ----

When the Multiscreen option is enabled, shared borders between adjacent monitors are treated as transparent by the floor, wall, and ceiling detection systems.  Mascots walk freely across monitor boundaries without being stopped or bounced at the screen edge.  Only the outermost edges of the combined virtual desktop act as real barriers.

---- Movement Velocity Tracking ----

Each mascot tracks its per-tick horizontal and vertical movement delta (lastDeltaX, lastDeltaY), updated every time the anchor position changes.  These values are exposed to XML scripting as mascot.lastDeltaX and mascot.lastDeltaY, allowing actions to make decisions based on current movement speed (e.g. scaling jump height or distance based on running speed).  Both values are also displayed in the debug window.

---- Jump Height Scaling by Speed ----

Jump target distances can be scaled based on the mascot's current movement speed at the moment of the jump.  Using mascot.lastDeltaX in the jump TargetX or TargetY expression allows short hops from a standstill, normal jumps from a walk, and longer jumps from a run.

---- Dynamic Scaling ----

Mascots can now be scaled at runtime using the new Scale action type.  Scale smoothly interpolates the mascot's display size toward a target scale factor each tick.  The scale can be driven by script expressions, for example tying mascot size to CPU load.  Per-image-set scale values are persisted in settings.properties so a mascot remembers its size on next spawn.

XML usage:
  <Action Name="ScaleWithLoad" Type="Embedded"
          Class="com.group_finity.mascot.action.Scale"
          Target="#{mascot.environment.cpuLoad / 100.0 * 1.9 + 0.1}"
          Speed="0.03" />

---- System Sensor Monitoring ----

A new CpuTempMonitor class polls system hardware sensor data every few seconds using a PowerShell script and LibreHardwareMonitorLib.dll.  The following values are exposed to XML scripting via mascot.environment:

  cpuTemp      - CPU Core Average temperature (degrees C)
  cpuLoad      - CPU Total load (%)
  gpuTemp      - GPU Core temperature (degrees C)
  gpuLoad      - GPU Core load (%)
  ramLoad      - Total RAM usage (%)
  batteryLevel - Battery charge level (%)

All values return -1 if unavailable.  Requirements: LibreHardwareMonitorLib.dll and get_cpu_temp.ps1 placed in the Shimeji root folder, and Shimeji running as administrator.

---- Affordance System ----

A new affordance system allows mascots to advertise capabilities and react to each other.  Two new action types support this:

AffordanceStay - A Stay-type action that advertises an affordance string while active, and watches for a nearby mascot advertising a specified trigger affordance.  When a triggering mascot is found within the configured Proximity distance, this mascot automatically transitions to a specified TriggerBehavior.  This enables mascot interactions such as a block waiting to be hit without requiring cross-mascot script calls.

XML usage:
  <Action Name="Stand" Type="Embedded"
          Class="com.group_finity.mascot.action.AffordanceStay"
          Affordance="BlockHittable"
          TriggerAffordance="BlockHitSignal"
          TriggerBehavior="SpawnContents"
          Proximity="80">
    <Animation> ... </Animation>
  </Action>

Manager also exposes getNearestAffordance() and triggerBehavior() methods for scripts that need to locate and interact with other mascots directly.

---- Synchronized Animation ----

A new SyncedStay action type locks a Stay animation's phase to a global wall-clock tick counter, so every mascot instance using the action displays the same animation frame at the same moment, regardless of when each mascot spawned or had its action reset.

XML usage:
  <Action Name="Stand" Type="Embedded"
          Class="com.group_finity.mascot.action.SyncedStay">
    <Animation>
      <Pose Image="/shime1.png" ImageAnchor="64,216" Velocity="0,0" Duration="24" />
      ...
    </Animation>
  </Action>

---- Always On Top Controls ----

Per-mascot and global Always On Top settings have been added.  Each mascot's right-click menu includes an Always On Top toggle that persists per image set in settings.properties.  A global Always On Top setting and a separate Always On Top for the debug window are available in the Settings dialog.

---- Video Player Window Detection ----

A built-in HTTP server (port 41221, localhost only) receives video player position data from a companion browser extension.  The extension tracks the video element in YouTube and Twitch tabs and sends its screen coordinates to Shimeji every 500ms.  Shimeji registers four thin border strips around the player (top, bottom, left, right edges) as synthetic interactive windows, bypassing the normal window occlusion check since the strips sit inside the browser window's bounds.  Mascots interact with the video player edges exactly as they would with any other application window.  The strips update automatically on scroll, resize, zoom change, and page navigation.  Device pixel ratio scaling is applied so the strips are accurate at any browser zoom level or system DPI setting.

See the Browser Extension section above for installation instructions.

---- Performance Improvements ----

The main tick loop in Manager has been optimized: mascot.tick() and mascot.apply() are now combined into a single list pass instead of two separate loops.  The mascot list snapshot is only rebuilt when the mascot list actually changes rather than every tick.

---- Additional Languages ----

Korean (ko-KR) and Korean romanization (kr) language files have been added, bringing the total supported interface languages to: English, Arabic, Catalan, German, Spanish, Finnish, French, Croatian, Italian, Japanese, Korean, Dutch, Polish, Brazilian Portuguese, European Portuguese, Romanian, Russian, Serbian, Vietnamese, Simplified Chinese, and Traditional Chinese.
