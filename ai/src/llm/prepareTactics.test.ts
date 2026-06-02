import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { tendencyFromCounts } from "./opponentTendency.js";
import {
  buildFallbackTactics,
  clampTactics,
  tacticsContradictsCounter,
  tacticsLooksValid,
} from "./prepareTactics.js";

const paperLean = tendencyFromCounts({ rock: 595, paper: 913, scissors: 632 })!;

describe("prepareTactics", () => {
  it("clamps long plans", () => {
    const long = "A".repeat(400);
    const out = clampTactics(long, 100);
    assert.ok(out.length <= 100);
  });

  it("rejects raw count dumps and wrong counters", () => {
    const bad =
      "Daniil shows a strong preference for Paper (958) and weakness against Scissors (688). I'll open with Rock to exploit this.";
    assert.equal(tacticsLooksValid(bad, paperLean), false);
    assert.equal(tacticsContradictsCounter(bad, paperLean), true);
  });

  it("accepts correct counter plan", () => {
    const good =
      "Daniil leans Paper. Open with Scissors to beat it; if they shift to Rock, answer with Paper.";
    assert.equal(tacticsLooksValid(good, paperLean), true);
  });

  it("fallback uses correct opening vs paper lean", () => {
    const line = buildFallbackTactics("Daniil", paperLean);
    assert.match(line, /Open with Scissors/i);
    assert.ok(!/Open with Rock/i.test(line));
  });
});
