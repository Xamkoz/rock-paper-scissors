import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  buildMoveIntelCatalog,
  coerceCitationForCatalog,
} from "./moveIntelCatalog.js";
import type { MatchDbContext } from "./matchContext.js";
import type { TacticalIntel } from "./tacticalIntel.js";
import {
  formatMovePickLogLine,
  parseMoveChoice,
  parseMovePick,
} from "./parse.js";

describe("parseMoveChoice", () => {
  it("parses JSON choice", () => {
    assert.equal(
      parseMoveChoice(
        '{"choice":"PAPER","reason":"After Rock they throw Paper.","intelSource":"h2h","intelSignal":"transitions"}',
      ),
      "PAPER",
    );
  });

  it("parses plain text", () => {
    assert.equal(parseMoveChoice("I pick SCISSORS"), "SCISSORS");
  });
});

describe("parseMovePick", () => {
  it("salvages when model dumps citations into reason field", () => {
    const pick = parseMovePick(
      '{"choice":"PAPER","reason":"intelSource: h2h, intelSignal: dominant, lean: PAPER, score: 40.5"}',
    );
    assert.equal(pick?.choice, "PAPER");
    assert.equal(pick?.intelSource, "h2h");
    assert.equal(pick?.intelSignal, "dominant");
    assert.ok(pick!.reason.includes("h2h"));
  });

  it("parses choice, reason, source, and signal", () => {
    const pick = parseMovePick(
      '{"choice":"SCISSORS","reason":"h2h Rock lean — counter with Scissors.","intelSource":"h2h","intelSignal":"dominant"}',
    );
    assert.equal(pick?.choice, "SCISSORS");
    assert.equal(pick?.reason, "h2h Rock lean — counter with Scissors.");
    assert.equal(pick?.intelSource, "h2h");
    assert.equal(pick?.intelSignal, "dominant");
  });

  it("parses slash citation copied from citeHints", () => {
    const pick = parseMovePick(
      '{"choice":"SCISSORS","intelSource":"thisMatch/repeat","intelSignal":"Opponent on PAPER ×2 — exploit or break streak","reason":"The opponent has thrown PAPER twice in a row, which is a strong hint to counter with SCISSORS."}',
    );
    assert.equal(pick?.choice, "SCISSORS");
    assert.equal(pick?.intelSource, "thisMatch");
    assert.equal(pick?.intelSignal, "repeat");
    assert.ok(pick!.reason.includes("SCISSORS"));
  });

  it("accepts signal aliases", () => {
    const pick = parseMovePick(
      '{"choice":"ROCK","reason":"Mix skewed to Scissors.","intelSource":"recent","intelSignal":"distribution"}',
    );
    assert.equal(pick?.intelSource, "recentVsOpponent");
    assert.equal(pick?.intelSignal, "distribution");
  });

  it("infers citation from prose when fields omitted", () => {
    const pick = parseMovePick(
      '{"choice":"SCISSORS","reason":"h2h lean towards Paper; h2h record favors opening Scissors; recentSeq mixed."}',
    );
    assert.equal(pick?.choice, "SCISSORS");
    assert.equal(pick?.intelSource, "h2h");
    assert.equal(pick?.intelSignal, "h2hRecord");
  });

  it("salvages truncated JSON with choice and long reason", () => {
    const truncated =
      '{"choice":"SCISSORS","reason":"TacticalIntel indicates lean towards Paper (h2h, lifetime) and recommended opening SCISSORS. The h2h record shows wins when opening with SCISSORS. The recentSeq shows';
    const pick = parseMovePick(truncated);
    assert.equal(pick?.choice, "SCISSORS");
    assert.equal(pick?.intelSource, "h2h");
    assert.ok(pick?.intelSignal);
    assert.ok(pick!.reason.length > 0);
  });

  it("salvages truncated JSON when thoughtProcess is cut off last", () => {
    const truncated =
      '{"choice":"SCISSORS","intelSource":"h2h","intelSignal":"dominant","reason":"H2H Paper lean — counter with Scissors.","thoughtProcess":"PatternRead shows Paper lean. citeHints favor dominant, but repeat rate suggests a switch. The recent seq also fa';
    const pick = parseMovePick(truncated);
    assert.equal(pick?.choice, "SCISSORS");
    assert.equal(pick?.intelSource, "h2h");
    assert.equal(pick?.intelSignal, "dominant");
    assert.ok(pick!.thoughtProcess?.includes("PatternRead"));
  });

  it("parses thoughtProcess with choice and citation", () => {
    const pick = parseMovePick(
      '{"choice":"PAPER","intelSource":"h2h","intelSignal":"dominant","reason":"H2H Rock lean — Paper beats Rock.","thoughtProcess":"H2H Rock lean. citeHints match dominant. Paper beats Rock."}',
    );
    assert.equal(pick?.choice, "PAPER");
    assert.equal(pick?.thoughtProcess, "H2H Rock lean. citeHints match dominant. Paper beats Rock.");
    assert.equal(pick?.intelSource, "h2h");
    assert.equal(pick?.intelSignal, "dominant");
  });

  it("formats log line with source and signal", () => {
    assert.equal(
      formatMovePickLogLine({
        choice: "PAPER",
        reason: "Lifetime Paper skew.",
        intelSource: "lifetime",
        intelSignal: "distribution",
      }),
      'cite=lifetime/distribution reason="Lifetime Paper skew."',
    );
  });

  it("maps tacticalIntel payload key to primary catalog source", () => {
    const pick = parseMovePick(
      '{"choice":"SCISSORS","intelSource":"tacticalIntel","intelSignal":"openWith","reason":"Open with Scissors to beat Paper lean."}',
      { primarySource: "lifetime" },
    );
    assert.equal(pick?.choice, "SCISSORS");
    assert.equal(pick?.intelSource, "lifetime");
    assert.equal(pick?.intelSignal, "openWith");
  });
});

describe("buildMoveIntelCatalog", () => {
  it("lists pattern signals per source", () => {
    const intel = {
      lifetime: {
        dominant: "PAPER",
        dominantPct: 50,
        distribution: { rockPct: 25, paperPct: 50, scissorsPct: 25 },
        openWith: "SCISSORS",
        label: "lifetime",
        sampleThrows: 100,
        patterns: {
          skew: "medium",
          secondary: "ROCK",
          secondaryPct: 25,
          repeatRatePct: 30,
          lastWindow: { size: 5, distribution: {}, counts: { total: 5 } },
          transitions: [{ after: "ROCK", next: { sample: 3 } }],
          responseToBot: [],
        },
      },
      h2h: null,
      recentVsOpponent: null,
      primarySource: "lifetime",
      primary: {
        dominant: "PAPER",
        dominantPct: 50,
        distribution: { rockPct: 25, paperPct: 50, scissorsPct: 25 },
        openWith: "SCISSORS",
      },
      sourcesByEfficiency: [],
      crossPatterns: { opponent: null, bot: null, pairCount: 0 },
    } as unknown as TacticalIntel;

    const ctx: MatchDbContext = {
      botUid: "b",
      opponentUid: "o",
      opponentName: "X",
      opponentProfile: null,
      currentMatch: null,
      headToHead: [],
      recentBotMatches: [],
      globalBotMatches: [],
      queryLimits: { headToHead: 0, recentBot: 0, globalBot: 0 },
      tacticalIntel: intel,
    };

    const { catalog } = buildMoveIntelCatalog(ctx);
    const life = catalog.find((e) => e.source === "lifetime");
    assert.ok(life?.signals.includes("distribution"));
    assert.ok(life?.signals.includes("transitions"));
    assert.ok(life?.signals.includes("openWith"));
  });

  it("includes new pattern signals when data supports them", () => {
    const intel = {
      lifetime: null,
      h2h: {
        dominant: "ROCK",
        dominantPct: 50,
        distribution: { rockPct: 50, paperPct: 25, scissorsPct: 25 },
        openWith: "PAPER",
        label: "h2h",
        sampleThrows: 20,
        patterns: {
          skew: "medium",
          repeatRatePct: 40,
          alternationRatePct: 60,
          lastWindow: { size: 0, distribution: {}, counts: { total: 0 } },
          transitions: [{ after: "ROCK", next: { sample: 3 } }],
          secondOrderTransitions: [{ first: "ROCK", second: "ROCK", next: { sample: 2 } }],
          responseToBot: [],
          outcomeThrows: {
            afterBotWin: { sample: 3, rockPct: 33, paperPct: 33, scissorsPct: 34 },
            afterBotLoss: { sample: 2, rockPct: 50, paperPct: 25, scissorsPct: 25 },
          },
          streakBreakBias: { sample: 4, continuePct: 75, breakPct: 25 },
        },
      },
      recentVsOpponent: null,
      primarySource: "h2h",
      primary: {
        dominant: "ROCK",
        dominantPct: 50,
        distribution: { rockPct: 50, paperPct: 25, scissorsPct: 25 },
        openWith: "PAPER",
      },
      sourcesByEfficiency: [],
      crossPatterns: { opponent: null, bot: null, pairCount: 0 },
      opponentRepeat: { move: "SCISSORS", streak: 2 },
      h2hRecord: { games: 3, botSeriesWins: 2, opponentSeriesWins: 1 },
    } as unknown as TacticalIntel;

    const ctx: MatchDbContext = {
      botUid: "b",
      opponentUid: "o",
      opponentName: "X",
      opponentProfile: null,
      currentMatch: null,
      headToHead: [],
      recentBotMatches: [],
      globalBotMatches: [],
      queryLimits: { headToHead: 0, recentBot: 0, globalBot: 0 },
      tacticalIntel: intel,
    };

    const { catalog } = buildMoveIntelCatalog(ctx);
    const h2h = catalog.find((e) => e.source === "h2h");
    assert.ok(h2h?.signals.includes("repeat"));
    assert.ok(h2h?.signals.includes("alternationRate"));
    assert.ok(h2h?.signals.includes("afterBotWin"));
    assert.ok(h2h?.signals.includes("streakBreakBias"));
    assert.ok(
      coerceCitationForCatalog(catalog, "h2h", "repeat")?.signal === "repeat",
    );
  });

  it("never includes matchScore or clinchPressure in catalog", () => {
    const ctx: MatchDbContext = {
      botUid: "b",
      opponentUid: "o",
      opponentName: "X",
      opponentProfile: null,
      currentMatch: null,
      headToHead: [],
      recentBotMatches: [],
      globalBotMatches: [],
      queryLimits: { headToHead: 0, recentBot: 0, globalBot: 0 },
    };
    const { catalog } = buildMoveIntelCatalog(ctx, {
      opponentLeanThisMatch: "ROCK",
    });
    const tm = catalog.find((e) => e.source === "thisMatch");
    assert.ok(tm?.signals.includes("thisMatchRounds"));
    assert.ok(tm?.signals.includes("opponentLeanThisMatch"));
    assert.ok(!tm?.signals.includes("matchScore"));
    assert.ok(!tm?.signals.includes("clinchPressure"));
  });
});
