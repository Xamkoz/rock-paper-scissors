import {
  counterToOpponentThrow,
  COUNTER_TO_OPPONENT,
  lastOpponentThrowFromMatch,
  moveBeats,
  opponentThrowsFromMatch,
} from "./opponentTendency.js";
import type { MatchDbContext } from "./matchContext.js";
import type { IntelCatalogEntry } from "./moveIntelCatalog.js";
import { isSignalValidForSource } from "./moveIntelCatalog.js";
import { moveDisplayName } from "./movePrompt.js";
import type { MoveIntelSignal, MoveIntelSource, MovePickParsed } from "./parse.js";
import type { Move } from "../types.js";
import { detectOpponentRepeat, type RpsMove } from "./throwPatternIntel.js";

const TACTICS_DUMP =
  /\bleans?\s+(rock|paper|scissors)\b.*\bopen with\b|\bif they throw\b/i;

const BEATS_CLAIM = /\b(rock|paper|scissors)\s+beats\s+(rock|paper|scissors)\b/gi;

function asRpsMove(word: string | undefined): RpsMove | null {
  if (!word) return null;
  const u = word.toUpperCase();
  if (u === "ROCK" || u === "PAPER" || u === "SCISSORS") return u;
  return null;
}

/** Detect prose like "Scissors beats Rock" that violates RPS rules. */
export function reasonClaimsInvalidBeat(reason: string): boolean {
  for (const match of reason.matchAll(BEATS_CLAIM)) {
    const winner = asRpsMove(match[1]);
    const loser = asRpsMove(match[2]);
    if (winner && loser && !moveBeats(winner, loser)) return true;
  }
  return false;
}

function opponentMoveFromRepeatReason(reason: string): RpsMove | null {
  const r = reason;
  return (
    asRpsMove(r.match(/\b(rock|paper|scissors)\s+streak\b/i)?.[1]) ??
    asRpsMove(r.match(/\bon\s+(?:a\s+)?(rock|paper|scissors)\b/i)?.[1]) ??
    asRpsMove(r.match(/\b(rock|paper|scissors)\s*[×x]\s*\d+/i)?.[1]) ??
    asRpsMove(r.match(/\b(?:throwing|throws?|threw)\s+(rock|paper|scissors)\b/i)?.[1])
  );
}

function opponentMoveFromLeanReason(reason: string): RpsMove | null {
  const r = reason;
  return (
    asRpsMove(r.match(/\b(rock|paper|scissors)\s+lean\b/i)?.[1]) ??
    asRpsMove(r.match(/\bleans?\s+(?:towards?\s+)?(rock|paper|scissors)\b/i)?.[1]) ??
    asRpsMove(r.match(/\b(rock|paper|scissors)\s+skew\b/i)?.[1])
  );
}

function opponentMoveFromBeatClaim(reason: string): RpsMove | null {
  const m = reason.match(/\b(rock|paper|scissors)\s+beats\s+(rock|paper|scissors)\b/i);
  if (!m) return null;
  const winner = asRpsMove(m[1]);
  const loser = asRpsMove(m[2]);
  if (!winner || !loser || !moveBeats(winner, loser)) return null;
  return loser;
}

function thisMatchRepeatFromCtx(ctx: MatchDbContext): { move: RpsMove; streak: number } | undefined {
  return detectOpponentRepeat(opponentThrowsFromMatch(ctx.currentMatch, ctx.botUid));
}

/** Best guess of which opponent throw to counter from intel + reason + match history. */
export function resolveLikelyOpponentThrow(ctx: MatchDbContext, reason: string): RpsMove | null {
  const intel = ctx.tacticalIntel;
  const liveRepeat = thisMatchRepeatFromCtx(ctx);
  const fromMatch = lastOpponentThrowFromMatch(ctx.currentMatch, ctx.botUid);

  if (fromMatch) {
    if (liveRepeat?.move) return liveRepeat.move;
    return fromMatch;
  }

  const fromBeat = opponentMoveFromBeatClaim(reason);
  if (fromBeat) return fromBeat;

  const fromReason =
    opponentMoveFromRepeatReason(reason) ?? opponentMoveFromLeanReason(reason);
  if (fromReason) return fromReason;

  if (intel?.opponentRepeat?.move) return intel.opponentRepeat.move;

  const recent = intel?.recentOpponentThrows;
  if (recent?.length) {
    const last = asRpsMove(recent[recent.length - 1]);
    if (last) return last;
  }

  return intel?.primary?.dominant ?? null;
}

const PATTERN_COUNTER_SIGNALS: MoveIntelSignal[] = [
  "repeatRate",
  "alternationRate",
  "streakBreakBias",
  "recentSeq",
  "thisMatchRounds",
];

function resolveOpponentThrowForCounter(
  parsed: MovePickParsed,
  ctx: MatchDbContext,
): RpsMove | null {
  const { intelSignal, reason } = parsed;
  const intel = ctx.tacticalIntel;

  if (intelSignal === "repeat") {
    const live = thisMatchRepeatFromCtx(ctx);
    if (live?.move) return live.move;
    const last = lastOpponentThrowFromMatch(ctx.currentMatch, ctx.botUid);
    if (last) return last;
    if (intel?.opponentRepeat?.move) return intel.opponentRepeat.move;
    return opponentMoveFromRepeatReason(reason);
  }

  if (
    intelSignal === "dominant" ||
    intelSignal === "opponentLeanThisMatch" ||
    intelSignal === "openWith"
  ) {
    const fromReason = opponentMoveFromLeanReason(reason);
    if (fromReason) return fromReason;
    if (intel?.primary?.dominant) return intel.primary.dominant;
  }

  if (intelSignal === "transitions") {
    const afterThrow = reason.match(/\bafter\s+(?:their\s+)?(rock|paper|scissors)\b/i)?.[1];
    const transitionTarget = reason.match(/\b(?:throw|throws?|favor[s]?)\s+(rock|paper|scissors)\b/i)?.[1];
    return asRpsMove(transitionTarget) ?? asRpsMove(afterThrow);
  }

  if (PATTERN_COUNTER_SIGNALS.includes(intelSignal)) {
    return resolveLikelyOpponentThrow(ctx, reason);
  }

  return null;
}

/** Fix inverted counters (e.g. Scissors vs Rock streak) before submit. */
export function ensureCounterMatchesOpponentThrow(
  parsed: MovePickParsed,
  ctx: MatchDbContext,
): MovePickParsed {
  const opponentThrow = resolveOpponentThrowForCounter(parsed, ctx);
  if (!opponentThrow) {
    if (reasonClaimsInvalidBeat(parsed.reason) && ctx.tacticalIntel?.primary?.dominant) {
      const correct = counterToOpponentThrow(ctx.tacticalIntel.primary.dominant);
      return {
        ...parsed,
        choice: correct,
        reason: buildShortMoveReason(correct, parsed.intelSource, parsed.intelSignal, ctx),
      };
    }
    return parsed;
  }

  const correct = COUNTER_TO_OPPONENT[opponentThrow];
  if (parsed.choice === correct && !reasonClaimsInvalidBeat(parsed.reason)) return parsed;

  return {
    ...parsed,
    choice: correct,
    reason: buildShortMoveReason(correct, parsed.intelSource, parsed.intelSignal, ctx),
  };
}

function moveInProse(text: string): Move | null {
  const patterns = [
    /\b(?:open(?:ing)?|pick|play|throw|use|counter with)\s+(?:with\s+)?(rock|paper|scissors)\b/i,
    /\b(rock|paper|scissors)\s+beats\b/i,
  ];
  for (const re of patterns) {
    const m = text.match(re);
    if (m?.[1]) {
      const u = m[1].toUpperCase();
      if (u === "ROCK" || u === "PAPER" || u === "SCISSORS") return u;
    }
  }
  return null;
}

export function reasonLooksLikeTacticsDump(reason: string): boolean {
  const sentences = reason.split(/[.!?]+/).filter((s) => s.trim().length > 8);
  return TACTICS_DUMP.test(reason) || sentences.length >= 3;
}

export function reasonPrimaryMoveMatchesChoice(reason: string, choice: Move): boolean {
  const stated = moveInProse(reason);
  if (!stated) return true;
  return stated === choice;
}

export function reasonMatchesCitation(
  reason: string,
  source: MoveIntelSource,
  signal: MoveIntelSignal,
): boolean {
  const r = reason.toLowerCase();
  if (signal === "preparedTactics" || signal === "openWith") {
    return /\bopen(ing)?\b|\btactic|\bplan\b|\bcounter\b/i.test(r);
  }
  if (signal === "dominant" || signal === "opponentLeanThisMatch") {
    return /\blean\b|\bdominant\b|\breads?\b|\bplay(s|ed|ing)?\s+(rock|paper|scissors)\b|\bbeats\b/i.test(
      r,
    );
  }
  if (signal === "transitions") return /\bafter\b|\btransition\b/i.test(r);
  if (signal === "repeat") return /\brepeat\b|\bstreak\b|\bin a row\b/i.test(r);
  if (signal === "repeatRate") return /\brepeat\s+rate\b|\bpattern\b|\bcontinue\b/i.test(r);
  if (signal === "h2hRecord") return /\bh2h\b|\bseries\b|\brecord\b/i.test(r);
  if (signal === "thisMatchRounds") {
    return /\bthis match\b|\bthrow(s)? so far\b|\bcounters\b/i.test(r);
  }
  return !reasonLooksLikeTacticsDump(reason);
}

function primarySource(ctx: MatchDbContext): MoveIntelSource {
  const s = ctx.tacticalIntel?.primarySource;
  if (s && s !== "none") return s;
  if (ctx.tacticalIntel?.h2h) return "h2h";
  if (ctx.tacticalIntel?.recentVsOpponent) return "recentVsOpponent";
  if (ctx.tacticalIntel?.global) return "global";
  if (ctx.tacticalIntel?.lifetime) return "lifetime";
  return "thisMatch";
}

function pickCitationForContent(
  reason: string,
  choice: Move,
  ctx: MatchDbContext,
  catalog: IntelCatalogEntry[],
): { source: MoveIntelSource; signal: MoveIntelSignal } | null {
  const src = primarySource(ctx);
  const openWith = ctx.tacticalIntel?.primary?.openWith;
  if (
    openWith === choice &&
    (/\bopen(ing)?\b/i.test(reason) || reasonLooksLikeTacticsDump(reason)) &&
    isSignalValidForSource(catalog, src, "openWith")
  ) {
    return { source: src, signal: "openWith" };
  }
  if (
    reasonLooksLikeTacticsDump(reason) &&
    ctx.tactics?.trim() &&
    isSignalValidForSource(catalog, "thisMatch", "preparedTactics")
  ) {
    return { source: "thisMatch", signal: "preparedTactics" };
  }
  if (
    openWith &&
    openWith !== choice &&
    /\bopen(ing)?\s+with\b/i.test(reason) &&
    isSignalValidForSource(catalog, src, "dominant")
  ) {
    return { source: src, signal: "dominant" };
  }
  if (
    (/\blean\b/i.test(reason) || /\bbeats\b/i.test(reason)) &&
    isSignalValidForSource(catalog, "thisMatch", "opponentLeanThisMatch")
  ) {
    return { source: "thisMatch", signal: "opponentLeanThisMatch" };
  }
  if (
    /\blean\b/i.test(reason) &&
    isSignalValidForSource(catalog, src, "dominant")
  ) {
    return { source: src, signal: "dominant" };
  }
  return null;
}

const CROSS_MATCH_SOURCES: MoveIntelSource[] = [
  "lifetime",
  "h2h",
  "recentVsOpponent",
  "global",
];

/** When live throws exist, stop attributing picks to stale pre-match leans. */
function nudgeCitationToThisMatchHistory(
  parsed: MovePickParsed,
  ctx: MatchDbContext,
  catalog: IntelCatalogEntry[],
): MovePickParsed {
  const lastThrow = lastOpponentThrowFromMatch(ctx.currentMatch, ctx.botUid);
  const liveRepeat = thisMatchRepeatFromCtx(ctx);

  if (
    parsed.intelSignal === "thisMatchRounds" &&
    !lastThrow &&
    !liveRepeat
  ) {
    if (isSignalValidForSource(catalog, "thisMatch", "preparedTactics") && ctx.tactics?.trim()) {
      return {
        ...parsed,
        intelSignal: "preparedTactics",
        reason: buildShortMoveReason(parsed.choice, "thisMatch", "preparedTactics", ctx),
      };
    }
    const src = primarySource(ctx);
    if (isSignalValidForSource(catalog, src, "openWith")) {
      return {
        ...parsed,
        intelSource: src,
        intelSignal: "openWith",
        reason: buildShortMoveReason(parsed.choice, src, "openWith", ctx),
      };
    }
  }

  if (parsed.intelSource === "thisMatch" && parsed.intelSignal === "repeat") {
    if (!liveRepeat) {
      if (isSignalValidForSource(catalog, "thisMatch", "preparedTactics") && ctx.tactics?.trim()) {
        return {
          ...parsed,
          intelSignal: "preparedTactics",
          reason: buildShortMoveReason(parsed.choice, "thisMatch", "preparedTactics", ctx),
        };
      }
      const src = primarySource(ctx);
      if (isSignalValidForSource(catalog, src, "openWith")) {
        return {
          ...parsed,
          intelSource: src,
          intelSignal: "openWith",
          reason: buildShortMoveReason(parsed.choice, src, "openWith", ctx),
        };
      }
    }
    return {
      ...parsed,
      reason: buildShortMoveReason(parsed.choice, parsed.intelSource, "repeat", ctx),
    };
  }

  if (
    lastThrow &&
    parsed.intelSource === "thisMatch" &&
    parsed.intelSignal === "preparedTactics" &&
    isSignalValidForSource(catalog, "thisMatch", "thisMatchRounds")
  ) {
    return {
      ...parsed,
      intelSignal: "thisMatchRounds",
      reason: buildShortMoveReason(parsed.choice, "thisMatch", "thisMatchRounds", ctx),
    };
  }

  if (parsed.intelSource === "thisMatch") return parsed;

  if (!lastThrow) return parsed;
  if (!CROSS_MATCH_SOURCES.includes(parsed.intelSource)) return parsed;
  if (!["dominant", "openWith", "preparedTactics"].includes(parsed.intelSignal)) return parsed;

  if (isSignalValidForSource(catalog, "thisMatch", "thisMatchRounds")) {
    return {
      ...parsed,
      intelSource: "thisMatch",
      intelSignal: "thisMatchRounds",
      reason: buildShortMoveReason(parsed.choice, "thisMatch", "thisMatchRounds", ctx),
    };
  }
  return parsed;
}

function reciteScoreCitation(
  reason: string,
  choice: Move,
  ctx: MatchDbContext,
  catalog: IntelCatalogEntry[],
): { source: MoveIntelSource; signal: MoveIntelSignal } {
  const fromContent = pickCitationForContent(reason, choice, ctx, catalog);
  if (fromContent) return fromContent;
  if (isSignalValidForSource(catalog, "thisMatch", "thisMatchRounds")) {
    return { source: "thisMatch", signal: "thisMatchRounds" };
  }
  if (isSignalValidForSource(catalog, "thisMatch", "opponentLeanThisMatch")) {
    return { source: "thisMatch", signal: "opponentLeanThisMatch" };
  }
  const src = primarySource(ctx);
  return { source: src, signal: "dominant" };
}

export function buildShortMoveReason(
  choice: Move,
  source: MoveIntelSource,
  signal: MoveIntelSignal,
  ctx: MatchDbContext,
): string {
  const pick = moveDisplayName(choice) ?? choice;
  const opp = ctx.opponentName.split(" ")[0] ?? ctx.opponentName;
  const lean = ctx.tacticalIntel?.primary?.dominant;
  const leanName = lean ? (moveDisplayName(lean) ?? lean) : "their mix";

  switch (signal) {
    case "openWith":
      return `Opening ${pick} vs ${opp}'s ${leanName} lean (${source} openWith).`;
    case "dominant":
      return `${source} read ${leanName} — ${pick} counters ${opp}.`;
    case "preparedTactics":
      return `${pick} follows the pre-match plan vs ${opp}.`;
    case "thisMatchRounds": {
      const last = lastOpponentThrowFromMatch(ctx.currentMatch, ctx.botUid);
      const lastName = last ? (moveDisplayName(last) ?? last) : "throws so far";
      return `${pick} counters ${opp}'s ${lastName} this match (${source} thisMatchRounds).`;
    }
    case "opponentLeanThisMatch":
      return `${pick} vs ${opp}'s in-match lean (${source}).`;
    case "transitions":
      return `${pick} from ${source} transition read vs ${opp}.`;
    case "repeat": {
      const streakMove =
        thisMatchRepeatFromCtx(ctx)?.move ??
        lastOpponentThrowFromMatch(ctx.currentMatch, ctx.botUid) ??
        ctx.tacticalIntel?.opponentRepeat?.move;
      const streakName = streakMove ? (moveDisplayName(streakMove) ?? streakMove) : "their throw";
      return `${pick} counters ${opp}'s ${streakName} repeat (${source}).`;
    }
    case "repeatRate": {
      const last =
        lastOpponentThrowFromMatch(ctx.currentMatch, ctx.botUid) ??
        ctx.tacticalIntel?.opponentRepeat?.move;
      const lastName = last ? (moveDisplayName(last) ?? last) : "their last throw";
      return `${pick} counters ${opp}'s ${lastName} (${source} repeatRate).`;
    }
    default:
      return `${pick} from ${source}/${signal} vs ${opp}.`;
  }
}

/** Drop hallucinated beat rules and align prose with the submitted choice. */
export function sanitizeThoughtProcess(
  parsed: MovePickParsed,
  final: MovePickParsed,
  ctx: MatchDbContext,
): string | undefined {
  const raw = parsed.thoughtProcess?.trim();
  if (!raw) return undefined;
  if (
    reasonClaimsInvalidBeat(raw) ||
    !reasonPrimaryMoveMatchesChoice(raw, final.choice)
  ) {
    return buildShortMoveReason(final.choice, final.intelSource, final.intelSignal, ctx);
  }
  return raw;
}

/** Deterministic pick when the move LLM times out (uses live throws, then pre-match open). */
export function buildDeterministicMovePick(
  ctx: MatchDbContext,
  catalog: IntelCatalogEntry[],
  thisMatchRepeat?: { move: RpsMove; streak: number },
): MovePickParsed {
  const liveRepeat = thisMatchRepeatFromCtx(ctx) ?? thisMatchRepeat;
  const lastThrow = lastOpponentThrowFromMatch(ctx.currentMatch, ctx.botUid);
  const src = primarySource(ctx);
  const intel = ctx.tacticalIntel;

  let choice: Move = intel?.primary?.openWith ?? "ROCK";
  let source: MoveIntelSource = "thisMatch";
  let signal: MoveIntelSignal = "openWith";

  if (
    liveRepeat &&
    liveRepeat.streak >= 2 &&
    isSignalValidForSource(catalog, "thisMatch", "repeat")
  ) {
    choice = counterToOpponentThrow(liveRepeat.move);
    source = "thisMatch";
    signal = "repeat";
  } else if (
    lastThrow &&
    isSignalValidForSource(catalog, "thisMatch", "thisMatchRounds")
  ) {
    choice = counterToOpponentThrow(lastThrow);
    source = "thisMatch";
    signal = "thisMatchRounds";
  } else if (
    intel?.primary?.openWith &&
    isSignalValidForSource(catalog, src, "openWith")
  ) {
    choice = intel.primary.openWith;
    source = src;
    signal = "openWith";
  } else if (intel?.primary?.dominant && isSignalValidForSource(catalog, src, "dominant")) {
    choice = counterToOpponentThrow(intel.primary.dominant);
    source = src;
    signal = "dominant";
  }

  const draft: MovePickParsed = {
    choice,
    reason: buildShortMoveReason(choice, source, signal, ctx),
    intelSource: source,
    intelSignal: signal,
  };
  return normalizeMovePick(draft, ctx, catalog);
}

/** Fix tactics dump, wrong citation, or choice/reason mismatch from small models. */
export function normalizeMovePick(
  parsed: MovePickParsed,
  ctx: MatchDbContext,
  catalog: IntelCatalogEntry[],
): MovePickParsed {
  let working = ensureCounterMatchesOpponentThrow(parsed, ctx);
  let { choice, reason, intelSource, intelSignal } = working;
  let nextReason = reason.trim();
  let nextSource = intelSource;
  let nextSignal = intelSignal;

  if (nextSignal === "matchScore" || nextSignal === "clinchPressure") {
    const better = reciteScoreCitation(nextReason, choice, ctx, catalog);
    nextSource = better.source;
    nextSignal = better.signal;
    nextReason = buildShortMoveReason(choice, nextSource, nextSignal, ctx);
  }

  const needsReasonFix =
    reasonLooksLikeTacticsDump(nextReason) ||
    !reasonPrimaryMoveMatchesChoice(nextReason, choice) ||
    !reasonMatchesCitation(nextReason, nextSource, nextSignal);

  if (needsReasonFix) {
    const better = pickCitationForContent(nextReason, choice, ctx, catalog);
    if (better) {
      nextSource = better.source;
      nextSignal = better.signal;
    }
    nextReason = buildShortMoveReason(choice, nextSource, nextSignal, ctx);
  }

  const nudged = nudgeCitationToThisMatchHistory(
    {
      choice,
      reason: nextReason,
      thoughtProcess: parsed.thoughtProcess,
      intelSource: nextSource,
      intelSignal: nextSignal,
    },
    ctx,
    catalog,
  );

  const countered = ensureCounterMatchesOpponentThrow(nudged, ctx);
  const thoughtProcess = sanitizeThoughtProcess(parsed, countered, ctx);
  return thoughtProcess ? { ...countered, thoughtProcess } : countered;
}
