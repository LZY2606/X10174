PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS seq (
    name TEXT PRIMARY KEY,
    next INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS seismic_event (
    id INTEGER PRIMARY KEY,
    event_code TEXT NOT NULL UNIQUE,
    origin_ms INTEGER NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    depth_km REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS station (
    id INTEGER PRIMARY KEY,
    station_code TEXT NOT NULL,
    version INTEGER NOT NULL,
    global_version INTEGER NOT NULL UNIQUE,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    elevation_m REAL NOT NULL,
    created_ms INTEGER NOT NULL,
    UNIQUE(station_code, version)
);

CREATE TABLE IF NOT EXISTS velocity_model (
    id INTEGER PRIMARY KEY,
    model_code TEXT NOT NULL,
    version INTEGER NOT NULL,
    created_ms INTEGER NOT NULL,
    UNIQUE(model_code, version)
);

CREATE TABLE IF NOT EXISTS model_layer (
    id INTEGER PRIMARY KEY,
    model_id INTEGER NOT NULL REFERENCES velocity_model(id) ON DELETE CASCADE,
    layer_order INTEGER NOT NULL,
    top_depth_km REAL NOT NULL,
    bottom_depth_km REAL NOT NULL,
    vp_kms REAL NOT NULL,
    vs_kms REAL NOT NULL,
    UNIQUE(model_id, layer_order)
);

CREATE TABLE IF NOT EXISTS waveform_segment (
    id INTEGER PRIMARY KEY,
    station_code TEXT NOT NULL,
    channel TEXT NOT NULL,
    seg_key TEXT NOT NULL,
    version INTEGER NOT NULL,
    start_ms INTEGER NOT NULL,
    sample_rate_hz REAL NOT NULL,
    sample_count INTEGER NOT NULL,
    samples TEXT NOT NULL,
    content_sha256 TEXT NOT NULL,
    source TEXT NOT NULL,
    clock_offset_ms REAL NOT NULL DEFAULT 0,
    clipped INTEGER NOT NULL DEFAULT 0,
    file_tag TEXT,
    created_ms INTEGER NOT NULL,
    UNIQUE(seg_key, version)
);

CREATE TABLE IF NOT EXISTS reception_log (
    id INTEGER PRIMARY KEY,
    seg_key TEXT NOT NULL,
    content_sha256 TEXT NOT NULL,
    segment_id INTEGER REFERENCES waveform_segment(id),
    duplicate_of INTEGER REFERENCES waveform_segment(id),
    source TEXT NOT NULL,
    received_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS interpretation (
    id INTEGER PRIMARY KEY,
    interp_code TEXT NOT NULL UNIQUE,
    event_code TEXT NOT NULL,
    velocity_model_id INTEGER REFERENCES velocity_model(id),
    head_seq INTEGER,
    station_pin_version INTEGER,
    frozen_ms INTEGER,
    created_ms INTEGER NOT NULL,
    parent_interp_id INTEGER REFERENCES interpretation(id)
);

CREATE TABLE IF NOT EXISTS interp_station_pin (
    id INTEGER PRIMARY KEY,
    interp_id INTEGER NOT NULL REFERENCES interpretation(id) ON DELETE CASCADE,
    station_code TEXT NOT NULL,
    version INTEGER NOT NULL,
    UNIQUE(interp_id, station_code)
);

CREATE TABLE IF NOT EXISTS pick_event (
    seq INTEGER PRIMARY KEY,
    interp_id INTEGER NOT NULL REFERENCES interpretation(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    payload TEXT NOT NULL,
    undo_of_seq INTEGER,
    created_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pick_event_interp ON pick_event(interp_id, seq);

CREATE TABLE IF NOT EXISTS undo_entry (
    id INTEGER PRIMARY KEY,
    interp_id INTEGER NOT NULL REFERENCES interpretation(id) ON DELETE CASCADE,
    forward_seq INTEGER NOT NULL,
    inverse_seq INTEGER NOT NULL,
    created_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_undo_interp ON undo_entry(interp_id, id);
