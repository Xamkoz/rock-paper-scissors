import { describe, it } from "node:test";
import assert from "node:assert/strict";
import type { Match } from "../types.js";
import type { MatchDbContext } from "./matchContext.js";
import { buildTacticalIntel } from "./tacticalIntel.js";
import {
  predictOpponentMoveForSignal,
  scoreAllRoundsInMatch,
} from "./intelSignalRoundScoring.js";

const baseMatch = (): Match => ({
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
  currentRound: 3,
  player1Wins: 2,
  player2Wins: 0,
  winnerId: "bot",
  rounds: [
    {
      roundNumber: 1,
      player1Choice: "SCISSORS",
      player2Choice: "PAPER",
      player1Submitted: true,
      player2Submitted: true,
      winner: "bot",
      resolvedAt: 1,
    },
    {
      roundNumber: 2,
      player1Choice: "ROCK",
      player2Choice: "PAPER",
      player1Submitted: true,
      player2Submitted: true,
      winner: "opp",
      resolvedAt: 2,
    },
    {
      roundNumber: 3,
      player1Choice: "SCISSORS",
      player2Choice: "PAPER",
      player1Submitted: true,
      player2Submitted: true,
      winner: "bot",
      resolvedAt: 3,
    },
  ],
  createdAt: 0,
  lastActivityAt: 0,
});

const ctx = (): MatchDbContext => ({
  botUid: "bot",
  opponentUid: "opp",
  opponentName: "Daniil",
  opponentProfile: {
    uid: "opp",
    displayName: "Daniil",
    elo: 949,
    throwsRock: 595,
    throwsPaper: 913,
    throwsScissors: 632,
  },
  currentMatch: null,
  headToHead: [],
  recentBotMatches: [],
  globalBotMatches: [],
  queryLimits: { headToHead: 5, recentBot: 0, globalBot: 0 },
});

describe("predictOpponentMoveForSignal", () => {
  it("predicts lifetime dominant lean", () => {
    const intel = buildTacticalIntel(baseMatch(), ctx());
    const predicted = predictOpponentMoveForSignal("lifetime", "dominant", { ...ctx(), tacticalIntel: intel }, {
      roundNumber: 1,
      priorPairs: [],
      priorOpponentThrows: [],
      actualOpponent: "PAPER",
    });
    assert.equal(predicted, "PAPER");
  });

  it("predicts repeat when opponent is on a streak", () => {
    const intel = buildTacticalIntel(baseMatch(), ctx());
    const predicted = predictOpponentMoveForSignal("thisMatch", "repeat", { ...ctx(), tacticalIntel: intel }, {
      roundNumber: 3,
      priorPairs: [
        { bot: "SCISSORS", opponent: "PAPER" },
        { bot: "ROCK", opponent: "PAPER" },
      ],
      priorOpponentThrows: ["PAPER", "PAPER"],
      actualOpponent: "PAPER",
    });
    assert.equal(predicted, "PAPER");
  });
});

describe("scoreAllRoundsInMatch", () => {
  it("scores multiple signals per round", () => {
    const intel = buildTacticalIntel(baseMatch(), ctx());
    const rows = scoreAllRoundsInMatch(baseMatch(), "bot", {
      ...ctx(),
      tacticalIntel: intel,
    });
    assert.ok(rows.length > 3, `expected many signal rows, got ${rows.length}`);
    const round1 = rows.filter((r) => r.roundNumber === 1);
    assert.ok(round1.length >= 2);
    const dominantHits = rows.filter(
      (r) => r.signal === "dominant" && r.source === "lifetime" && r.leanHit,
    );
    assert.ok(dominantHits.length >= 1);
  });
});
