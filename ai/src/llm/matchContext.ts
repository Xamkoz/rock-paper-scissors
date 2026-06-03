import type { MatchDatabase } from "../db/matchDatabase.js";
import { matchToSummary, type HistoricalMatchSummary } from "../db/matchRows.js";
import type { IntelCitationPickStats } from "../db/tacticalIntelCitationDb.js";
import type { SignalLeanStats } from "../db/roundSignalScoresDb.js";
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
  /** All-opponent archived throws (population prior), up to globalBot limit. */
  globalBotMatches: HistoricalMatchSummary[];
  queryLimits: { headToHead: number; recentBot: number; globalBot: number };
  /** Pre-match tactical plan from the first LLM call (round 1). */
  tactics?: string;
  /** Structured opponent read used for tactics and logging. */
  tacticalIntel?: TacticalIntel;
  /** Historical citation counts for signal exploration (pick time only). */
  signalPickStats?: IntelCitationPickStats[];
  /** Counterfactual lean measurements (all applicable signals per round). */
  signalLeanStats?: SignalLeanStats[];
}

const DEFAULT_H2H_LIMIT = 15;
const DEFAULT_RECENT_LIMIT = 10;
const DEFAULT_GLOBAL_LIMIT = 100;

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
  limits: { headToHead?: number; recentBot?: number; globalBot?: number } = {},
): Promise<MatchDbContext> {
  const headToHeadLimit = limits.headToHead ?? DEFAULT_H2H_LIMIT;
  const recentLimit = limits.recentBot ?? DEFAULT_RECENT_LIMIT;
  const globalLimit = limits.globalBot ?? DEFAULT_GLOBAL_LIMIT;

  const h2hMatches = db.listHeadToHead(selfUid, opponentUid, headToHeadLimit);
  const fetchLimit = Math.max(recentLimit, globalLimit);
  const botMatches =
    fetchLimit > 0 ? db.listMatchesForUser(selfUid, fetchLimit) : [];
  const botSummaries = await toSummaries(db, botMatches, selfUid);

  return {
    botUid: selfUid,
    opponentUid,
    opponentName,
    opponentProfile,
    currentMatch,
    headToHead: await toSummaries(db, h2hMatches, selfUid),
    recentBotMatches: botSummaries.slice(0, recentLimit),
    globalBotMatches: botSummaries.slice(0, globalLimit),
    queryLimits: {
      headToHead: headToHeadLimit,
      recentBot: recentLimit,
      globalBot: globalLimit,
    },
  };
}

export function formatMatchDbContextForPrompt(ctx: MatchDbContext): string {
  return JSON.stringify(ctx, null, 2);
}
