import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { DatabaseSync } from "node:sqlite";
import { initSchema } from "./schema.js";
import { getLlmModelMatchStats } from "./llmModelDb.js";

const BOT = "bot-uid";

function openDb(): DatabaseSync {
  const db = new DatabaseSync(":memory:");
  initSchema(db);
  return db;
}

function seedMatch(
  db: DatabaseSync,
  id: string,
  opts: {
    winnerId: string | null;
    botEloDelta: number;
    model: string;
    pickRounds?: number[];
  },
): void {
  db.prepare(
    `INSERT INTO matches (
      id, player1, player2, player1_name, player2_name, pair_key, match_mode, status,
      player1_wins, player2_wins, winner_id, player1_elo_delta, player2_elo_delta,
      bot_uid, created_at, last_activity_at, saved_at
    ) VALUES (?, ?, 'opp', 'Bot', 'Opp', 'a|b', 'BO3', 'completed', 2, 1, ?, ?, 0, ?, 1, 2, 3)`,
  ).run(id, BOT, opts.winnerId, opts.botEloDelta, BOT);

  const rounds = opts.pickRounds ?? [1, 2, 3];
  for (const rn of rounds) {
    db.prepare(
      `INSERT INTO round_timings (
        match_id, round_number, choice, llm_model, context_ms, pick_ms, submit_ms, total_ms, ok, created_at
      ) VALUES (?, ?, 'ROCK', ?, 1, 1, 1, 1, 1, 1)`,
    ).run(id, rn, opts.model);
  }
}

describe("getLlmModelMatchStats", () => {
  it("aggregates win rate and avg ELO delta per primary model from sqlite bot_uid", () => {
    const db = openDb();
    seedMatch(db, "m1", {
      winnerId: BOT,
      botEloDelta: 10,
      model: "gemma",
    });
    seedMatch(db, "m2", {
      winnerId: BOT,
      botEloDelta: 6,
      model: "gemma",
    });
    seedMatch(db, "m3", {
      winnerId: "opp",
      botEloDelta: -8,
      model: "qwen",
    });

    const stats = getLlmModelMatchStats(db);
    const gemma = stats.find((s) => s.model === "gemma");
    const qwen = stats.find((s) => s.model === "qwen");

    assert.equal(gemma?.matches, 2);
    assert.equal(gemma?.wins, 2);
    assert.equal(gemma?.winPct, 100);
    assert.equal(gemma?.totalEloDelta, 16);
    assert.equal(gemma?.avgEloDelta, 8);

    assert.equal(qwen?.matches, 1);
    assert.equal(qwen?.wins, 0);
    assert.equal(qwen?.avgEloDelta, -8);
  });

  it("ignores matches without bot_uid on archive", () => {
    const db = openDb();
    db.prepare(
      `INSERT INTO matches (
        id, player1, player2, player1_name, player2_name, pair_key, match_mode, status,
        player1_wins, player2_wins, winner_id, player1_elo_delta, player2_elo_delta,
        created_at, last_activity_at, saved_at
      ) VALUES ('legacy', ?, 'opp', 'Bot', 'Opp', 'a|b', 'BO3', 'completed', 1, 0, ?, 5, 0, 1, 2, 3)`,
    ).run(BOT, BOT);
    db.prepare(
      `INSERT INTO round_timings (
        match_id, round_number, choice, llm_model, context_ms, pick_ms, submit_ms, total_ms, ok, created_at
      ) VALUES ('legacy', 1, 'ROCK', 'gemma', 1, 1, 1, 1, 1, 1)`,
    ).run();

    assert.equal(getLlmModelMatchStats(db).length, 0);
  });
});
