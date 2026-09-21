CREATE TABLE IF NOT EXISTS station (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code TEXT NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS station_set_version (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS station_version (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  station_id INTEGER NOT NULL,
  version INTEGER NOT NULL,
  set_version INTEGER NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  elevation_m REAL NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE (station_id, version)
);
CREATE TABLE IF NOT EXISTS waveform_segment (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  station_id INTEGER NOT NULL,
  content_hash TEXT NOT NULL,
  version INTEGER NOT NULL,
  sample_rate REAL NOT NULL,
  start_epoch_ms INTEGER NOT NULL,
  samples BLOB NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE (station_id, content_hash)
);
CREATE TABLE IF NOT EXISTS waveform_receipt (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  segment_id INTEGER NOT NULL,
  received_at TEXT NOT NULL,
  source TEXT
);
CREATE TABLE IF NOT EXISTS velocity_model (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS velocity_model_version (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  model_id INTEGER NOT NULL,
  version INTEGER NOT NULL,
  p_velocity REAL NOT NULL,
  s_velocity REAL NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE (model_id, version)
);
CREATE TABLE IF NOT EXISTS event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  origin_epoch_ms INTEGER NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  depth_km REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS branch (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  parent_id INTEGER,
  model_version_id INTEGER NOT NULL,
  pick_version INTEGER NOT NULL DEFAULT 0,
  frozen INTEGER NOT NULL DEFAULT 0,
  frozen_station_set INTEGER,
  frozen_model_version_id INTEGER,
  frozen_pick_version INTEGER,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pick (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  branch_id INTEGER NOT NULL,
  station_id INTEGER NOT NULL,
  phase TEXT NOT NULL,
  epoch_ms INTEGER NOT NULL,
  polarity TEXT NOT NULL DEFAULT 'NONE',
  ci_low_ms INTEGER,
  ci_high_ms INTEGER,
  confidence REAL NOT NULL DEFAULT 1.0,
  status TEXT NOT NULL DEFAULT 'CANDIDATE',
  source TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 1,
  merged_into INTEGER
);
CREATE TABLE IF NOT EXISTS action (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  branch_id INTEGER NOT NULL,
  seq INTEGER NOT NULL,
  type TEXT NOT NULL,
  payload TEXT NOT NULL,
  undone INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  UNIQUE (branch_id, seq)
);
CREATE TABLE IF NOT EXISTS clock_correction (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  branch_id INTEGER NOT NULL,
  station_id INTEGER NOT NULL,
  offset_ms INTEGER NOT NULL,
  UNIQUE (branch_id, station_id)
);
