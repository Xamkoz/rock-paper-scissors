import type { MatchDbContext } from "./matchContext.js";
import type { TendencySlice } from "./tacticalIntel.js";
import type { MoveIntelSignal, MoveIntelSource } from "./parse.js";
import type { IntelCatalogEntry } from "./moveIntelCatalog.js";
import type { RpsMove } from "./throwPatternIntel.js";
import { counterToOpponentThrow } from "./opponentTendency.js";
import { isSignalValidForSource } from "./moveIntelCatalog.js";
import { pickExplorationSignals } from "./intelCitationRanking.js";
import { moveCode } from "./movePrompt.js";
import {
  topMoveFromDistribution,
  type ThrowPatternProfile,
} from "./throwPatternIntel.js";

export interface IntelCitationHint {
  source: MoveIntelSource;
  signal: MoveIntelSignal;
  hint: string;
}

export interface PickIntelHintContext {
  thisMatchPatterns?: ThrowPatternProfile | null;
  thisMatchRepeat?: { move: RpsMove; streak: number };
}

const ROTATING_SIGNALS: MoveIntelSignal[] = [
  "secondOrderTransition",
  "afterBotWin",
  "afterBotLoss",
  "alternationRate",
  "streakBreakBias",
  "transitions",
  "responseToBot",
  "secondary",
  "repeatRate",
  "lastWindow",
  "distribution",
  "skew",
  "openWith",
  "dominant",
  "opponentLifetime",
  "priorMatches",
  "crossOpponent",
];

function primarySource(ctx: MatchDbContext): MoveIntelSource {
  const s = ctx.tacticalIntel?.primarySource;
  if (s && s !== "none") return s;
  if (ctx.tacticalIntel?.h2h) return "h2h";
  if (ctx.tacticalIntel?.recentVsOpponent) return "recentVsOpponent";
  if (ctx.tacticalIntel?.global) return "global";
  return "lifetime";
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

function pushHint(
  hints: IntelCitationHint[],
  catalog: IntelCatalogEntry[],
  source: MoveIntelSource,
  signal: MoveIntelSignal,
  hint: string,
): void {
  if (!isSignalValidForSource(catalog, source, signal)) return;
  if (hints.some((h) => h.source === source && h.signal === signal)) return;
  hints.push({ source, signal, hint });
}

function dominantOpponentThisMatch(
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

/** Concrete citations for this round — steers the model past always picking dominant. */
export function buildIntelCitationHints(
  ctx: MatchDbContext,
  round: number,
  thisMatchRounds: Array<{ bot?: string; opponent?: string }>,
  catalog: IntelCatalogEntry[],
  hintCtx: PickIntelHintContext = {},
): IntelCitationHint[] {
  const hints: IntelCitationHint[] = [];
  const intel = ctx.tacticalIntel;
  const primary = primarySource(ctx);
  const slice = sliceForSource(ctx, primary);
  const patterns = slice?.patterns;

  const thisRepeat = hintCtx.thisMatchRepeat;
  if (thisRepeat && thisRepeat.streak >= 2) {
    const counter = counterToOpponentThrow(thisRepeat.move);
    pushHint(
      hints,
      catalog,
      "thisMatch",
      "repeat",
      `This match: opponent on ${thisRepeat.move} ×${thisRepeat.streak} — counter with ${counter}`,
    );
  } else if (intel?.opponentRepeat && intel.opponentRepeat.streak >= 2) {
    const counter = counterToOpponentThrow(intel.opponentRepeat.move);
    for (const source of ["h2h", "recentVsOpponent", "global"] as const) {
      pushHint(
        hints,
        catalog,
        source,
        "repeat",
        `Opponent on ${intel.opponentRepeat.move} ×${intel.opponentRepeat.streak} — counter with ${counter}`,
      );
      if (hints.some((h) => h.signal === "repeat")) break;
    }
  }

  if (thisMatchRounds.length > 0) {
    const last = thisMatchRounds[thisMatchRounds.length - 1];
    const opponentSeq = thisMatchRounds
      .map((r) => r.opponent)
      .filter((o): o is RpsMove => o === "ROCK" || o === "PAPER" || o === "SCISSORS")
      .slice(-8)
      .join("→");
    const lastPart =
      last?.bot && last.opponent
        ? ` Last: bot ${last.bot} vs opp ${last.opponent}.`
        : "";
    const seqPart = opponentSeq ? ` Opp seq: ${opponentSeq}.` : "";
    pushHint(
      hints,
      catalog,
      "thisMatch",
      "thisMatchRounds",
      `${thisMatchRounds.length} throw(s) this match.${seqPart}${lastPart}`,
    );
    const lean = dominantOpponentThisMatch(thisMatchRounds);
    if (lean) {
      pushHint(
        hints,
        catalog,
        "thisMatch",
        "opponentLeanThisMatch",
        `This-match lean ${lean} so far`,
      );
    }
  }

  if (ctx.tactics?.trim()) {
    pushHint(
      hints,
      catalog,
      "thisMatch",
      "preparedTactics",
      ctx.tactics.trim().slice(0, 100),
    );
  }

  const explorationSignals = pickExplorationSignals(
    round,
    5,
    catalog,
    ctx.signalPickStats,
    ROTATING_SIGNALS,
    ctx.signalLeanStats,
  );
  for (const signal of explorationSignals) {
    const before = hints.length;
    addMetaExplorationHint(hints, catalog, ctx, signal);
    if (hints.length > before) continue;
    addRotatingPatternHint(hints, catalog, primary, signal, patterns, slice);
    if (hints.length === before && hintCtx.thisMatchPatterns) {
      addRotatingPatternHint(
        hints,
        catalog,
        "thisMatch",
        signal,
        hintCtx.thisMatchPatterns,
        undefined,
      );
    }
  }

  if (hintCtx.thisMatchPatterns) {
    const tmRotated = pickExplorationSignals(
      round + 1,
      2,
      catalog.filter((e) => e.source === "thisMatch"),
      ctx.signalPickStats,
      ROTATING_SIGNALS,
      ctx.signalLeanStats,
    );
    for (const signal of tmRotated) {
      addRotatingPatternHint(
        hints,
        catalog,
        "thisMatch",
        signal,
        hintCtx.thisMatchPatterns,
        undefined,
      );
    }
  }

  if (intel && intel.h2hRecord.games > 0) {
    pushHint(
      hints,
      catalog,
      "h2h",
      "h2hRecord",
      `H2H series ${intel.h2hRecord.botSeriesWins}-${intel.h2hRecord.opponentSeriesWins} (${intel.h2hRecord.games}g)`,
    );
  }

  if (intel && intel.recentOpponentThrows.length >= 3) {
    const seq = intel.recentOpponentThrows
      .slice(-5)
      .map((t) => moveCode(t) ?? "?")
      .join(",");
    pushHint(
      hints,
      catalog,
      "recentVsOpponent",
      "recentSeq",
      `Recent opponent seq: ${seq}`,
    );
  }

  return prioritizeExplorationHints(hints, explorationSignals, thisMatchRounds.length > 0).slice(
    0,
    7,
  );
}

/** Surface under-sampled signals early, but always pin live thisMatch hints first. */
function prioritizeExplorationHints(
  hints: IntelCitationHint[],
  explorationSignals: MoveIntelSignal[],
  pinThisMatch = false,
): IntelCitationHint[] {
  let ordered = hints;
  if (explorationSignals.length > 0) {
    const exploreSet = new Set(explorationSignals);
    const preferred: IntelCitationHint[] = [];
    const rest: IntelCitationHint[] = [];
    for (const h of hints) {
      if (exploreSet.has(h.signal)) preferred.push(h);
      else rest.push(h);
    }
    ordered = [...preferred, ...rest];
  }
  if (!pinThisMatch) return ordered;
  const thisMatch = ordered.filter((h) => h.source === "thisMatch");
  const other = ordered.filter((h) => h.source !== "thisMatch");
  return [...thisMatch, ...other];
}

function pickRotatingSignals(round: number, count: number): MoveIntelSignal[] {
  const start = (Math.max(1, round) - 1) % ROTATING_SIGNALS.length;
  const out: MoveIntelSignal[] = [];
  for (let i = 0; i < count; i++) {
    out.push(ROTATING_SIGNALS[(start + i) % ROTATING_SIGNALS.length]!);
  }
  return out;
}

/** Hints for catalog signals that are not backed by a single tendency pattern blob. */
function addMetaExplorationHint(
  hints: IntelCitationHint[],
  catalog: IntelCatalogEntry[],
  ctx: MatchDbContext,
  signal: MoveIntelSignal,
): void {
  const intel = ctx.tacticalIntel;

  if (signal === "opponentLifetime" && ctx.opponentProfile) {
    const p = ctx.opponentProfile;
    const total = p.throwsRock + p.throwsPaper + p.throwsScissors;
    if (total > 0) {
      const lean =
        p.throwsRock >= p.throwsPaper && p.throwsRock >= p.throwsScissors
          ? "ROCK"
          : p.throwsPaper >= p.throwsScissors
            ? "PAPER"
            : "SCISSORS";
      pushHint(
        hints,
        catalog,
        "lifetime",
        "opponentLifetime",
        `Profile ${p.elo} elo — career lean ${lean}, mix R${Math.round((p.throwsRock / total) * 100)}/P${Math.round((p.throwsPaper / total) * 100)}/S${Math.round((p.throwsScissors / total) * 100)}`,
      );
    } else {
      pushHint(
        hints,
        catalog,
        "lifetime",
        "opponentLifetime",
        `Profile ${p.elo} elo — no throw totals yet`,
      );
    }
    return;
  }

  if (signal === "priorMatches" && ctx.headToHead.length > 0) {
    const g = ctx.headToHead[0]!;
    const last = g.rounds.at(-1);
    const lastLine =
      last?.opponentMove != null
        ? ` last opp ${moveCode(last.opponentMove) ?? "?"}`
        : "";
    pushHint(
      hints,
      catalog,
      "h2h",
      "priorMatches",
      `Prior H2H ${g.botWins}-${g.opponentWins} (${g.rounds.length} rounds logged)${lastLine}`,
    );
    return;
  }

  if (signal === "crossOpponent" && intel?.crossPatterns.opponent) {
    const cross = intel.crossPatterns.opponent;
    const d = cross.distribution;
    pushHint(
      hints,
      catalog,
      "recentVsOpponent",
      "crossOpponent",
      `Cross-match opp lean ${cross.dominant} (${cross.dominantPct}%) mix R${d.rockPct}/P${d.paperPct}/S${d.scissorsPct} (n=${intel.crossPatterns.pairCount})`,
    );
  }
}

function addRotatingPatternHint(
  hints: IntelCitationHint[],
  catalog: IntelCatalogEntry[],
  primary: MoveIntelSource,
  signal: MoveIntelSignal,
  patterns: TendencySlice["patterns"] | undefined,
  slice: TendencySlice | undefined,
): void {
  if (!patterns && !slice) return;

  if (signal === "transitions" && patterns?.transitions.length) {
    const t = patterns.transitions[0]!;
    const topNext = Object.entries({
      ROCK: t.next.rockPct,
      PAPER: t.next.paperPct,
      SCISSORS: t.next.scissorsPct,
    }).sort((a, b) => b[1] - a[1])[0]?.[0];
    pushHint(
      hints,
      catalog,
      primary,
      "transitions",
      `After ${t.after} → likely ${topNext ?? "mix"} (n=${t.next.sample})`,
    );
    return;
  }

  if (signal === "responseToBot" && patterns?.responseToBot.length) {
    const r = patterns.responseToBot[0]!;
    pushHint(
      hints,
      catalog,
      primary,
      "responseToBot",
      `After bot ${r.whenBotThrew} opponent skews R${r.opponentNext.rockPct}/P${r.opponentNext.paperPct}/S${r.opponentNext.scissorsPct}`,
    );
    return;
  }

  if (signal === "secondary" && patterns?.secondary) {
    pushHint(
      hints,
      catalog,
      primary,
      "secondary",
      `Secondary lean ${patterns.secondary} (${patterns.secondaryPct}%)`,
    );
    return;
  }

  if (signal === "repeatRate" && patterns && patterns.repeatRatePct >= 15) {
    const last = patterns.lastThrows.at(-1);
    const counterHint =
      last != null ? ` on ${last} — counter with ${counterToOpponentThrow(last)}` : "";
    pushHint(
      hints,
      catalog,
      primary,
      "repeatRate",
      `Repeat rate ${patterns.repeatRatePct}%${counterHint}`,
    );
    return;
  }

  if (signal === "alternationRate" && patterns && patterns.alternationRatePct >= 15) {
    pushHint(
      hints,
      catalog,
      primary,
      "alternationRate",
      `Alternation rate ${patterns.alternationRatePct}% (switches vs repeats)`,
    );
    return;
  }

  if (signal === "secondOrderTransition" && patterns?.secondOrderTransitions.length) {
    const t = patterns.secondOrderTransitions[0]!;
    const top = topMoveFromDistribution(t.next);
    pushHint(
      hints,
      catalog,
      primary,
      "secondOrderTransition",
      `After ${t.first[0]}${t.second[0]} → likely ${top ?? "mix"} (n=${t.next.sample})`,
    );
    return;
  }

  if (signal === "afterBotWin" && patterns?.outcomeThrows?.afterBotWin.sample) {
    const d = patterns.outcomeThrows.afterBotWin;
    const top = topMoveFromDistribution(d);
    pushHint(
      hints,
      catalog,
      primary,
      "afterBotWin",
      `After bot won prior round → opp skews ${top ?? "mix"} (n=${d.sample})`,
    );
    return;
  }

  if (signal === "afterBotLoss" && patterns?.outcomeThrows?.afterBotLoss.sample) {
    const d = patterns.outcomeThrows.afterBotLoss;
    const top = topMoveFromDistribution(d);
    pushHint(
      hints,
      catalog,
      primary,
      "afterBotLoss",
      `After bot lost prior round → opp skews ${top ?? "mix"} (n=${d.sample})`,
    );
    return;
  }

  if (signal === "streakBreakBias" && patterns?.streakBreakBias) {
    const b = patterns.streakBreakBias;
    pushHint(
      hints,
      catalog,
      primary,
      "streakBreakBias",
      `On ≥2 streak: continue ${b.continuePct}% / break ${b.breakPct}% (n=${b.sample})`,
    );
    return;
  }

  if (signal === "lastWindow" && patterns && patterns.lastWindow.size >= 3) {
    const w = patterns.lastWindow.distribution;
    pushHint(
      hints,
      catalog,
      primary,
      "lastWindow",
      `Last ${patterns.lastWindow.size} throws R${w.rockPct}/P${w.paperPct}/S${w.scissorsPct}`,
    );
    return;
  }

  if (signal === "distribution" && patterns) {
    const d = patterns.distribution;
    pushHint(
      hints,
      catalog,
      primary,
      "distribution",
      `Mix R${d.rockPct}/P${d.paperPct}/S${d.scissorsPct}`,
    );
    return;
  }

  if (signal === "skew" && patterns?.skew) {
    pushHint(
      hints,
      catalog,
      primary,
      "skew",
      `Skew ${patterns.skew} on ${patterns.dominant}`,
    );
    return;
  }

  if (signal === "openWith" && slice?.openWith && slice.dominant) {
    pushHint(
      hints,
      catalog,
      primary,
      "openWith",
      `Open ${slice.openWith} vs ${slice.dominant} lean`,
    );
    return;
  }

  if (signal === "dominant" && slice?.dominant) {
    pushHint(
      hints,
      catalog,
      primary,
      "dominant",
      `Dominant ${slice.dominant} (${slice.dominantPct}%)`,
    );
  }
}
