import { describe, it } from "node:test";
import assert from "node:assert/strict";
import type { MatchDbContext } from "./matchContext.js";
import type { TacticalIntel } from "./tacticalIntel.js";
import type { IntelCitationPickStats } from "../db/tacticalIntelCitationDb.js";
import { buildMoveIntelCatalog } from "./moveIntelCatalog.js";
import { buildIntelCitationHints } from "./moveIntelHints.js";
import { analyzeThrowPattern } from "./throwPatternIntel.js";

const h2hPatterns = analyzeThrowPattern(
  ["ROCK", "PAPER", "ROCK", "ROCK", "SCISSORS", "ROCK", "PAPER", "ROCK"] as const,
);

const intel = (): TacticalIntel =>
  ({
    bot: "Bot",
    opponent: "Opp",
    mode: "BO3",
    primarySource: "h2h",
    primary: {
      dominant: "ROCK",
      dominantPct: 60,
      distribution: { rockPct: 60, paperPct: 20, scissorsPct: 20 },
      openWith: "PAPER",
    },
    sourcesByEfficiency: [],
    h2h: {
      label: "h2h",
      sampleThrows: 30,
      dominant: "ROCK",
      dominantPct: 60,
      openWith: "PAPER",
      patterns: h2hPatterns,
    },
    h2hRecord: { games: 3, botSeriesWins: 2, opponentSeriesWins: 1 },
    recentOpponentThrows: ["ROCK", "PAPER"],
    priorH2hGames: [],
    counters: { ROCK: "PAPER", PAPER: "SCISSORS", SCISSORS: "ROCK" },
    crossPatterns: { opponent: null, bot: null, pairCount: 0 },
    opponentRepeat: { move: "ROCK", streak: 3 },
  }) as unknown as TacticalIntel;

const ctx = (): MatchDbContext => ({
  botUid: "bot",
  opponentUid: "opp",
  opponentName: "Opp",
  opponentProfile: null,
  currentMatch: null,
  headToHead: [],
  recentBotMatches: [],
  globalBotMatches: [],
  queryLimits: { headToHead: 0, recentBot: 0, globalBot: 0 },
  tactics: "Open Paper.",
  tacticalIntel: intel(),
});

describe("buildIntelCitationHints", () => {
  it("suggests varied signals including transitions and repeat", () => {
    const { catalog } = buildMoveIntelCatalog(ctx());
    const hints = buildIntelCitationHints(
      ctx(),
      2,
      [{ bot: "PAPER", opponent: "ROCK" }],
      catalog,
    );
    const signals = hints.map((h) => h.signal);
    assert.ok(signals.includes("repeat"));
    assert.ok(signals.includes("thisMatchRounds"));
    assert.ok(hints.length >= 3);
  });

  it("rotates secondary signals by round", () => {
    const { catalog } = buildMoveIntelCatalog(ctx());
    const stats: IntelCitationPickStats[] = [
      {
        source: "h2h",
        signal: "dominant",
        picks: 500,
        roundWins: 200,
        roundWinPct: 40,
      },
    ];
    const withStats = { ...ctx(), signalPickStats: stats };
    const r1 = buildIntelCitationHints(withStats, 1, [], catalog).map((h) => h.signal);
    const r2 = buildIntelCitationHints(withStats, 2, [], catalog).map((h) => h.signal);
    assert.notDeepEqual(r1, r2);
  });
});
