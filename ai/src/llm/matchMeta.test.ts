import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { buildMatchScoreMeta, winsToFinish } from "./matchMeta.js";

describe("matchMeta", () => {
  it("computes clinch pressure when one win from series", () => {
    assert.equal(winsToFinish("BO3"), 2);
    const meta = buildMatchScoreMeta("BO3", 1, 1);
    assert.equal(meta.clinchPressure, true);
    assert.equal(meta.botWinsNeeded, 1);
    assert.equal(meta.opponentWinsNeeded, 1);
  });
});
