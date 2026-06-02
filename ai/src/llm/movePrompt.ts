import type { HistoricalMatchSummary } from "../db/matchRows.js";
import type { Match, UserProfile } from "../types.js";
import type { MatchDbContext } from "./matchContext.js";
import { compactMatchForPick } from "./compactMatch.js";

const MOVE_SYSTEM = `RPS: ROCK>SCISSORS, PAPER>ROCK, SCISSORS>PAPER. Reply ONLY {"choice":"ROCK"|"PAPER"|"SCISSORS"}.`;

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

  const payload = {
    r: snap.round,
    sc: [snap.yourWins, snap.opponentWins],
    vs: ctx.opponentName,
    prior,
    prof: slimProfile(ctx.opponentProfile),
    h2h: slimH2h(ctx.headToHead, 2),
  };

  return JSON.stringify(payload);
}

export const MOVE_SYSTEM_PROMPT = MOVE_SYSTEM;

export const pickMoveContextLimits = { headToHead: 2, recentBot: 0 };
