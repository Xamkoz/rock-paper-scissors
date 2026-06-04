import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { accumulateProfileStatsFromMatch } from "./profileStatsBackfill";

describe("accumulateProfileStatsFromMatch", () => {
  it("counts one win and one loss from a completed match", () => {
    const map = new Map();
    accumulateProfileStatsFromMatch(map, {
      status: "completed",
      player1: "alice",
      player2: "bob",
      winnerId: "alice",
      player1Wins: 2,
      player2Wins: 0,
      rounds: [
        {
          resolvedAt: 1,
          winner: "alice",
          player1Choice: "ROCK",
          player2Choice: "SCISSORS",
          player1Submitted: true,
          player2Submitted: true,
          player1MoveMs: 900,
          player2MoveMs: 1100,
        },
        {
          resolvedAt: 2,
          winner: "alice",
          player1Choice: "PAPER",
          player2Choice: "ROCK",
          player1Submitted: true,
          player2Submitted: true,
        },
      ],
    });

    assert.deepEqual(map.get("alice"), {
      wins: 1,
      losses: 0,
      draws: 0,
      roundsWon: 2,
      roundsLost: 0,
      roundsDraw: 0,
      throwsRock: 1,
      throwsPaper: 1,
      throwsScissors: 0,
      moveTimeMs: 900,
      moveCount: 2,
    });
    assert.deepEqual(map.get("bob"), {
      wins: 0,
      losses: 1,
      draws: 0,
      roundsWon: 0,
      roundsLost: 2,
      roundsDraw: 0,
      throwsRock: 1,
      throwsPaper: 0,
      throwsScissors: 1,
      moveTimeMs: 1100,
      moveCount: 2,
    });
  });

  it("counts draws and ignores abandoned matches", () => {
    const map = new Map();
    accumulateProfileStatsFromMatch(map, {
      status: "completed",
      player1: "alice",
      player2: "bob",
      player1Wins: 1,
      player2Wins: 1,
      rounds: [
        { resolvedAt: 1, winner: "tie", player1Submitted: true, player2Submitted: true },
      ],
    });
    accumulateProfileStatsFromMatch(map, {
      status: "abandoned",
      player1: "alice",
      player2: "bob",
      resolution: "abandoned",
    });

    assert.equal(map.get("alice")?.draws, 1);
    assert.equal(map.get("bob")?.draws, 1);
    assert.equal(map.get("alice")?.wins, 0);
    assert.equal(map.get("alice")?.roundsDraw, 1);
  });

  it("aggregates multiple completed matches for the same player", () => {
    const map = new Map();
    const match = {
      status: "completed",
      player1: "guest",
      player2: "bot",
      winnerId: "guest",
      player1Wins: 2,
      player2Wins: 1,
      rounds: [
        {
          resolvedAt: 1,
          winner: "guest",
          player1Choice: "SCISSORS",
          player2Choice: "PAPER",
          player1Submitted: true,
          player2Submitted: true,
        },
      ],
    };
    accumulateProfileStatsFromMatch(map, match);
    accumulateProfileStatsFromMatch(map, {
      ...match,
      player2: "other",
      winnerId: "other",
      player1Wins: 0,
      player2Wins: 2,
    });

    assert.equal(map.get("guest")?.wins, 1);
    assert.equal(map.get("guest")?.losses, 1);
    assert.equal(map.get("bot")?.losses, 1);
    assert.equal(map.get("other")?.wins, 1);
  });
});
