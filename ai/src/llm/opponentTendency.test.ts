import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { lastOpponentThrowFromMatch, moveBeats, tendencyFromCounts } from "./opponentTendency.js";

import type { Match } from "../types.js";

describe("lastOpponentThrowFromMatch", () => {
  it("reads opponent throw from last resolved round", () => {
    const match = {
      id: "m1",
      player1: "bot",
      player2: "opp",
      rounds: [
        {
          roundNumber: 1,
          player1Choice: "SCISSORS",
          player2Choice: "PAPER",
          player1Submitted: true,
          player2Submitted: true,
          winner: "opp",
          resolvedAt: 1,
        },
      ],
    } as Match;
    assert.equal(lastOpponentThrowFromMatch(match, "bot"), "PAPER");
  });
});

describe("moveBeats", () => {
  it("knows Paper beats Rock and Scissors does not", () => {
    assert.equal(moveBeats("PAPER", "ROCK"), true);
    assert.equal(moveBeats("SCISSORS", "ROCK"), false);
    assert.equal(moveBeats("ROCK", "SCISSORS"), true);
  });
});

describe("tendencyFromCounts", () => {
  it("suggests Scissors vs Paper lean", () => {
    const t = tendencyFromCounts({ rock: 595, paper: 913, scissors: 632 });
    assert.equal(t?.dominant, "PAPER");
    assert.equal(t?.openWith, "SCISSORS");
  });
});
