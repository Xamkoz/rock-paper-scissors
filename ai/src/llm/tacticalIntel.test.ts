import { describe, it } from "node:test";
import assert from "node:assert/strict";
import type { MatchDbContext } from "./matchContext.js";
import type { Match } from "../types.js";
import { buildTacticalIntel, formatTacticalIntelCompact } from "./tacticalIntel.js";

const baseMatch = (): Match => ({
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
  headToHead: [
    {
      id: "h1",
      opponentUid: "opp",
      opponentName: "Daniil",
      matchMode: "BO3",
      botWins: 2,
      opponentWins: 1,
      rounds: [
        { roundNumber: 1, opponentMove: "PAPER", botMove: "SCISSORS" },
        { roundNumber: 2, opponentMove: "PAPER", botMove: "ROCK" },
      ],
    },
  ],
  recentBotMatches: [],
  queryLimits: { headToHead: 5, recentBot: 0 },
});

describe("buildTacticalIntel", () => {
  it("includes lifetime, h2h, and compact log", () => {
    const intel = buildTacticalIntel(baseMatch(), ctx());
    assert.equal(intel.lifetime?.dominant, "PAPER");
    assert.equal(intel.h2h?.sampleThrows, 2);
    assert.equal(intel.h2hRecord.games, 1);
    assert.equal(intel.primary?.openWith, "SCISSORS");
    const line = formatTacticalIntelCompact(intel);
    assert.match(line, /vs=Daniil/);
    assert.match(line, /life=P/);
    assert.match(line, /read=PAPER→open SCISSORS/);
    assert.equal(intel.lifetime?.patterns.ranked[0]?.move, "PAPER");
    assert.ok(intel.lifetime!.patterns.repeatRatePct >= 0);
  });
});
