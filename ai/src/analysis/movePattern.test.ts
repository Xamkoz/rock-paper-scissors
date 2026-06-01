import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { analyzeMovePattern, collectOpponentThrows } from "./movePattern.js";
import type { Match } from "../types.js";

const baseMatch = (overrides: Partial<Match>): Match => ({
  id: "m1",
  player1: "bot",
  player2: "human",
  player1Name: "Bot",
  player2Name: "Human",
  matchMode: "BO3",
  status: "completed",
  player1Ready: true,
  player2Ready: true,
  readyDeadlineAt: 0,
  currentRound: 1,
  player1Wins: 1,
  player2Wins: 0,
  rounds: [],
  createdAt: 0,
  lastActivityAt: 0,
  ...overrides,
});

describe("movePattern", () => {
  it("collects opponent throws from resolved rounds", () => {
    const match = baseMatch({
      rounds: [
        {
          roundNumber: 1,
          player1Submitted: true,
          player2Submitted: true,
          player1Choice: "ROCK",
          player2Choice: "SCISSORS",
          resolvedAt: 1,
        },
        {
          roundNumber: 2,
          player1Submitted: true,
          player2Submitted: true,
          player1Choice: "PAPER",
          player2Choice: "SCISSORS",
          resolvedAt: 2,
        },
      ],
    });
    const throws = collectOpponentThrows("bot", "human", [match]);
    assert.deepEqual(throws, ["SCISSORS", "SCISSORS"]);
  });

  it("picks counter to dominant opponent move", () => {
    const match = baseMatch({
      rounds: [
        {
          roundNumber: 1,
          player1Submitted: true,
          player2Submitted: true,
          player1Choice: "ROCK",
          player2Choice: "ROCK",
          resolvedAt: 1,
        },
        {
          roundNumber: 2,
          player1Submitted: true,
          player2Submitted: true,
          player1Choice: "ROCK",
          player2Choice: "ROCK",
          resolvedAt: 2,
        },
      ],
    });
    const summary = analyzeMovePattern("bot", "human", [match]);
    assert.equal(summary.dominantMove, "ROCK");
    assert.equal(summary.counterMove, "PAPER");
  });
});
