import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { allIntelSignals } from "./moveIntelCatalog.js";
import type { MoveIntelSignal } from "./parse.js";
import {
  formatIntelSignalsRankedLog,
  intelSignalMinPicksExploration,
  pickExplorationSignals,
  rankIntelSignalsByPickEfficiency,
  aggregatePicksBySignal,
  aggregateSignalSamples,
} from "./intelCitationRanking.js";

describe("rankIntelSignalsByPickEfficiency", () => {
  it("ranks signals globally, aggregating picks across sources", () => {
    const ranked = rankIntelSignalsByPickEfficiency([
      {
        source: "h2h",
        signal: "transitions",
        picks: 10,
        roundWins: 7,
        roundWinPct: 70,
      },
      {
        source: "h2h",
        signal: "dominant",
        picks: 5,
        roundWins: 2,
        roundWinPct: 40,
      },
      {
        source: "recentVsOpponent",
        signal: "dominant",
        picks: 49,
        roundWins: 13,
        roundWinPct: 26.5,
      },
    ]);

    assert.equal(ranked.length, allIntelSignals().length);
    assert.equal(ranked[0]!.signal, "transitions");
    const dominant = ranked.find((r) => r.signal === "dominant");
    assert.equal(dominant!.picks, 54);
    assert.equal(dominant!.roundWins, 15);
    assert.ok(ranked.some((r) => r.signal === "afterBotWin" && r.picks === 0));

    const signals = formatIntelSignalsRankedLog(ranked);
    assert.match(signals, /#1/);
    assert.match(signals, /transitions/);
    assert.match(signals, /Rank.*Signal.*Score/);
    assert.match(signals, /afterBotWin/);
    assert.doesNotMatch(signals, /Source/);
  });
});

describe("pickExplorationSignals", () => {
  it("prefers under-sampled signals from the pick catalog", () => {
    const catalog = [
      { source: "h2h" as const, signals: ["dominant", "transitions", "afterBotWin"] as MoveIntelSignal[] },
    ];
    const stats = [
      { source: "h2h" as const, signal: "dominant" as const, picks: 200, roundWins: 80, roundWinPct: 40 },
      { source: "h2h" as const, signal: "transitions" as const, picks: 5, roundWins: 2, roundWinPct: 40 },
    ];
    const bySignal = aggregatePicksBySignal(stats);
    assert.ok(intelSignalMinPicksExploration(3, bySignal) >= 5);
    const picked = pickExplorationSignals(1, 2, catalog, stats, ["dominant"]);
    assert.ok(picked.includes("afterBotWin") || picked.includes("transitions"));
    assert.ok(!picked.includes("dominant") || picked[0] !== "dominant");
  });

  it("reserves slots for never-cited signals before warm under-sampled ones", () => {
    const catalog = [
      {
        source: "lifetime" as const,
        signals: ["dominant", "opponentLifetime", "distribution"] as MoveIntelSignal[],
      },
      {
        source: "h2h" as const,
        signals: ["priorMatches", "transitions"] as MoveIntelSignal[],
      },
      {
        source: "recentVsOpponent" as const,
        signals: ["crossOpponent"] as MoveIntelSignal[],
      },
    ];
    const stats = [
      { source: "h2h" as const, signal: "dominant" as const, picks: 200, roundWins: 80, roundWinPct: 40 },
      { source: "h2h" as const, signal: "transitions" as const, picks: 10, roundWins: 4, roundWinPct: 40 },
    ];
    const picked = pickExplorationSignals(3, 4, catalog, stats, ["dominant"]);
    assert.ok(
      picked.some((s) =>
        ["opponentLifetime", "priorMatches", "crossOpponent", "distribution"].includes(s),
      ),
    );
    assert.ok(!picked.every((s) => s === "transitions" || s === "dominant"));
  });

  it("uses counterfactual lean opportunities when pick citations are sparse", () => {
    const catalog = [
      { source: "h2h" as const, signals: ["dominant", "transitions"] as MoveIntelSignal[] },
    ];
    const leanStats = [
      {
        source: "h2h" as const,
        signal: "transitions" as const,
        opportunities: 40,
        leanHits: 20,
        leanPct: 50,
      },
    ];
    const bySignal = aggregateSignalSamples([], leanStats);
    assert.equal(bySignal.get("transitions")?.leanOpps, 40);
    const picked = pickExplorationSignals(1, 1, catalog, [], ["dominant"], leanStats);
    assert.equal(picked[0], "dominant");
  });
});
