import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { tendencyFromCounts } from "./opponentTendency.js";

describe("tendencyFromCounts", () => {
  it("suggests Scissors vs Paper lean", () => {
    const t = tendencyFromCounts({ rock: 595, paper: 913, scissors: 632 });
    assert.equal(t?.dominant, "PAPER");
    assert.equal(t?.openWith, "SCISSORS");
  });
});
