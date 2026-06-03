import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { analyzeThrowPattern, analyzeThrowPatternFromPairs } from "./throwPatternIntel.js";

describe("analyzeThrowPattern", () => {
  it("computes distribution, repeat rate, and transitions", () => {
    const throws = ["PAPER", "PAPER", "SCISSORS", "ROCK", "PAPER"] as const;
    const p = analyzeThrowPattern([...throws]);
    assert.equal(p?.dominant, "PAPER");
    assert.equal(p?.counts.paper, 3);
    assert.ok(p!.repeatRatePct >= 20);
    assert.ok(p!.alternationRatePct >= 20);
    assert.ok(p!.transitions.length > 0);
  });

  it("computes second-order transitions and streak break bias", () => {
    const throws = [
      "ROCK",
      "ROCK",
      "ROCK",
      "PAPER",
      "PAPER",
      "SCISSORS",
    ] as const;
    const p = analyzeThrowPattern([...throws]);
    assert.ok(p!.secondOrderTransitions.length > 0);
    assert.ok(p!.streakBreakBias && p!.streakBreakBias.sample >= 2);
  });
});

describe("analyzeThrowPatternFromPairs", () => {
  it("builds responseToBot and outcome throws from pair sequence", () => {
    const p = analyzeThrowPatternFromPairs([
      { bot: "ROCK", opponent: "PAPER" },
      { bot: "SCISSORS", opponent: "PAPER" },
      { bot: "PAPER", opponent: "SCISSORS" },
      { bot: "ROCK", opponent: "ROCK" },
    ]);
    assert.equal(p?.dominant, "PAPER");
    assert.ok(p!.responseToBot.length > 0);
    assert.ok(p!.outcomeThrows && p!.outcomeThrows.afterBotWin.sample >= 1);
  });
});
