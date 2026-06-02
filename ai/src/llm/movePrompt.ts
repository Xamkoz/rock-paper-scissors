import type { HistoricalMatchSummary } from "../db/matchRows.js";
import type { Match, UserProfile } from "../types.js";
import type { MatchDbContext } from "./matchContext.js";
import { compactMatchForPick } from "./compactMatch.js";

const MOVE_SYSTEM = `RPS: ROCK>SCISSORS, PAPER>ROCK, SCISSORS>PAPER. Reply ONLY {"choice":"ROCK"|"PAPER"|"SCISSORS"}.`;

const RECENT_PICKS_MAX_CHARS = 200;

function moveLetter(move?: string): string | undefined {
  if (!move) return undefined;
  const u = move.toUpperCase();
  if (u.startsWith("R")) return "R";
  if (u.startsWith("P")) return "P";
  if (u.startsWith("S")) return "S";
  return undefined;
}

function slimProfile(p: UserProfile | null): [number, number, number, number] | undefined {
  if (!p) return undefined;
  return [p.elo, p.throwsRock, p.throwsPaper, p.throwsScissors];
}

export function countHistoryThrowPairs(ctx: MatchDbContext): number {
  return collectThrowPairs(ctx).length;
}

function collectThrowPairs(ctx: MatchDbContext): string[] {
  const pairs: string[] = [];

  const addGame = (g: HistoricalMatchSummary) => {
    for (const r of g.rounds) {
      const y = moveLetter(r.botMove);
      const o = moveLetter(r.opponentMove);
      if (!y && !o) continue;
      pairs.push(`${y ?? "-"}${o ?? "-"}`);
    }
  };

  for (const g of ctx.headToHead) addGame(g);
  for (const g of ctx.recentBotMatches) {
    if (g.id === ctx.currentMatch?.id) continue;
    addGame(g);
  }

  return pairs;
}

function truncateToMax(text: string, maxChars: number): string {
  if (text.length <= maxChars) return text;
  let cut = text.slice(text.length - maxChars);
  const comma = cut.indexOf(",");
  if (comma > 0) cut = cut.slice(comma + 1);
  return cut;
}

/** Opponent lifetime throw counts when no local round history yet (short; not padded). */
function opponentTrendLine(p: UserProfile): string {
  const total = p.throwsRock + p.throwsPaper + p.throwsScissors;
  if (total <= 0) return "";

  const ranked = [
    { c: "R", n: p.throwsRock },
    { c: "P", n: p.throwsPaper },
    { c: "S", n: p.throwsScissors },
  ].sort((a, b) => b.n - a.n);

  return `oTrend:${ranked.map((x) => `${x.c}${x.n}`).join("/")}`;
}

/**
 * ~200 symbols of recent you/opp throw pairs for round 1 (always present).
 * Prefer local DB games; pad with opponent Firestore throw stats if needed.
 */
export function buildRecentPicksForRound1(
  ctx: MatchDbContext,
  maxChars = RECENT_PICKS_MAX_CHARS,
): string {
  const pairs = collectThrowPairs(ctx);
  let text = pairs.length > 0 ? pairs.join(",") : "";

  if (text.length < maxChars && ctx.opponentProfile) {
    const trend = opponentTrendLine(ctx.opponentProfile);
    if (trend) text = text.length > 0 ? `${text},${trend}` : trend;
  }

  if (text.length === 0) return "none";
  return truncateToMax(text, maxChars);
}

/** Last few throws per past game — minimal tokens. */
function slimH2h(games: HistoricalMatchSummary[], maxGames: number): Array<{
  s: [number, number];
  t: Array<[string | undefined, string | undefined]>;
}> {
  return games.slice(0, maxGames).map((g) => ({
    s: [g.botWins, g.opponentWins],
    t: g.rounds.slice(-3).map((r) => [moveLetter(r.botMove), moveLetter(r.opponentMove)]),
  }));
}

/** One minified JSON line — less input tokens, faster inference. */
export function buildFastMoveUserPrompt(match: Match, ctx: MatchDbContext): string {
  const snap = compactMatchForPick(match, ctx.botUid);
  const prior = (
    snap.priorRounds as Array<{ you?: string; opp?: string }>
  ).map((r) => [moveLetter(r.you), moveLetter(r.opp)]);

  const payload: Record<string, unknown> = {
    r: snap.round,
    sc: [snap.yourWins, snap.opponentWins],
    vs: ctx.opponentName,
    prior,
    prof: slimProfile(ctx.opponentProfile),
    h2h: slimH2h(ctx.headToHead, 2),
  };

  if (snap.round === 1 && prior.length === 0) {
    payload.recent = buildRecentPicksForRound1(ctx);
  }

  return JSON.stringify(payload);
}

export const MOVE_SYSTEM_PROMPT = MOVE_SYSTEM;

/** recentBot: last concluded games for round-1 `recent` (~200 chars). */
export const pickMoveContextLimits = { headToHead: 5, recentBot: 8 };
