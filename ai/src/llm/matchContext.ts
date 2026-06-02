import type { MatchDatabase } from "../db/matchDatabase.js";
import { matchToSummary, type HistoricalMatchSummary } from "../db/matchRows.js";
import type { TacticalIntel } from "./tacticalIntel.js";
import type { Match, UserProfile } from "../types.js";

/** Query result passed to the LLM (bounded, structured summaries). */
export interface MatchDbContext {
  botUid: string;
  opponentUid: string;
  opponentName: string;
  opponentProfile: UserProfile | null;
  currentMatch: Match | null;
  headToHead: HistoricalMatchSummary[];
  recentBotMatches: HistoricalMatchSummary[];
  queryLimits: { headToHead: number; recentBot: number };
  /** Pre-match tactical plan from the first LLM call (round 1). */
  tactics?: string;
  /** Structured opponent read used for tactics and logging. */
  tacticalIntel?: TacticalIntel;
}

const DEFAULT_H2H_LIMIT = 15;
const DEFAULT_RECENT_LIMIT = 10;

async function toSummaries(
  db: MatchDatabase,
  matches: Match[],
  botUid: string,
): Promise<HistoricalMatchSummary[]> {
  return matches.map((match) =>
    matchToSummary(match, botUid, db.getDescription(match.id)),
  );
}

export async function buildMatchDbContext(
  db: MatchDatabase,
  selfUid: string,
  opponentUid: string,
  opponentName: string,
  currentMatch: Match | null,
  opponentProfile: UserProfile | null,
  limits: { headToHead?: number; recentBot?: number } = {},
): Promise<MatchDbContext> {
  const headToHeadLimit = limits.headToHead ?? DEFAULT_H2H_LIMIT;
  const recentLimit = limits.recentBot ?? DEFAULT_RECENT_LIMIT;

  const h2hMatches = db.listHeadToHead(selfUid, opponentUid, headToHeadLimit);
  const recentMatches =
    recentLimit > 0 ? db.listMatchesForUser(selfUid, recentLimit) : [];

  return {
    botUid: selfUid,
    opponentUid,
    opponentName,
    opponentProfile,
    currentMatch,
    headToHead: await toSummaries(db, h2hMatches, selfUid),
    recentBotMatches: await toSummaries(db, recentMatches, selfUid),
    queryLimits: { headToHead: headToHeadLimit, recentBot: recentLimit },
  };
}

export function formatMatchDbContextForPrompt(ctx: MatchDbContext): string {
  return JSON.stringify(ctx, null, 2);
}
