import type { Match } from "../types.js";
import type { MatchDbContext } from "./matchContext.js";
import { buildMoveIntelCatalogForPick } from "./movePrompt.js";
import type { IntelCatalogEntry } from "./moveIntelCatalog.js";
import { moveCode, type MoveThrowPair } from "./movePrompt.js";
import type { MoveIntelSignal, MoveIntelSource } from "./parse.js";
import type { TendencySlice } from "./tacticalIntel.js";
import {
  analyzeThrowPatternFromPairs,
  detectOpponentRepeat,
  topMoveFromDistribution,
  type RpsMove,
  type ThrowPatternProfile,
} from "./throwPatternIntel.js";

export interface RoundSignalScoreRow {
  matchId: string;
  roundNumber: number;
  source: MoveIntelSource;
  signal: MoveIntelSignal;
  leanHit: boolean;
}

interface RoundPickContext {
  roundNumber: number;
  priorPairs: MoveThrowPair[];
  priorOpponentThrows: RpsMove[];
  actualOpponent: RpsMove;
  priorRoundOutcome?: "bot_won" | "bot_lost" | "tie";
}

function sliceForSource(
  ctx: MatchDbContext,
  source: MoveIntelSource,
): TendencySlice | undefined {
  const intel = ctx.tacticalIntel;
  if (!intel) return undefined;
  if (source === "h2h") return intel.h2h;
  if (source === "recentVsOpponent") return intel.recentVsOpponent;
  if (source === "global") return intel.global;
  if (source === "lifetime") return intel.lifetime;
  return undefined;
}

function patternsForSource(
  source: MoveIntelSource,
  ctx: MatchDbContext,
  roundCtx: RoundPickContext,
): ThrowPatternProfile | null {
  if (source === "thisMatch") {
    if (roundCtx.priorPairs.length < 2) return null;
    return analyzeThrowPatternFromPairs(roundCtx.priorPairs);
  }
  return sliceForSource(ctx, source)?.patterns ?? null;
}

function dominantFromThrows(throws: RpsMove[]): RpsMove | null {
  if (throws.length === 0) return null;
  const counts = { ROCK: 0, PAPER: 0, SCISSORS: 0 };
  for (const t of throws) counts[t]++;
  if (counts.ROCK >= counts.PAPER && counts.ROCK >= counts.SCISSORS) return "ROCK";
  if (counts.PAPER >= counts.SCISSORS) return "PAPER";
  return "SCISSORS";
}

function priorRoundOutcome(
  match: Match,
  botUid: string,
  roundNumber: number,
): RoundPickContext["priorRoundOutcome"] | undefined {
  const prev = match.rounds.find((r) => r.roundNumber === roundNumber - 1);
  if (!prev?.winner) return undefined;
  if (prev.winner === "tie") return "tie";
  return prev.winner === botUid ? "bot_won" : "bot_lost";
}

function matchAtRoundPick(match: Match, roundNumber: number): Match {
  return {
    ...match,
    currentRound: roundNumber,
  };
}

/** Predict opponent throw from a citable signal (null = not measurable this round). */
export function predictOpponentMoveForSignal(
  source: MoveIntelSource,
  signal: MoveIntelSignal,
  ctx: MatchDbContext,
  roundCtx: RoundPickContext,
): RpsMove | null {
  const slice = sliceForSource(ctx, source);
  const patterns = patternsForSource(source, ctx, roundCtx);
  const lastOpp = roundCtx.priorOpponentThrows.at(-1);
  const lastBot = roundCtx.priorPairs.at(-1)?.bot as RpsMove | undefined;

  switch (signal) {
    case "dominant":
      return slice?.dominant ?? patterns?.dominant ?? null;
    case "distribution":
      if (!patterns) return null;
      return (
        topMoveFromDistribution({
          ...patterns.distribution,
          sample: patterns.counts.total,
        }) ?? patterns.dominant
      );
    case "openWith":
      return slice?.dominant ?? patterns?.dominant ?? null;
    case "secondary":
      return patterns?.secondary ?? null;
    case "skew":
      return patterns?.dominant ?? null;
    case "lastWindow":
      if (!patterns || patterns.lastWindow.size < 3) return null;
      return (
        topMoveFromDistribution({
          ...patterns.lastWindow.distribution,
          sample: patterns.lastWindow.counts.total,
        }) ?? null
      );
    case "transitions": {
      if (!lastOpp || !patterns?.transitions.length) return null;
      const t = patterns.transitions.find((row) => row.after === lastOpp);
      return t ? (topMoveFromDistribution(t.next) ?? null) : null;
    }
    case "secondOrderTransition": {
      if (roundCtx.priorOpponentThrows.length < 2 || !patterns?.secondOrderTransitions.length) {
        return null;
      }
      const first = roundCtx.priorOpponentThrows.at(-2)!;
      const second = roundCtx.priorOpponentThrows.at(-1)!;
      const t = patterns.secondOrderTransitions.find(
        (row) => row.first === first && row.second === second,
      );
      return t ? (topMoveFromDistribution(t.next) ?? null) : null;
    }
    case "responseToBot": {
      if (!lastBot || !patterns?.responseToBot.length) return null;
      const r = patterns.responseToBot.find((row) => row.whenBotThrew === lastBot);
      return r ? (topMoveFromDistribution(r.opponentNext) ?? null) : null;
    }
    case "afterBotWin":
      if (roundCtx.priorRoundOutcome !== "bot_won" || !patterns?.outcomeThrows) return null;
      return topMoveFromDistribution(patterns.outcomeThrows.afterBotWin) ?? null;
    case "afterBotLoss":
      if (roundCtx.priorRoundOutcome !== "bot_lost" || !patterns?.outcomeThrows) return null;
      return topMoveFromDistribution(patterns.outcomeThrows.afterBotLoss) ?? null;
    case "repeat": {
      const rep = detectOpponentRepeat(roundCtx.priorOpponentThrows);
      return rep?.move ?? null;
    }
    case "streakBreakBias": {
      const rep = detectOpponentRepeat(roundCtx.priorOpponentThrows);
      if (!rep) return null;
      const bias = patterns?.streakBreakBias;
      if (!bias || bias.continuePct === bias.breakPct) return rep.move;
      return bias.continuePct > bias.breakPct ? rep.move : null;
    }
    case "opponentLeanThisMatch":
      return dominantFromThrows(roundCtx.priorOpponentThrows);
    case "recentSeq": {
      const seq = ctx.tacticalIntel?.recentOpponentThrows;
      if (!seq?.length) return null;
      const last = seq[seq.length - 1];
      return last === "ROCK" || last === "PAPER" || last === "SCISSORS" ? last : null;
    }
    case "crossOpponent":
      return ctx.tacticalIntel?.crossPatterns.opponent?.dominant ?? null;
    default:
      return null;
  }
}

export function scoreSignalsForRound(
  matchId: string,
  roundNumber: number,
  catalog: IntelCatalogEntry[],
  ctx: MatchDbContext,
  roundCtx: RoundPickContext,
): RoundSignalScoreRow[] {
  const rows: RoundSignalScoreRow[] = [];
  for (const entry of catalog) {
    for (const signal of entry.signals) {
      const predicted = predictOpponentMoveForSignal(entry.source, signal, ctx, roundCtx);
      if (!predicted) continue;
      rows.push({
        matchId,
        roundNumber,
        source: entry.source,
        signal,
        leanHit: predicted === roundCtx.actualOpponent,
      });
    }
  }
  return rows;
}

/** Counterfactual lean scoring for every applicable catalog signal each round. */
export function scoreAllRoundsInMatch(
  match: Match,
  botUid: string,
  ctx: MatchDbContext,
): RoundSignalScoreRow[] {
  if (!ctx.tacticalIntel) return [];

  const botIsP1 = match.player1 === botUid;
  const resolved = match.rounds
    .filter((r) => r.resolvedAt && r.player1Choice && r.player2Choice)
    .sort((a, b) => a.roundNumber - b.roundNumber);

  const dbCtx = { ...ctx, tacticalIntel: ctx.tacticalIntel };
  const allRows: RoundSignalScoreRow[] = [];
  const priorPairs: MoveThrowPair[] = [];

  for (const round of resolved) {
    const oppRaw = botIsP1 ? round.player2Choice : round.player1Choice;
    const actualOpponent = moveCode(oppRaw) as RpsMove | undefined;
    if (actualOpponent !== "ROCK" && actualOpponent !== "PAPER" && actualOpponent !== "SCISSORS") {
      continue;
    }

    const matchAtPick = matchAtRoundPick(match, round.roundNumber);
    const { catalog } = buildMoveIntelCatalogForPick(matchAtPick, dbCtx);
    const priorOpponentThrows = priorPairs
      .map((p) => p.opponent)
      .filter((o): o is RpsMove => o === "ROCK" || o === "PAPER" || o === "SCISSORS");

    const roundCtx: RoundPickContext = {
      roundNumber: round.roundNumber,
      priorPairs: [...priorPairs],
      priorOpponentThrows,
      actualOpponent,
      priorRoundOutcome: priorRoundOutcome(match, botUid, round.roundNumber),
    };

    allRows.push(
      ...scoreSignalsForRound(match.id, round.roundNumber, catalog, dbCtx, roundCtx),
    );

    const botRaw = botIsP1 ? round.player1Choice : round.player2Choice;
    priorPairs.push({
      bot: moveCode(botRaw),
      opponent: actualOpponent,
    });
  }

  return allRows;
}

export function formatRoundSignalScoreLog(
  rows: RoundSignalScoreRow[],
  matchId: string,
): string {
  if (rows.length === 0) return `match=${matchId} signal-rounds=0`;
  const rounds = new Set(rows.map((r) => r.roundNumber)).size;
  const hits = rows.filter((r) => r.leanHit).length;
  return `match=${matchId} signal-rounds=${rows.length} rounds=${rounds} leanHits=${hits}/${rows.length}`;
}
