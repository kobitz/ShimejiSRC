# Credits

koaibo stands on a lot of other people's work. Thank you to all of them.

## Shimeji lineage

- **Yuki Yamada / Group Finity** - created the original *Shimeji* desktop mascot engine.
- **Shimeji-ee Group** - the English-language *Shimeji-ee* fork this project started from.
- **Kilkakon** ([kilkakon.com](https://kilkakon.com)) - maintained Shimeji-ee and wrote many of the engine features this fork builds on (the 1.0.23 actions, multipage splash, and more). Their licence asks that Kilkakon and the original authors be credited; see `licence.txt` and `originallicence.txt`.
- **Ko** ([github.com/kobitz](https://github.com/kobitz)) - this fork: the AI assistant layer, voice, sensors, situational awareness, agent tools, shop and installer. Code written with help from Claude (Anthropic).

## Libraries bundled in the jar

- **JNA** (Java Native Access) - Win32 / native calls.
- **JNativeHook** - global keyboard and mouse hooks.
- **Nashorn (standalone)** - the JavaScript engine for XML conditions.
- **ASM** - bytecode library used by Nashorn.
- **NimROD Look and Feel** - Swing theme.
- **AbsoluteLayout** (NetBeans) - settings window layout.

## Local AI and data services

- **Ollama** - runs the models locally.
- **Gemma** (Google) - default chat/vision model (`gemma4`); **nomic-embed-text** (Nomic AI) - drive search embeddings.
- **faster-whisper / OpenAI Whisper** - speech recognition.
- **Open-Meteo** - weather, no API key.
- **LibreHardwareMonitor** - CPU temperature sensor in `TempSensor.exe`.

## Fonts

- **Ubuntu** and **Ubuntu Mono** (Canonical / Dalton Maag, Ubuntu Font Licence) - bubble and readout text.

## Mascots and the characters they portray

Every mascot's artwork belongs to its artist, and every character belongs to its rights holder. Characters shipped or referenced here include Hollow Knight's Hornet and Mosscreeps (Team Cherry), Holo from *Spice and Wolf* (Isuna Hasekura, Keito Koume), Paimon from *Genshin Impact* (HoYoverse / miHoYo), and 2B from *NieR: Automata* (PlatinumGames / Square Enix). This is a fan project; no endorsement by any of them is implied.

**Sprite artists: TODO (Ko to fill in).** Name the artist of each bundled mascot (2B, Campfire, Holo, Hornet, Mosscreep, Mossfly, Paimon) here, with a link to where they published it, before the next release. Only mascots you are allowed to redistribute should ship (`installer/mascots.txt`).

**Get Mascots** never re-hosts anything: mascots listed from Cachomon and other creators open the creator's own page. Each entry shows its creator; please support them there (many are on Patreon).

## Licence

The fork's own code is MIT (see `LICENSE`). Upstream notices are kept in `licence.txt`, `originallicence.txt` and `originalreadme.txt`.
