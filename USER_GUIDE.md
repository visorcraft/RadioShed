# RadioShed User Guide

> Your music turned into stations. No subscriptions, no telemetry, internet optional.

This guide covers the user-facing features of RadioShed. For an overview of the
architecture, see [README.md](README.md). For roadmap and historical decisions,
see [SPEC.md](SPEC.md). For common confusion points, see [FAQ.md](FAQ.md).

---

## Getting started

### First launch

The onboarding flow walks you through:

1. **Welcome** — short intro card. "Skip tour" jumps straight to Stations Home.
2. **Permissions (Android only)** — RadioShed needs **All-files access** to
   read music outside the app's private storage. The toggle goes through
   Android's per-app settings; the app re-checks the permission every time
   it returns to the foreground.
3. **Folder** — pick a folder containing your music. The default is
   `/storage/emulated/0/Music`; tap **Browse for folder** to pick something
   else. The display name auto-fills from the chosen folder until you type
   over it.
4. **Scan** — RadioShed walks the folder, reads tags, indexes albums and
   artists. Hundreds of tracks scan in seconds; tens of thousands take a
   minute or two on the first pass. Subsequent rescans only touch changed
   files.
5. **Done** — preset stations are seeded and you're dropped into the app.

If you skip the tour from Welcome, RadioShed still seeds preset stations
so you don't land on an empty Stations Home.

### What's a "station"?

A station is a rule for picking what to play next. The built-in stations cover
the obvious cases:

- **All Music** — every track in your library, shuffled.
- **Latest Hits** — recently-added tracks, weighted toward newer.
- **Discover** — under-played tracks (Smart Shuffle).
- **Forgotten Gems** — high-rated tracks you haven't heard recently.
- **Decade stations** — `2020s`, `2010s`, etc., based on album year.
- **Liked Songs** — auto-populated from the heart toggle.

You can edit any station's source rule, layer multiple sources together
(e.g. "tracks from the Folk folder, *and* tracks rated 4 stars or higher"),
or build entirely custom stations from scratch via Stations Home → "+".

---

## Library

### Folder management

Settings → Library Settings shows every registered folder. Per-folder you can:

- **Edit** — rename, toggle recursive scan, change include/exclude globs.
  Tap **Test rule** to preview what tracks the current globs would
  filter — the preview shows up to 10 example matches per bucket so you
  can verify before saving (saving triggers a rescan).
- **Color** — assign an accent color shown on the folder card and on
  station chips that filter by this folder.
- **Rescan** — re-walks the folder and updates the DB. Touched-files-only
  is the default; the scanner skips files whose mtime hasn't changed.
- **Delete** — un-registers the folder. Tracks under it are marked
  unavailable in the DB but their ratings/likes/playlist memberships
  survive — re-add the folder later and the DB picks up where it left
  off.

### Glob patterns

Include and exclude globs let you scope what RadioShed indexes. Syntax:

- `*` matches any run of characters except `/`
- `**` matches any run including `/`
- `?` matches one character (not `/`)

Examples:

- `*.flac` — only FLAC files
- `**/Spoken Word/**` — anything under a "Spoken Word" subfolder (good
  for excluding audiobooks)
- `**/*.m4b` — exclude every `.m4b` file (audiobook format)

Glob patterns are matched against the path **relative** to the folder root,
so a glob like `Music/**` doesn't need to know where on disk your library
sits.

### Library Browser

The Library tab has four sub-tabs: **Songs**, **Artists**, **Albums**,
**Genres**. Each supports sort + filter + scroll-to-top.

- **Songs** sort: title, recently added, play count, rating
- **Artists** sort: name, recently added, total play count
- **Albums** sort: title, year, artist
- **Genres** are lower-cased and de-duped on scan; tap one to see all
  matching tracks

Tap any track to play it; long-press for the rich context sheet (Play
next / Add to queue / Add to playlist / Go to album/artist / View lyrics
/ Track info / Share / Set as ringtone).

### Find duplicates

Library Settings → **Find duplicate tracks** groups tracks by tag fuzz
(lowercased artist + title + duration to the second). Surfaces re-rips
and accidental re-imports. Tap a row to open its parent folder in the
in-app file browser. No delete action — the user picks manually.

### Tag editor

Track Info → **Edit tags…** opens a dialog for title, artist, album,
album-artist, genre, year, track #, disc #. Writes commit directly to
the file via jaudiotagger; on success, the folder rescans automatically
so the library DB picks up the new metadata.

Out of scope for v1: multi-value tags, custom TXXX/Vorbis frames, lyrics
writes. (See FAQ for what to do if you need those.)

### Batch tag editor

Library → Songs tab → long-press any track and choose **Select** from
the context sheet to enter **selection mode**. Taps then toggle checkboxes
instead of playing; a bottom bar shows the count and an **Edit tags**
button.

The batch editor merges the selected tracks' tags:

- **Uniform** — all tracks share the same value; the field is pre-filled.
- **Mixed** — placeholder shows *"Multiple values"*; leave it untouched
  and those tracks keep their original values.
- **Empty** — placeholder shows *"Empty"*; same rule — untouched means
  unchanged.

Only fields you actually edit are written. After saving, the dialog
shows a summary count (*"47 written, 2 failed"*). On success, every
affected folder rescans automatically so the library DB updates.
Switching to another tab clears the selection.

---

## Playback

### Now Playing

Tap the MiniPlayer to expand. Now Playing shows:

- **Artwork** — tap to view fullscreen.
- **Title / artist / album** — artist and album are tappable links to
  their detail screens.
- **Transport** — replay 10s / play-pause / forward 10s. Long-press
  replay/forward for fast scrub.
- **Queue button** — opens the Up Next sheet. Badge shows queue depth.
- **Heart** — adds to Liked Songs.
- **Star** — five-star rating.
- **Volume / EQ / shuffle / repeat** — at the bottom.
- **Tap progress bar** — seek-to-time dialog.
- **Long-press play/pause** — toggles shuffle for the current session.
- **Long-press queue button** — saves the current queue as a playlist.

### Fullscreen visualizer

Open the visualizer from the equalizer icon in Now Playing's bottom action bar
(on compact phones, use More → **Open visualizer**). It runs full-bleed over the
player, keeps the display awake, and continues across track changes.

Choose among nine distinct renderers: **Prism Spectrum**, **Neon Oscilloscope**,
**Radial Bloom**, **Aurora Ribbons**, **Bass Ripples**, **Hyperspace**,
**Spectral Waterfall**, **Analog VU**, and **Vinyl Turntable**. Vinyl rotates
with the music, shows track progress on its outer rim, and moves its tonearm as
the track plays. Bloom, Ripples, and Vinyl incorporate available album artwork
at the center of the light show. Five palettes, speed and intensity controls,
and optional 20-second auto-cycling persist across launches and backup / restore.

A Musical Director watches rolling energy for sustained builds and coordinated
drops, adding gentle camera movement, scene swells, and staggered impact rings
without changing your settings. Desktop ambient mode includes deterministic
musical arcs so it demonstrates the same choreography without pretending to
sample live audio.

- **Live audio (Android):** tap **Ambient · enable live audio** and grant
  microphone access to analyze the audible ExoPlayer session. Permission denial
  or an unsupported OEM effect falls back to generated visuals without
  interrupting playback. RadioShed does not record or save microphone data.
- **Ambient (desktop / unsupported devices):** deterministic, track-seeded
  visuals. The badge always says when visuals are generated rather than sampled
  from live audio.
- **Controls:** tap/click to show or hide them; double-tap to play or pause.
  Swipe left or right while controls are hidden to change scene. Controls
  auto-hide after inactivity. Use the floating deck for transport, direct mode
  selection, palettes, auto-cycle, speed, and intensity. Open menus stay visible
  until dismissed.
- **Keyboard / remote:** **V** or Left/Right changes mode, **P** changes palette,
  **A** toggles auto-cycle, **H** shows or hides controls, **Space** / **Enter**
  plays or pauses, and **Esc** exits. When controls are hidden, D-pad Up/Down
  adjusts volume. With controls visible, D-pad navigation reaches every button
  and slider.
- **Reduced motion:** follows the existing Display setting, slowing animation,
  reducing particles, disabling impact rings, camera drift/punch, beat flashes,
  scene light sweeps, and auto-cycle. Scene changes use a short fade only.

### Up Next

Drag-to-reorder the priority queue (the "Play next" / "Add to queue"
items the user added manually). Tap the X to remove. Long-press a row
for the same track context sheet as elsewhere.

### Playback rules

Each station has a playback rule (Smart Shuffle, weighted, sequential,
etc.). On Smart Shuffle, RadioShed avoids same-artist or same-album
sequences and biases toward under-played tracks. You can tune the
weighting per-station in the Station Editor.

### Crossfade and gapless

End-of-track fade-out is configurable in Settings (0–10 s, default off).
Per-station crossfade is configured in the Station Editor:

- **Off** — no overlap; the end-fade setting applies if configured.
- **Timed** — fixed overlap in seconds (1–12 s) with a choice of curve
  (Equal Power, Linear, S-curve).
- **Smart** — lets the engine decide overlap based on track content
  (default).

Gapless is on by default and handled at the engine level. Both features
use a dual-player architecture on Android (overlapping players) and
libvlc gapless on desktop.

### Sleep timer

MiniPlayer or Now Playing → sleep-timer button. Set minutes; +15 to
extend; toggle "stop vs pause" on expire. The MiniPlayer shows a
countdown badge while a timer is active.

### Drive Mode

Settings → Drive Mode toggle. Renders large transport buttons across
MiniPlayer and Now Playing for in-car use.

**Auto-triggers** — Drive Mode can turn on automatically:

- **Bluetooth device** — pick a paired device; when it connects, Drive
  Mode activates.
- **Schedule** — set a daily time window (e.g. 07:00–09:00 for the
  commute). Windows can cross midnight.

Auto-exit is configurable: when the trigger ends (device disconnects or
 the window closes), Drive Mode either turns off automatically or stays
on until you exit manually. The controller only acts on *edges* — a
manual toggle while no trigger is active won't be overridden.

---

## Stations

### Stations Home

Tile grid of every station. Long-press a tile for the station context
sheet (Play / Edit / Duplicate / Reset stats / Pin / Hide / Delete).
Tile size is configurable (S / M / L / XL).

- **Pull to refresh** rescans every registered folder.
- **Filter input** appears when you have many stations.
- **Surprise** is a one-tap shuffle across all stations; long-press for
  "Shuffle 25" — a 25-track playable queue.

### Station Editor

Stations Home → "+" (new) or long-press a custom station → Edit.
The editor has four sections: Identity, Color, Source, Playback.

The **Source** section is where a station gets its rule for picking
tracks. One of four types per station:

- **Folders** — pick one or more music folders. Each selected folder
  shows as a chip with its display name and full path. Tap
  "Add folder…" to open the folder browser and pick another. If the
  folder you pick isn't in your Library yet, it gets queued as a
  pending chip ("Will be added to your Library and scanned on save")
  — when you tap Save, RadioShed inserts the folder into your
  Library, runs a scan with inline progress
  (`"Scanning Music (2/3): 247 tracks"`), then closes the editor.
  Already-scanned folders skip the scan.
- **Smart Query** — pick a kind (Newest, Most Played, Highest Rated,
  Liked Songs, Adaptive Mix, Forgotten Gems, …) and a track limit.
  Best for "Top 100 tracks I've never played" style stations.
- **Tag Filter** — pick a tag key from the dropdown
  (`GENRE`, `ALBUM_ARTIST`, `ARTIST`, `MOOD`, `LANGUAGE`, `LABEL`,
  `GROUPING`, `COMPOSER`, `YEAR`, `COMMENT`) or select "Custom…" to
  type any tag your files use. Type values comma-separated.
  Toggle between **Match ANY listed value** (track needs one of
  them) and **Match ALL listed values** (track needs every one).
  Matching is case-insensitive, so `blackpink` matches files
  tagged `BLACKPINK` or `BlackPink`. `ALBUM_ARTIST` and `ARTIST`
  are special-cased to match against your library's artist
  records (not just the raw tag), which is what most people
  actually want — a track from a single-artist album doesn't
  need an explicit `ALBUM_ARTIST` tag to show up.
- **Path Pattern** — glob include / exclude patterns matched
  against each track's path. `*` matches any chars within a
  name; `**` matches any directory depth. Useful when files
  are organized but not at folder boundaries (e.g. all `*.flac`
  anywhere under a `Live/` subdirectory).

The **Playback** section controls how the station picks the next
track within whatever the Source rule resolved:

- **Play order** — Sequential, Shuffle, Smart shuffle (with
  no-repeat memory), Weighted random.
- **Weighting** — Uniform, Favor recent, Favor under-played,
  Favor higher-rated.
- **No-repeat memory** — don't replay the last N tracks (0–50).
- **Album spacing** — skip same-album picks for N tracks
  (small libraries: keep this low or playback starves).
- **Artist spacing** — skip same-artist picks for N tracks.

If a station ends up matching zero tracks (rule is too narrow,
typo in a tag value, sub-folder you pointed at is empty), a
banner appears above the MiniPlayer:
`"<station>" matched 0 tracks. Edit the station's rule or scan more
music.` Tap the banner to dismiss.

### Station Detail

Per-station screen with header card, source-rule summary, candidate
count, pinned tracks, top-played, recently-played sections, and the
station's playback history.

### Pinned tracks

Pin tracks to a station so they always appear regardless of the source
rule. Useful for "this album always plays on the Folk station even
though it's tagged as Indie".

### Drag-to-reorder

Stations Home → menu → Reorder. Drag-and-drop. Order persists across
launches.

---

## Widgets (Android)

### Station Launcher

A home-screen widget with up to **6 station tiles**. Long-press the home
screen → Widgets → RadioShed → Station Launcher. On first placement a
configuration screen opens where you pick a station for each slot; tap
an empty slot to assign it, tap a filled slot to change it.

Tapping a tile launches RadioShed and immediately starts that station.
The widget artwork refreshes when the underlying station changes, but
not in real time — swipe away or wait for the next system refresh if
a station's art looks stale after an edit.

### Now Playing

A 4×1 widget showing the current track, play/pause, and next. Tapping
the track body opens the app. Artwork and transport state update on
every track change.

---

## Lyrics

Now Playing → lyrics button. RadioShed tries, in order:

1. **Embedded** — USLT (ID3v2), `LYRICS` Vorbis comment, `©lyr` (M4A).
   Synced ID3v2 SYLT frames are picked up too.
2. **Local sidecar** — `track.lrc` next to the audio file.
3. **LRClib** — community-curated synced lyrics, fetched only if
   "Auto-fetch lyrics" is on AND the connectivity policy allows.

### Lyrics screen

- **Synced highlight** — current line scrolls into view.
- **Tap a line** — seeks playback to that timestamp.
- **Long-press a line** — copies that line to the clipboard.
- **Pinch** — adjust font size.
- **A- / A+** — same as pinch but explicit.
- **Sync offset slider** — shift lyrics ±5s to fix a mistimed file.
- **Search** — find a phrase across the lyrics.
- **Source** — shows where the lyrics came from (Embedded / .lrc /
  LRClib).
- **Manual tag-fix retry** — if LRClib didn't find anything, override
  artist/title and retry.
- **Copy all** — copies the full lyrics body.

---

## Equalizer

Settings → Equalizer.

- 5-band system equalizer (more bands deferred — see SPEC §7).
- Built-in presets (Bass / Treble / Vocal / Pop / Rock / etc.).
- Custom presets — save your own gain curves.
- Bypass toggle.
- A/B compare — hold a preset on slot B and toggle between A (current)
  and B for instant comparison.
- Pre-amp slider for ReplayGain headroom.
- Reset all bands to 0.
- Copy gains as JSON / Import preset JSON.

---

## Backup & Restore

Settings → Backup & Restore.

### What's backed up

- Stations (custom only — built-ins re-seed on a fresh install)
- Playlists (track membership by file path)
- Ratings (1–5 stars)
- Liked tracks
- Listening history
- Settings (every user-toggle: theme, font scale, EQ presets, etc.)
- Album artwork blobs (in `.rsbak` zipped backups; the legacy `.json`
  format is plain JSON without artwork)

### Format

New exports produce `radioshed-backup-<ts>.rsbak` — a zip containing
`manifest.json` plus `artwork/<sha256>` blobs. Restore auto-detects zip
vs plain JSON, so old `.json` backups still work; you can hand-edit a
`.rsbak` by extracting it with any zip tool.

### Auto-backup

Enable in Settings. Runs weekly on app launch. Retention cap is
configurable (default 8). Older backups beyond the cap are pruned
automatically.

### Sharing a backup

Backup row → Share. Sends the file via Android's share sheet (Gmail,
Drive, etc.). Useful for moving to a new device.

### Importing from another device

Backup screen → **Import…** → system file picker. Pick the file
(`.rsbak` or `.json`). The Restore? dialog previews what's inside
before committing.

---

## File Manager

Reachable from Track Info → **Open folder**. Independent of the
third-party file managers Android ships with — RadioShed renders its
own UI, so behavior is consistent across OEMs.

### Capabilities

- Breadcrumb top bar (each segment tappable for one-tap-up nav)
- Folder header with file/folder counts + Play All / Shuffle All
- Long-press a folder for context sheet (Play all / Shuffle all / Add
  as music folder / Pin to bookmarks / Copy path)
- Long-press a file for rich context sheet (Play / Play next / Add to
  queue / Add to playlist / Copy path / Show full path / Share /
  Select…)
- Sort: Name A→Z / Name Z→A / Recently modified / Largest first
- In-page search filter
- Quick-jump bookmarks (presets + user-pinned, up to 12 pinned)
- Multi-select mode (long-press file → "Select…" → bulk add to queue,
  shuffle, etc.)
- Track duration overlay when a file is indexed in the library

---

## Search

Top bar search across the whole library.

### Scope chips

- **All** — tracks, artists, albums together
- **Tracks** / **Artists** / **Albums** — narrow to one bucket
- **Liked-only** — restrict to liked tracks

Scope persists across keystrokes and across sessions.

### Search DSL

For power users, queries can use field syntax:

- `artist:Hendrix` — match artist name
- `album:abbey road`
- `title:hey jude`
- `genre:rock`
- `year:1969` — exact year
- `year:>1980` — after 1980
- `year:1990-1999` — decade range
- `format:flac`

Combine field terms with free text:

```
artist:Beatles year:>1965 love
```

### Recent searches

Recent queries appear as chips below the input. Tap to re-run; tap the
X on a chip to remove that one entry; "Clear all" with confirmation
purges them.

### Search history voice input

Tap the mic icon to dictate a query. Result lands in the input; press
Enter or the keyboard's Search action to submit.

---

## Stats

Settings → Listening Stats.

- **Total listened** with this-week-vs-last-week delta
- **Top artists / albums / tracks / stations** for the selected window
- **30-day timeline** (configurable window: 7d, 30d, 90d, 365d)
- **Hour-of-day** distribution
- **Weekday** distribution
- **Top genres**
- **Listening streak** (consecutive days with at least one play)
- **Most-skipped** tracks
- **Completion rate** tile
- **Avg tracks/day**

---

## Listening History

Settings → History. Every track-finish event with timestamp.

- **Filter by station / search / date range**
- **Date-bucket headers** ("Today", "Yesterday", "Mar 4", etc.)
- **Continue session** — long-press a row → "Continue from here" plays
  forward from that point.
- **Granular trim** — Clear last hour / Clear last 24h / Clear all
  (each with confirmation).
- **Export to CSV** — for offline analysis.
- **Long-press → copy artist – title** — paste into any chat.

---

## Theme & display

Settings → Display.

- **Theme**: System / Light / Dark / AMOLED-black
- **Material You** dynamic color (Android 12+)
- **Font scale**: XS, S, M (default), L, XL, XXL
- **Reduce motion** — disables non-essential animations
- **Now Playing layout** — Auto / Compact / Expanded / Three-pane
  (CarPlay-style)
- **Stations Home tile size** — S / M / L / XL
- **Stations Home sort** — Custom (drag order) / Name A→Z /
  Name Z→A / Most played / Least played / Recently played

---

## DB browser (developer)

Long-press the version row in Settings → About to open the read-only
database stats screen. Lists row counts for every major table —
useful for diagnosing scanner/playback weirdness without `adb pull`.

---

## Privacy & data

- **No telemetry, ever.** Nothing about your listening leaves the
  device unless you explicitly share it.
- **Internet** is optional — only used for lyrics fetch (LRClib),
  artwork fetch (Cover Art Archive), and Voice Search. Each can be
  disabled in Settings → Connectivity.
- **Connectivity policy**: Always / Wi-Fi only / Ask each time /
  Never — applies to every network call by default.
- **Per-service overrides**: Cover Art Archive and Lyrics (LRClib)
  each have an independent Inherit / Always / Wi-Fi only / Never
  picker so you can, say, allow album-cover fetches on cellular
  but never fetch lyrics off Wi-Fi (or vice versa).
- **Compact database**: Settings → Online → "Compact database"
  reclaims disk space SQLite is holding after a "Clear all caches"
  pass. Safe (full transactional rollback) and runs in 1-2 seconds
  on a typical library.
- **Permissions**: only `MANAGE_EXTERNAL_STORAGE` (Android) for
  reading music files.
- **Source code**: GPL-3.0-only. See the `LICENSE` file.

---

## Troubleshooting

See [FAQ.md](FAQ.md) for the most common gotchas.

For bug reports: Settings → About → "Copy build info", paste into the
issue, attach your crash log if relevant (Settings → Crash log →
Share).
