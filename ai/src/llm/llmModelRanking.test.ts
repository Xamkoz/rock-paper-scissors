import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseLlmModels } from "../config.js";
import {
  formatLlmModelsRankedLog,
  rankLlmModels,
  scoreLlmModel,
} from "./llmModelRanking.js";

describe("parseLlmModels", () => {
  it("uses LLM_MODEL when LLM_MODELS unset", () => {
    assert.deepEqual(parseLlmModels("gemma3:4b"), ["gemma3:4b"]);
  });

  it("parses comma list and caps at three", () => {
    assert.deepEqual(
      parseLlmModels("a", "b,c,d,e"),
      ["b", "c", "d"],
    );
  });

  it("dedupes while preserving order", () => {
    assert.deepEqual(parseLlmModels("x", "a,a,b"), ["a", "b"]);
  });
});

describe("rankLlmModels", () => {
  it("prefers higher win rate and ELO when warmup succeeds", () => {
    const ranked = rankLlmModels(
      ["fast", "slow"],
      [
        {
          model: "slow",
          matches: 10,
          wins: 9,
          losses: 1,
          draws: 0,
          winPct: 90,
          totalEloDelta: 80,
          avgEloDelta: 8,
        },
        {
          model: "fast",
          matches: 10,
          wins: 5,
          losses: 5,
          draws: 0,
          winPct: 50,
          totalEloDelta: -20,
          avgEloDelta: -2,
        },
      ],
      [
        { model: "fast", listed: true, warmupOk: true, warmupMs: 800 },
        { model: "slow", listed: true, warmupOk: true, warmupMs: 2000 },
      ],
    );
    assert.equal(ranked[0]?.model, "slow");
    assert.equal(ranked[0]?.rank, 1);
    assert.ok((ranked[0]?.score ?? 0) > (ranked[1]?.score ?? 0));
  });

  it("puts failed warmup last", () => {
    const ranked = rankLlmModels(
      ["bad", "good"],
      [],
      [
        { model: "bad", listed: true, warmupOk: false, warmupMs: 100 },
        { model: "good", listed: true, warmupOk: true, warmupMs: 500 },
      ],
    );
    assert.equal(ranked[0]?.model, "good");
    assert.equal(ranked[1]?.model, "bad");
    assert.equal(scoreLlmModel(null, ranked[1]!.warmup), 0);
  });

  it("formats startup rank table with ELO column", () => {
    const ranked = rankLlmModels(
      ["m1"],
      [
        {
          model: "m1",
          matches: 4,
          wins: 3,
          losses: 1,
          draws: 0,
          winPct: 75,
          totalEloDelta: 12,
          avgEloDelta: 3,
        },
      ],
      [{ model: "m1", listed: true, warmupOk: true, warmupMs: 1200 }],
    );
    const block = formatLlmModelsRankedLog(ranked);
    assert.match(block, /#1 ★/);
    assert.match(block, /m1/);
    assert.match(block, /\+3\/match/);
    assert.match(block, /1\.2s ok/);
    assert.match(block, /Active model for matches: m1/);
  });
});
