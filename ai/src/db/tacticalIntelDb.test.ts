import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { initSchema } from "./schema.js";
import { backfillGlobalIntelLean, getTacticalIntelLeanAccuracy } from "./tacticalIntelDb.js";

function seedMatch(
  db: DatabaseSync,
  id: string,
  botUid: string,
  oppUid: string,
  activityAt: number,
  rounds: Array<{ p1: string; p2: string }>,
): void {
  db.prepare(
    `INSERT INTO matches (
      id, player1, player2, player1_name, player2_name, pair_key, match_mode, status,
      player1_wins, player2_wins, bot_uid, created_at, last_activity_at, saved_at
    ) VALUES (?, ?, ?, 'Bot', 'Opp', ?, 'BO3', 'completed', 2, 1, ?, ?, ?, ?)`,
  ).run(id, botUid, oppUid, [botUid, oppUid].sort().join("|"), botUid, activityAt, activityAt, activityAt);

  for (let i = 0; i < rounds.length; i++) {
    const r = rounds[i]!;
    db.prepare(
      `INSERT INTO rounds (match_id, round_number, player1_choice, player2_choice, resolved_at)
       VALUES (?, ?, ?, ?, ?)`,
    ).run(id, i + 1, r.p1, r.p2, activityAt + i);
  }

  db.prepare(
    `INSERT INTO tactical_intel_outcomes (
      match_id, bot_won, primary_source, rounds_played,
      lifetime_lean_hits, lifetime_lean_rounds,
      h2h_lean_hits, h2h_lean_rounds,
      recent_lean_hits, recent_lean_rounds,
      global_lean_hits, global_lean_rounds,
      primary_matched_best, saved_at
    ) VALUES (?, 1, 'h2h', ?, 1, ?, 1, ?, 0, 0, 0, 0, 0, ?)`,
  ).run(id, rounds.length, rounds.length, rounds.length, Date.now());
}

describe("backfillGlobalIntelLean", () => {
  it("scores global lean from archived population throws", () => {
    const db = new DatabaseSync(":memory:");
    initSchema(db);

    seedMatch(db, "old", "bot", "opp-a", 1000, [
      { p1: "PAPER", p2: "PAPER" },
      { p1: "PAPER", p2: "ROCK" },
      { p1: "PAPER", p2: "SCISSORS" },
    ]);
    seedMatch(db, "new", "bot", "opp-b", 2000, [
      { p1: "ROCK", p2: "PAPER" },
      { p1: "SCISSORS", p2: "PAPER" },
    ]);

    const updated = backfillGlobalIntelLean(db);
    assert.equal(updated, 1);

    const row = db
      .prepare(`SELECT global_lean_hits, global_lean_rounds FROM tactical_intel_outcomes WHERE match_id = 'new'`)
      .get() as { global_lean_hits: number; global_lean_rounds: number };
    assert.equal(row.global_lean_rounds, 2);
    assert.equal(row.global_lean_hits, 2);

    const lean = getTacticalIntelLeanAccuracy(db);
    const global = lean.find((r) => r.source === "global");
    assert.ok(global);
    assert.equal(global!.leanRounds, 2);
  });
});
