import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  pickMoveMaxTokens,
  pickMoveTimeoutCapMs,
  tacticsMaxTokens,
  tacticsUseLlm,
} from "./timing.js";

describe("timing defaults (speed)", () => {
  it("uses higher pick token budget for thoughtProcess", () => {
    const prevPick = process.env.LLM_PICK_MAX_TOKENS;
    const prevTactics = process.env.LLM_TACTICS_MAX_TOKENS;
    const prevUseLlm = process.env.LLM_TACTICS_USE_LLM;
    delete process.env.LLM_PICK_MAX_TOKENS;
    delete process.env.LLM_TACTICS_MAX_TOKENS;
    delete process.env.LLM_TACTICS_USE_LLM;
    try {
      assert.equal(pickMoveMaxTokens(), 512);
      assert.equal(pickMoveTimeoutCapMs(), 30_000);
      assert.equal(tacticsMaxTokens(), 128);
      assert.equal(tacticsUseLlm(), false);
    } finally {
      if (prevPick === undefined) delete process.env.LLM_PICK_MAX_TOKENS;
      else process.env.LLM_PICK_MAX_TOKENS = prevPick;
      if (prevTactics === undefined) delete process.env.LLM_TACTICS_MAX_TOKENS;
      else process.env.LLM_TACTICS_MAX_TOKENS = prevTactics;
      if (prevUseLlm === undefined) delete process.env.LLM_TACTICS_USE_LLM;
      else process.env.LLM_TACTICS_USE_LLM = prevUseLlm;
    }
  });
});
