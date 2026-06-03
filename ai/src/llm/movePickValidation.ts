import type { MatchDbContext } from "./matchContext.js";
import type { IntelCatalogEntry } from "./moveIntelCatalog.js";
import { isSignalValidForSource } from "./moveIntelCatalog.js";
import { moveDisplayName } from "./movePrompt.js";
import type { MoveIntelSignal, MoveIntelSource, MovePickParsed } from "./parse.js";
import type { Move } from "../types.js";

const TACTICS_DUMP =
  /\bleans?\s+(rock|paper|scissors)\b.*\bopen with\b|\bif they throw\b/i;

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
  if (signal === "h2hRecord") return /\bh2h\b|\bseries\b|\brecord\b/i.test(r);
  if (signal === "thisMatchRounds") {
    return /\bthis match\b|\bthrow(s)? so far\b|\blast:\b/i.test(r);
  }
  return !reasonLooksLikeTacticsDump(reason);
}

function primarySource(ctx: MatchDbContext): MoveIntelSource {
  const s = ctx.tacticalIntel?.primarySource;
  if (s && s !== "none") return s;
  if (ctx.tacticalIntel?.h2h) return "h2h";
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
    case "thisMatchRounds":
      return `${pick} from throws so far this match vs ${opp}.`;
    case "opponentLeanThisMatch":
      return `${pick} vs ${opp}'s in-match lean (${source}).`;
    case "transitions":
      return `${pick} from ${source} transition read vs ${opp}.`;
    case "repeat":
      return `${pick} vs ${opp}'s repeat pattern (${source}).`;
    default:
      return `${pick} from ${source}/${signal} vs ${opp}.`;
  }
}

/** Fix tactics dump, wrong citation, or choice/reason mismatch from small models. */
export function normalizeMovePick(
  parsed: MovePickParsed,
  ctx: MatchDbContext,
  catalog: IntelCatalogEntry[],
): MovePickParsed {
  let { choice, reason, intelSource, intelSignal } = parsed;
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

  return {
    choice,
    reason: nextReason,
    intelSource: nextSource,
    intelSignal: nextSignal,
  };
}
