import { mkdir } from "node:fs/promises";
import { dirname } from "node:path";
import { DatabaseSync } from "node:sqlite";
import type { Match, MatchStatus } from "../types.js";
import {
  matchToRow,
  rowsToMatch,
  roundsToRows,
  type MatchRow,
  type RoundRow,
} from "./matchRows.js";
import type { TacticalIntel } from "../llm/tacticalIntel.js";
import type { TacticalIntelOutcome } from "../llm/tacticalIntelTracking.js";
import { initSchema, pairKey } from "./schema.js";
import { getPickIntelCitationStats } from "./tacticalIntelCitationDb.js";
import {
  getPrimaryMatchedBestStats,
  getTacticalIntelLeanAccuracy,
  getTacticalIntelPrimaryLeaderboard,
  saveTacticalIntelOutcome as persistTacticalIntelOutcome,
  saveTacticalIntelSnapshot as persistTacticalIntelSnapshot,
} from "./tacticalIntelDb.js";
import type { RoundTimingRecord } from "./timing.js";

function isConcluded(status: MatchStatus): boolean {
  return status === "completed" || status === "abandoned";
}

export class MatchDatabase {
  private readonly db: DatabaseSync;

  private constructor(dbPath: string) {
    this.db = new DatabaseSync(dbPath);
    this.db.exec("PRAGMA journal_mode = WAL");
    this.db.exec("PRAGMA foreign_keys = ON");
    initSchema(this.db);
  }

  static async open(dbPath: string): Promise<MatchDatabase> {
    await mkdir(dirname(dbPath), { recursive: true });
    return new MatchDatabase(dbPath);
  }

  close(): void {
    this.db.close();
  }

  /** Raw SQLite handle (startup model ranking, tests). */
  getSqlite(): DatabaseSync {
    return this.db;
  }

  saveTacticalIntelSnapshot(
    matchId: string,
    intel: TacticalIntel,
    tacticsFallback: boolean,
  ): void {
    persistTacticalIntelSnapshot(this.db, matchId, intel, tacticsFallback);
  }

  saveTacticalIntelOutcome(outcome: TacticalIntelOutcome): void {
    persistTacticalIntelOutcome(this.db, outcome);
  }

  getTacticalIntelPrimaryLeaderboard() {
    return getTacticalIntelPrimaryLeaderboard(this.db);
  }

  getTacticalIntelLeanAccuracy() {
    return getTacticalIntelLeanAccuracy(this.db);
  }

  getPrimaryMatchedBestStats() {
    return getPrimaryMatchedBestStats(this.db);
  }

  getPickIntelCitationStats() {
    return getPickIntelCitationStats(this.db);
  }

  recordRoundTiming(record: RoundTimingRecord): void {
    this.db
      .prepare(
        `INSERT INTO round_timings (
          match_id, round_number, choice, pick_reason, pick_intel_source, pick_intel_signal,
          llm_model, context_ms, pick_ms, submit_ms, total_ms, ok, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(match_id, round_number) DO UPDATE SET
          choice = excluded.choice,
          pick_reason = excluded.pick_reason,
          pick_intel_source = excluded.pick_intel_source,
          pick_intel_signal = excluded.pick_intel_signal,
          llm_model = excluded.llm_model,
          context_ms = excluded.context_ms,
          pick_ms = excluded.pick_ms,
          submit_ms = excluded.submit_ms,
          total_ms = excluded.total_ms,
          ok = excluded.ok,
          created_at = excluded.created_at`,
      )
      .run(
        record.matchId,
        record.roundNumber,
        record.choice ?? null,
        record.pickReason ?? null,
        record.pickIntelSource ?? null,
        record.pickIntelSignal ?? null,
        record.llmModel ?? null,
        record.contextMs,
        record.pickMs,
        record.submitMs,
        record.totalMs,
        record.ok ? 1 : 0,
        Date.now(),
      );
  }

  saveConcluded(match: Match, botUid: string, description?: string): void {
    if (!isConcluded(match.status)) return;

    const row = matchToRow(match, Date.now());
    const roundRows = roundsToRows(match.id, match.rounds);

    const insertMatch = this.db.prepare(`
      INSERT INTO matches (
        id, player1, player2, player1_name, player2_name, pair_key, match_mode, status,
        player1_wins, player2_wins, winner_id, resolution, player1_elo_delta, player2_elo_delta,
        bot_uid, created_at, last_activity_at, saved_at
      ) VALUES (
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
      )
      ON CONFLICT(id) DO UPDATE SET
        player1_name = excluded.player1_name,
        player2_name = excluded.player2_name,
        bot_uid = excluded.bot_uid,
        status = excluded.status,
        player1_wins = excluded.player1_wins,
        player2_wins = excluded.player2_wins,
        winner_id = excluded.winner_id,
        resolution = excluded.resolution,
        player1_elo_delta = excluded.player1_elo_delta,
        player2_elo_delta = excluded.player2_elo_delta,
        last_activity_at = excluded.last_activity_at,
        saved_at = excluded.saved_at
    `);

    const insertRound = this.db.prepare(`
      INSERT INTO rounds (
        match_id, round_number, player1_choice, player2_choice, winner_id, resolved_at
      ) VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(match_id, round_number) DO UPDATE SET
        player1_choice = excluded.player1_choice,
        player2_choice = excluded.player2_choice,
        winner_id = excluded.winner_id,
        resolved_at = excluded.resolved_at
    `);

    const deleteRounds = this.db.prepare(`DELETE FROM rounds WHERE match_id = ?`);

    const setDesc = this.db.prepare(`
      INSERT INTO match_descriptions (match_id, description, updated_at)
      VALUES (?, ?, ?)
      ON CONFLICT(match_id) DO UPDATE SET
        description = excluded.description,
        updated_at = excluded.updated_at
    `);

    this.db.exec("BEGIN");
    try {
      insertMatch.run(
        row.id,
        row.player1,
        row.player2,
        row.player1_name,
        row.player2_name,
        row.pair_key,
        row.match_mode,
        row.status,
        row.player1_wins,
        row.player2_wins,
        row.winner_id,
        row.resolution,
        row.player1_elo_delta,
        row.player2_elo_delta,
        botUid,
        row.created_at,
        row.last_activity_at,
        row.saved_at,
      );
      deleteRounds.run(match.id);
      for (const r of roundRows) {
        insertRound.run(
          r.match_id,
          r.round_number,
          r.player1_choice,
          r.player2_choice,
          r.winner_id,
          r.resolved_at,
        );
      }
      if (description) setDesc.run(match.id, description, Date.now());
      this.db.exec("COMMIT");
    } catch (err) {
      this.db.exec("ROLLBACK");
      throw err;
    }
  }

  getDescription(matchId: string): string | undefined {
    const row = this.db
      .prepare(`SELECT description FROM match_descriptions WHERE match_id = ?`)
      .get(matchId) as { description: string } | undefined;
    return row?.description;
  }

  getMatch(matchId: string): Match | null {
    const row = this.db
      .prepare(`SELECT * FROM matches WHERE id = ?`)
      .get(matchId) as unknown as MatchRow | undefined;
    if (!row) return null;
    const rounds = this.loadRounds([matchId]).get(matchId) ?? [];
    return rowsToMatch(row, rounds);
  }

  listMatchesForUser(uid: string, limit = 50): Match[] {
    const rows = this.db
      .prepare(
        `SELECT * FROM matches
         WHERE (player1 = ? OR player2 = ?)
           AND status IN ('completed', 'abandoned')
         ORDER BY last_activity_at DESC
         LIMIT ?`,
      )
      .all(uid, uid, limit) as unknown as MatchRow[];
    return this.hydrateMatches(rows);
  }

  listHeadToHead(uidA: string, uidB: string, limit = 50): Match[] {
    const key = pairKey(uidA, uidB);
    const rows = this.db
      .prepare(
        `SELECT * FROM matches
         WHERE pair_key = ?
           AND status IN ('completed', 'abandoned')
         ORDER BY last_activity_at DESC
         LIMIT ?`,
      )
      .all(key, limit) as unknown as MatchRow[];
    return this.hydrateMatches(rows);
  }

  private hydrateMatches(rows: MatchRow[]): Match[] {
    if (rows.length === 0) return [];
    const roundsByMatch = this.loadRounds(rows.map((r) => r.id));
    return rows.map((row) => rowsToMatch(row, roundsByMatch.get(row.id) ?? []));
  }

  private loadRounds(matchIds: string[]): Map<string, RoundRow[]> {
    const map = new Map<string, RoundRow[]>();
    if (matchIds.length === 0) return map;

    const placeholders = matchIds.map(() => "?").join(", ");
    const roundRows = this.db
      .prepare(
        `SELECT * FROM rounds
         WHERE match_id IN (${placeholders})
         ORDER BY match_id, round_number`,
      )
      .all(...matchIds) as unknown as RoundRow[];

    for (const r of roundRows) {
      const list = map.get(r.match_id) ?? [];
      list.push(r);
      map.set(r.match_id, list);
    }
    return map;
  }
}
