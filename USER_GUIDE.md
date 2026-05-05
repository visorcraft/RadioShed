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

End-of-track fade-out is enabled by default (configurable seconds in
Settings). True dual-player crossfade and sample-accurate gapless are
deferred — see SPEC §7.

### Sleep timer

MiniPlayer or Now Playing → sleep-timer button. Set minutes; +15 to
extend; toggle "stop vs pause" on expire. The MiniPlayer shows a
countdown badge while a timer is active.

### Drive Mode

Settings → Drive Mode toggle. Renders large transport buttons across
MiniPlayer and Now Playing for in-car use. Auto-trigger registry (BT MAC,
speed, schedule) is deferred — manual toggle for now.

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
- **Connectivity policy**: Always / Wi-Fi only / Never. Applies to
  every network call.
- **Permissions**: only `MANAGE_EXTERNAL_STORAGE` (Android) for
  reading music files.
- **Source code**: proprietary. See the `LICENSE` file.

---

## Troubleshooting

See [FAQ.md](FAQ.md) for the most common gotchas.

For bug reports: Settings → About → "Copy build info", paste into the
issue, attach your crash log if relevant (Settings → Crash log →
Share).
