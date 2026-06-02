import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { buildTacticalIntel } from "./tacticalIntel.js";
import type { MatchDbContext } from "./matchContext.js";
import type { Match } from "../types.js";
import { rankIntelSourcesByEfficiency } from "./tacticalIntelRanking.js";

const ctx = (): MatchDbContext => ({
  botUid: "bot",
  opponentUid: "opp",
  opponentName: "Daniil",
  opponentProfile: {
    uid: "opp",
    displayName: "Daniil",
    elo: 900,
    throwsRock: 100,
    throwsPaper: 400,
    throwsScissors: 100,
  },
  currentMatch: null,
  headToHead: [
    {
      id: "h1",
      opponentUid: "opp",
      opponentName: "Daniil",
      matchMode: "BO3",
      botWins: 2,
      opponentWins: 0,
      rounds: [
        { roundNumber: 1, opponentMove: "PAPER", botMove: "SCISSORS" },
        { roundNumber: 2, opponentMove: "PAPER", botMove: "SCISSORS" },
        { roundNumber: 3, opponentMove: "PAPER", botMove: "SCISSORS" },
        { roundNumber: 4, opponentMove: "PAPER", botMove: "SCISSORS" },
        { roundNumber: 5, opponentMove: "PAPER", botMove: "SCISSORS" },
        { roundNumber: 6, opponentMove: "SCISSORS", botMove: "ROCK" },
      ],
    },
  ],
  recentBotMatches: [],
  queryLimits: { headToHead: 0, recentBot: 0 },
});

const match = (): Match => ({
  id: "m1",
  player1: "bot",
  player2: "opp",
  player1Name: "Bot",
  player2Name: "Daniil",
  matchMode: "BO3",
  status: "active",
  player1Ready: true,
  player2Ready: true,
  readyDeadlineAt: 0,
  currentRound: 1,
  player1Wins: 0,
  player2Wins: 0,
  rounds: [],
  createdAt: 0,
  lastActivityAt: 0,
});

describe("rankIntelSourcesByEfficiency", () => {
  it("ranks h2h above lifetime when h2h has more sample and better history", () => {
    const intel = buildTacticalIntel(match(), ctx());
    const ranked = rankIntelSourcesByEfficiency(
      intel,
      [
        { source: "lifetime", leanHits: 45, leanRounds: 100, leanPct: 45 },
        { source: "h2h", leanHits: 18, leanRounds: 20, leanPct: 90 },
        { source: "recentVsOpponent", leanHits: 2, leanRounds: 3, leanPct: 66.7 },
      ],
      [
        { source: "h2h", matches: 8, wins: 7, winPct: 87.5 },
        { source: "lifetime", matches: 20, wins: 9, winPct: 45 },
      ],
    );
    assert.equal(ranked[0]!.source, "h2h");
    assert.equal(ranked[0]!.rank, 1);
  });
});
