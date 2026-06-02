import type { HistoricalMatchSummary } from "../db/matchRows.js";
import type { Match, UserProfile } from "../types.js";
import type { MatchDbContext } from "./matchContext.js";
import { formatTacticalIntelCompact } from "./tacticalIntel.js";
import { compactMatchForPick } from "./compactMatch.js";
import { MOVE_PICK_JSON_EXAMPLE, MOVE_PICK_JSON_SHAPE } from "./parse.js";
import { buildMoveIntelCatalog, glossaryForCatalog } from "./moveIntelCatalog.js";
import type { TacticalIntel } from "./tacticalIntel.js";

/** Round 1 (no throws yet) needs full history; later rounds rely on this-match state + cached plan. */
export function useCompactMovePrompt(round: number, resolvedRoundsInMatch: number): boolean {
  return round > 1 && resolvedRoundsInMatch > 0;
}

export function buildMoveSystemPrompt(round: number, resolvedRoundsInMatch = 0): string {
  if (useCompactMovePrompt(round, resolvedRoundsInMatch)) {
    return [
      "Pick the bot's next rock-paper-scissors move.",
      "Rules: ROCK beats SCISSORS, PAPER beats ROCK, SCISSORS beats PAPER.",
      "JSON: score = series wins; thisMatchRounds = throws so far; preparedTactics = pre-match plan; read = opponent lean.",
      "Adapt to actual throws in thisMatchRounds; avoid blindly repeating lastBotMove.",
      `Reply JSON only: choice, intelSource, intelSignal, reason (one short sentence; citations before reason).`,
      `Example: ${MOVE_PICK_JSON_EXAMPLE}`,
      "intelSource + intelSignal must match intelCatalog.",
    ].join(" ");
  }
  return [
    "Pick the bot's next rock-paper-scissors move to beat the opponent.",
    "Rules: ROCK beats SCISSORS, PAPER beats ROCK, SCISSORS beats PAPER.",
    "Use tacticalIntel.read/openWith, preparedTactics, and thisMatchRounds; avoid repeating lastBotMove unless adapting.",
    `Reply JSON only: choice, intelSource, intelSignal, reason (one short sentence; put citations before reason).`,
    `Example: ${MOVE_PICK_JSON_EXAMPLE}`,
    "intelSource + intelSignal must match intelCatalog.",
  ].join(" ");
}

const CROSS_MATCH_MAX_PAIRS = 40;
/** Raw cross-game pairs only when tacticalIntel is missing (move pick). */
const MOVE_PICK_CROSS_MAX_PAIRS = 8;
const PREPARED_TACTICS_MAX_CHARS = 220;

/** ROCK | PAPER | SCISSORS for prompts and output. */
export function moveCode(move?: string): string | undefined {
  if (!move) return undefined;
  const u = move.trim().toUpperCase();
  if (u === "ROCK" || u === "R") return "ROCK";
  if (u === "PAPER" || u === "P") return "PAPER";
  if (u === "SCISSORS" || u === "S") return "SCISSORS";
  return undefined;
}

/** Single-letter shorthand for compact cross-match lists (internal only). */
export function moveLetter(move?: string): string | undefined {
  const code = moveCode(move);
  if (code === "ROCK") return "R";
  if (code === "PAPER") return "P";
  if (code === "SCISSORS") return "S";
  return undefined;
}

/** Full move name for human-readable text (recaps, logs). */
export function moveDisplayName(move?: string): string | undefined {
  const code = moveCode(move);
  if (code === "ROCK") return "Rock";
  if (code === "PAPER") return "Paper";
  if (code === "SCISSORS") return "Scissors";
  return undefined;
}

export interface MoveThrowPair {
  bot?: string;
  opponent?: string;
}

function slimOpponentLifetime(
  p: UserProfile | null,
): { elo: number; lean?: string; mix?: { rockPct: number; paperPct: number; scissorsPct: number } } | undefined {
  if (!p) return undefined;
  const total = p.throwsRock + p.throwsPaper + p.throwsScissors;
  if (total <= 0) return { elo: p.elo };
  const ranked = [
    { move: "ROCK", n: p.throwsRock },
    { move: "PAPER", n: p.throwsPaper },
    { move: "SCISSORS", n: p.throwsScissors },
  ].sort((a, b) => b.n - a.n);
  return {
    elo: p.elo,
    lean: ranked[0]!.move,
    mix: {
      rockPct: Math.round((p.throwsRock / total) * 100),
      paperPct: Math.round((p.throwsPaper / total) * 100),
      scissorsPct: Math.round((p.throwsScissors / total) * 100),
    },
  };
}

function truncatePreparedTactics(text: string): string {
  const oneLine = text.replace(/\s+/g, " ").trim();
  if (oneLine.length <= PREPARED_TACTICS_MAX_CHARS) return oneLine;
  return `${oneLine.slice(0, PREPARED_TACTICS_MAX_CHARS - 1)}…`;
}

/** Letter pairs (bot+opponent) for fallback when pattern intel is unavailable. */
export function compactThrowPairLetters(pairs: MoveThrowPair[]): string[] {
  return pairs.map(({ bot, opponent }) => {
    const b = moveLetter(bot) ?? "?";
    const o = moveLetter(opponent) ?? "?";
    return `${b}${o}`;
  });
}

function slimTacticalIntelForMove(intel: TacticalIntel): Record<string, unknown> {
  const primarySlice =
    intel.primarySource === "h2h"
      ? intel.h2h
      : intel.primarySource === "recentVsOpponent"
        ? intel.recentVsOpponent
        : intel.lifetime;
  const patterns = primarySlice?.patterns;

  const out: Record<string, unknown> = {
    ranks: intel.sourcesByEfficiency.slice(0, 3).map((r) => ({
      rank: r.rank,
      source: r.source,
      lean: r.dominant,
      open: r.openWith,
    })),
    read: intel.primary?.dominant,
    openWith: intel.primary?.openWith,
    source: intel.primarySource,
  };

  if (intel.h2hRecord.games > 0) {
    out.h2h = {
      games: intel.h2hRecord.games,
      botWins: intel.h2hRecord.botSeriesWins,
      oppWins: intel.h2hRecord.opponentSeriesWins,
    };
  }
  if (intel.opponentRepeat) out.repeat = intel.opponentRepeat;

  const seq = intel.recentOpponentThrows
    .slice(-6)
    .map((t) => moveCode(t))
    .filter((m): m is string => !!m);
  if (seq.length > 0) out.recentSeq = seq;

  if (patterns) {
    out.primary = {
      skew: patterns.skew,
      secondary: patterns.secondary,
      repeatPct: patterns.repeatRatePct,
      ...(patterns.lastWindow.size > 0
        ? { lastWindow: { size: patterns.lastWindow.size, mix: patterns.lastWindow.distribution } }
        : {}),
    };
  }

  const cross = intel.crossPatterns.opponent;
  if (cross) {
    out.crossOpponent = { lean: cross.dominant, mix: cross.distribution };
  }

  return out;
}

export function countHistoryThrowPairs(ctx: MatchDbContext): number {
  return collectThrowPairs(ctx).length;
}

function collectThrowPairs(ctx: MatchDbContext): MoveThrowPair[] {
  const pairs: MoveThrowPair[] = [];

  const addGame = (g: HistoricalMatchSummary) => {
    for (const r of g.rounds) {
      const bot = moveCode(r.botMove);
      const opponent = moveCode(r.opponentMove);
      if (!bot && !opponent) continue;
      pairs.push({ bot, opponent });
    }
  };

  for (const g of ctx.headToHead) addGame(g);
  for (const g of ctx.recentBotMatches) {
    if (g.id === ctx.currentMatch?.id) continue;
    addGame(g);
  }

  return pairs;
}

function opponentLifetimeTrend(
  p: UserProfile,
): { rock: number; paper: number; scissors: number; dominant: string } {
  const ranked = [
    { move: "ROCK", n: p.throwsRock },
    { move: "PAPER", n: p.throwsPaper },
    { move: "SCISSORS", n: p.throwsScissors },
  ].sort((a, b) => b.n - a.n);
  return {
    rock: p.throwsRock,
    paper: p.throwsPaper,
    scissors: p.throwsScissors,
    dominant: ranked[0]!.move,
  };
}

/** Prior bot/opponent throws from cached games (round 1 when this match has no rounds yet). */
export function buildCrossMatchHistory(
  ctx: MatchDbContext,
  maxPairs = CROSS_MATCH_MAX_PAIRS,
): { throwPairs: MoveThrowPair[]; opponentLifetimeTrend?: ReturnType<typeof opponentLifetimeTrend> } {
  const pairs = collectThrowPairs(ctx);
  const throwPairs = pairs.length > maxPairs ? pairs.slice(-maxPairs) : pairs;

  const out: {
    throwPairs: MoveThrowPair[];
    opponentLifetimeTrend?: ReturnType<typeof opponentLifetimeTrend>;
  } = { throwPairs };

  if (ctx.opponentProfile) {
    const total =
      ctx.opponentProfile.throwsRock +
      ctx.opponentProfile.throwsPaper +
      ctx.opponentProfile.throwsScissors;
    if (total > 0) {
      out.opponentLifetimeTrend = opponentLifetimeTrend(ctx.opponentProfile);
    }
  }

  return out;
}

/** @deprecated Use buildCrossMatchHistory — kept for pickMove log line. */
export function buildRecentPicksForRound1(ctx: MatchDbContext): string {
  const { throwPairs } = buildCrossMatchHistory(ctx);
  if (throwPairs.length === 0) return "none";
  return JSON.stringify(throwPairs);
}

function formatPriorMatches(
  games: HistoricalMatchSummary[],
  maxGames: number,
  maxRoundsPerGame = 2,
): Array<{
  score: { bot: number; opponent: number };
  lastRounds: MoveThrowPair[];
}> {
  return games.slice(0, maxGames).map((g) => ({
    score: { bot: g.botWins, opponent: g.opponentWins },
    lastRounds: g.rounds.slice(-maxRoundsPerGame).map((r) => ({
      bot: moveCode(r.botMove),
      opponent: moveCode(r.opponentMove),
    })),
  }));
}

/** Opponent intel for tactics prep and round-1 move context. */
export function buildOpponentIntelPayload(
  match: Match,
  ctx: MatchDbContext,
): Record<string, unknown> {
  const snap = compactMatchForPick(match, ctx.botUid);
  const cross = buildCrossMatchHistory(ctx);
  const payload: Record<string, unknown> = {
    bot: snap.botName,
    opponent: ctx.opponentName,
    mode: match.matchMode,
    opponentLifetime: slimOpponentLifetime(ctx.opponentProfile),
    priorMatches: formatPriorMatches(ctx.headToHead, 3),
    h2hCount: ctx.headToHead.length,
  };
  if (cross.throwPairs.length > 0 || cross.opponentLifetimeTrend) {
    payload.crossMatchHistory = cross;
  }
  return payload;
}

function dominantOpponentMoveThisMatch(
  rounds: Array<{ opponent?: string }>,
): string | undefined {
  const counts = { ROCK: 0, PAPER: 0, SCISSORS: 0 };
  for (const r of rounds) {
    const o = r.opponent;
    if (o === "ROCK" || o === "PAPER" || o === "SCISSORS") counts[o]++;
  }
  const total = counts.ROCK + counts.PAPER + counts.SCISSORS;
  if (total === 0) return undefined;
  if (counts.ROCK >= counts.PAPER && counts.ROCK >= counts.SCISSORS) return "ROCK";
  if (counts.PAPER >= counts.SCISSORS) return "PAPER";
  return "SCISSORS";
}

function compactTacticalRead(ctx: MatchDbContext): Record<string, unknown> | undefined {
  const primary = ctx.tacticalIntel?.primary;
  if (!primary) return undefined;
  const out: Record<string, unknown> = {
    read: primary.dominant,
    openWith: primary.openWith,
    source: ctx.tacticalIntel!.primarySource,
  };
  if (ctx.tacticalIntel?.opponentRepeat) out.repeat = ctx.tacticalIntel.opponentRepeat;
  return out;
}

/** Later rounds: live match + plan only (static history sent on round 1). */
export function buildCompactMoveUserPrompt(match: Match, ctx: MatchDbContext): string {
  const snap = compactMatchForPick(match, ctx.botUid);
  const thisMatchRounds = (
    snap.priorRounds as Array<{ bot?: string; opponent?: string }>
  ).map((r) => ({
    bot: moveCode(r.bot),
    opponent: moveCode(r.opponent),
  }));

  const lastRound = thisMatchRounds[thisMatchRounds.length - 1];
  const payload: Record<string, unknown> = {
    bot: snap.botName,
    opponent: ctx.opponentName,
    round: snap.round,
    score: { bot: snap.botWins, opponent: snap.opponentWins },
    thisMatchRounds,
  };

  if (lastRound?.bot) payload.lastBotMove = lastRound.bot;
  const matchLean = dominantOpponentMoveThisMatch(thisMatchRounds);
  if (matchLean) payload.opponentLeanThisMatch = matchLean;
  if (ctx.tactics?.trim()) payload.preparedTactics = ctx.tactics.trim();
  const read = compactTacticalRead(ctx);
  if (read) payload.read = read;

  const { catalog } = buildMoveIntelCatalogForPick(match, ctx);
  payload.intelCatalog = catalog;
  payload.intelSignalGlossary = glossaryForCatalog(catalog);

  return JSON.stringify(payload);
}

/** Round 1: opponent summary + slim tactical intel (no raw cross-game pair dump). */
export function buildFullMoveUserPrompt(match: Match, ctx: MatchDbContext): string {
  const snap = compactMatchForPick(match, ctx.botUid);
  const thisMatchRounds = (
    snap.priorRounds as Array<{ bot?: string; opponent?: string }>
  ).map((r) => ({
    bot: moveCode(r.bot),
    opponent: moveCode(r.opponent),
  }));

  const lastRound = thisMatchRounds[thisMatchRounds.length - 1];

  const payload: Record<string, unknown> = {
    bot: snap.botName,
    opponent: ctx.opponentName,
    round: snap.round,
    score: { bot: snap.botWins, opponent: snap.opponentWins },
    thisMatchRounds,
    opponentLifetime: slimOpponentLifetime(ctx.opponentProfile),
    priorMatches: formatPriorMatches(ctx.headToHead, 1, 2),
  };

  if (lastRound?.bot) payload.lastBotMove = lastRound.bot;
  if (ctx.tactics?.trim()) {
    payload.preparedTactics = truncatePreparedTactics(ctx.tactics);
  }
  if (ctx.tacticalIntel) {
    payload.tacticalIntel = slimTacticalIntelForMove(ctx.tacticalIntel);
  } else if (snap.round === 1 && thisMatchRounds.length === 0) {
    const { throwPairs } = buildCrossMatchHistory(ctx, MOVE_PICK_CROSS_MAX_PAIRS);
    const letters = compactThrowPairLetters(throwPairs);
    if (letters.length > 0) payload.crossPairs = letters;
  }

  const { catalog } = buildMoveIntelCatalogForPick(match, ctx);
  payload.intelCatalog = catalog;
  payload.intelSignalGlossary = glossaryForCatalog(catalog);

  return JSON.stringify(payload);
}

/** Catalog for pick validation (must match the user prompt for this round). */
export function buildMoveIntelCatalogForPick(
  match: Match,
  ctx: MatchDbContext,
): ReturnType<typeof buildMoveIntelCatalog> {
  const snap = compactMatchForPick(match, ctx.botUid);
  const rounds = (
    snap.priorRounds as Array<{ bot?: string; opponent?: string }>
  ).map((r) => ({
    opponent: moveCode(r.opponent),
  }));
  return buildMoveIntelCatalog(ctx, {
    opponentLeanThisMatch: dominantOpponentMoveThisMatch(rounds),
  });
}

/** Structured JSON for the move-pick LLM (explicit bot vs opponent). */
export function buildFastMoveUserPrompt(match: Match, ctx: MatchDbContext): string {
  const snap = compactMatchForPick(match, ctx.botUid);
  const priorCount = (
    snap.priorRounds as Array<{ bot?: string; opponent?: string }>
  ).filter((r) => r.bot || r.opponent).length;
  if (useCompactMovePrompt(Number(snap.round), priorCount)) {
    return buildCompactMoveUserPrompt(match, ctx);
  }
  return buildFullMoveUserPrompt(match, ctx);
}

/** Compact intel for move log lines. */
export function formatMoveIntelLog(ctx: MatchDbContext): string {
  if (ctx.tacticalIntel) return formatTacticalIntelCompact(ctx.tacticalIntel);
  return "";
}

/** @deprecated Use buildMoveSystemPrompt(round) — kept for post-start warmup import site. */
export const MOVE_SYSTEM_PROMPT = buildMoveSystemPrompt(1);

export const pickMoveContextLimits = { headToHead: 5, recentBot: 8 };
