import type { DatabaseSync } from "node:sqlite";
import type { TacticalIntel } from "../llm/tacticalIntel.js";
import type {
  LeanAccuracyRow,
  PrimarySourceLeaderboardRow,
  TacticalIntelOutcome,
} from "../llm/tacticalIntelTracking.js";

function boolToInt(v: boolean | null | undefined): number | null {
  if (v == null) return null;
  return v ? 1 : 0;
}

export function saveTacticalIntelSnapshot(
  db: DatabaseSync,
  matchId: string,
  intel: TacticalIntel,
  tacticsFallback: boolean,
): void {
  db.prepare(
    `INSERT INTO tactical_intel_snapshots (
      match_id, primary_source,
      lifetime_dominant, lifetime_open_with,
      h2h_dominant, h2h_open_with,
      recent_dominant, recent_open_with,
      tactics_fallback, saved_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(match_id) DO UPDATE SET
      primary_source = excluded.primary_source,
      lifetime_dominant = excluded.lifetime_dominant,
      lifetime_open_with = excluded.lifetime_open_with,
      h2h_dominant = excluded.h2h_dominant,
      h2h_open_with = excluded.h2h_open_with,
      recent_dominant = excluded.recent_dominant,
      recent_open_with = excluded.recent_open_with,
      tactics_fallback = excluded.tactics_fallback,
      saved_at = excluded.saved_at`,
  ).run(
    matchId,
    intel.primarySource,
    intel.lifetime?.dominant ?? null,
    intel.lifetime?.openWith ?? null,
    intel.h2h?.dominant ?? null,
    intel.h2h?.openWith ?? null,
    intel.recentVsOpponent?.dominant ?? null,
    intel.recentVsOpponent?.openWith ?? null,
    tacticsFallback ? 1 : 0,
    Date.now(),
  );
}

export function saveTacticalIntelOutcome(db: DatabaseSync, outcome: TacticalIntelOutcome): void {
  db.prepare(
    `INSERT INTO tactical_intel_outcomes (
      match_id, bot_won, primary_source, rounds_played,
      lifetime_lean_hits, lifetime_lean_rounds,
      h2h_lean_hits, h2h_lean_rounds,
      recent_lean_hits, recent_lean_rounds,
      lifetime_open_hit, h2h_open_hit, recent_open_hit,
      best_lean_source, primary_matched_best, saved_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(match_id) DO UPDATE SET
      bot_won = excluded.bot_won,
      primary_source = excluded.primary_source,
      rounds_played = excluded.rounds_played,
      lifetime_lean_hits = excluded.lifetime_lean_hits,
      lifetime_lean_rounds = excluded.lifetime_lean_rounds,
      h2h_lean_hits = excluded.h2h_lean_hits,
      h2h_lean_rounds = excluded.h2h_lean_rounds,
      recent_lean_hits = excluded.recent_lean_hits,
      recent_lean_rounds = excluded.recent_lean_rounds,
      lifetime_open_hit = excluded.lifetime_open_hit,
      h2h_open_hit = excluded.h2h_open_hit,
      recent_open_hit = excluded.recent_open_hit,
      best_lean_source = excluded.best_lean_source,
      primary_matched_best = excluded.primary_matched_best,
      saved_at = excluded.saved_at`,
  ).run(
    outcome.matchId,
    outcome.botWon ? 1 : 0,
    outcome.primarySource,
    outcome.roundsPlayed,
    outcome.lifetimeLeanHits,
    outcome.lifetimeLeanRounds,
    outcome.h2hLeanHits,
    outcome.h2hLeanRounds,
    outcome.recentLeanHits,
    outcome.recentLeanRounds,
    boolToInt(outcome.lifetimeOpenHit),
    boolToInt(outcome.h2hOpenHit),
    boolToInt(outcome.recentOpenHit),
    outcome.bestLeanSource,
    outcome.primaryMatchedBest ? 1 : 0,
    Date.now(),
  );
}

export function getTacticalIntelPrimaryLeaderboard(
  db: DatabaseSync,
): PrimarySourceLeaderboardRow[] {
  const rows = db
    .prepare(
      `SELECT primary_source AS source,
              COUNT(*) AS matches,
              SUM(bot_won) AS wins
       FROM tactical_intel_outcomes
       GROUP BY primary_source
       ORDER BY wins * 1.0 / COUNT(*) DESC, COUNT(*) DESC`,
    )
    .all() as Array<{ source: string; matches: number; wins: number }>;

  return rows.map((r) => ({
    source: r.source,
    matches: r.matches,
    wins: r.wins,
    winPct: r.matches > 0 ? Math.round((r.wins / r.matches) * 1000) / 10 : 0,
  }));
}

export function getTacticalIntelLeanAccuracy(db: DatabaseSync): LeanAccuracyRow[] {
  const row = db
    .prepare(
      `SELECT
        SUM(lifetime_lean_hits) AS life_hits,
        SUM(lifetime_lean_rounds) AS life_rounds,
        SUM(h2h_lean_hits) AS h2h_hits,
        SUM(h2h_lean_rounds) AS h2h_rounds,
        SUM(recent_lean_hits) AS recent_hits,
        SUM(recent_lean_rounds) AS recent_rounds
       FROM tactical_intel_outcomes`,
    )
    .get() as {
    life_hits: number | null;
    life_rounds: number | null;
    h2h_hits: number | null;
    h2h_rounds: number | null;
    recent_hits: number | null;
    recent_rounds: number | null;
  };

  const slice = (
    source: LeanAccuracyRow["source"],
    hits: number | null,
    rounds: number | null,
  ): LeanAccuracyRow => {
    const h = hits ?? 0;
    const r = rounds ?? 0;
    return {
      source,
      leanHits: h,
      leanRounds: r,
      leanPct: r > 0 ? Math.round((h / r) * 1000) / 10 : 0,
    };
  };

  return [
    slice("lifetime", row.life_hits, row.life_rounds),
    slice("h2h", row.h2h_hits, row.h2h_rounds),
    slice("recentVsOpponent", row.recent_hits, row.recent_rounds),
  ].sort((a, b) => b.leanPct - a.leanPct);
}

/** When primary source matched the best lean read for that match. */
export function getPrimaryMatchedBestStats(
  db: DatabaseSync,
): { matches: number; wins: number; winPct: number } {
  const row = db
    .prepare(
      `SELECT COUNT(*) AS matches, SUM(bot_won) AS wins
       FROM tactical_intel_outcomes
       WHERE primary_matched_best = 1`,
    )
    .get() as { matches: number; wins: number };
  return {
    matches: row.matches,
    wins: row.wins ?? 0,
    winPct: row.matches > 0 ? Math.round(((row.wins ?? 0) / row.matches) * 1000) / 10 : 0,
  };
}
