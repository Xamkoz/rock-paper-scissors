import { describe, it } from "node:test";
import assert from "node:assert/strict";
import type { MatchDbContext } from "./matchContext.js";
import type { Match } from "../types.js";
import type { TacticalIntel } from "./tacticalIntel.js";
import {
  buildCompactMoveUserPrompt,
  buildFastMoveUserPrompt,
  buildFullMoveUserPrompt,
  useCompactMovePrompt,
} from "./movePrompt.js";

const baseMatch = (round: number, priorRounds: Match["rounds"]): Match => ({
  id: "m1",
  player1: "bot",
  player2: "opp",
  player1Name: "Bot",
  player2Name: "Daniil",
  matchMode: "BO10",
  status: "active",
  player1Ready: true,
  player2Ready: true,
  readyDeadlineAt: Date.now() + 60_000,
  currentRound: round,
  player1Wins: 3,
  player2Wins: 0,
  rounds: priorRounds,
  createdAt: 0,
  lastActivityAt: 0,
});

const intel = (): TacticalIntel =>
  ({
    bot: "Bot",
    opponent: "Daniil",
    mode: "BO10",
    primarySource: "h2h",
    primary: {
      dominant: "ROCK",
      dominantPct: 60,
      distribution: { rockPct: 60, paperPct: 20, scissorsPct: 20 },
      openWith: "PAPER",
    },
    sourcesByEfficiency: [],
    h2hRecord: { games: 5, botSeriesWins: 10, opponentSeriesWins: 5 },
    recentOpponentThrows: ["PAPER", "SCISSORS"],
    priorH2hGames: [],
    counters: { ROCK: "PAPER", PAPER: "SCISSORS", SCISSORS: "ROCK" },
    crossPatterns: { opponent: null, bot: null, pairCount: 0 },
  }) as unknown as TacticalIntel;

const ctx = (): MatchDbContext => ({
  botUid: "bot",
  opponentUid: "opp",
  opponentName: "Daniil",
  opponentProfile: {
    uid: "opp",
    displayName: "Daniil",
    elo: 900,
    throwsRock: 100,
    throwsPaper: 200,
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
      opponentWins: 1,
      rounds: [{ roundNumber: 1, botMove: "ROCK", opponentMove: "SCISSORS" }],
    },
  ],
  recentBotMatches: [],
  queryLimits: { headToHead: 5, recentBot: 0 },
  tactics: "Open Paper vs Rock lean.",
  tacticalIntel: intel(),
});

describe("useCompactMovePrompt", () => {
  it("is false on round 1 with no prior throws", () => {
    assert.equal(useCompactMovePrompt(1, 0), false);
  });

  it("is true from round 2 when match has history", () => {
    assert.equal(useCompactMovePrompt(2, 1), true);
    assert.equal(useCompactMovePrompt(8, 7), true);
  });
});

describe("buildFastMoveUserPrompt", () => {
  it("omits lifetime and pattern blob on later rounds", () => {
    const prior = [1, 2, 3, 4, 5, 6, 7].map((n) => ({
      roundNumber: n,
      player1Submitted: true,
      player2Submitted: true,
      player1Choice: "PAPER" as const,
      player2Choice: "ROCK" as const,
      winner: "bot" as const,
      resolvedAt: n,
    }));
    const match = baseMatch(8, prior);
    const full = JSON.parse(buildFullMoveUserPrompt(match, ctx())) as Record<string, unknown>;
    const compact = JSON.parse(buildFastMoveUserPrompt(match, ctx())) as Record<string, unknown>;

    assert.ok(full.opponentLifetime);
    assert.ok(full.tacticalIntel);
    assert.ok(full.priorMatches);
    assert.equal(compact.opponentLifetime, undefined);
    assert.equal(compact.tacticalIntel, undefined);
    assert.equal(compact.priorMatches, undefined);
    assert.equal(compact.preparedTactics, "Open Paper vs Rock lean.");
    assert.equal(compact.opponentLeanThisMatch, "ROCK");
    assert.equal((compact.read as { read: string }).read, "ROCK");
    assert.ok(JSON.stringify(compact).length < JSON.stringify(full).length);
  });

  it("uses full payload on round 1", () => {
    const match = baseMatch(1, []);
    const payload = JSON.parse(buildFastMoveUserPrompt(match, ctx())) as Record<string, unknown>;
    assert.ok(payload.opponentLifetime);
    assert.ok(payload.tacticalIntel);
  });

  it("round 1 omits raw cross history when tactical intel is present", () => {
    const match = baseMatch(1, []);
    const payload = JSON.parse(buildFastMoveUserPrompt(match, ctx())) as Record<string, unknown>;
    assert.equal(payload.crossMatchHistory, undefined);
    assert.equal(payload.crossPairs, undefined);
    const intel = payload.tacticalIntel as Record<string, unknown>;
    assert.ok(intel.recentSeq);
    assert.equal(intel.primaryPatterns, undefined);
    if (intel.crossOpponent) {
      assert.equal((intel.crossOpponent as { transitions?: unknown }).transitions, undefined);
    }
  });

  it("round 1 glossary only lists catalog signals", () => {
    const match = baseMatch(1, []);
    const payload = JSON.parse(buildFastMoveUserPrompt(match, ctx())) as {
      intelCatalog: Array<{ signals: string[] }>;
      intelSignalGlossary: Record<string, string>;
    };
    const cited = new Set(payload.intelCatalog.flatMap((e) => e.signals));
    for (const key of Object.keys(payload.intelSignalGlossary)) {
      assert.ok(cited.has(key), `unexpected glossary key ${key}`);
    }
    assert.ok(Object.keys(payload.intelSignalGlossary).length < 20);
  });
});
