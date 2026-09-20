PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS station (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    lat REAL NOT NULL,
    lon REAL NOT NULL,
    elevation_m REAL NOT NULL,
    version INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    superseded_at TEXT
);

CREATE TABLE IF NOT EXISTS velocity_model (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    version INTEGER NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS velocity_layer (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    model_id INTEGER NOT NULL REFERENCES velocity_model(id) ON DELETE CASCADE,
    top_depth_km REAL NOT NULL,
    vp_kms REAL NOT NULL,
    vs_kms REAL NOT NULL,
    ord INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS seismic_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    origin_time REAL NOT NULL,
    lat REAL NOT NULL,
    lon REAL NOT NULL,
    depth_km REAL NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS waveform_track (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    station_code TEXT NOT NULL,
    channel TEXT NOT NULL,
    version INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (station_code, channel)
);

CREATE TABLE IF NOT EXISTS waveform_segment (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    track_id INTEGER NOT NULL REFERENCES waveform_track(id) ON DELETE CASCADE,
    content_id INTEGER NOT NULL REFERENCES waveform_content(id),
    start_time REAL NOT NULL,
    sample_rate_hz REAL NOT NULL,
    clock_offset_s REAL NOT NULL DEFAULT 0,
    clipped INTEGER NOT NULL DEFAULT 0,
    received_count INTEGER NOT NULL DEFAULT 1,
    first_received_at TEXT NOT NULL,
    last_received_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS waveform_content (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sha256 TEXT NOT NULL UNIQUE,
    sample_count INTEGER NOT NULL,
    samples BLOB NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS import_receipt (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    content_id INTEGER NOT NULL REFERENCES waveform_content(id),
    source_name TEXT NOT NULL,
    received_at TEXT NOT NULL,
    duplicate_of_segment INTEGER REFERENCES waveform_segment(id)
);

CREATE TABLE IF NOT EXISTS interpretation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    event_id INTEGER NOT NULL REFERENCES seismic_event(id),
    model_id INTEGER NOT NULL REFERENCES velocity_model(id),
    station_version_map TEXT NOT NULL,
    track_version_map TEXT NOT NULL,
    pick_version INTEGER NOT NULL DEFAULT 0,
    frozen INTEGER NOT NULL DEFAULT 0,
    frozen_at TEXT,
    frozen_bundle TEXT,
    parent_interpretation_id INTEGER REFERENCES interpretation(id),
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS pick (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    interpretation_id INTEGER NOT NULL REFERENCES interpretation(id) ON DELETE CASCADE,
    station_code TEXT NOT NULL,
    phase TEXT NOT NULL CHECK (phase IN ('P','S')),
    time REAL NOT NULL,
    polarity TEXT CHECK (polarity IN ('+','-','?')),
    ci_half_width_s REAL NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'CANDIDATE'
        CHECK (status IN ('CANDIDATE','SELECTED','MERGED','NOISE')),
    merged_into INTEGER REFERENCES pick(id),
    source TEXT NOT NULL,
    pick_version INTEGER NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS action_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    interpretation_id INTEGER NOT NULL REFERENCES interpretation(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL,
    type TEXT NOT NULL,
    undo_payload TEXT NOT NULL,
    redo_payload TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (interpretation_id, seq)
);

CREATE TABLE IF NOT EXISTS clock_correction (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    interpretation_id INTEGER NOT NULL REFERENCES interpretation(id) ON DELETE CASCADE,
    station_code TEXT NOT NULL,
    shift_s REAL NOT NULL,
    applied_at TEXT NOT NULL,
    UNIQUE (interpretation_id, station_code)
);

CREATE INDEX IF NOT EXISTS idx_segment_track ON waveform_segment(track_id);
CREATE INDEX IF NOT EXISTS idx_pick_interp ON pick(interpretation_id);
CREATE INDEX IF NOT EXISTS idx_layer_model ON velocity_layer(model_id);


-- Migration for databases created before station versioning allowed repeated codes.
DROP TABLE IF EXISTS station_new;
CREATE TABLE station_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    lat REAL NOT NULL,
    lon REAL NOT NULL,
    elevation_m REAL NOT NULL,
    version INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    superseded_at TEXT
);
INSERT INTO station_new(id, code, name, lat, lon, elevation_m, version, created_at, superseded_at)
SELECT id, code, name, lat, lon, elevation_m, version, created_at, superseded_at FROM station;
DROP TABLE station;
ALTER TABLE station_new RENAME TO station;
CREATE INDEX IF NOT EXISTS idx_station_code_version ON station(code, version);
