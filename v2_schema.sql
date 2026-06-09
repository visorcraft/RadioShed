-- Schema as of v2
CREATE TABLE artist (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    sort_name TEXT NOT NULL UNIQUE,
    musicbrainz_id TEXT,
    image_uri TEXT,
    bio TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE artist_alias (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    artist_id INTEGER NOT NULL REFERENCES artist(id) ON DELETE CASCADE,
    alias_name TEXT Not NULL,
    alias_sort_name TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL
);

CREATE INDEX artist_alias_artist_id_idx ON artist_alias(artist_id);

CREATE TABLE album (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    title TEXT Not NULL,
    artist_id INTEGER REFERENCES artist(id) ON DELETE SET NULL,
    sort_title TEXT NOT NULL,
    year INTEGER,
    musicbrainz_id TEXT,
    artwork_uri TEXT,
    track_count INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (sort_title, artist_id)
);

CREATE INDEX album_artist_idx ON album(artist_id);
CREATE INDEX album_sort_idx ON album(sort_title);

CREATE TABLE track (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    path TEXT Not NULL UNIQUE,
    file_name TEXT Not NULL,
    file_size INTEGER Not NULL,
    file_ctime INTEGER Not NULL,
    file_mtime INTEGER Not NULL,
    title TEXT Not NULL,
    album_id INTEGER REFERENCES album(id) ON DELETE SET NULL,
    track_number INTEGER,
    disc_number INTEGER,
    duration_ms INTEGER NOT NULL,
    bitrate_kbps INTEGER,
    sample_rate_hz INTEGER,
    channels INTEGER,
    format TEXT Not NULL,
    replay_gain_track REAL,
    replay_gain_album REAL,
    replay_gain_track_peak REAL,
    bpm REAL,
    musicbrainz_id TEXT,
    acoustid_fingerprint TEXT,
    cue_offset_start_ms INTEGER,
    cue_offset_end_ms INTEGER,
    is_available INTEGER Not NULL DEFAULT 1,
    added_at INTEGER Not NULL,
    updated_at INTEGER Not NULL
);

CREATE INDEX track_album_idx ON track(album_id);
CREATE INDEX track_added_idx ON track(added_at);
CREATE INDEX track_format_idx ON track(format);
CREATE INDEX track_available_idx ON track(is_available);

CREATE TABLE track_folder (
    track_id INTEGER Not NULL REFERENCES track(id) ON DELETE CASCADE,
    folder_id INTEGER Not NULL REFERENCES folder(id) ON DELETE CASCADE,
    PRIMARY KEY (track_id, folder_id)
);

CREATE INDEX track_folder_folder_idx ON track_folder(folder_id);

CREATE TABLE track_artist (
    track_id INTEGER Not NULL REFERENCES track(id) ON DELETE CASCADE,
    artist_id INTEGER Not NULL REFERENCES artist(id) ON DELETE CASCADE,
    role TEXT Not NULL DEFAULT 'primary',
    position INTEGER Not NULL DEFAULT 0,
    PRIMARY KEY (track_id, artist_id, role)
);

CREATE INDEX track_artist_a_idx ON track_artist(artist_id);

CREATE TABLE track_tag (
    track_id INTEGER Not NULL REFERENCES track(id) ON DELETE CASCADE,
    key TEXT Not NULL,
    value TEXT Not NULL,
    position INTEGER Not NULL DEFAULT 0,
    PRIMARY KEY (track_id, key, position)
);

CREATE INDEX track_tag_kv_idx ON track_tag(key, value);

CREATE TABLE track_personnel (
    track_id INTEGER Not NULL REFERENCES track(id) ON DELETE CASCADE,
    role TEXT Not NULL,
    name TEXT Not NULL,
    position INTEGER Not NULL DEFAULT 0,
    PRIMARY KEY (track_id, role, position)
);

CREATE TABLE folder (
    id INTEGER Not NULL PRIMARY KEY AUTOINCREMENT,
    display_name TEXT Not NULL,
    uri TEXT Not NULL UNIQUE,
    recursive INTEGER Not NULL DEFAULT 1,
    include_globs TEXT,
    exclude_globs TEXT,
    last_scanned_at INTEGER,
    track_count INTEGER Not NULL DEFAULT 0,
    is_available INTEGER Not NULL DEFAULT 1,
    accent_color INTEGER,
    created_at INTEGER Not NULL,
    updated_at INTEGER Not NULL
);

CREATE INDEX folder_uri_idx ON folder(uri);
