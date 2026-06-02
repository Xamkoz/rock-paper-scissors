import type { Match, Move } from "../types.js";
import { chatComplete } from "./chat.js";
import { compactMatchForPick, pickTimeBudgetMs } from "./compactMatch.js";
import type { MatchDbContext } from "./matchContext.js";
import { parseMoveChoice } from "./parse.js";

const SYSTEM_PROMPT = `You are a rock-paper-scissors bot. Rules: ROCK beats SCISSORS, PAPER beats ROCK, SCISSORS beats PAPER.
Use head-to-head history and the open match snapshot. Reply with JSON only: {"choice":"ROCK"|"PAPER"|"SCISSORS"}`;

const MOVE_H2H_LIMIT = 5;
const MOVE_RECENT_LIMIT = 3;

function buildMoveUserPrompt(match: Match, ctx: MatchDbContext): string {
  const profile = ctx.opponentProfile
    ? {
        displayName: ctx.opponentProfile.displayName,
        elo: ctx.opponentProfile.elo,
        throwsRock: ctx.opponentProfile.throwsRock,
        throwsPaper: ctx.opponentProfile.throwsPaper,
        throwsScissors: ctx.opponentProfile.throwsScissors,
      }
    : null;

  return `Pick your throw for round ${match.currentRound}.

OPEN_MATCH:
${JSON.stringify(compactMatchForPick(match, ctx.botUid))}

OPPONENT_PROFILE:
${JSON.stringify(profile)}

HEAD_TO_HEAD (${ctx.headToHead.length} games):
${JSON.stringify(ctx.headToHead)}

RECENT_BOT_GAMES (${ctx.recentBotMatches.length}):
${JSON.stringify(ctx.recentBotMatches)}`;
}

export interface PickMoveResult {
  choice: Move;
  pickMs: number;
}

export async function pickMoveWithLlm(
  match: Match,
  ctx: MatchDbContext,
  timeoutMs?: number,
): Promise<PickMoveResult> {
  const budget = timeoutMs ?? pickTimeBudgetMs(match);
  const { text, durationMs } = await chatComplete(
    SYSTEM_PROMPT,
    buildMoveUserPrompt(match, ctx),
    {
      maxTokens: 32,
      temperature: 0.6,
      json: true,
      logLabel: `move r${match.currentRound}`,
      logSummary: `match=${match.id} round=${match.currentRound} h2h=${ctx.headToHead.length} recent=${ctx.recentBotMatches.length}${budget ? ` budgetMs=${budget}` : ""}`,
      timeoutMs: budget,
    },
  );

  const parsed = parseMoveChoice(text);
  if (!parsed) {
    throw new Error(`LLM returned invalid move JSON: ${text.slice(0, 200)}`);
  }
  return { choice: parsed, pickMs: durationMs };
}

export const pickMoveContextLimits = {
  headToHead: MOVE_H2H_LIMIT,
  recentBot: MOVE_RECENT_LIMIT,
};
