PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_code TEXT NOT NULL UNIQUE,
    origin_time_ns INTEGER NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    depth_km REAL NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS station_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    station_code TEXT NOT NULL,
    version TEXT NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    elevation_m REAL NOT NULL,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    content_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(station_code, version)
);

CREATE TABLE IF NOT EXISTS velocity_models (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    model_code TEXT NOT NULL,
    version TEXT NOT NULL,
    layers_json TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(model_code, version)
);

CREATE TABLE IF NOT EXISTS waveform_clips (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    clip_code TEXT NOT NULL UNIQUE,
    station_code TEXT NOT NULL,
    latest_version_id INTEGER,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS waveform_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    clip_id INTEGER NOT NULL REFERENCES waveform_clips(id),
    version INTEGER NOT NULL,
    sample_rate_hz REAL NOT NULL,
    start_time_ns INTEGER NOT NULL,
    sample_count INTEGER NOT NULL,
    samples_json TEXT NOT NULL,
    gaps_json TEXT NOT NULL,
    overlaps_json TEXT NOT NULL,
    clipped_json TEXT NOT NULL,
    min_value REAL,
    max_value REAL,
    content_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(clip_id, version),
    UNIQUE(clip_id, content_hash)
);

CREATE TABLE IF NOT EXISTS waveform_receptions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    clip_id INTEGER NOT NULL REFERENCES waveform_clips(id),
    waveform_version_id INTEGER NOT NULL REFERENCES waveform_versions(id),
    source TEXT NOT NULL,
    received_at TEXT NOT NULL,
    duplicate INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS pick_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    pick_code TEXT NOT NULL,
    version INTEGER NOT NULL,
    event_id INTEGER NOT NULL REFERENCES events(id),
    station_code TEXT NOT NULL,
    phase TEXT NOT NULL CHECK(phase IN ('P','S')),
    time_ns INTEGER NOT NULL,
    polarity TEXT NOT NULL CHECK(polarity IN ('POSITIVE','NEGATIVE','NEUTRAL')),
    confidence_low_ns INTEGER NOT NULL,
    confidence_high_ns INTEGER NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('CANDIDATE','MERGED','NOISE')),
    source TEXT NOT NULL,
    merged_into_code TEXT,
    content_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(pick_code, version)
);

CREATE TABLE IF NOT EXISTS interpretation_branches (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    branch_code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    event_id INTEGER NOT NULL REFERENCES events(id),
    parent_branch_id INTEGER REFERENCES interpretation_branches(id),
    current_model_id INTEGER NOT NULL REFERENCES velocity_models(id),
    frozen_at TEXT,
    frozen_snapshot_json TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS branch_station_versions (
    branch_id INTEGER NOT NULL REFERENCES interpretation_branches(id) ON DELETE CASCADE,
    station_code TEXT NOT NULL,
    station_version_id INTEGER NOT NULL REFERENCES station_versions(id),
    PRIMARY KEY(branch_id, station_code)
);

CREATE TABLE IF NOT EXISTS branch_picks (
    branch_id INTEGER NOT NULL REFERENCES interpretation_branches(id) ON DELETE CASCADE,
    pick_code TEXT NOT NULL,
    current_pick_version_id INTEGER NOT NULL REFERENCES pick_versions(id),
    PRIMARY KEY(branch_id, pick_code)
);

CREATE TABLE IF NOT EXISTS branch_clock_corrections (
    branch_id INTEGER NOT NULL REFERENCES interpretation_branches(id),
    station_code TEXT NOT NULL,
    correction_ns INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(branch_id, station_code)
);

CREATE TABLE IF NOT EXISTS branch_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    branch_id INTEGER NOT NULL REFERENCES interpretation_branches(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    reversible INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    UNIQUE(branch_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_pick_event ON pick_versions(event_id);
CREATE INDEX IF NOT EXISTS idx_waveform_station ON waveform_clips(station_code);
CREATE INDEX IF NOT EXISTS idx_branch_events_branch ON branch_events(branch_id, seq);
