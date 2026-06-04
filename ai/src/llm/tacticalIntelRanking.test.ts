import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { buildTacticalIntel, attachIntelEfficiencyRankings } from "./tacticalIntel.js";
import type { MatchDbContext } from "./matchContext.js";
import type { Match } from "../types.js";
import {
  intelSourceMinPrimaryMatches,
  rankIntelSourcesByEfficiency,
  rankHistoricalIntelSourcesByEfficiency,
  selectPrimarySourceForMatch,
} from "./tacticalIntelRanking.js";

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
  globalBotMatches: [],
  queryLimits: { headToHead: 0, recentBot: 0, globalBot: 0 },
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

describe("selectPrimarySourceForMatch", () => {
  it("prefers under-sampled lifetime over default h2h primary", () => {
    const intel = buildTacticalIntel(match(), ctx());
    assert.equal(intel.primarySource, "h2h");

    const historicalPrimary = [
      { source: "lifetime", matches: 2, wins: 2, winPct: 100 },
      { source: "h2h", matches: 313, wins: 163, winPct: 52.1 },
      { source: "recentVsOpponent", matches: 13, wins: 5, winPct: 38.5 },
    ];
    const ranked = attachIntelEfficiencyRankings(intel, [], historicalPrimary);
    assert.equal(ranked.primarySource, "lifetime");

    const explored = selectPrimarySourceForMatch(ranked, historicalPrimary);
    assert.equal(explored?.primarySource, "lifetime");
  });

  it("fair share scales with archived primary history", () => {
    const min = intelSourceMinPrimaryMatches(4, [
      { source: "lifetime", matches: 2, wins: 2, winPct: 100 },
      { source: "h2h", matches: 313, wins: 163, winPct: 52.1 },
    ]);
    assert.equal(min, Math.max(5, Math.ceil(315 / 4)));
  });
});

describe("rankHistoricalIntelSourcesByEfficiency", () => {
  it("always lists global when other sources have history", () => {
    const ranked = rankHistoricalIntelSourcesByEfficiency(
      [
        { source: "lifetime", leanHits: 500, leanRounds: 1444, leanPct: 37.7 },
        { source: "h2h", leanHits: 500, leanRounds: 1412, leanPct: 36 },
        { source: "recentVsOpponent", leanHits: 500, leanRounds: 1438, leanPct: 35.7 },
        { source: "global", leanHits: 0, leanRounds: 0, leanPct: 0 },
      ],
      [{ source: "h2h", matches: 227, wins: 115, winPct: 50.7 }],
    );
    assert.equal(ranked.length, 4);
    assert.ok(ranked.some((r) => r.source === "global"));
    const global = ranked.find((r) => r.source === "global");
    assert.equal(global!.leanRoundsHistorical, 0);
    assert.equal(global!.rank, 4);
  });
});
