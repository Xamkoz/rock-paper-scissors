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
    assert.ok(p!.transitions.length > 0);
  });
});

describe("analyzeThrowPatternFromPairs", () => {
  it("builds responseToBot from pair sequence", () => {
    const p = analyzeThrowPatternFromPairs([
      { bot: "ROCK", opponent: "PAPER" },
      { bot: "SCISSORS", opponent: "PAPER" },
      { bot: "PAPER", opponent: "SCISSORS" },
    ]);
    assert.equal(p?.dominant, "PAPER");
    assert.ok(p!.responseToBot.length > 0);
  });
});
