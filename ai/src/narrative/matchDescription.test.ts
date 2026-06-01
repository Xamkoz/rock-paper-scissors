import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { describeMatch } from "./matchDescription.js";
import type { Match } from "../types.js";

describe("describeMatch", () => {
  it("summarizes a win with ELO delta", () => {
    const match: Match = {
      id: "x",
      player1: "bot",
      player2: "alice",
      player1Name: "Bot",
      player2Name: "Alice",
      matchMode: "BO3",
      status: "completed",
      player1Ready: true,
      player2Ready: true,
      readyDeadlineAt: 0,
      currentRound: 3,
      player1Wins: 2,
      player2Wins: 1,
      rounds: [{ roundNumber: 1, player1Submitted: true, player2Submitted: true, resolvedAt: 1 }],
      winnerId: "bot",
      resolution: "player1_win",
      player1EloDelta: 8,
      player2EloDelta: -8,
      createdAt: 0,
      lastActivityAt: 0,
    };
    const line = describeMatch(match, "bot");
    assert.match(line, /won BO3 vs Alice/);
    assert.match(line, /\+8 ELO/);
  });
});
