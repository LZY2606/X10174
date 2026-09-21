PRAGMA journal_mode=WAL;

CREATE TABLE IF NOT EXISTS ev (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code TEXT NOT NULL UNIQUE,
  origin_time REAL NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  depth_m REAL NOT NULL,
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS station (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  current_version INTEGER NOT NULL DEFAULT 1,
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS station_version (
  station_id INTEGER NOT NULL,
  version INTEGER NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  elevation_m REAL NOT NULL,
  clock_correction_ms REAL NOT NULL DEFAULT 0,
  created_at REAL NOT NULL,
  PRIMARY KEY (station_id, version)
);

CREATE TABLE IF NOT EXISTS velocity_model (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE,
  current_version INTEGER NOT NULL DEFAULT 1,
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS velocity_model_version (
  model_id INTEGER NOT NULL,
  version INTEGER NOT NULL,
  layers_json TEXT NOT NULL,
  created_at REAL NOT NULL,
  PRIMARY KEY (model_id, version)
);

CREATE TABLE IF NOT EXISTS segment (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  station_id INTEGER NOT NULL,
  start_ms REAL NOT NULL,
  sample_rate_hz REAL NOT NULL,
  sample_count INTEGER NOT NULL,
  phase TEXT,
  content_hash TEXT NOT NULL,
  current_version INTEGER NOT NULL DEFAULT 1,
  created_at REAL NOT NULL,
  UNIQUE (station_id, start_ms, sample_rate_hz)
);

CREATE TABLE IF NOT EXISTS segment_version (
  segment_id INTEGER NOT NULL,
  version INTEGER NOT NULL,
  samples BLOB NOT NULL,
  content_hash TEXT NOT NULL,
  clipped INTEGER NOT NULL,
  created_at REAL NOT NULL,
  PRIMARY KEY (segment_id, version)
);

CREATE TABLE IF NOT EXISTS segment_reception (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  segment_id INTEGER NOT NULL,
  content_hash TEXT NOT NULL,
  received_at REAL NOT NULL,
  source TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS candidate (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id INTEGER NOT NULL,
  station_id INTEGER NOT NULL,
  phase TEXT NOT NULL,
  segment_id INTEGER,
  arrival_ms REAL NOT NULL,
  ci_low_ms REAL NOT NULL,
  ci_high_ms REAL NOT NULL,
  polarity TEXT,
  weight REAL NOT NULL DEFAULT 1.0,
  weight_source TEXT NOT NULL DEFAULT 'manual',
  status TEXT NOT NULL DEFAULT 'active',
  merged_into INTEGER,
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS pick_event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id INTEGER NOT NULL,
  station_id INTEGER NOT NULL,
  seq INTEGER NOT NULL,
  type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  inverse_json TEXT,
  undo_of INTEGER,
  created_at REAL NOT NULL,
  UNIQUE (event_id, station_id, seq)
);

CREATE TABLE IF NOT EXISTS interpretation (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE,
  parent_id INTEGER,
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS interpretation_version (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  interpretation_id INTEGER NOT NULL,
  version INTEGER NOT NULL,
  frozen INTEGER NOT NULL DEFAULT 0,
  pick_versions_json TEXT NOT NULL,
  station_versions_json TEXT NOT NULL,
  model_id INTEGER,
  model_version INTEGER,
  created_at REAL NOT NULL,
  UNIQUE (interpretation_id, version)
);

CREATE TABLE IF NOT EXISTS interpretation_pick (
  interpretation_version_id INTEGER NOT NULL,
  candidate_id INTEGER NOT NULL,
  PRIMARY KEY (interpretation_version_id, candidate_id)
);
