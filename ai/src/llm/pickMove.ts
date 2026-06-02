import type { Match, Move } from "../types.js";
import { chatComplete } from "./chat.js";
import { pickTimeBudgetMs } from "./compactMatch.js";
import type { MatchDbContext } from "./matchContext.js";
import {
  buildFastMoveUserPrompt,
  MOVE_SYSTEM_PROMPT,
  pickMoveContextLimits,
} from "./movePrompt.js";
import { parseMoveChoice } from "./parse.js";

export { pickMoveContextLimits };

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
  const userPrompt = buildFastMoveUserPrompt(match, ctx);
  const { text, durationMs } = await chatComplete(MOVE_SYSTEM_PROMPT, userPrompt, {
    maxTokens: 16,
    temperature: 0.1,
    json: true,
    logLabel: `move r${match.currentRound}`,
    logSummary: `match=${match.id} round=${match.currentRound} chars=${userPrompt.length}${budget ? ` budgetMs=${budget}` : ""}`,
    timeoutMs: budget,
  });

  const parsed = parseMoveChoice(text);
  if (!parsed) {
    throw new Error(`LLM returned invalid move JSON: ${text.slice(0, 200)}`);
  }
  return { choice: parsed, pickMs: durationMs };
}
