import { describe, it } from "node:test";
import assert from "node:assert/strict";
import type { Match } from "../types.js";
import { buildTacticalIntel } from "./tacticalIntel.js";
import type { MatchDbContext } from "./matchContext.js";
import { evaluateTacticalIntelOutcome } from "./tacticalIntelTracking.js";

const ctx = (): MatchDbContext => ({
  botUid: "bot",
  opponentUid: "opp",
  opponentName: "Daniil",
  opponentProfile: {
    uid: "opp",
    displayName: "Daniil",
    elo: 900,
    throwsRock: 100,
    throwsPaper: 300,
    throwsScissors: 100,
  },
  currentMatch: null,
  headToHead: [],
  recentBotMatches: [],
  queryLimits: { headToHead: 0, recentBot: 0 },
});

describe("evaluateTacticalIntelOutcome", () => {
  it("scores lean hits per source", () => {
    const match: Match = {
      id: "m1",
      player1: "bot",
      player2: "opp",
      player1Name: "Bot",
      player2Name: "Daniil",
      matchMode: "BO3",
      status: "completed",
      player1Ready: true,
      player2Ready: true,
      readyDeadlineAt: 0,
      currentRound: 2,
      player1Wins: 2,
      player2Wins: 0,
      winnerId: "bot",
      rounds: [
        {
          roundNumber: 1,
          player1Submitted: true,
          player2Submitted: true,
          player1Choice: "SCISSORS",
          player2Choice: "PAPER",
          winner: "bot",
          resolvedAt: 1,
        },
        {
          roundNumber: 2,
          player1Submitted: true,
          player2Submitted: true,
          player1Choice: "ROCK",
          player2Choice: "PAPER",
          winner: "opp",
          resolvedAt: 2,
        },
      ],
      createdAt: 0,
      lastActivityAt: 0,
    };
    const intel = buildTacticalIntel(match, ctx());
    const outcome = evaluateTacticalIntelOutcome(match, "bot", intel);
    assert.equal(outcome.botWon, true);
    assert.equal(outcome.lifetimeLeanHits, 2);
    assert.equal(outcome.lifetimeLeanRounds, 2);
    assert.equal(outcome.bestLeanSource, "lifetime");
    assert.equal(outcome.lifetimeOpenHit, true);
  });
});
