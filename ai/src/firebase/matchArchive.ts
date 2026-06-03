import type { Firestore } from "firebase/firestore";
import { getMatch } from "./firestoreApi.js";
import type { Match, MatchStatus } from "../types.js";

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isConcluded(status: MatchStatus): boolean {
  return status === "completed" || status === "abandoned";
}

function resolvedRoundsWithChoices(match: Match): number {
  return match.rounds.filter(
    (r) => r.resolvedAt && r.player1Choice && r.player2Choice,
  ).length;
}

/**
 * Re-fetch a concluded match until resolved rounds include both choices (or timeout).
 * The first "completed" snapshot can arrive before rounds[] is fully populated.
 */
export async function loadConcludedMatchForArchive(
  db: Firestore,
  matchId: string,
  fallback: Match,
  maxWaitMs = 10_000,
): Promise<Match> {
  const deadline = Date.now() + maxWaitMs;
  let best = fallback;

  while (Date.now() < deadline) {
    const fresh = await getMatch(db, matchId);
    if (!fresh || !isConcluded(fresh.status)) {
      await delay(250);
      continue;
    }
    best = fresh;
    const resolvedCount = fresh.rounds.filter((r) => r.resolvedAt).length;
    const withChoices = resolvedRoundsWithChoices(fresh);
    if (resolvedCount === 0 || withChoices >= resolvedCount) return fresh;
    await delay(250);
  }

  return best;
}
