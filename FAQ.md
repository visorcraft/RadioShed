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

Auto-triggers are deferred. The Drive Mode toggle exists today; the
"automatic on BT-X-connected" / "automatic above 25 km/h" / "automatic
between 8am-9am" registry is on the SPEC roadmap but not built. Use the
manual toggle in Settings or NowPlaying for now.

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
3. Open `https://github.com/anthropics/radioshed/issues` and paste.

If the bug isn't a crash (UI weirdness, etc.), describe the steps and
include the build string.

---

### "Why is RadioShed proprietary?"

Distribution control, not user surveillance. The binary is closed
because:

- No F-Droid / AltStore / community sideloads — distribution is via
  authorized direct download (Android) and App Store (iOS, when the
  iOS impl ships).
- App Store / Play Store flavors get the same binary as direct
  download.
- Open-source dependencies are still respected — see the About →
  Credits screen for full attribution.
- Backup data is portable JSON — you're never locked in to RadioShed.
  Switching to another player means losing only the listening history
  / ratings interpretation, not the music itself.

There is no telemetry, ever. The proprietary status is about who
ships builds, not who watches users.
