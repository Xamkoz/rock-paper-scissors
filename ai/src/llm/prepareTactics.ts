import type { Match } from "../types.js";
import { chatComplete } from "./chat.js";
import type { MatchDbContext } from "./matchContext.js";
import type { OpponentTendency } from "./opponentTendency.js";
import { COUNTER_TO_OPPONENT } from "./opponentTendency.js";
import type { MatchDatabase } from "../db/matchDatabase.js";
import {
  attachIntelEfficiencyRankings,
  buildTacticalIntel,
  logTacticalIntel,
  type TacticalIntel,
  tacticalIntelToTacticsPrompt,
} from "./tacticalIntel.js";
import {
  tacticsBudgetMs,
  tacticsMaxChars,
  tacticsMaxTokens,
  tacticsUseLlm,
} from "./timing.js";

function buildTacticsSystemPrompt(botName: string, opponentName: string): string {
  return [
    `Write a brief tactical plan for RPS bot "${botName}" vs "${opponentName}".`,
    "Do not pick a move in this step — only the plan.",
    "Counters (memorize): if opponent throws Rock → bot uses Paper; Paper → Scissors; Scissors → Rock.",
    "Use suggestedOpening and opponentLikelyLean from the JSON (primary read).",
    "Use patterns inside each tendency: distribution, ranked moves, skew, repeatRatePct, transitions (after X), responseToBot, crossPatterns.",
    "Plain text, 2–3 short sentences, under 60 words. Name throws Rock, Paper, Scissors only.",
    "Never quote raw throw totals or numbers in parentheses. Use percentages from the JSON if needed.",
    "Say 'counter with Scissors' not 'weakness against Scissors'.",
  ].join(" ");
}

export function buildFallbackTactics(
  opponentName: string,
  tendency: OpponentTendency | null,
): string {
  if (!tendency) {
    return `No strong read on ${opponentName}; vary Rock, Paper, and Scissors and adapt after round one.`;
  }
  const lean = tendency.dominant.charAt(0) + tendency.dominant.slice(1).toLowerCase();
  const open =
    tendency.openWith.charAt(0) + tendency.openWith.slice(1).toLowerCase();
  const adapt = COUNTER_TO_OPPONENT;
  const ifRock = adapt.ROCK.charAt(0) + adapt.ROCK.slice(1).toLowerCase();
  const ifPaper = adapt.PAPER.charAt(0) + adapt.PAPER.slice(1).toLowerCase();
  const ifScissors = adapt.SCISSORS.charAt(0) + adapt.SCISSORS.slice(1).toLowerCase();
  return (
    `${opponentName} leans ${lean} (~${tendency.dominantPct}%). ` +
    `Open with ${open} to beat that. ` +
    `If they throw Rock use ${ifRock}; Paper use ${ifPaper}; Scissors use ${ifScissors}.`
  );
}

/** Raw lifetime totals the model should not echo in prose. */
const RAW_COUNT_IN_PROSE = /\(\s*\d{2,}\s*\)|\b\d{3,}\s+throws?\b/i;

export function sanitizeTactics(text: string): string {
  return text
    .replace(/[{}\[\]"]/g, "")
    .replace(RAW_COUNT_IN_PROSE, "")
    .replace(/\s+/g, " ")
    .trim();
}

export function clampTactics(text: string, maxChars: number): string {
  let t = sanitizeTactics(text);
  if (t.length <= maxChars) return t;
  t = t.slice(0, maxChars).trim();
  const lastSpace = t.lastIndexOf(" ");
  if (lastSpace > Math.floor(maxChars * 0.6)) {
    t = t.slice(0, lastSpace);
  }
  if (!/[.!?]$/.test(t)) t += ".";
  if (t.length > maxChars) {
    t = t.slice(0, maxChars).trim();
    if (!/[.!?]$/.test(t)) t = t.slice(0, maxChars - 1).trim() + ".";
  }
  return t;
}

/** Opening contradicts the stated counter to opponent's lean. */
export function tacticsContradictsCounter(
  text: string,
  tendency: OpponentTendency | null,
): boolean {
  if (!tendency) return false;
  const open = tendency.openWith.toLowerCase();
  const wrong = { rock: "paper", paper: "scissors", scissors: "rock" }[open] as string;
  const dom = tendency.dominant.toLowerCase();

  const openPattern =
    /\b(open|start|lead|leading|initially\s+open|opening)\s+(with\s+)?(rock|paper|scissors)\b/gi;
  let m: RegExpExecArray | null;
  while ((m = openPattern.exec(text)) !== null) {
    const stated = m[3]!.toLowerCase();
    if (stated === wrong) return true;
    if (stated !== open) return true;
  }

  if (
    dom === "paper" &&
    /\bweakness\s+against\s+scissors\b/i.test(text) &&
    /\bopen(ing)?\s+with\s+rock\b/i.test(text)
  ) {
    return true;
  }

  return false;
}

export function tacticsLooksValid(
  text: string,
  tendency: OpponentTendency | null,
): boolean {
  const t = sanitizeTactics(text);
  if (t.length < 20 || /^\s*\{/.test(text)) return false;
  if (RAW_COUNT_IN_PROSE.test(text)) return false;
  if (tacticsContradictsCounter(t, tendency)) return false;
  return true;
}

export interface PrepareTacticsResult {
  tactics: string;
  intel: TacticalIntel;
  durationMs: number;
  fromFallback: boolean;
}

function buildPreparedIntel(
  match: Match,
  ctx: MatchDbContext,
  db?: Pick<
    MatchDatabase,
    "getTacticalIntelLeanAccuracy" | "getTacticalIntelPrimaryLeaderboard"
  >,
): TacticalIntel {
  let intel = buildTacticalIntel(match, ctx);
  if (db) {
    intel = attachIntelEfficiencyRankings(
      intel,
      db.getTacticalIntelLeanAccuracy(),
      db.getTacticalIntelPrimaryLeaderboard(),
    );
  }
  logTacticalIntel(intel, "tactics-intel", { matchId: match.id });
  return intel;
}

/** Pre-match plan + intel. Skips tactics LLM unless LLM_TACTICS_USE_LLM=true (faster round 1). */
export async function prepareTacticsForMatch(
  match: Match,
  ctx: MatchDbContext,
  db?: Pick<
    MatchDatabase,
    "getTacticalIntelLeanAccuracy" | "getTacticalIntelPrimaryLeaderboard"
  >,
): Promise<PrepareTacticsResult> {
  const intel = buildPreparedIntel(match, ctx, db);
  const maxChars = tacticsMaxChars();
  const fallback = buildFallbackTactics(intel.opponent, intel.primary);

  if (!tacticsUseLlm()) {
    return {
      tactics: clampTactics(fallback, maxChars),
      intel,
      durationMs: 0,
      fromFallback: true,
    };
  }

  const botName = intel.bot;
  const opponentName = intel.opponent;

  let text = "";
  let durationMs = 0;
  try {
    const result = await chatComplete(
      buildTacticsSystemPrompt(botName, opponentName),
      JSON.stringify(tacticalIntelToTacticsPrompt(intel)),
      {
        maxTokens: tacticsMaxTokens(),
        temperature: 0.2,
        logLabel: `tactics ${match.id}`,
        logSummary: `match=${match.id} vs=${opponentName} read=${intel.primary?.dominant ?? "?"} open=${intel.primary?.openWith ?? "?"} src=${intel.primarySource}`,
        timeoutMs: tacticsBudgetMs(),
      },
    );
    text = result.text;
    durationMs = result.durationMs;
  } catch {
    return {
      tactics: clampTactics(fallback, maxChars),
      intel,
      durationMs,
      fromFallback: true,
    };
  }

  let tactics = clampTactics(text, maxChars);
  if (!tacticsLooksValid(tactics, intel.primary)) {
    tactics = clampTactics(fallback, maxChars);
    return { tactics, intel, durationMs, fromFallback: true };
  }

  return { tactics, intel, durationMs, fromFallback: false };
}

/** @deprecated Use prepareTacticsForMatch */
export const prepareTacticsWithLlm = prepareTacticsForMatch;
