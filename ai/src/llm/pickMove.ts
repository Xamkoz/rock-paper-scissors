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
  parseMoveChoice,
  parseMovePick,
  type MoveIntelSignal,
  type MoveIntelSource,
  type ParseMovePickOptions,
} from "./parse.js";
import { normalizeMovePick } from "./movePickValidation.js";
import { log } from "../log.js";

export { pickMoveContextLimits };

function logFailedMovePickReason(
  round: number,
  opponentName: string,
  text: string,
  options?: ParseMovePickOptions,
): void {
  const pick = parseMovePick(text, options);
  if (pick) {
    log(
      `[move:reason] r${round} ${pick.choice} vs ${opponentName} ${formatMovePickLogLine(pick)} (not submitted — catalog/parse rejected)`,
    );
    return;
  }
  const choice = parseMoveChoice(text);
  let reason: string | undefined;
  try {
    const json = JSON.parse(text.trim()) as { reason?: string };
    if (typeof json.reason === "string" && json.reason.trim()) reason = json.reason.trim();
  } catch {
    // ignore
  }
  if (choice || reason) {
    log(
      `[move:reason] r${round} ${choice ?? "?"} vs ${opponentName} reason="${reason ?? "?"}" (not submitted — invalid citation JSON)`,
    );
  }
}

export interface PickMoveResult {
  choice: Move;
  reason: string;
  intelSource: MoveIntelSource;
  intelSignal: MoveIntelSignal;
  pickMs: number;
  llmModel: string;
  llmResponse: string;
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
  const primarySource =
    ctx.tacticalIntel?.primarySource && ctx.tacticalIntel.primarySource !== "none"
      ? ctx.tacticalIntel.primarySource
      : undefined;
  const parseMovePickOptions = primarySource ? { primarySource } : undefined;
  const { text, durationMs } = await chatComplete(
    buildMoveSystemPrompt(match.currentRound, priorCount),
    userPrompt,
    {
    maxTokens: pickMoveMaxTokens(),
    temperature: 0.25,
    json: true,
    logLabel: `move r${match.currentRound}${compact ? " compact" : ""}`,
    logSummary: `match=${match.id} round=${match.currentRound} compact=${compact ? 1 : 0} h2h=${ctx.headToHead.length} histPairs=${histPairs} crossLen=${crossLen} tactics=${ctx.tactics ? 1 : 0} chars=${userPrompt.length}${budget ? ` budgetMs=${budget}` : ""}${intelLog ? ` intel=${intelLog}` : ""}`,
    timeoutMs: budget,
    parseMovePickOptions,
    },
  );

  const parsed = parseMovePick(text, parseMovePickOptions);
  if (!parsed) {
    logFailedMovePickReason(match.currentRound, ctx.opponentName, text, parseMovePickOptions);
    throw new Error(
      `LLM returned invalid move JSON (need choice, reason, intelSource, intelSignal): ${text.slice(0, 200)}`,
    );
  }

  const { catalog } = buildMoveIntelCatalogForPick(match, ctx);
  const normalized = normalizeMovePick(parsed, ctx, catalog);
  const citation = coerceCitationForCatalog(
    catalog,
    normalized.intelSource,
    normalized.intelSignal,
  );
  if (!citation) {
    log(
      `[move:reason] r${match.currentRound} ${normalized.choice} vs ${ctx.opponentName} ${formatMovePickLogLine(normalized)} (not submitted — not in intelCatalog)`,
    );
    throw new Error(
      `LLM cited ${normalized.intelSource}/${normalized.intelSignal} not in intelCatalog`,
    );
  }

  return {
    choice: normalized.choice,
    reason: normalized.reason,
    intelSource: citation.source,
    intelSignal: citation.signal,
    pickMs: durationMs,
    llmModel,
    llmResponse: text,
  };
}

export { formatMovePickLogLine };
