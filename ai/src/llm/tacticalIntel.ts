import type { HistoricalMatchSummary } from "../db/matchRows.js";
import type { Match } from "../types.js";
import type { MatchDbContext } from "./matchContext.js";
import { compactMatchForPick } from "./compactMatch.js";
import { logRankedSection } from "../log/startupRankLog.js";
import { log } from "../log.js";
import { buildCrossMatchHistory, moveCode, type MoveThrowPair } from "./movePrompt.js";
import {
  COUNTER_TO_OPPONENT,
  type OpponentTendency,
  tendencyFromCounts,
} from "./opponentTendency.js";
import {
  analyzeDistributionOnly,
  analyzeThrowPattern,
  analyzeThrowPatternFromPairs,
  formatPatternCompact,
  type MoveCounts,
  type RpsMove,
  type ThrowPatternProfile,
} from "./throwPatternIntel.js";
import type { IntelCitationPickStats } from "../db/tacticalIntelCitationDb.js";
import {
  formatIntelSignalsRankedLines,
  rankIntelSignalsByPickEfficiency,
} from "./intelCitationRanking.js";
import {
  formatHistoricalIntelSourcesRankedLines,
  formatHistoricalIntelSourcesRankedLog,
  formatIntelSourcesRankedLog,
  rankHistoricalIntelSourcesByEfficiency,
  rankIntelSourcesByEfficiency,
  selectPrimarySourceForMatch,
  type IntelSourceEfficiency,
} from "./tacticalIntelRanking.js";
import type {
  LeanAccuracyRow,
  PrimarySourceLeaderboardRow,
} from "./tacticalIntelTracking.js";
import { resolve } from "node:path";
import { packageRoot } from "../config.js";
import { appendLogFile } from "../log.js";
import { gameplayDetailLogEnabled } from "../logConfig.js";

export interface TendencySlice extends OpponentTendency {
  label: "lifetime" | "h2h" | "recentVsOpponent" | "global";
  sampleThrows: number;
  patterns: ThrowPatternProfile;
}

export interface H2hSeriesRecord {
  games: number;
  botSeriesWins: number;
  opponentSeriesWins: number;
}

export interface PriorH2hGame {
  score: { bot: number; opponent: number };
  opponentLean?: RpsMove;
  lastRounds: MoveThrowPair[];
  opponentPattern?: Pick<
    ThrowPatternProfile,
    "dominant" | "dominantPct" | "distribution" | "lastThrows"
  >;
}

export interface OpponentRepeatStreak {
  move: RpsMove;
  streak: number;
}

export interface CrossThrowPatterns {
  opponent: ThrowPatternProfile | null;
  bot: ThrowPatternProfile | null;
  pairCount: number;
}

export interface TacticalIntel {
  bot: string;
  opponent: string;
  mode: Match["matchMode"];
  opponentElo?: number;
  lifetime?: TendencySlice;
  h2h?: TendencySlice;
  recentVsOpponent?: TendencySlice;
  /** Population prior: all archived opponents combined. */
  global?: TendencySlice;
  crossPatterns: CrossThrowPatterns;
  h2hRecord: H2hSeriesRecord;
  priorH2hGames: PriorH2hGame[];
  recentOpponentThrows: RpsMove[];
  opponentRepeat?: OpponentRepeatStreak;
  primary: OpponentTendency | null;
  primarySource: "lifetime" | "h2h" | "recentVsOpponent" | "global" | "none";
  /** Intel sources sorted by efficiency (best first). */
  sourcesByEfficiency: IntelSourceEfficiency[];
  counters: typeof COUNTER_TO_OPPONENT;
}

function countOpponentThrows(games: HistoricalMatchSummary[]): MoveCounts {
  const counts = { rock: 0, paper: 0, scissors: 0, total: 0 };
  for (const g of games) {
    for (const r of g.rounds) {
      const o = moveCode(r.opponentMove);
      if (o === "ROCK") counts.rock++;
      else if (o === "PAPER") counts.paper++;
      else if (o === "SCISSORS") counts.scissors++;
    }
  }
  counts.total = counts.rock + counts.paper + counts.scissors;
  return counts;
}

function collectOpponentThrowSequence(games: HistoricalMatchSummary[]): RpsMove[] {
  const seq: RpsMove[] = [];
  for (const g of games) {
    for (const r of g.rounds) {
      const o = moveCode(r.opponentMove);
      if (o === "ROCK" || o === "PAPER" || o === "SCISSORS") seq.push(o);
    }
  }
  return seq;
}

function collectH2hPairs(games: HistoricalMatchSummary[]): MoveThrowPair[] {
  const pairs: MoveThrowPair[] = [];
  for (const g of games) {
    for (const r of g.rounds) {
      pairs.push({
        bot: moveCode(r.botMove),
        opponent: moveCode(r.opponentMove),
      });
    }
  }
  return pairs;
}

function sliceFromCounts(
  counts: MoveCounts,
  label: TendencySlice["label"],
  opponentThrows: RpsMove[],
  pairs?: MoveThrowPair[],
): TendencySlice | null {
  const base = tendencyFromCounts({
    rock: counts.rock,
    paper: counts.paper,
    scissors: counts.scissors,
  });
  if (!base) return null;

  const patterns =
    opponentThrows.length > 0
      ? analyzeThrowPattern(opponentThrows, pairs) ??
        analyzeDistributionOnly(counts)
      : analyzeDistributionOnly(counts);
  if (!patterns) return null;

  return {
    ...base,
    label,
    sampleThrows: counts.total,
    patterns,
  };
}

function opponentLeanFromGame(g: HistoricalMatchSummary): RpsMove | undefined {
  const c = countOpponentThrows([g]);
  return analyzeDistributionOnly(c)?.dominant;
}

function detectRepeat(throws: RpsMove[]): OpponentRepeatStreak | undefined {
  if (throws.length < 2) return undefined;
  const last = throws[throws.length - 1]!;
  let streak = 1;
  for (let i = throws.length - 2; i >= 0; i--) {
    if (throws[i] === last) streak++;
    else break;
  }
  if (streak < 2) return undefined;
  return { move: last, streak };
}

const GLOBAL_PRIMARY_MIN_THROWS = 30;

function pickPrimary(
  lifetime?: TendencySlice,
  h2h?: TendencySlice,
  recent?: TendencySlice,
  global?: TendencySlice,
): { primary: OpponentTendency | null; primarySource: TacticalIntel["primarySource"] } {
  if (h2h && h2h.sampleThrows >= 6) {
    return { primary: h2h, primarySource: "h2h" };
  }
  if (recent && recent.sampleThrows >= 4) {
    return { primary: recent, primarySource: "recentVsOpponent" };
  }
  if (global && global.sampleThrows >= GLOBAL_PRIMARY_MIN_THROWS) {
    return { primary: global, primarySource: "global" };
  }
  if (lifetime) {
    return { primary: lifetime, primarySource: "lifetime" };
  }
  if (h2h) return { primary: h2h, primarySource: "h2h" };
  if (recent) return { primary: recent, primarySource: "recentVsOpponent" };
  if (global) return { primary: global, primarySource: "global" };
  return { primary: null, primarySource: "none" };
}

export function primaryTendencySlice(intel: TacticalIntel): TendencySlice | undefined {
  switch (intel.primarySource) {
    case "h2h":
      return intel.h2h;
    case "recentVsOpponent":
      return intel.recentVsOpponent;
    case "global":
      return intel.global;
    case "lifetime":
      return intel.lifetime;
    default:
      return undefined;
  }
}

function buildCrossPatterns(pairs: MoveThrowPair[]): CrossThrowPatterns {
  const opponent = analyzeThrowPatternFromPairs(pairs);
  const botThrows = pairs
    .map((p) => p.bot)
    .filter((b): b is RpsMove => b === "ROCK" || b === "PAPER" || b === "SCISSORS");
  const bot =
    botThrows.length > 0 ? analyzeThrowPattern(botThrows) : null;
  return { opponent, bot, pairCount: pairs.length };
}

export function buildTacticalIntel(
  match: Match,
  ctx: MatchDbContext,
  sourcesByEfficiency: IntelSourceEfficiency[] = [],
): TacticalIntel {
  const snap = compactMatchForPick(match, ctx.botUid);
  const cross = buildCrossMatchHistory(ctx, 60);

  const lifetimeCounts = ctx.opponentProfile
    ? {
        rock: ctx.opponentProfile.throwsRock,
        paper: ctx.opponentProfile.throwsPaper,
        scissors: ctx.opponentProfile.throwsScissors,
        total:
          ctx.opponentProfile.throwsRock +
          ctx.opponentProfile.throwsPaper +
          ctx.opponentProfile.throwsScissors,
      }
    : { rock: 0, paper: 0, scissors: 0, total: 0 };

  const lifetime =
    lifetimeCounts.total > 0
      ? sliceFromCounts(lifetimeCounts, "lifetime", [], undefined) || undefined
      : undefined;

  const h2hCounts = countOpponentThrows(ctx.headToHead);
  const h2hThrows = collectOpponentThrowSequence(ctx.headToHead);
  const h2hPairs = collectH2hPairs(ctx.headToHead);
  const h2h = sliceFromCounts(h2hCounts, "h2h", h2hThrows, h2hPairs) || undefined;

  const recentCounts = countOpponentThrows([]);
  for (const p of cross.throwPairs) {
    const o = p.opponent;
    if (o === "ROCK") recentCounts.rock++;
    else if (o === "PAPER") recentCounts.paper++;
    else if (o === "SCISSORS") recentCounts.scissors++;
  }
  recentCounts.total = recentCounts.rock + recentCounts.paper + recentCounts.scissors;

  const recentThrows = cross.throwPairs
    .map((p) => p.opponent)
    .filter((o): o is RpsMove => o === "ROCK" || o === "PAPER" || o === "SCISSORS");

  const recentVsOpponent =
    recentCounts.total > 0
      ? sliceFromCounts(recentCounts, "recentVsOpponent", recentThrows, cross.throwPairs) ||
        undefined
      : undefined;

  const globalGames = ctx.globalBotMatches.filter((g) => g.id !== match.id);
  const globalCounts = countOpponentThrows(globalGames);
  const globalThrows = collectOpponentThrowSequence(globalGames);
  const globalPairs = collectH2hPairs(globalGames);
  const global =
    globalCounts.total > 0
      ? sliceFromCounts(globalCounts, "global", globalThrows, globalPairs) || undefined
      : undefined;

  let botSeriesWins = 0;
  let opponentSeriesWins = 0;
  for (const g of ctx.headToHead) {
    botSeriesWins += g.botWins;
    opponentSeriesWins += g.opponentWins;
  }

  const recentOpponentThrows = recentThrows;
  const crossPatterns = buildCrossPatterns(cross.throwPairs);

  const priorH2hGames: PriorH2hGame[] = ctx.headToHead.slice(0, 5).map((g) => {
    const gameThrows = collectOpponentThrowSequence([g]);
    const gamePattern = analyzeThrowPattern(gameThrows);
    return {
      score: { bot: g.botWins, opponent: g.opponentWins },
      opponentLean: opponentLeanFromGame(g),
      lastRounds: g.rounds.slice(-3).map((r) => ({
        bot: moveCode(r.botMove),
        opponent: moveCode(r.opponentMove),
      })),
      opponentPattern: gamePattern
        ? {
            dominant: gamePattern.dominant,
            dominantPct: gamePattern.dominantPct,
            distribution: gamePattern.distribution,
            lastThrows: gamePattern.lastThrows,
          }
        : undefined,
    };
  });

  const { primary, primarySource } = pickPrimary(lifetime, h2h, recentVsOpponent, global);

  return {
    bot: String(snap.botName ?? "bot"),
    opponent: ctx.opponentName,
    mode: match.matchMode,
    opponentElo: ctx.opponentProfile?.elo,
    lifetime,
    h2h,
    recentVsOpponent,
    global,
    crossPatterns,
    h2hRecord: {
      games: ctx.headToHead.length,
      botSeriesWins,
      opponentSeriesWins,
    },
    priorH2hGames,
    recentOpponentThrows,
    opponentRepeat: detectRepeat(recentOpponentThrows),
    primary,
    primarySource,
    sourcesByEfficiency,
    counters: COUNTER_TO_OPPONENT,
  };
}

function formatTendencyLine(tag: string, t?: TendencySlice): string {
  if (!t) return `${tag}=—`;
  return formatPatternCompact(tag, t.patterns);
}

/** One-line tactical intel for logs. */
export function formatTacticalIntelCompact(intel: TacticalIntel): string {
  const cross = intel.crossPatterns;
  const parts = [
    `vs=${intel.opponent}`,
    `mode=${intel.mode}`,
    intel.opponentElo != null ? `elo=${intel.opponentElo}` : null,
    `h2h=${intel.h2hRecord.games}g(${intel.h2hRecord.botSeriesWins}-${intel.h2hRecord.opponentSeriesWins})`,
    formatTendencyLine("life", intel.lifetime),
    formatTendencyLine("h2h", intel.h2h),
    formatTendencyLine("recent", intel.recentVsOpponent),
    formatTendencyLine("global", intel.global),
    cross.opponent ? formatPatternCompact("cross", cross.opponent) : null,
    cross.bot ? `botMix=R${cross.bot.distribution.rockPct}/P${cross.bot.distribution.paperPct}/S${cross.bot.distribution.scissorsPct}` : null,
    intel.primary
      ? `read=${intel.primary.dominant}→open ${intel.primary.openWith}(${intel.primarySource})`
      : "read=none",
    intel.opponentRepeat
      ? `repeat=${intel.opponentRepeat.move[0]}×${intel.opponentRepeat.streak}`
      : null,
  ].filter(Boolean);
  return parts.join(" ");
}

function patternToPayload(p: ThrowPatternProfile) {
  return {
    counts: p.counts,
    distribution: p.distribution,
    ranked: p.ranked,
    dominant: p.dominant,
    dominantPct: p.dominantPct,
    secondary: p.secondary,
    secondaryPct: p.secondaryPct,
    skew: p.skew,
    suggestedCounter: p.suggestedCounter,
    lastThrows: p.lastThrows,
    lastWindow: p.lastWindow,
    repeatRatePct: p.repeatRatePct,
    alternationRatePct: p.alternationRatePct,
    transitions: p.transitions,
    responseToBot: p.responseToBot,
  };
}

function sliceToPayload(t?: TendencySlice) {
  if (!t) return undefined;
  return {
    dominant: t.dominant,
    dominantPct: t.dominantPct,
    throwMixPct: t.distribution,
    suggestedCounter: t.openWith,
    sampleThrows: t.sampleThrows,
    patterns: patternToPayload(t.patterns),
  };
}

/** Compact JSON for tactics LLM (lean + opening + primary patterns only). */
export function tacticalIntelToTacticsPrompt(intel: TacticalIntel): Record<string, unknown> {
  const primarySlice = primaryTendencySlice(intel);
  const patterns = primarySlice?.patterns;

  const payload: Record<string, unknown> = {
    bot: intel.bot,
    opponent: intel.opponent,
    mode: intel.mode,
    counters: intel.counters,
    primarySource: intel.primarySource,
    h2hRecord: intel.h2hRecord,
    ranks: intel.sourcesByEfficiency.slice(0, 3).map((r) => ({
      source: r.source,
      lean: r.dominant,
      open: r.openWith,
    })),
  };

  if (intel.primary) {
    payload.opponentLikelyLean = intel.primary.dominant;
    payload.leanPct = intel.primary.dominantPct;
    payload.throwMixPct = intel.primary.distribution;
    payload.suggestedOpening = intel.primary.openWith;
  }
  if (patterns) {
    payload.patterns = {
      skew: patterns.skew,
      secondary: patterns.secondary,
      repeatRatePct: patterns.repeatRatePct,
      distribution: patterns.distribution,
    };
  }
  const recent = intel.recentOpponentThrows.slice(-8);
  if (recent.length > 0) payload.recentThrows = recent;
  if (intel.opponentRepeat) payload.repeat = intel.opponentRepeat;

  return payload;
}

/** JSON-safe payload for tactics LLM (no raw throw totals). */
export function tacticalIntelToPayload(intel: TacticalIntel): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    bot: intel.bot,
    opponent: intel.opponent,
    mode: intel.mode,
    counters: intel.counters,
    h2hRecord: intel.h2hRecord,
    priorH2hGames: intel.priorH2hGames,
    primarySource: intel.primarySource,
    sourcesByEfficiency: intel.sourcesByEfficiency.map((r) => ({
      source: r.source,
      rank: r.rank,
      efficiencyScore: r.efficiencyScore,
      dominant: r.dominant,
      openWith: r.openWith,
      sampleThrows: r.sampleThrows,
      leanPctHistorical: r.leanPctHistorical,
      winPctAsPrimary: r.winPctAsPrimary,
    })),
    crossPatterns: {
      pairCount: intel.crossPatterns.pairCount,
      opponent: intel.crossPatterns.opponent
        ? patternToPayload(intel.crossPatterns.opponent)
        : null,
      bot: intel.crossPatterns.bot ? patternToPayload(intel.crossPatterns.bot) : null,
    },
  };

  if (intel.opponentElo != null) payload.opponentElo = intel.opponentElo;

  const life = sliceToPayload(intel.lifetime);
  const h2h = sliceToPayload(intel.h2h);
  const recent = sliceToPayload(intel.recentVsOpponent);
  const global = sliceToPayload(intel.global);
  if (life) payload.lifetimeTendency = life;
  if (h2h) payload.h2hTendency = h2h;
  if (recent) payload.recentVsOpponentTendency = recent;
  if (global) payload.globalTendency = global;

  if (intel.primary) {
    payload.opponentLikelyLean = intel.primary.dominant;
    payload.leanPct = intel.primary.dominantPct;
    payload.throwMixPct = intel.primary.distribution;
    payload.suggestedOpening = intel.primary.openWith;
    const primarySlice = primaryTendencySlice(intel);
    if (primarySlice?.patterns) {
      payload.primaryPatterns = patternToPayload(primarySlice.patterns);
    }
  }

  if (intel.recentOpponentThrows.length > 0) {
    payload.recentOpponentThrows = intel.recentOpponentThrows.slice(-12);
  }
  if (intel.opponentRepeat) {
    payload.opponentRepeatStreak = intel.opponentRepeat;
  }

  return payload;
}

export function attachIntelEfficiencyRankings(
  intel: TacticalIntel,
  historicalLean: LeanAccuracyRow[],
  historicalPrimary: PrimarySourceLeaderboardRow[],
): TacticalIntel {
  const sourcesByEfficiency = rankIntelSourcesByEfficiency(
    intel,
    historicalLean,
    historicalPrimary,
  );
  const ranked = { ...intel, sourcesByEfficiency };
  const explored = selectPrimarySourceForMatch(ranked, historicalPrimary);
  if (!explored) return ranked;
  return {
    ...ranked,
    primary: explored.primary,
    primarySource: explored.primarySource,
  };
}

export interface TacticalIntelLogMeta {
  matchId?: string;
}

export function getTacticsIntelEfficiencyLogPath(): string | null {
  const raw = process.env.TACTICS_INTEL_LOG_PATH?.trim();
  if (raw === "false" || raw === "0" || raw === "off") return null;
  return resolve(packageRoot, raw || "data/tactics-intel.log");
}

const BOT_START_INTEL_TAG = "bot-start";

/** Process startup: source + source/signal efficiency leaderboards. */
export function logBotStartIntelEfficiency(
  historicalLean: LeanAccuracyRow[],
  historicalPrimary: PrimarySourceLeaderboardRow[],
  pickCitationStats: IntelCitationPickStats[] = [],
): void {
  const sourceRankings = rankHistoricalIntelSourcesByEfficiency(
    historicalLean,
    historicalPrimary,
  );
  const sourceLines = formatHistoricalIntelSourcesRankedLines(sourceRankings);
  logRankedSection(`${BOT_START_INTEL_TAG}:intel-sources`, sourceLines);

  const signalRows = rankIntelSignalsByPickEfficiency(pickCitationStats);
  const signalLines = formatIntelSignalsRankedLines(signalRows);
  logRankedSection(`${BOT_START_INTEL_TAG}:intel-signals`, signalLines);

  const filePath = getTacticsIntelEfficiencyLogPath();
  if (!filePath) return;
  appendLogFile(
    filePath,
    `[${BOT_START_INTEL_TAG}:intel-sources]\n${sourceLines.join("\n")}`,
  );
  appendLogFile(
    filePath,
    `[${BOT_START_INTEL_TAG}:intel-signals]\n${signalLines.join("\n")}`,
  );
}

/** Pre-match efficiency ranking — console + append to tactics intel log file. */
export function logIntelEfficiencyToFile(
  intel: TacticalIntel,
  meta?: TacticalIntelLogMeta,
): void {
  if (intel.sourcesByEfficiency.length === 0) return;
  const filePath = getTacticsIntelEfficiencyLogPath();
  if (!filePath) return;

  const ranked = formatIntelSourcesRankedLog(
    intel.sourcesByEfficiency,
    intel.primarySource,
  );
  const prefix = [
    meta?.matchId ? `match=${meta.matchId}` : null,
    `vs=${intel.opponent}`,
    `primary=${intel.primarySource}`,
  ]
    .filter(Boolean)
    .join(" ");

  appendLogFile(
    filePath,
    `[${BOT_START_INTEL_TAG}:intel-efficiency] ${prefix} | ${ranked}`,
  );

  if (process.env.TACTICS_INTEL_LOG_JSON === "true") {
    appendLogFile(
      filePath,
      `[${BOT_START_INTEL_TAG}:intel-efficiency:json] ${JSON.stringify({
        matchId: meta?.matchId,
        opponent: intel.opponent,
        primarySource: intel.primarySource,
        rankings: intel.sourcesByEfficiency,
      })}`,
    );
  }
}

export function logTacticalIntel(
  intel: TacticalIntel,
  tag = "tactics-intel",
  meta?: TacticalIntelLogMeta,
): void {
  if (intel.sourcesByEfficiency.length > 0) {
    const ranked = formatIntelSourcesRankedLog(
      intel.sourcesByEfficiency,
      intel.primarySource,
    );
    if (gameplayDetailLogEnabled()) {
      log(`[${tag}:efficiency] ${ranked}`);
    }
    logIntelEfficiencyToFile(intel, meta);
  }
  if (gameplayDetailLogEnabled()) {
    log(`[${tag}] ${formatTacticalIntelCompact(intel)}`);
    log(`[${tag}:json] ${JSON.stringify(tacticalIntelToPayload(intel))}`);
  }
}
