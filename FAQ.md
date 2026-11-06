# RadioShed FAQ

Common confusion points, in rough order of how often they bite.

---

### "I added a folder, but only some files appear"

Three things to check, in order:

1. **The scanner ran**. Library Settings → folder card → tap rescan.
   The progress bar shows what it's reading.
2. **Glob filters**. Edit folder → check Include / Exclude patterns.
   The default is no filter. If you have an include glob, only matching
   files are indexed. Tap **Test rule** to preview which files the
   current globs would filter.
3. **File format**. RadioShed indexes mp3, m4a/aac, flac/alac, ogg/opus,
   wav/aiff. WMA, APE, WV, DSD, MPC are recognized but require an FFmpeg
   build that's not bundled today (deferred). Files with unrecognized
   extensions are silently skipped.

If the scanner finished but a track is still missing, copy its full path
and grep the listing in Settings → About → long-press version →
Database stats → tracks count. If the row count rose but the track is
absent, the file probably has a corrupt header — open Track Info on a
sibling file and check the format/duration is right; if not, jaudiotagger
choked on the tag block and the scanner skipped it.

---

### "All-files access keeps un-granting itself"

Some Android OEM ROMs (heavily-skinned Samsung / Xiaomi / Vivo builds)
revoke `MANAGE_EXTERNAL_STORAGE` after a few weeks of inactivity. There
is no app-side fix — Android's permission auto-revoke is a system
feature.

Workaround: open RadioShed at least once every few weeks, or disable
"Remove permissions if app isn't used" in
*Settings → Apps → RadioShed → Permissions → All files access*.

---

### "I edited tags but the track still shows the old artist/album"

The edit writes to the file, then triggers a folder rescan. If the
rescan didn't catch up:

1. Wait a moment — the dialog shows "Rescanning folder…" while it
   runs. Closing the dialog before the rescan finishes is fine; the
   rescan keeps running in the background.
2. If still wrong, manually rescan the folder (Library Settings →
   folder card → rescan icon).

If the file's mtime didn't change (some tag editors preserve it), the
scanner skips it. Tag-write commits via jaudiotagger DO update mtime,
but if you edited the file with a third-party tool the mtime might be
preserved — easy fix is to `touch` the file or just resave it.

---

### "Batch tag editor shows 'Multiple values' — will it overwrite everything?"

No. The placeholder *"Multiple values"* (or *"Empty"*) is just a hint.
Only fields you actually type into are written; untouched fields are
skipped entirely, so every track keeps its original value for that tag.

If you want to set the same value across all selected tracks, type it
into the field — the new value is applied to every selected track on
Save.

---

### "Glob `**/Music/**` doesn't match my Music folder"

Globs are matched against the path **relative to the folder root**. If
you registered `/storage/emulated/0` as a folder and want to match
"Music folder under it", use `Music/**` — the leading slash is implicit
since paths under the root never start with `/`.

If you registered `/storage/emulated/0/Music` directly, the glob is
matched against everything under it (e.g. `Albums/Foo.flac`) — so
`**/Foo/**` works to match a Foo subdirectory regardless of nesting.

---

### "The notification heart is filled but the track isn't liked"

You're on a build older than `2026-05-04u`. Update — the heart now
reflects state correctly via `setCustomLayout` updates on track
transitions and toggles.

---

### "I can't pin more than 12 folders to bookmarks"

Hard cap by design. The bookmarks row would otherwise scroll forever
on a folder browser screen. If you need more, use **Add as music
folder** instead — registered folders aren't capped.

---

### "Lyrics show 'No lyrics found' but the track has a `.lrc` file"

The `.lrc` filename must match the audio filename exactly (different
extension):

- `Hey Jude.flac` ↔ `Hey Jude.lrc` ✓
- `Hey Jude.flac` ↔ `Hey-Jude.lrc` ✗ (different basename)

Sidecar lookup is case-sensitive on Android, so `Track.lrc` and
`track.lrc` are different files.

If LRClib is your fallback, check Settings → Connectivity policy and
Settings → Auto-fetch lyrics. If both are on and you still get nothing,
LRClib genuinely doesn't have synced lyrics for that track — you can
override the search via Lyrics → "..." → Tag-fix retry.

---

### "Duplicate detection missed an obvious duplicate"

The detector groups by `lower(artist) + lower(title) + duration_seconds`.
If your two copies have:

- Different tags (typo, alt artist name) — won't group
- Different durations more than a second apart (sometimes happens after
  re-encoding) — won't group

The fix is to fix the tags (use the in-app tag editor on one of the
copies) and re-run detection. Detection has no false-positive control;
a remaster vs original can show up as a "duplicate" if the tags collapse —
that's why there's no in-app delete action.

---

### "A Tag Filter station with `ALBUM_ARTIST` returns nothing (or used to crash)"

Before build `2026-05-18a` this could hard-crash the app — fixed in
that build.

If you typed values that didn't match exactly (e.g. `blackpink` when
the files are tagged `BLACKPINK`), the station resolved to zero
tracks and Smart-shuffle stations crashed trying to pick from an
empty pool. Both halves are fixed:

- Tag matching is now case-insensitive across the board, so
  `blackpink` matches `BLACKPINK` and `BlackPink`.
- `ALBUM_ARTIST` and `ARTIST` route to your library's artist records
  rather than the raw tag table — useful because single-artist
  albums usually don't carry an explicit `ALBUM_ARTIST` tag, but you
  still want them to show up.
- An empty pool no longer crashes. If the rule legitimately matches
  zero tracks you'll see a banner above the MiniPlayer:
  `"<station>" matched 0 tracks. Edit the station's rule or scan
  more music.`

If you're on an older build and want the fix, update to
`2026-05-18a` or later.

---

### "Folders station only shows my top-level music folder, not the sub-folders I want"

Before build `2026-05-18a` the Folders source rule only listed
already-registered Library folders — to use a sub-folder for a
station, you first had to add it as a Library folder via Library
Settings → gear → Add Folder. That's not obvious.

Now the Station Editor's Folders source lets you browse for any
folder directly. If you pick a path that isn't in your Library yet,
it shows up as a chip labelled `"Will be added to your Library and
scanned on save"`. When you tap Save, RadioShed inserts that folder
into your Library, runs a scan with inline progress, then closes
the editor. Folders already in your Library skip the scan.

If you want a station that spans a parent folder AND some of its
sub-folders, you can — pick each one separately. Tracks scanned by
multiple folder roots end up associated with whichever folder root
scanned them last; no duplicates, but the per-folder track count
on Library Settings can drift from reality until you next rescan
the parent.

---

### "The Stations Home is empty after the first launch"

If you skipped the tour from Welcome on a clean install of an older
build, the preset seeder didn't run. Update to `2026-05-04u` or later.
On a current build, reseed via:

- Settings → Reset all settings to defaults (nuclear, also clears
  preferences), or
- Manually create stations via Stations Home → "+"

The seeder is idempotent — restarting the app after a missed seed will
NOT re-seed. The only way to trigger it is via the path above.

---

### "Backup file from the new app won't open in the old app"

Right — the new format is `.rsbak` (zipped), the old is `.json` (plain).
The new app reads both formats; the old app reads only `.json`. To
share a backup with someone on an older build, you can:

1. Extract the `.rsbak` with any zip tool (it's a standard zip).
2. Hand them the `manifest.json` from inside.

Going forward, the new app produces `.rsbak` because it includes
artwork blobs. Plain `.json` exports stop happening as soon as the
platform ships a packager (Android always; iOS not yet).

---

### "I deleted a track from disk but it's still in the library"

The scanner detects deleted-from-disk tracks by diffing the post-scan
file list against the DB. If you delete a file outside RadioShed and
DON'T trigger a rescan, the DB still has the row and Stations Home
will try to play it (and fail with "could not load").

Fix: rescan the folder. Library Settings → folder card → rescan icon.
The diff marks the missing files unavailable; ratings and likes
survive in case you later restore the file.

If you want them gone for good, Settings → Reset all settings clears
everything; otherwise just leave them — unavailable rows don't appear
in any UI and don't get queued.

---

### "Drive Mode auto-trigger from BT didn't fire"

Check three things:

1. **The trigger is configured.** Settings → Drive Mode → "Add trigger" →
   pick a paired Bluetooth device or set a schedule window. Triggers are
   saved per-device; if you renamed or re-paired the car unit, re-add it.
2. **Permission.** Android 12+ needs **Nearby devices** (BLUETOOTH_CONNECT)
   granted for MAC matching. RadioShed asks when you open the trigger
   picker; if you denied it, grant it in system Settings.
3. **Auto-exit.** If auto-exit is OFF, the trigger's *falling* edge
   (disconnect / end of window) is ignored and Drive Mode stays on.
   Turn auto-exit ON if you want it to shut off when the trigger ends.

Note: speed-based triggers (e.g. "above 25 km/h") are deferred and not
built yet. Bluetooth and schedule triggers are the ones that ship today.

---

### "Where are my backups stored?"

Android: `<app filesDir>/backups/`, which surfaces under
`Internal storage > Android > data > com.visorcraft.radioshed >
files > backups` in your phone's Files app on devices that surface
app-private dirs.

You can also share any backup via Settings → Backup & Restore →
backup row → Share, which sends the file out via Android's share
sheet.

---

### "What's `manifest.json` inside a `.rsbak`?"

The `.rsbak` format is a regular zip with two kinds of entries:

- `manifest.json` — the bundle data (stations, playlists, ratings, etc).
- `artwork/<sha256>` — raw image bytes, content-addressed.

Any zip tool will open it. The manifest is the same JSON shape the old
plain-bundle format used, so you can edit it manually if you really
need to (rezip with manifest.json as the first entry to preserve the
"diagnostic-friendly" ordering).

---

### "The DB browser is empty / shows zeroes"

Settings → About → long-press version row → Database stats. If every
count is "?", the DB couldn't be opened — check Settings → Crash log
for an underlying exception.

If counts are "0" but you have music in the app, you're probably
looking at a freshly-restored backup and the rescan hasn't run yet.
Library Settings → rescan all folders.

---

### "The Station Launcher widget shows empty slots / old artwork"

The widget updates when the system asks for a refresh (typically on
placement, on boot, and when the app explicitly signals an update).
It does **not** live-update in real time when you edit a station.

To force a refresh: remove the widget and re-add it, or restart the
device. If a slot shows "Empty" even though you configured it, open
the widget config again and re-save — the placement flow sometimes
skips the config screen on certain launchers.

---

### "I changed my Android device. How do I move my data?"

Old device:

1. Settings → Backup & Restore → Export. The `.rsbak` file lands in
   the backups dir.
2. Backup row → Share → send the file to yourself (Drive, Gmail, etc).

New device:

1. Install RadioShed.
2. Onboarding: register your music folder, let it scan.
3. Settings → Backup & Restore → Import → pick the `.rsbak`.
4. Restore? dialog — review counts → Restore.

Stations, playlists, ratings, liked tracks, history, settings, EQ
presets, and album artwork all survive. Only your file paths must
match — if your music is at a different path on the new device,
playlist track-membership lookups fall back to suffix matching
(filename only), so most playlists will round-trip even with a
different mount point.

---

### "How do I report a bug?"

1. Settings → About → "Copy build info" — copies a string like
   `RadioShed v0.1.0 (code 1) built 2026-05-04v` to clipboard.
2. Settings → Crash log → if you see your crash, tap Share.
3. Open `https://github.com/visorcraft/RadioShed/issues` and paste.

If the bug isn't a crash (UI weirdness, etc.), describe the steps and
include the build string.

---

### "What license is RadioShed?"

[GPL-3.0-only](LICENSE), the same license as Grexa. You may use, share,
and modify RadioShed under those terms. Other components keep their own
licenses; About → Licenses lists them.

There is no telemetry. Backup data is portable JSON, so a switch to
another player keeps the music and loses only RadioShed's listening
history and ratings interpretation.
