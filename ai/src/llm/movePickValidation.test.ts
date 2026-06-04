import { describe, it } from "node:test";
import assert from "node:assert/strict";
import type { Match } from "../types.js";
import type { MatchDbContext } from "./matchContext.js";
import type { TacticalIntel } from "./tacticalIntel.js";
import {
  buildDeterministicMovePick,
  ensureCounterMatchesOpponentThrow,
  normalizeMovePick,
  reasonClaimsInvalidBeat,
  reasonLooksLikeTacticsDump,
  reasonPrimaryMoveMatchesChoice,
  sanitizeThoughtProcess,
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
  it("detects invalid beat claims in reason", () => {
    assert.ok(reasonClaimsInvalidBeat("Scissors beats Rock to break the streak."));
    assert.ok(!reasonClaimsInvalidBeat("Paper beats Rock."));
  });

  it("corrects Scissors pick against Rock repeat to Paper", () => {
    const fixed = ensureCounterMatchesOpponentThrow(
      {
        choice: "SCISSORS",
        reason:
          "The opponent is on a ROCK streak (ROCK ×21), so I need to break this pattern by throwing SCISSORS, which beats ROCK.",
        intelSource: "thisMatch",
        intelSignal: "repeat",
      },
      {
        ...ctx(),
        tacticalIntel: {
          ...ctx().tacticalIntel,
          opponentRepeat: { move: "ROCK", streak: 21 },
        } as unknown as TacticalIntel,
      },
    );
    assert.equal(fixed.choice, "PAPER");
    assert.ok(!reasonClaimsInvalidBeat(fixed.reason));
    assert.match(fixed.reason, /Rock repeat/i);
  });

  it("prefers live in-match repeat over stale pre-match opponentRepeat", () => {
    const fixed = ensureCounterMatchesOpponentThrow(
      {
        choice: "SCISSORS",
        reason: "Scissors counters Guest's Paper repeat (thisMatch).",
        intelSource: "thisMatch",
        intelSignal: "repeat",
      },
      {
        ...ctx(),
        tacticalIntel: {
          ...ctx().tacticalIntel,
          opponentRepeat: { move: "PAPER", streak: 5 },
        } as unknown as TacticalIntel,
        currentMatch: {
          id: "m1",
          player1: "bot",
          player2: "opp",
          rounds: [
            {
              roundNumber: 1,
              player1Choice: "SCISSORS",
              player2Choice: "ROCK",
              player1Submitted: true,
              player2Submitted: true,
              winner: "opp",
              resolvedAt: 1,
            },
            {
              roundNumber: 2,
              player1Choice: "SCISSORS",
              player2Choice: "ROCK",
              player1Submitted: true,
              player2Submitted: true,
              winner: "opp",
              resolvedAt: 2,
            },
          ],
        } as Match,
      },
    );
    assert.equal(fixed.choice, "PAPER");
    assert.match(fixed.reason, /Rock repeat/i);
  });

  it("rewrites round-1 thisMatch/repeat without live streak to preparedTactics", () => {
    const fixed = normalizeMovePick(
      {
        choice: "SCISSORS",
        reason: "Scissors counters Guest's Paper repeat (thisMatch).",
        intelSource: "thisMatch",
        intelSignal: "repeat",
      },
      {
        ...ctx(),
        tacticalIntel: {
          ...ctx().tacticalIntel,
          opponentRepeat: { move: "PAPER", streak: 5 },
        } as unknown as TacticalIntel,
      },
      [
        { source: "thisMatch", signals: ["preparedTactics", "thisMatchRounds"] },
        { source: "lifetime", signals: ["dominant", "openWith"] },
      ],
    );
    assert.equal(fixed.intelSignal, "preparedTactics");
    assert.match(fixed.reason, /pre-match plan/i);
  });

  it("rewrites preparedTactics to thisMatchRounds once throws exist", () => {
    const fixed = normalizeMovePick(
      {
        choice: "SCISSORS",
        reason: "Scissors follows the pre-match plan vs Guest.",
        intelSource: "thisMatch",
        intelSignal: "preparedTactics",
      },
      {
        ...ctx(),
        currentMatch: {
          id: "m1",
          player1: "bot",
          player2: "opp",
          rounds: [
            {
              roundNumber: 1,
              player1Choice: "SCISSORS",
              player2Choice: "PAPER",
              player1Submitted: true,
              player2Submitted: true,
              winner: "opp",
              resolvedAt: 1,
            },
          ],
        } as Match,
      },
      [{ source: "thisMatch", signals: ["preparedTactics", "thisMatchRounds"] }],
    );
    assert.equal(fixed.intelSignal, "thisMatchRounds");
    assert.match(fixed.reason, /Paper this match/i);
  });

  it("corrects repeatRate pick using last opponent throw in match", () => {
    const fixed = ensureCounterMatchesOpponentThrow(
      {
        choice: "SCISSORS",
        reason: "This match has a repeat rate of 100%, indicating they continue their pattern.",
        intelSource: "thisMatch",
        intelSignal: "repeatRate",
      },
      {
        ...ctx(),
        currentMatch: {
          id: "m1",
          player1: "bot",
          player2: "opp",
          rounds: [
            {
              roundNumber: 1,
              player1Choice: "PAPER",
              player2Choice: "ROCK",
              player1Submitted: true,
              player2Submitted: true,
              winner: "bot",
              resolvedAt: 1,
            },
            {
              roundNumber: 2,
              player1Choice: "PAPER",
              player2Choice: "ROCK",
              player1Submitted: true,
              player2Submitted: true,
              winner: "bot",
              resolvedAt: 2,
            },
          ],
        } as Match,
      },
    );
    assert.equal(fixed.choice, "PAPER");
    assert.match(fixed.reason, /Rock/i);
  });

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
    assert.equal(fixed.intelSignal, "preparedTactics");
    assert.match(fixed.reason, /pre-match plan/i);
  });

  it("rewrites thoughtProcess when it claims an invalid beat", () => {
    const parsed = {
      choice: "ROCK" as const,
      reason: "Counter scissors repeat.",
      intelSource: "thisMatch" as const,
      intelSignal: "repeat" as const,
      thoughtProcess:
        "Opponent last throw was SCISSORS. Scissors beats Paper. I choose SCISSORS.",
    };
    const fixed = normalizeMovePick(parsed, ctx(), catalog);
    assert.equal(fixed.choice, "ROCK");
    assert.ok(!reasonClaimsInvalidBeat(fixed.thoughtProcess ?? ""));
    assert.match(fixed.thoughtProcess ?? "", /Rock|ROCK/i);
  });

  it("deterministic pick opens with Scissors vs paper lean on round 1", () => {
    const pick = buildDeterministicMovePick(ctx(), catalog);
    assert.equal(pick.choice, "SCISSORS");
    assert.equal(pick.intelSignal, "openWith");
  });

  it("deterministic pick counters live scissors repeat with Rock", () => {
    const pick = buildDeterministicMovePick(
      {
        ...ctx(),
        currentMatch: {
          id: "m1",
          player1: "bot",
          player2: "opp",
          rounds: [
            {
              roundNumber: 1,
              player1Choice: "PAPER",
              player2Choice: "SCISSORS",
              player1Submitted: true,
              player2Submitted: true,
              winner: "opp",
              resolvedAt: 1,
            },
            {
              roundNumber: 2,
              player1Choice: "ROCK",
              player2Choice: "SCISSORS",
              player1Submitted: true,
              player2Submitted: true,
              winner: "opp",
              resolvedAt: 2,
            },
          ],
        } as Match,
      },
      [
        { source: "lifetime", signals: ["dominant", "openWith"] },
        {
          source: "thisMatch",
          signals: ["repeat", "thisMatchRounds", "preparedTactics"],
        },
      ],
    );
    assert.equal(pick.choice, "ROCK");
    assert.equal(pick.intelSignal, "repeat");
  });
});
