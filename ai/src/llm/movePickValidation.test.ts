import { describe, it } from "node:test";
import assert from "node:assert/strict";
import type { MatchDbContext } from "./matchContext.js";
import type { TacticalIntel } from "./tacticalIntel.js";
import {
  normalizeMovePick,
  reasonLooksLikeTacticsDump,
  reasonPrimaryMoveMatchesChoice,
} from "./movePickValidation.js";

const ctx = (): MatchDbContext => ({
  botUid: "bot",
  opponentUid: "opp",
  opponentName: "Daniil (melkor217)",
  opponentProfile: null,
  currentMatch: null,
  headToHead: [],
  recentBotMatches: [],
  globalBotMatches: [],
  queryLimits: { headToHead: 5, recentBot: 0, globalBot: 0 },
  tactics:
    "Daniil leans Paper (~53%). Open with Scissors to beat that. If they throw Rock use Paper.",
  tacticalIntel: {
    primarySource: "lifetime",
    primary: { dominant: "PAPER", openWith: "SCISSORS", dominantPct: 53 },
  } as unknown as TacticalIntel,
});

import type { MoveIntelSignal } from "./parse.js";

const catalog = [
  { source: "lifetime" as const, signals: ["dominant", "openWith"] as MoveIntelSignal[] },
  {
    source: "thisMatch" as const,
    signals: ["thisMatchRounds", "preparedTactics"] as MoveIntelSignal[],
  },
];

describe("movePickValidation", () => {
  it("detects tactics dump pasted into reason", () => {
    const reason =
      "Daniil leans Paper (~53%). Open with Scissors to beat that. If they throw Rock use Paper.";
    assert.ok(reasonLooksLikeTacticsDump(reason));
    assert.equal(reasonPrimaryMoveMatchesChoice(reason, "PAPER"), false);
  });

  it("rewrites bad round-1 pick to match choice and openWith citation", () => {
    const fixed = normalizeMovePick(
      {
        choice: "PAPER",
        reason:
          "Daniil (melkor217) leans Paper (~53%). Open with Scissors to beat that. If they throw Rock use Paper.",
        intelSource: "thisMatch",
        intelSignal: "matchScore",
      },
      ctx(),
      catalog,
    );
    assert.equal(fixed.intelSignal, "preparedTactics");
    assert.equal(fixed.intelSource, "thisMatch");
    assert.match(fixed.reason, /Paper follows the pre-match plan/i);
    assert.ok(!reasonLooksLikeTacticsDump(fixed.reason));
  });

  it("recites matchScore citation to throw intel", () => {
    const fixed = normalizeMovePick(
      {
        choice: "PAPER",
        reason: "Score 0-1 (need 2 to win, opp needs 1) — Paper beats Rock.",
        intelSource: "thisMatch",
        intelSignal: "matchScore",
      },
      ctx(),
      [
        { source: "thisMatch", signals: ["thisMatchRounds", "opponentLeanThisMatch"] },
        { source: "lifetime", signals: ["dominant", "openWith"] },
      ],
    );
    assert.equal(fixed.intelSignal, "opponentLeanThisMatch");
    assert.match(fixed.reason, /in-match lean/i);
  });

  it("recites clinchPressure citation to thisMatchRounds when no throw read in reason", () => {
    const fixed = normalizeMovePick(
      {
        choice: "SCISSORS",
        reason: "Score 0-0 (need 2 to win, opp needs 2).",
        intelSource: "thisMatch",
        intelSignal: "clinchPressure",
      },
      ctx(),
      [
        { source: "thisMatch", signals: ["thisMatchRounds", "preparedTactics"] },
        { source: "lifetime", signals: ["dominant", "openWith"] },
      ],
    );
    assert.equal(fixed.intelSignal, "thisMatchRounds");
    assert.match(fixed.reason, /throws so far this match/i);
  });
});
