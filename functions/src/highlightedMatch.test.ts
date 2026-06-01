import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { Timestamp } from "firebase-admin/firestore";
import {
  hasMinimumHighlightedMatchRounds,
  mergeRecentCompletedMatches,
  pickBiggestEloGainMatch,
  type HighlightedMatchCandidate,
} from "./highlightedMatch";
import type { MatchDoc } from "./highlightedMatchTypes";

function matchDoc(overrides: Partial<MatchDoc> & Pick<MatchDoc, "lastActivityAt">): MatchDoc {
  return {
    player1: "u1",
    player2: "u2",
    player1Name: "Me",
    player2Name: "Them",
    matchMode: "BO3",
    status: "completed",
    player1Wins: 2,
    player2Wins: 0,
    rounds: [],
    ...overrides,
  };
}

function candidate(
  matchId: string,
  doc: MatchDoc,
): HighlightedMatchCandidate {
  return { matchId, match: doc };
}

describe("pickBiggestEloGainMatch", () => {
  it("picks highest positive ELO gain in the window", () => {
    const ts = (ms: number) => Timestamp.fromMillis(ms);
    const picks = pickBiggestEloGainMatch(
      [
        candidate("a", matchDoc({ lastActivityAt: ts(1000), player1EloDelta: 12 })),
        candidate("b", matchDoc({ lastActivityAt: ts(2000), player1EloDelta: 25 })),
        candidate("c", matchDoc({ lastActivityAt: ts(3000), player1EloDelta: 18 })),
      ],
      "u1",
    );
    assert.equal(picks?.matchId, "b");
  });

  it("prefers newer match on tie", () => {
    const ts = (ms: number) => Timestamp.fromMillis(ms);
    const picks = pickBiggestEloGainMatch(
      [
        candidate("older", matchDoc({ lastActivityAt: ts(1000), player1EloDelta: 20 })),
        candidate("newer", matchDoc({ lastActivityAt: ts(5000), player1EloDelta: 20 })),
      ],
      "u1",
    );
    assert.equal(picks?.matchId, "newer");
  });

  it("ignores losses and zero deltas", () => {
    const ts = (ms: number) => Timestamp.fromMillis(ms);
    const picks = pickBiggestEloGainMatch(
      [
        candidate("loss", matchDoc({ lastActivityAt: ts(1000), player1EloDelta: -30 })),
        candidate("zero", matchDoc({ lastActivityAt: ts(2000), player1EloDelta: 0 })),
      ],
      "u1",
    );
    assert.equal(picks, null);
  });

  it("ignores matches with fewer than two rounds", () => {
    const ts = (ms: number) => Timestamp.fromMillis(ms);
    const picks = pickBiggestEloGainMatch(
      [
        candidate(
          "one-round",
          matchDoc({
            lastActivityAt: ts(3000),
            player1EloDelta: 40,
            player1Wins: 1,
            player2Wins: 0,
            rounds: [{ roundNumber: 1, resolvedAt: ts(2500) }],
          }),
        ),
        candidate(
          "two-rounds",
          matchDoc({
            lastActivityAt: ts(2000),
            player1EloDelta: 12,
            player1Wins: 2,
            player2Wins: 0,
            rounds: [
              { roundNumber: 1, resolvedAt: ts(1500) },
              { roundNumber: 2, resolvedAt: ts(1800) },
            ],
          }),
        ),
      ],
      "u1",
    );
    assert.equal(picks?.matchId, "two-rounds");
  });
});

describe("mergeRecentCompletedMatches", () => {
  it("keeps completed matches newest first and dedupes", () => {
    const ts = (ms: number) => Timestamp.fromMillis(ms);
    const docs = [
      {
        id: "m1",
        data: () => matchDoc({ lastActivityAt: ts(1000), status: "completed" }),
      },
      {
        id: "m2",
        data: () => matchDoc({ lastActivityAt: ts(3000), status: "active" }),
      },
      {
        id: "m1",
        data: () => matchDoc({ lastActivityAt: ts(1000), status: "completed" }),
      },
      {
        id: "m3",
        data: () => matchDoc({ lastActivityAt: ts(2000), status: "abandoned" }),
      },
    ] as unknown as import("firebase-admin/firestore").QueryDocumentSnapshot[];

    const merged = mergeRecentCompletedMatches(docs, 10);
    assert.deepEqual(
      merged.map((entry) => entry.matchId),
      ["m3", "m1"],
    );
  });

  it("drops matches with fewer than two rounds", () => {
    const ts = (ms: number) => Timestamp.fromMillis(ms);
    const docs = [
      {
        id: "short",
        data: () =>
          matchDoc({
            lastActivityAt: ts(3000),
            status: "completed",
            player1Wins: 1,
            player2Wins: 0,
            rounds: [{ roundNumber: 1, resolvedAt: ts(2500) }],
          }),
      },
      {
        id: "long",
        data: () =>
          matchDoc({
            lastActivityAt: ts(2000),
            status: "completed",
            player1Wins: 2,
            player2Wins: 0,
            rounds: [
              { roundNumber: 1, resolvedAt: ts(1500) },
              { roundNumber: 2, resolvedAt: ts(1800) },
            ],
          }),
      },
    ] as unknown as import("firebase-admin/firestore").QueryDocumentSnapshot[];

    const merged = mergeRecentCompletedMatches(docs, 10);
    assert.deepEqual(
      merged.map((entry) => entry.matchId),
      ["long"],
    );
    assert.equal(hasMinimumHighlightedMatchRounds(merged[0].match), true);
  });
});
