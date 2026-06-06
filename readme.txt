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
12. Browser Extension
13. AI Assistant

==== Homepage ==== 

Homepage: http://kilkakon.com/shimeji

==== Requirements ====

1. Windows 10 or higher (64-bit)
2. Internet connection for first-time setup (models are ~4 GB total)

Java is bundled -- no system Java installation needed.
Run Setup.bat once after extracting; it installs everything else automatically.

==== How to Start ====

First-time setup:
  Right-click Setup.bat and choose "Run as administrator".
  This installs Ollama, Python, faster-whisper, and downloads the AI models.
  You only need to do this once.

To run:
  Double-click Shimeji.exe  (or ShimejiTest.exe for the debug build).

Right-click the tray icon for general options.

Right-click a Shimeji for options relating to it.

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
The behaviors.xml file specifies when Shimeji performs each action.  More detail on this file will /hopefully be added later.
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

==== Trouble Shooting ====

Shimeji takes a LOT of time to start if you have a lot of image sets, so give it some time.  Try moving all but one image set from the img folder to the img/unused folder to see if you have a memory problem.

If the tray icon appears but no Shimeji appear:

1. Make sure you only have image set folders in your img directory.
2. Run Debug.bat for console output -- it will show any startup errors.
3. Check ShimejieeLog0.log for errors.

If the AI assistant isn't responding:
1. Make sure Setup.bat was run and completed successfully.
2. Check that Ollama is running: open a command prompt and type "ollama list".
   If it fails, run "ollama serve" manually.
3. Make sure a <Personality> block exists in the mascot's behaviors.xml.

If voice recognition isn't working:
1. Make sure a microphone is set as the default recording device in Windows sound settings.
2. Check that faster-whisper installed correctly: open a command prompt and run
   "python -c "from faster_whisper import WhisperModel; print('ok')"".
   If it fails, re-run Setup.bat.

==== Changes ====

The following changes have been made from the base Shimeji-ee source.

---- Multi-Window Support ----

The interactive window detection system has been overhauled.  Previously, each mascot performed its own full EnumWindows scan every tick, meaning 12 mascots would trigger 12 separate scans per tick.  Now, WindowsEnvironment.beginTick() runs a single shared EnumWindows scan once per tick, and all mascots read from that shared snapshot.  This significantly reduces CPU overhead when many mascots are running.

Multi-monitor awareness has also been improved.  The work area is now calculated per-monitor using MonitorFromPoint and GetMonitorInfo, allowing Shimeji to correctly respect taskbar positions and work area boundaries on each individual screen in a multi-monitor setup.

---- Global Hotkeys ----

A new HotkeyManager class provides global hotkey support via the JNativeHook library.  Hotkeys are configured in conf/hotkeys.properties and can trigger any named behavior on all mascots or on a specific image set.  Keys support modifier combinations (ctrl, shift, alt, meta) and mouse buttons (MOUSE1-MOUSE5).

A hold-to-loop mode is available by appending !hold to the behavior name.  When a key is held, the behavior plays through cleanly to completion and loops from the start, rather than restarting on every OS key-repeat event.  Releasing the key returns the mascot to its normal behavior on its next natural action completion.

---- Dynamic Scaling ----

Mascots can now be scaled at runtime using the new Scale action type.  Scale smoothly interpolates the mascot's display size toward a target scale factor each tick.  The scale can be driven by script expressions, for example tying mascot size to CPU load.  Per-image-set scale values are persisted in settings.properties so a mascot remembers its size on next spawn.

XML usage:
  <Action Name="ScaleWithLoad" Type="Embedded"
          Class="com.group_finity.mascot.action.Scale"
          Target="#{mascot.environment.cpuLoad / 100.0 * 1.9 + 0.1}"
          Speed="0.03" />

---- System Sensor Monitoring ----

CpuTempMonitor exposes hardware sensor data to XML scripting via mascot.environment:

  cpuTemp      - CPU Core Average temperature (degrees C)
  cpuLoad      - CPU Total load (%)
  gpuTemp      - GPU Core temperature (degrees C, NVIDIA only)
  gpuLoad      - GPU Core load (%, NVIDIA only)
  ramLoad      - Total RAM usage (%)
  batteryLevel - Battery charge level (%)

All values return -1 if unavailable.  CPU temperature is read by TempSensor.exe, a
self-contained companion process that loads LibreHardwareMonitor once at startup and
streams readings every second -- this avoids the overhead of spawning a new process
each tick.  TempSensor.exe requires Shimeji to be running as administrator.  GPU metrics
use nvidia-smi (no admin required).  RAM and battery are read inline with no external
process.

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

---- Performance Improvements ----

The main tick loop in Manager has been optimized: mascot.tick() and mascot.apply() are now combined into a single list pass instead of two separate loops.  The mascot list snapshot is only rebuilt when the mascot list actually changes rather than every tick.

---- Looped Sequences ----

The Sequence action type now supports a Loop attribute.  When Loop="true", the sequence cycles back to its first child action after the last one completes, repeating indefinitely until the behavior ends naturally.  Without Loop="true" (or with Loop="false", the default), a sequence plays through once and stops as before.

XML usage:
  <Action Name="Dragged" Type="Sequence" Loop="true">
    <ActionReference Name="Pinched"/>
    <ActionReference Name="Resisting"/>
  </Action>

---- Nested Sequence Actions ----

Sequence actions can now be nested inside one another to arbitrary depth.  An inline anonymous Sequence can appear anywhere a named ActionReference is expected, allowing complex conditional branching and sub-sequences to be expressed directly in the XML without requiring separately named top-level actions for every intermediate step.  Each nested Sequence supports its own Condition, Loop, and child actions independently.

XML usage:
  <Action Name="ManualJump" Type="Sequence" Loop="false">
    <Action Type="Select">
      <Action Type="Sequence" Condition="${mascot.environment.floor.isOn(mascot.anchor)}">
        <ActionReference Name="Jumping" TargetX="..." TargetY="..."/>
        <Action Type="Sequence">
          <ActionReference Name="Stand" Duration="1"/>
        </Action>
      </Action>
      <ActionReference Name="Fall"/>
    </Action>
  </Action>

---- Additional Languages ----

Korean (ko-KR) and Korean romanization (kr) language files have been added, bringing the total supported interface languages to: English, Arabic, Catalan, German, Spanish, Finnish, French, Croatian, Italian, Japanese, Korean, Dutch, Polish, Brazilian Portuguese, European Portuguese, Romanian, Russian, Serbian, Vietnamese, Simplified Chinese, and Traditional Chinese.

---- Animation Templates ----

Reusable animation definitions can be declared once and referenced by multiple actions,
eliminating copy-paste of identical frame lists.  Declare a template inside <ActionList>:

  <AnimationTemplate Name="FloatLoop">
    <Pose Image="/float1.png" Duration="8" />
    <Pose Image="/float2.png" Duration="8" />
  </AnimationTemplate>

Reference it from any action, supplying per-action overrides:

  <Animation Template="FloatLoop" ImageAnchor="48,128" Velocity="-2,0" />

The ImageAnchor, Velocity, and Duration attributes on the <Animation> element override
the template values for every frame.  The same template can serve different surfaces by
changing only the anchor (floor "48,128", ceiling "48,0", wall "16,128").

---- Per-Pose ImageAnchor ----

ImageAnchor is now a per-pose attribute rather than a per-animation attribute.  This
decouples the collision/render anchor from the image file, meaning one image file can be
used at different anchor positions in different actions without duplicating pixel data in
memory.  The anchor is also the collision point, so surface cling actions (ceiling, wall)
automatically use the correct contact point without separate image variants.

---- FaceDirection on Dragged ----

The Dragged action accepts a FaceDirection="true" attribute (default false).  When true,
the mascot faces the direction the cursor is moving while being dragged, updating only
when the cursor X position actually changes.  Default false preserves the original
behavior for mascots that should not flip during drag.

---- Audio Level Sensor ----

mascot.environment.audioLevel exposes the real-time system speaker RMS (~0-32767) to XML
behavior conditions and script expressions.  Backed by the WASAPI loopback capture.  Use
to trigger behaviors when music is playing, on volume spikes, or for audio-reactive
animations.

Example behavior condition:
  Condition="${mascot.environment.audioLevel > 5000}"

---- Tint Action ----

The Tint action applies a color overlay to a mascot blended with its sprite using alpha
compositing.  Both color and opacity can be driven by JavaScript expressions evaluated
every tick, so the tint updates live without restarting any behavior.  Color channels are
independently lerped toward their targets each tick, producing smooth gradual shifts.

Schema attributes:
  Color        -- Hex color string ("FF0000") or a JS expression returning one
  Opacity      -- Fixed opacity 0.0-1.0; use Target for a scriptable value instead
  Target       -- Scriptable opacity expression (0.0 to 1.0)
  LerpFactor   -- Smoothing per tick (0.0 = no movement, 1.0 = instant; default 0.1)
  Duration     -- How long the action runs in ticks; the tint expression persists after
  ClearOnExpiry -- true to snap-clear the tint when Duration expires

A Tint registered once (e.g. prepended to a StandUp sequence) persists and re-evaluates
its expressions every tick for the lifetime of the mascot, regardless of what behavior
is currently running.

XML usage:
  <!-- Sensor-driven: color and opacity track GPU/RAM load live -->
  <Action Name="TintWithLoad" Type="Embedded"
          Class="com.group_finity.mascot.action.Tint"
          Color="#{(function(){
              var g = Math.max(0, Math.min(255, Math.round(mascot.environment.gpuLoad / 100 * 255)));
              var h = ('0' + g.toString(16)).slice(-2);
              return 'FF' + h + h;
          })()}"
          Target="#{(mascot.environment.ramLoad + mascot.environment.gpuLoad - 50) / 150}"
          LerpFactor="0.08" />

  <!-- Flash: static color for a set duration, then auto-clear -->
  <Action Name="FlashRed" Type="Embedded"
          Class="com.group_finity.mascot.action.Tint"
          Color="FF0000" Opacity="0.4" Duration="25" ClearOnExpiry="true" />

Toggleable behaviors that drive a Tint can use ClearTintOnDisable="true" on the
<Behavior> tag.  When the behavior is unchecked from the right-click menu, the tint
clears immediately; when checked, the behavior starts even if its Frequency is 0.

==== Browser Extension ====

A browser extension is available that allows Shimeji to treat video players on YouTube and Twitch as interactive windows, so mascots can walk along the edges of the video just as they do with normal application windows.

The extension works by detecting the video player element on the page and sending its screen coordinates to a local HTTP server that Shimeji listens on (http://127.0.0.1:41221/video).  Four thin strips are reported along the top, bottom, left, and right edges of the player, which Shimeji registers as window borders.  The strips are automatically removed when the tab is no longer active or when the page is unloaded.

Supported sites: YouTube, Twitch.

---- Installation (Firefox / LibreWolf) ----

1.  Ensure xpinstall.signatures.required is set to false in about:config.
2.  Download the .xpi file.
3.  Rename it to shimeji-video-tracker@local.xpi.
4.  Place it in the extensions folder inside your browser profile directory.
    Your profile directory can be found via about:profiles (Root Directory).
    Create the extensions folder if it does not exist.
5.  Restart the browser.  The extension will be loaded automatically on every launch.

Alternatively, for a temporary install: go to about:debugging, click This Firefox,
then Load Temporary Add-on and select the .xpi file.  This will be removed on restart.

---- Installation (Chrome / Chromium) ----

Chrome requires extensions to be published on the Chrome Web Store to install permanently.
For development or personal use, unpack the extension into a folder and load it via
chrome://extensions with Developer Mode enabled, using the Load Unpacked button.

---- Behavior ----

The extension only reports the video player while its tab is the active tab in the browser.
Switching to a different tab removes the fake window from Shimeji immediately.
Switching to a different application (without changing tabs) keeps the window active,
so mascots remain on the video player even when you are using another program.
Pausing or buffering a video does not remove the window; the position continues to be reported.
Navigating to a different video on YouTube or Twitch is handled automatically.

==== AI Assistant ====

Each mascot can be given a personality and become an AI-powered chat companion that
perceives and reacts to your environment in real time.  The AI layer runs entirely locally
using Ollama -- no internet connection or API key required after setup.

---- Enabling the AI ----

Add a <Personality> block inside the <Information> block of the mascot's behaviors.xml:

  <Information>
    <Name>Hornet</Name>
    <Personality>You are Hornet from Hollow Knight.
      Personality: Stern, terse, dry.
      Speech style: One or two sentences maximum. No filler words.
    </Personality>
  </Information>

If the Personality block is non-empty, assistant mode activates automatically on spawn.
It can also be toggled per-mascot from the right-click menu.

---- Talking to a Mascot ----

Left-click the mascot to open a typed input dialog.
Voice commands also work if a microphone is connected -- just say the mascot's name
followed by your message.  The mascot's name trigger is configured via <VoiceTrigger>
in behaviors.xml (defaults to the image set name).

Click a speech bubble to reply in context.

---- Spontaneous Reactions ----

Mascots observe their environment and comment without being prompted:
  - Active window title (what program you are using)
  - System audio (what is playing through your speakers, transcribed via Whisper)
  - Screen content (what is visible on screen, via a vision model)

Reactions fire at a randomized 45-90 second cadence per channel, with a global cooldown
per channel across all mascots so they do not all speak simultaneously.

---- Memory ----

Each mascot keeps a persistent memory at img/[ImageSet]/conf/memory.json.  It records
facts observed about you, past conversations, and emotional tone.  Every 20 interactions
it asks the AI to distill the exchange history into a concise facts-and-tone summary.

Permanent memories can be saved by the AI mid-conversation using a [REMEMBER:] tag.
These are never overwritten by summarization and are only injected into the prompt when
their keywords match your message.

---- Peer Conversations ----

When one mascot speaks, nearby mascots can overhear and respond, forming chains of
conversation between characters.  Chain probability decreases with depth
({35%, 25%, 15%, 5%}) and a per-pair cooldown prevents immediate re-triggering.
Each mascot builds a separate memory of its relationship with each peer.

---- Timers and Reminders ----

Ask a mascot to remind you of something and it will set a countdown bubble:
  "Remind me to check the oven in 20 minutes."
  "Remind me at 5:30."

Click an active countdown to pause it; click again to resume.  The X button clears it.
When the timer fires the reminder text replaces the countdown and the bubble returns to
full opacity.

---- Weather and Time Context ----

Mascots automatically know the current time and local weather (via Open-Meteo, no API
key needed) and will state accurate values when asked.  Location is auto-detected from
your IP by default; set WeatherLocation in settings.properties to a city name to
override.

---- Screen Queries ----

Ask "what am I looking at?" or "what's on my screen?" and the mascot will capture a
screenshot and describe it using a local vision model (moondream by default, configurable
via VisionModel in settings.properties).

---- Speech Constraints ----

<SpeechRule> -- A hard constraint injected directly into the prompt RULES section,
  more reliably followed than personality prose.  Useful for forced third-person,
  forbidden words, or required speech patterns.  Include WRONG/RIGHT examples for
  best results.

<ThirdPersonRewrite> -- When set to true, first-person pronouns in the AI's response
  are rewritten to the mascot's name by regex post-processing, reinforcing third-person
  speech even when the model slips.

---- Settings ----

The Chat Bubbles tab in the Settings dialog exposes:
  OllamaModel      -- which Ollama chat model to use (default: gemma3:4b)
  OllamaEndpoint   -- Ollama server URL (default: http://localhost:11434/api/generate)
  VisionModel      -- Ollama model for screen queries (default: gemma3:4b)
  BubbleWidth      -- Width of speech bubbles in pixels (default: 180)
  BubbleFontSize   -- Font size in speech bubbles (default: 14)
  WhisperThreads   -- CPU threads for voice recognition (default: half of available)
  WeatherLocation  -- "auto" for IP geolocation, or a city name (default: auto)
