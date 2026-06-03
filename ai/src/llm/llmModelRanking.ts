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

let cachedModelRankings: LlmModelRanked[] = [];

export function setLlmModelRankings(ranked: LlmModelRanked[]): void {
  cachedModelRankings = ranked;
}

export function getLlmModelRankings(): LlmModelRanked[] {
  return cachedModelRankings;
}

/** Min concluded matches per model before defaulting to the top-ranked model. */
export function llmModelMinMatchesExploration(
  modelCount: number,
  historical: LlmModelMatchRow[],
): number {
  const raw = process.env.LLM_MODEL_MIN_MATCHES?.trim();
  if (raw !== undefined && raw !== "") {
    const n = Number(raw);
    if (Number.isFinite(n) && n >= 0) return Math.floor(n);
  }
  if (modelCount <= 1) return 0;
  const total = historical.reduce((sum, h) => sum + h.matches, 0);
  return Math.max(10, Math.ceil(total / modelCount));
}

function refreshRankedHistorical(
  ranked: LlmModelRanked[],
  historical: LlmModelMatchRow[],
): LlmModelRanked[] {
  const histByModel = new Map(historical.map((h) => [h.model, h]));
  return ranked.map((row) => {
    const h = histByModel.get(row.model) ?? row.historical;
    return {
      ...row,
      historical: h,
      score: scoreLlmModel(h, row.warmup),
    };
  });
}

/**
 * Pick model for the next match: under-sampled warmup-ok models first (fewest matches),
 * then the highest-scoring model once each has enough history.
 */
export function selectLlmModelForMatch(
  ranked: LlmModelRanked[],
  historical: LlmModelMatchRow[],
): string {
  if (ranked.length === 0) return getLlmConfig().model;

  const refreshed = refreshRankedHistorical(ranked, historical);
  const warmupOk = refreshed.filter((r) => r.warmup.warmupOk);
  if (warmupOk.length === 0) return refreshed[0]!.model;

  const minMatches = llmModelMinMatchesExploration(warmupOk.length, historical);
  const underSampled = warmupOk.filter((r) => (r.historical?.matches ?? 0) < minMatches);
  if (underSampled.length > 0) {
    underSampled.sort((a, b) => {
      const ma = a.historical?.matches ?? 0;
      const mb = b.historical?.matches ?? 0;
      if (ma !== mb) return ma - mb;
      return b.score - a.score;
    });
    return underSampled[0]!.model;
  }

  warmupOk.sort((a, b) => b.score - a.score);
  return warmupOk[0]!.model;
}

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
  const activeModel = rows.find((r) => r.rank === 1);
  const minNote =
    rows.length > 1
      ? " (rotates to under-sampled models until fair share, then top rank)"
      : "";
  return [
    "LLM models ranked by match win rate + ELO change (primary model per match)",
    ...formatTableLines(
      ["Rank", "Model", "Score", "Win rate", "ELO Δ", "Warmup"],
      tableRows,
    ),
    activeModel ? `Default when exploration done: ${activeModel.model}${minNote}` : "",
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

export async function warmupLlmModel(
  model: string,
  opts?: { quiet?: boolean },
): Promise<LlmModelWarmupResult> {
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
        quiet: opts?.quiet,
      },
    );
    const ok = Boolean(parseMovePick(text) ?? parseMoveChoice(text));
    return { model, listed, warmupOk: ok, warmupMs: durationMs };
  } catch {
    return { model, listed, warmupOk: false, warmupMs: Date.now() - startedAt };
  }
}

/** Warm up each model; rank by match win% + avg ELO delta from concluded bot matches. */
export async function bootstrapLlmModels(
  db: DatabaseSync,
  opts?: { quiet?: boolean },
): Promise<LlmModelRanked[]> {
  const configured = getLlmConfig().models;
  const historical = getLlmModelMatchStats(db);
  const warmups: LlmModelWarmupResult[] = [];

  for (const model of configured) {
    if (!opts?.quiet) {
      log(`[bot-start:llm-models]   warming up ${model}…`);
    }
    warmups.push(await warmupLlmModel(model, { quiet: opts?.quiet }));
  }

  const ranked = rankLlmModels(configured, historical, warmups);
  setLlmModelRankings(ranked);
  const active = selectLlmModelForMatch(ranked, historical);
  if (ranked.find((r) => r.model === active)?.warmup.warmupOk) {
    setActiveLlmModel(active);
  }
  return ranked;
}

export function logLlmModelsRankedAtStart(rows: LlmModelRanked[]): void {
  logRankedSection("bot-start:llm-models", formatLlmModelsRankedLines(rows));
}
