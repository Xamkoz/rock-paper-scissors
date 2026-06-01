import { Timestamp, type Firestore, type QueryDocumentSnapshot } from "firebase-admin/firestore";
import type { MatchDoc } from "./highlightedMatchTypes";

export const HIGHLIGHTED_MATCH_POOL_SIZE = 100;
export const HIGHLIGHTED_MATCH_MIN_ROUNDS = 2;

export interface HighlightedMatchCandidate {
  matchId: string;
  match: MatchDoc;
}

export function myEloDeltaForUser(match: MatchDoc, userId: string): number | null {
  if (userId === match.player1) return match.player1EloDelta ?? null;
  if (userId === match.player2) return match.player2EloDelta ?? null;
  return null;
}

export function lastActivityAtMs(match: MatchDoc): number {
  const ts = match.lastActivityAt;
  return ts instanceof Timestamp ? ts.toMillis() : 0;
}

export function resolvedRoundCount(match: MatchDoc): number {
  return match.rounds.filter((round) => round.resolvedAt != null).length;
}

/** Requires at least [minimum] resolved rounds, or total round wins on older docs. */
export function hasMinimumHighlightedMatchRounds(
  match: MatchDoc,
  minimum: number = HIGHLIGHTED_MATCH_MIN_ROUNDS,
): boolean {
  if (resolvedRoundCount(match) >= minimum) return true;
  return match.player1Wins + match.player2Wins >= minimum;
}

/** Best positive ELO gain in [candidates]; newest match wins ties. */
export function pickBiggestEloGainMatch(
  candidates: HighlightedMatchCandidate[],
  userId: string,
): HighlightedMatchCandidate | null {
  let best: HighlightedMatchCandidate | null = null;
  let bestDelta = 0;
  let bestActivityAt = 0;

  for (const candidate of candidates) {
    const delta = myEloDeltaForUser(candidate.match, userId);
    if (delta == null || delta <= 0) continue;
    if (!hasMinimumHighlightedMatchRounds(candidate.match)) continue;
    const activityAt = lastActivityAtMs(candidate.match);
    if (
      best == null ||
      delta > bestDelta ||
      (delta === bestDelta && activityAt > bestActivityAt)
    ) {
      best = candidate;
      bestDelta = delta;
      bestActivityAt = activityAt;
    }
  }

  return best;
}

export function mergeRecentCompletedMatches(
  docs: QueryDocumentSnapshot[],
  limit: number,
): HighlightedMatchCandidate[] {
  const byId = new Map<string, HighlightedMatchCandidate>();
  for (const doc of docs) {
    const match = doc.data() as MatchDoc;
    const status = match.status;
    if (status !== "completed" && status !== "abandoned") continue;
    if (!hasMinimumHighlightedMatchRounds(match)) continue;
    byId.set(doc.id, { matchId: doc.id, match });
  }
  return [...byId.values()]
    .sort((a, b) => lastActivityAtMs(b.match) - lastActivityAtMs(a.match))
    .slice(0, limit);
}

export async function fetchHighlightedMatchCandidates(
  db: Firestore,
  userId: string,
  sinceMs: number,
  limit: number = HIGHLIGHTED_MATCH_POOL_SIZE,
): Promise<HighlightedMatchCandidate[]> {
  const since = Timestamp.fromMillis(sinceMs);
  const perSide = Math.max(1, limit);
  const [asPlayer1, asPlayer2] = await Promise.all([
    db.collection("matches")
      .where("player1", "==", userId)
      .where("lastActivityAt", ">=", since)
      .orderBy("lastActivityAt", "desc")
      .limit(perSide)
      .get(),
    db.collection("matches")
      .where("player2", "==", userId)
      .where("lastActivityAt", ">=", since)
      .orderBy("lastActivityAt", "desc")
      .limit(perSide)
      .get(),
  ]);
  return mergeRecentCompletedMatches([...asPlayer1.docs, ...asPlayer2.docs], limit);
}

export async function findHighlightedMatchId(
  db: Firestore,
  userId: string,
  windowStartMs: number,
): Promise<string | null> {
  const candidates = await fetchHighlightedMatchCandidates(db, userId, windowStartMs);
  return pickBiggestEloGainMatch(candidates, userId)?.matchId ?? null;
}
