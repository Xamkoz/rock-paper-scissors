import type { Match, Move } from "../types.js";
import { chatComplete } from "./chat.js";
import { getLlmConfig } from "./client.js";
import { pickTimeBudgetMs } from "./compactMatch.js";
import { pickMoveMaxTokens } from "./timing.js";
import type { MatchDbContext } from "./matchContext.js";
import {
  buildCrossMatchHistory,
  buildFastMoveUserPrompt,
  buildMoveIntelCatalogForPick,
  buildMoveSystemPrompt,
  countHistoryThrowPairs,
  formatMoveIntelLog,
  pickMoveContextLimits,
  useCompactMovePrompt,
} from "./movePrompt.js";
import { compactMatchForPick } from "./compactMatch.js";
import { coerceCitationForCatalog } from "./moveIntelCatalog.js";
import {
  formatMovePickLogLine,
  parseMovePick,
  type MoveIntelSignal,
  type MoveIntelSource,
} from "./parse.js";

export { pickMoveContextLimits };

export interface PickMoveResult {
  choice: Move;
  reason: string;
  intelSource: MoveIntelSource;
  intelSignal: MoveIntelSignal;
  pickMs: number;
  llmModel: string;
}

export async function pickMoveWithLlm(
  match: Match,
  ctx: MatchDbContext,
  timeoutMs?: number,
): Promise<PickMoveResult> {
  const budget = timeoutMs ?? pickTimeBudgetMs(match);
  const snap = compactMatchForPick(match, ctx.botUid);
  const priorCount = (
    snap.priorRounds as Array<{ bot?: string; opponent?: string }>
  ).filter((r) => r.bot || r.opponent).length;
  const compact = useCompactMovePrompt(match.currentRound, priorCount);
  const userPrompt = buildFastMoveUserPrompt(match, ctx);
  const crossLen =
    match.currentRound === 1 ? buildCrossMatchHistory(ctx).throwPairs.length : 0;
  const histPairs = match.currentRound === 1 ? countHistoryThrowPairs(ctx) : 0;
  const intelLog = formatMoveIntelLog(ctx);
  const llmModel = getLlmConfig().model;
  const { text, durationMs } = await chatComplete(
    buildMoveSystemPrompt(match.currentRound, priorCount),
    userPrompt,
    {
    maxTokens: pickMoveMaxTokens(),
    temperature: 0.35,
    json: true,
    logLabel: `move r${match.currentRound}${compact ? " compact" : ""}`,
    logSummary: `match=${match.id} round=${match.currentRound} compact=${compact ? 1 : 0} h2h=${ctx.headToHead.length} histPairs=${histPairs} crossLen=${crossLen} tactics=${ctx.tactics ? 1 : 0} chars=${userPrompt.length}${budget ? ` budgetMs=${budget}` : ""}${intelLog ? ` intel=${intelLog}` : ""}`,
    timeoutMs: budget,
    },
  );

  const parsed = parseMovePick(text);
  if (!parsed) {
    throw new Error(
      `LLM returned invalid move JSON (need choice, reason, intelSource, intelSignal): ${text.slice(0, 200)}`,
    );
  }

  const { catalog } = buildMoveIntelCatalogForPick(match, ctx);
  const citation = coerceCitationForCatalog(
    catalog,
    parsed.intelSource,
    parsed.intelSignal,
  );
  if (!citation) {
    throw new Error(
      `LLM cited ${parsed.intelSource}/${parsed.intelSignal} not in intelCatalog`,
    );
  }

  return {
    choice: parsed.choice,
    reason: parsed.reason,
    intelSource: citation.source,
    intelSignal: citation.signal,
    pickMs: durationMs,
    llmModel,
  };
}

export { formatMovePickLogLine };
