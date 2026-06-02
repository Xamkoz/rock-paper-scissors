import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  bestOfRounds,
  calculateElo,
  calculateMatchElo,
  eloMultiplierForMatch,
  inferMatchResolution,
  matchResolutionForWinner,
  parseMatchMode,
  parseMatchModes,
  countRoundWins,
  pickSharedMatchMode,
  resolveRound,
  seriesOutcomeAfterRound,
  winsToFinish,
} from "./game";

describe("resolveRound", () => {
  it("detects ties", () => {
    assert.equal(resolveRound("ROCK", "ROCK"), "tie");
  });

  it("rock beats scissors", () => {
    assert.equal(resolveRound("ROCK", "SCISSORS"), "player1");
    assert.equal(resolveRound("SCISSORS", "ROCK"), "player2");
  });
});

describe("calculateElo", () => {
  it("updates ratings for a win", () => {
    const result = calculateElo(1000, 1000, 1);
    assert.equal(result.deltaA, 16);
    assert.equal(result.deltaB, -16);
  });
});

describe("calculateMatchElo", () => {
  const players = {
    winnerId: "p1",
    player1: "p1",
    player2: "p2",
    player1Wins: 2,
    player2Wins: 0,
  };

  it("enforces zero-sum deltas after rounding", () => {
    const result = calculateMatchElo(1042, 978, 1, {
      winnerId: "p1",
      player1: "p1",
      player2: "p2",
      player1Wins: 2,
      player2Wins: 1,
      matchMode: "BO3",
      endReason: "normal",
    });
    assert.equal(result.deltaB, -result.deltaA);
  });

  it("scales BO3 wins to 25% of base ELO swing", () => {
    const result = calculateMatchElo(1000, 1000, 1, {
      ...players,
      matchMode: "BO3",
      endReason: "normal",
      player2Wins: 1,
    });
    assert.equal(result.deltaA, 4);
    assert.equal(result.deltaB, -4);
    assert.equal(result.multiplier, 0.25);
  });

  it("scales BO5 wins to 40% of base ELO swing", () => {
    const result = calculateMatchElo(1000, 1000, 1, {
      ...players,
      matchMode: "BO5",
      endReason: "normal",
      player2Wins: 1,
    });
    assert.equal(result.deltaA, 6);
    assert.equal(result.deltaB, -6);
    assert.equal(result.multiplier, 0.4);
  });

  it("scales BO10 wins to 90% of base ELO swing", () => {
    const result = calculateMatchElo(1000, 1000, 1, {
      ...players,
      matchMode: "BO10",
      endReason: "normal",
      player2Wins: 4,
    });
    assert.equal(result.deltaA, 14);
    assert.equal(result.deltaB, -14);
    assert.equal(result.multiplier, 0.9);
  });

  it("does not double ELO swing on clock timeout when opponent won rounds", () => {
    const result = calculateMatchElo(1000, 1000, 1, {
      ...players,
      matchMode: "BO10",
      endReason: "clock_timeout",
      player2Wins: 3,
    });
    assert.equal(result.deltaA, 14);
    assert.equal(result.multiplier, 0.9);
  });

  it("does not double ELO swing on round timeout when opponent won rounds", () => {
    const result = calculateMatchElo(1000, 1000, 1, {
      ...players,
      matchMode: "BO5",
      endReason: "round_timeout",
      player2Wins: 2,
    });
    assert.equal(result.deltaA, 6);
    assert.equal(result.multiplier, 0.4);
  });

  it("doubles ELO swing on clock timeout shutout", () => {
    const result = calculateMatchElo(1000, 1000, 1, {
      ...players,
      matchMode: "BO10",
      endReason: "clock_timeout",
      player2Wins: 0,
    });
    assert.equal(result.deltaA, 29);
    assert.equal(result.multiplier, 1.8);
  });

  it("doubles ELO swing on round timeout shutout", () => {
    const result = calculateMatchElo(1000, 1000, 1, {
      ...players,
      matchMode: "BO5",
      endReason: "round_timeout",
      player2Wins: 0,
    });
    assert.equal(result.deltaA, 13);
    assert.equal(result.multiplier, 0.8);
  });

  it("doubles ELO swing when opponent won no rounds", () => {
    const result = calculateMatchElo(1000, 1000, 1, {
      ...players,
      matchMode: "BO3",
      endReason: "normal",
      player2Wins: 0,
    });
    assert.equal(result.deltaA, 8);
    assert.equal(result.multiplier, 0.5);
  });
});

describe("eloMultiplierForMatch", () => {
  it("combines mode weight and domination bonus once", () => {
    assert.equal(
      eloMultiplierForMatch({
        matchMode: "BO10",
        endReason: "clock_timeout",
        winnerId: "p1",
        player1: "p1",
        player2: "p2",
        player1Wins: 6,
        player2Wins: 0,
      }),
      1.8,
    );
  });
});

describe("match modes", () => {
  it("defaults unknown values to BO3", () => {
    assert.equal(parseMatchMode(undefined), "BO3");
    assert.equal(parseMatchMode("BO5"), "BO5");
    assert.equal(parseMatchMode("BO10"), "BO10");
  });

  it("maps wins to finish by mode", () => {
    assert.equal(winsToFinish("BO3"), 2);
    assert.equal(winsToFinish("BO5"), 3);
    assert.equal(winsToFinish("BO10"), 6);
    assert.equal(bestOfRounds("BO10"), 10);
  });

  it("counts round wins for forfeit score display", () => {
    const rounds = [
      { resolvedAt: 1, winner: "p1" },
      { resolvedAt: 2, winner: "tie" },
      { resolvedAt: null, winner: undefined },
    ];
    assert.equal(countRoundWins(rounds, "p1"), 1);
    assert.equal(countRoundWins(rounds, "p2"), 0);
  });

  it("series ends by first-to-target or points cap", () => {
    assert.deepEqual(seriesOutcomeAfterRound("BO10", 6, 3, 9), {
      kind: "winner",
      player: "player1",
    });
    assert.deepEqual(seriesOutcomeAfterRound("BO10", 6, 4, 10), {
      kind: "winner",
      player: "player1",
    });
    assert.deepEqual(seriesOutcomeAfterRound("BO10", 5, 5, 12), { kind: "draw" });
    assert.deepEqual(seriesOutcomeAfterRound("BO10", 5, 5, 9), { kind: "draw" });
    assert.deepEqual(seriesOutcomeAfterRound("BO10", 4, 4, 12), { kind: "continue" });
    assert.deepEqual(seriesOutcomeAfterRound("BO10", 4, 4, 10), { kind: "continue" });
    assert.deepEqual(seriesOutcomeAfterRound("BO10", 5, 4, 9), { kind: "continue" });
    assert.deepEqual(seriesOutcomeAfterRound("BO5", 2, 2, 5), { kind: "continue" });
    assert.deepEqual(seriesOutcomeAfterRound("BO3", 1, 1, 3), { kind: "continue" });
    assert.deepEqual(seriesOutcomeAfterRound("BO5", 3, 2, 7), {
      kind: "winner",
      player: "player1",
    });
    assert.deepEqual(seriesOutcomeAfterRound("BO3", 2, 1, 6), {
      kind: "winner",
      player: "player1",
    });
  });

  it("parses multiple queue modes with legacy fallback", () => {
    assert.deepEqual(parseMatchModes(["BO5", "BO3", "BO10"]), ["BO5", "BO3", "BO10"]);
    assert.deepEqual(parseMatchModes(undefined, "BO5"), ["BO5"]);
    assert.deepEqual(parseMatchModes([], "BO3"), ["BO3"]);
  });

  it("picks a single shared mode when only one overlaps", () => {
    assert.equal(pickSharedMatchMode(["BO3", "BO5"], ["BO5"]), "BO5");
    assert.equal(pickSharedMatchMode(["BO3"], ["BO5"]), null);
  });

  it("picks randomly among shared modes when both accept both", () => {
    const both = ["BO3", "BO5"] as const;
    assert.equal(pickSharedMatchMode([...both], [...both], () => 0), "BO3");
    assert.equal(pickSharedMatchMode([...both], [...both], () => 0.99), "BO5");
  });

  it("infers match resolution from legacy fields", () => {
    assert.equal(
      inferMatchResolution({
        status: "completed",
        player1: "p1",
        player2: "p2",
        winnerId: "p1",
      }),
      "player1_win",
    );
    assert.equal(
      inferMatchResolution({
        status: "completed",
        player1: "p1",
        player2: "p2",
        player1Wins: 5,
        player2Wins: 5,
      }),
      "draw",
    );
    assert.equal(
      inferMatchResolution({
        status: "completed",
        player1: "p1",
        player2: "p2",
        player1Wins: 2,
        player2Wins: 1,
      }),
      "player1_win",
    );
    assert.equal(inferMatchResolution({ status: "abandoned" }), "abandoned");
    assert.equal(inferMatchResolution({ status: "active" }), null);
  });

  it("maps winner id to stored resolution", () => {
    assert.equal(matchResolutionForWinner("p1", "p1"), "player1_win");
    assert.equal(matchResolutionForWinner("p1", "p2"), "player2_win");
  });
});
