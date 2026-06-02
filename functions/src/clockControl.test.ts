import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { Timestamp } from "firebase-admin/firestore";
import {
  applyClockIncrement,
  clockExpiry,
  CLOCK_INCREMENT_MS,
  INITIAL_CLOCK_MS,
  MAX_CLOCK_MS,
  playerSubmittedInRound,
  tickClocks,
} from "./clockControl";

describe("clockControl", () => {
  it("ticks only for players without a submission", () => {
    const t0 = Timestamp.fromMillis(0);
    const t5 = Timestamp.fromMillis(5_000);
    const result = tickClocks(
      { player1ClockMs: 50_000, player2ClockMs: 50_000, clocksUpdatedAt: t0 },
      { player1Submitted: true },
      t5,
    );
    assert.equal(result.player1ClockMs, 50_000);
    assert.equal(result.player2ClockMs, 45_000);
  });

  it("supports legacy choice fields on open rounds", () => {
    const t0 = Timestamp.fromMillis(0);
    const t5 = Timestamp.fromMillis(5_000);
    const result = tickClocks(
      { player1ClockMs: 50_000, player2ClockMs: 50_000, clocksUpdatedAt: t0 },
      { player1Choice: "ROCK" },
      t5,
    );
    assert.equal(result.player1ClockMs, 50_000);
    assert.equal(result.player2ClockMs, 45_000);
  });

  it("adds increment after each resolved round", () => {
    assert.deepEqual(applyClockIncrement(10_000, 20_000), {
      player1ClockMs: 10_000 + CLOCK_INCREMENT_MS,
      player2ClockMs: 20_000 + CLOCK_INCREMENT_MS,
    });
  });

  it("caps clock increment at max", () => {
    assert.deepEqual(applyClockIncrement(88_000, MAX_CLOCK_MS), {
      player1ClockMs: MAX_CLOCK_MS,
      player2ClockMs: MAX_CLOCK_MS,
    });
  });

  it("detects clock expiry", () => {
    assert.equal(clockExpiry(0, 5_000, {}), "player1");
    assert.equal(clockExpiry(5_000, 0, { player1Submitted: true }), "player2");
    assert.equal(clockExpiry(5_000, 0, { player1Choice: "ROCK" }), "player2");
    assert.equal(clockExpiry(0, 0, {}), "both");
    assert.equal(clockExpiry(INITIAL_CLOCK_MS, INITIAL_CLOCK_MS, {}), null);
  });

  it("playerSubmittedInRound finds submission on a resolved round", () => {
    const match = {
      player1: "p1",
      player2: "p2",
      rounds: [
        { roundNumber: 1, player1Submitted: true, resolvedAt: Timestamp.now() },
        { roundNumber: 2, player2Submitted: false },
      ],
    };
    assert.equal(playerSubmittedInRound(match, 1, "p1"), true);
    assert.equal(playerSubmittedInRound(match, 2, "p1"), false);
    assert.equal(playerSubmittedInRound(match, 1, "p2"), false);
  });
});
