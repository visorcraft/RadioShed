# Head-unit / Drive Mode smoke test

Manual checklist for Android head-unit and steering-wheel key behaviour after Drive Mode changes.

## Prerequisites

- Android device or emulator with a media library scanned
- At least two stations configured
- Drive Mode enabled (Settings → Drive Mode, or auto-trigger via Bluetooth/speed if configured)

## On-screen transport bar

1. Open Now Playing with Drive Mode active — large transport buttons render.
2. Tap **Previous**, **Play/Pause**, **Next** — playback responds without leaving the screen.
3. Tap **Station picker** — full-screen picker opens; selecting a station starts playback.
4. Tap **Voice search** (when available) — speak a station name; best match plays or haptic reject fires.
5. Tap **Mute** — volume drops to silence; tap again restores prior level.

## Hardware / steering-wheel keys

Configure bindings under Settings → Drive Mode → Key bindings.

| Action | Expected |
| --- | --- |
| Previous | Skips to prior track |
| Next | Skips forward |
| Play / Pause | Toggles playback |
| Mute | Toggles user volume between 0 and prior level |
| Station picker | Opens on-screen station picker (same as button) |
| Voice search | Starts voice station match (same as button) |

## Auto Drive Mode triggers

- **Bluetooth:** Connect a configured device — Drive Mode enters; disconnect — exits (when no other trigger holds it).
- **Speed:** Above enter threshold — Drive Mode enters; below exit threshold — exits.
- **Schedule:** Window start/end toggles Drive Mode at configured times.

## Regression notes

- Transport keys must work while the screen is on and RadioShed is foreground.
- UI-only key actions (`station_picker`, `voice_search`) require Now Playing / Drive layout visible to consume [DriveKeyDispatcher](composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/car/DriveKeyDispatcher.kt) events.
