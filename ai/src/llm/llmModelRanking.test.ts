import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseLlmModels } from "../config.js";
import {
  formatLlmModelsRankedLog,
  llmModelMinMatchesExploration,
  rankLlmModels,
  scoreLlmModel,
  selectLlmModelForMatch,
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
    assert.match(block, /Default when exploration done: m1/);
  });
});

describe("selectLlmModelForMatch", () => {
  const warmupOk = { listed: true, warmupOk: true, warmupMs: 1000 };

  it("picks the model with fewest matches when under fair-share target", () => {
    const historical = [
      {
        model: "qwen2.5:3b",
        matches: 140,
        wins: 76,
        losses: 64,
        draws: 0,
        winPct: 54.3,
        totalEloDelta: -15,
        avgEloDelta: -0.1,
      },
      {
        model: "gemma3:4b",
        matches: 10,
        wins: 4,
        losses: 6,
        draws: 0,
        winPct: 40,
        totalEloDelta: -1,
        avgEloDelta: -0.1,
      },
      {
        model: "llama3.2:3b",
        matches: 3,
        wins: 1,
        losses: 2,
        draws: 0,
        winPct: 33.3,
        totalEloDelta: -4,
        avgEloDelta: -1.3,
      },
    ];
    const ranked = rankLlmModels(
      ["qwen2.5:3b", "gemma3:4b", "llama3.2:3b"],
      historical,
      [
        { model: "qwen2.5:3b", ...warmupOk },
        { model: "gemma3:4b", ...warmupOk },
        { model: "llama3.2:3b", ...warmupOk },
      ],
    );
    assert.equal(llmModelMinMatchesExploration(3, historical), 51);
    assert.equal(selectLlmModelForMatch(ranked, historical), "llama3.2:3b");
  });

  it("uses top-ranked model once all models meet the exploration target", () => {
    const historical = [
      {
        model: "qwen2.5:3b",
        matches: 50,
        wins: 30,
        losses: 20,
        draws: 0,
        winPct: 60,
        totalEloDelta: 10,
        avgEloDelta: 0.2,
      },
      {
        model: "gemma3:4b",
        matches: 50,
        wins: 20,
        losses: 30,
        draws: 0,
        winPct: 40,
        totalEloDelta: -5,
        avgEloDelta: -0.1,
      },
    ];
    const ranked = rankLlmModels(
      ["qwen2.5:3b", "gemma3:4b"],
      historical,
      [
        { model: "qwen2.5:3b", ...warmupOk },
        { model: "gemma3:4b", ...warmupOk },
      ],
    );
    assert.equal(selectLlmModelForMatch(ranked, historical), "qwen2.5:3b");
  });
});
