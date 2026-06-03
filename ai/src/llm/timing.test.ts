import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  padPickThinkTime,
  pickMoveMaxTokens,
  pickMoveTimeoutCapMs,
  pickThinkMultiplier,
  tacticsMaxTokens,
  tacticsUseLlm,
} from "./timing.js";

describe("timing defaults (speed)", () => {
  it("uses think multiplier without raising timeout caps", () => {
    const prevPick = process.env.LLM_PICK_MAX_TOKENS;
    const prevTactics = process.env.LLM_TACTICS_MAX_TOKENS;
    const prevUseLlm = process.env.LLM_TACTICS_USE_LLM;
    const prevMult = process.env.LLM_PICK_THINK_MULTIPLIER;
    delete process.env.LLM_PICK_MAX_TOKENS;
    delete process.env.LLM_TACTICS_MAX_TOKENS;
    delete process.env.LLM_TACTICS_USE_LLM;
    delete process.env.LLM_PICK_THINK_MULTIPLIER;
    try {
      assert.equal(pickMoveMaxTokens(), 128);
      assert.equal(pickMoveTimeoutCapMs(), 30_000);
      assert.equal(tacticsMaxTokens(), 128);
      assert.equal(tacticsUseLlm(), false);
      assert.equal(pickThinkMultiplier(), 1.2);
    } finally {
      if (prevPick === undefined) delete process.env.LLM_PICK_MAX_TOKENS;
      else process.env.LLM_PICK_MAX_TOKENS = prevPick;
      if (prevTactics === undefined) delete process.env.LLM_TACTICS_MAX_TOKENS;
      else process.env.LLM_TACTICS_MAX_TOKENS = prevTactics;
      if (prevUseLlm === undefined) delete process.env.LLM_TACTICS_USE_LLM;
      else process.env.LLM_TACTICS_USE_LLM = prevUseLlm;
      if (prevMult === undefined) delete process.env.LLM_PICK_THINK_MULTIPLIER;
      else process.env.LLM_PICK_THINK_MULTIPLIER = prevMult;
    }
  });

  it("pads pick time up to multiplier within phase budget", async () => {
    const started = Date.now();
    const padMs = await padPickThinkTime(1000, 5000);
    assert.equal(padMs, 200);
    assert.ok(Date.now() - started >= 150);
  });
});
