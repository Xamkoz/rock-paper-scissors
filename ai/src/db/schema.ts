import type { DatabaseSync } from "node:sqlite";
import { warn } from "../log.js";

export function pairKey(uidA: string, uidB: string): string {
  return [uidA, uidB].sort().join("|");
}

const REQUIRED_MATCH_COLUMNS = ["pair_key", "saved_at"] as const;

const REQUIRED_TABLES = [
  "matches",
  "rounds",
  "match_descriptions",
  "round_timings",
  "tactical_intel_snapshots",
  "tactical_intel_outcomes",
] as const;

function tableExists(db: DatabaseSync, name: string): boolean {
  const row = db
    .prepare(`SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?`)
    .get(name);
  return row != null;
}

function matchColumnNames(db: DatabaseSync): string[] {
  const rows = db.prepare(`PRAGMA table_info(matches)`).all() as Array<{ name: string }>;
  return rows.map((r) => r.name);
}

/** Drop stale tables when an old matches.db shape is detected (CREATE IF NOT EXISTS does not alter). */
function needsSchemaReset(db: DatabaseSync): boolean {
  if (!tableExists(db, "matches")) return false;
  if (tableExists(db, "llm_calls")) return true;
  const cols = matchColumnNames(db);
  if (cols.includes("describe_llm_ms") || cols.includes("payload")) return true;
  if (tableExists(db, "round_timings")) {
    const rtCols = db.prepare(`PRAGMA table_info(round_timings)`).all() as Array<{
      name: string;
    }>;
    if (!rtCols.some((c) => c.name === "pick_ms")) return true;
  }
  for (const name of REQUIRED_TABLES) {
    if (!tableExists(db, name)) return true;
  }
  return REQUIRED_MATCH_COLUMNS.some((c) => !cols.includes(c));
}

export function initSchema(db: DatabaseSync): void {
  if (needsSchemaReset(db)) {
    warn(
      "[db] incompatible schema — recreating tables (remove data/matches.db manually if you want a clean slate)",
    );
    db.exec(`
      DROP TABLE IF EXISTS llm_calls;
      DROP TABLE IF EXISTS round_timings;
      DROP TABLE IF EXISTS match_descriptions;
      DROP TABLE IF EXISTS rounds;
      DROP TABLE IF EXISTS matches;
    `);
  }
  db.exec(SCHEMA_DDL);
  migrateRoundTimingsPickCitation(db);
  migrateMatchesBotUid(db);
}

function matchColumnNamesList(db: DatabaseSync): string[] {
  if (!tableExists(db, "matches")) return [];
  return matchColumnNames(db);
}

function roundTimingsColumnNames(db: DatabaseSync): string[] {
  const rows = db.prepare(`PRAGMA table_info(round_timings)`).all() as Array<{
    name: string;
  }>;
  return rows.map((r) => r.name);
}

/** Add pick citation columns without wiping existing timing rows. */
function migrateRoundTimingsPickCitation(db: DatabaseSync): void {
  if (!tableExists(db, "round_timings")) return;
  const cols = roundTimingsColumnNames(db);
  if (!cols.includes("pick_reason")) {
    db.exec(`ALTER TABLE round_timings ADD COLUMN pick_reason TEXT`);
  }
  if (!cols.includes("pick_intel_source")) {
    db.exec(`ALTER TABLE round_timings ADD COLUMN pick_intel_source TEXT`);
  }
  if (!cols.includes("pick_intel_signal")) {
    db.exec(`ALTER TABLE round_timings ADD COLUMN pick_intel_signal TEXT`);
  }
  if (!cols.includes("llm_model")) {
    db.exec(`ALTER TABLE round_timings ADD COLUMN llm_model TEXT`);
  }
}

function migrateMatchesBotUid(db: DatabaseSync): void {
  if (!tableExists(db, "matches")) return;
  const cols = matchColumnNamesList(db);
  if (!cols.includes("bot_uid")) {
    db.exec(`ALTER TABLE matches ADD COLUMN bot_uid TEXT`);
  }
}

export const SCHEMA_DDL = `
CREATE TABLE IF NOT EXISTS matches (
  id TEXT PRIMARY KEY,
  player1 TEXT NOT NULL,
  player2 TEXT NOT NULL,
  player1_name TEXT NOT NULL,
  player2_name TEXT NOT NULL,
  pair_key TEXT NOT NULL,
  match_mode TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('completed', 'abandoned')),
  player1_wins INTEGER NOT NULL DEFAULT 0,
  player2_wins INTEGER NOT NULL DEFAULT 0,
  winner_id TEXT,
  resolution TEXT,
  player1_elo_delta INTEGER,
  player2_elo_delta INTEGER,
  bot_uid TEXT,
  created_at INTEGER NOT NULL,
  last_activity_at INTEGER NOT NULL,
  saved_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_matches_pair_activity
  ON matches (pair_key, last_activity_at DESC);
CREATE INDEX IF NOT EXISTS idx_matches_player1_activity
  ON matches (player1, last_activity_at DESC);
CREATE INDEX IF NOT EXISTS idx_matches_player2_activity
  ON matches (player2, last_activity_at DESC);

CREATE TABLE IF NOT EXISTS rounds (
  match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  round_number INTEGER NOT NULL,
  player1_choice TEXT,
  player2_choice TEXT,
  winner_id TEXT,
  resolved_at INTEGER,
  PRIMARY KEY (match_id, round_number)
);

CREATE TABLE IF NOT EXISTS match_descriptions (
  match_id TEXT PRIMARY KEY REFERENCES matches(id) ON DELETE CASCADE,
  description TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS round_timings (
  match_id TEXT NOT NULL,
  round_number INTEGER NOT NULL,
  choice TEXT,
  context_ms INTEGER NOT NULL,
  pick_ms INTEGER NOT NULL,
  submit_ms INTEGER NOT NULL,
  total_ms INTEGER NOT NULL,
  ok INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (match_id, round_number)
);

CREATE TABLE IF NOT EXISTS tactical_intel_snapshots (
  match_id TEXT PRIMARY KEY,
  primary_source TEXT NOT NULL,
  lifetime_dominant TEXT,
  lifetime_open_with TEXT,
  h2h_dominant TEXT,
  h2h_open_with TEXT,
  recent_dominant TEXT,
  recent_open_with TEXT,
  tactics_fallback INTEGER NOT NULL DEFAULT 0,
  saved_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS tactical_intel_outcomes (
  match_id TEXT PRIMARY KEY,
  bot_won INTEGER NOT NULL,
  primary_source TEXT NOT NULL,
  rounds_played INTEGER NOT NULL,
  lifetime_lean_hits INTEGER NOT NULL DEFAULT 0,
  lifetime_lean_rounds INTEGER NOT NULL DEFAULT 0,
  h2h_lean_hits INTEGER NOT NULL DEFAULT 0,
  h2h_lean_rounds INTEGER NOT NULL DEFAULT 0,
  recent_lean_hits INTEGER NOT NULL DEFAULT 0,
  recent_lean_rounds INTEGER NOT NULL DEFAULT 0,
  lifetime_open_hit INTEGER,
  h2h_open_hit INTEGER,
  recent_open_hit INTEGER,
  best_lean_source TEXT,
  primary_matched_best INTEGER NOT NULL DEFAULT 0,
  saved_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tactical_outcomes_primary
  ON tactical_intel_outcomes (primary_source);
`;
