import type { DatabaseSync } from "node:sqlite";
import { getLlmModelMatchStats, type LlmModelMatchRow } from "../db/llmModelDb.js";
import {
  formatMs,
  formatPctHits,
  formatTableLines,
  logRankedSection,
} from "../log/startupRankLog.js";
import { log } from "../log.js";
import {
  chatComplete,
  POST_START_SYSTEM_PROMPT,
  POST_START_WARMUP_USER,
} from "./chat.js";
import { getLlmConfig, setActiveLlmModel } from "./client.js";
import { parseMoveChoice, parseMovePick } from "./parse.js";

export interface LlmModelWarmupResult {
  model: string;
  listed: boolean;
  warmupOk: boolean;
  warmupMs: number;
}

export interface LlmModelRanked {
  rank: number;
  model: string;
  score: number;
  historical: LlmModelMatchRow | null;
  warmup: LlmModelWarmupResult;
}

const MIN_MATCHES_FOR_HISTORY = 2;
/** Each avg ELO point per match counts this much in the composite score. */
const ELO_SCORE_WEIGHT = 2;

export function scoreLlmModel(
  historical: LlmModelMatchRow | null,
  warmup: LlmModelWarmupResult,
): number {
  if (!warmup.warmupOk) return 0;

  if (historical && historical.matches >= MIN_MATCHES_FOR_HISTORY) {
    const score = historical.winPct + historical.avgEloDelta * ELO_SCORE_WEIGHT;
    if (!warmup.listed) return Math.round((score - 5) * 10) / 10;
    return Math.round(score * 10) / 10;
  }

  let score = 40 + Math.max(0, 15 - warmup.warmupMs / 500);
  if (!warmup.listed) score -= 5;
  return Math.round(score * 10) / 10;
}

export function rankLlmModels(
  configured: string[],
  historical: LlmModelMatchRow[],
  warmups: LlmModelWarmupResult[],
): LlmModelRanked[] {
  const histByModel = new Map(historical.map((h) => [h.model, h]));
  const warmupByModel = new Map(warmups.map((w) => [w.model, w]));

  const ranked = configured.map((model) => {
    const h = histByModel.get(model) ?? null;
    const w = warmupByModel.get(model) ?? {
      model,
      listed: false,
      warmupOk: false,
      warmupMs: 0,
    };
    return {
      rank: 0,
      model,
      score: scoreLlmModel(h, w),
      historical: h,
      warmup: w,
    };
  });

  ranked.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score;
    const aElo = a.historical?.avgEloDelta ?? -999;
    const bElo = b.historical?.avgEloDelta ?? -999;
    if (bElo !== aElo) return bElo - aElo;
    const aWin = a.historical?.winPct ?? 0;
    const bWin = b.historical?.winPct ?? 0;
    if (bWin !== aWin) return bWin - aWin;
    if (a.warmup.warmupOk !== b.warmup.warmupOk) return a.warmup.warmupOk ? -1 : 1;
    return a.warmup.warmupMs - b.warmup.warmupMs;
  });

  return ranked.map((row, i) => ({ ...row, rank: i + 1 }));
}

function formatEloDelta(avg: number): string {
  if (avg === 0) return "0";
  return avg > 0 ? `+${avg}` : String(avg);
}

function formatMatchHistory(hist: LlmModelMatchRow | null): { winRate: string; elo: string } {
  if (!hist || hist.matches <= 0) {
    return { winRate: "—", elo: "—" };
  }
  return {
    winRate: formatPctHits(hist.wins, hist.matches, "matches"),
    elo: `${formatEloDelta(hist.avgEloDelta)}/match (${formatEloDelta(hist.totalEloDelta)} total)`,
  };
}

function formatWarmupCell(w: LlmModelWarmupResult): string {
  if (!w.warmupOk) return "failed";
  const listed = w.listed ? "" : " · not on server";
  return `${formatMs(w.warmupMs)} ok${listed}`;
}

export function formatLlmModelsRankedLines(rows: LlmModelRanked[]): string[] {
  if (rows.length === 0) {
    return ["No LLM models configured (set LLM_MODEL or LLM_MODELS)."];
  }
  const tableRows = rows.map((r) => {
    const { winRate, elo } = formatMatchHistory(r.historical);
    return [
      `#${r.rank}${r.rank === 1 ? " ★" : ""}`,
      r.model,
      String(r.score),
      winRate,
      elo,
      formatWarmupCell(r.warmup),
    ];
  });
  const active = rows.find((r) => r.rank === 1);
  return [
    "LLM models ranked by match win rate + ELO change (primary model per match)",
    ...formatTableLines(
      ["Rank", "Model", "Score", "Win rate", "ELO Δ", "Warmup"],
      tableRows,
    ),
    active ? `Active model for matches: ${active.model}` : "",
  ].filter(Boolean);
}

export function formatLlmModelsRankedLog(rows: LlmModelRanked[]): string {
  return formatLlmModelsRankedLines(rows).join("\n");
}

async function modelListedOnServer(model: string): Promise<boolean> {
  const { baseUrl, apiKey, timeoutMs } = getLlmConfig();
  const headers: Record<string, string> = {};
  if (apiKey) headers.Authorization = `Bearer ${apiKey}`;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.min(timeoutMs, 10_000));
  try {
    const res = await fetch(`${baseUrl}/models`, { headers, signal: controller.signal });
    if (!res.ok) return true;
    const data = (await res.json()) as { data?: Array<{ id?: string }> };
    const ids = data.data?.map((m) => m.id).filter(Boolean) ?? [];
    if (ids.length === 0) return true;
    return ids.includes(model);
  } catch {
    return true;
  } finally {
    clearTimeout(timer);
  }
}

export async function warmupLlmModel(model: string): Promise<LlmModelWarmupResult> {
  const listed = await modelListedOnServer(model);
  const startedAt = Date.now();
  try {
    const { text, durationMs } = await chatComplete(
      POST_START_SYSTEM_PROMPT,
      POST_START_WARMUP_USER,
      {
        model,
        maxTokens: 64,
        temperature: 0,
        json: true,
        logLabel: `warmup ${model}`,
        logSummary: `model=${model}`,
        timeoutMs: Math.min(getLlmConfig().timeoutMs, 45_000),
      },
    );
    const ok = Boolean(parseMovePick(text) ?? parseMoveChoice(text));
    return { model, listed, warmupOk: ok, warmupMs: durationMs };
  } catch {
    return { model, listed, warmupOk: false, warmupMs: Date.now() - startedAt };
  }
}

/** Warm up each model; rank by match win% + avg ELO delta from concluded bot matches. */
export async function bootstrapLlmModels(db: DatabaseSync): Promise<LlmModelRanked[]> {
  const configured = getLlmConfig().models;
  const historical = getLlmModelMatchStats(db);
  const warmups: LlmModelWarmupResult[] = [];

  for (const model of configured) {
    log(`[bot-start:llm-models]   warming up ${model}…`);
    warmups.push(await warmupLlmModel(model));
  }

  const ranked = rankLlmModels(configured, historical, warmups);
  if (ranked[0]?.warmup.warmupOk) {
    setActiveLlmModel(ranked[0].model);
  }
  return ranked;
}

export function logLlmModelsRankedAtStart(rows: LlmModelRanked[]): void {
  logRankedSection("bot-start:llm-models", formatLlmModelsRankedLines(rows));
}
