import type { MatchDbContext } from "./matchContext.js";
import type { TendencySlice } from "./tacticalIntel.js";
import type { ThrowPatternProfile } from "./throwPatternIntel.js";
import type { MoveIntelSignal, MoveIntelSource } from "./parse.js";

export interface IntelCatalogEntry {
  source: MoveIntelSource;
  signals: MoveIntelSignal[];
}

/** Short labels for each citable data parameter (shown in move-pick prompts). */
export const INTEL_SIGNAL_GLOSSARY: Record<MoveIntelSignal, string> = {
  dominant: "opponent's most common throw from this source",
  distribution: "rock/paper/scissors mix percentages",
  openWith: "bot opening that beats the source's dominant lean",
  skew: "how concentrated throws are on one move (high/medium/low)",
  secondary: "second-most common opponent throw",
  repeatRate: "how often opponent repeats the same throw back-to-back",
  lastWindow: "distribution over the last N throws in this source",
  transitions: "what opponent throws after ROCK/PAPER/SCISSORS",
  responseToBot: "opponent's next throw distribution after each bot throw",
  repeat: "current opponent repeat streak (move + length)",
  recentSeq: "recent opponent throw sequence (cross-game)",
  h2hRecord: "series win record vs this opponent",
  opponentLeanThisMatch: "dominant opponent throw in the current match only",
  thisMatchRounds: "bot/opponent throws so far this match",
  preparedTactics: "pre-match written tactical plan",
  opponentLifetime: "career throw totals from profile",
  priorMatches: "summaries of earlier bot vs opponent games",
  crossOpponent: "cross-match opponent pattern (all cached games)",
  sourcesByEfficiency: "historical ranking of which intel source works best",
};

function signalsFromPattern(p: ThrowPatternProfile): MoveIntelSignal[] {
  const out: MoveIntelSignal[] = ["dominant", "distribution", "skew"];
  if (p.secondary) out.push("secondary");
  if (p.repeatRatePct > 0) out.push("repeatRate");
  if (p.lastWindow.size > 0) out.push("lastWindow");
  if (p.transitions.length > 0) out.push("transitions");
  if (p.responseToBot.length > 0) out.push("responseToBot");
  return out;
}

function signalsFromSlice(slice: TendencySlice): MoveIntelSignal[] {
  const base: MoveIntelSignal[] = ["dominant", "distribution", "openWith"];
  const fromPatterns = signalsFromPattern(slice.patterns);
  const merged = new Set<MoveIntelSignal>([...base, ...fromPatterns]);
  return [...merged];
}

/** Which intel sources + data parameters are available for this pick. */
export function buildMoveIntelCatalog(
  ctx: MatchDbContext,
  opts?: { opponentLeanThisMatch?: string },
): { catalog: IntelCatalogEntry[]; glossary: Partial<Record<MoveIntelSignal, string>> } {
  const catalog: IntelCatalogEntry[] = [];
  const intel = ctx.tacticalIntel;

  if (intel?.lifetime) {
    catalog.push({ source: "lifetime", signals: signalsFromSlice(intel.lifetime) });
  } else if (ctx.opponentProfile) {
    catalog.push({
      source: "lifetime",
      signals: ["opponentLifetime", "distribution", "dominant"],
    });
  }

  if (intel?.h2h) {
    const signals = signalsFromSlice(intel.h2h);
    if (intel.h2hRecord.games > 0) signals.push("h2hRecord");
    catalog.push({ source: "h2h", signals: [...new Set(signals)] });
  }

  if (intel?.recentVsOpponent) {
    const signals = signalsFromSlice(intel.recentVsOpponent);
    if (intel.recentOpponentThrows.length > 0) signals.push("recentSeq");
    catalog.push({ source: "recentVsOpponent", signals: [...new Set(signals)] });
  }

  const thisMatchSignals: MoveIntelSignal[] = ["thisMatchRounds"];
  if (opts?.opponentLeanThisMatch) thisMatchSignals.push("opponentLeanThisMatch");
  catalog.push({ source: "thisMatch", signals: thisMatchSignals });

  if (ctx.tactics?.trim()) {
    const existing = catalog.find((e) => e.source === "thisMatch");
    if (existing) {
      existing.signals.push("preparedTactics");
    } else {
      catalog.push({ source: "thisMatch", signals: ["preparedTactics"] });
    }
  }

  if (ctx.headToHead.length > 0) {
    const h2h = catalog.find((e) => e.source === "h2h");
    if (h2h) h2h.signals.push("priorMatches");
    else catalog.push({ source: "h2h", signals: ["priorMatches", "h2hRecord"] });
  }

  if (intel?.crossPatterns.opponent) {
    const recent = catalog.find((e) => e.source === "recentVsOpponent");
    if (recent) recent.signals.push("crossOpponent");
    else
      catalog.push({
        source: "recentVsOpponent",
        signals: ["crossOpponent", "distribution", "dominant", "transitions"],
      });
  }

  if (intel && intel.sourcesByEfficiency.length > 0) {
    for (const entry of catalog) {
      entry.signals = [
        ...new Set<MoveIntelSignal>([...entry.signals, "sourcesByEfficiency"]),
      ];
    }
  }

  if (intel?.opponentRepeat) {
    for (const source of ["h2h", "recentVsOpponent", "thisMatch"] as const) {
      const entry = catalog.find((e) => e.source === source);
      if (entry) entry.signals.push("repeat");
    }
  }

  for (const entry of catalog) {
    entry.signals = [...new Set(entry.signals)];
  }

  return { catalog, glossary: glossaryForCatalog(catalog) };
}

/** Glossary entries only for signals cited in this pick's catalog (smaller prompts). */
export function glossaryForCatalog(
  catalog: IntelCatalogEntry[],
): Partial<Record<MoveIntelSignal, string>> {
  const signals = new Set(catalog.flatMap((e) => e.signals));
  const out: Partial<Record<MoveIntelSignal, string>> = {};
  for (const s of signals) {
    out[s] = INTEL_SIGNAL_GLOSSARY[s];
  }
  return out;
}

/** Full grid of citable source/signal pairs (startup leaderboard). */
export function buildStaticIntelCatalog(): IntelCatalogEntry[] {
  const patternSignals: MoveIntelSignal[] = [
    "dominant",
    "distribution",
    "openWith",
    "skew",
    "secondary",
    "repeatRate",
    "lastWindow",
    "transitions",
    "responseToBot",
  ];
  return [
    {
      source: "lifetime",
      signals: [...patternSignals, "opponentLifetime", "sourcesByEfficiency"],
    },
    {
      source: "h2h",
      signals: [
        ...patternSignals,
        "h2hRecord",
        "priorMatches",
        "repeat",
        "sourcesByEfficiency",
      ],
    },
    {
      source: "recentVsOpponent",
      signals: [
        ...patternSignals,
        "recentSeq",
        "crossOpponent",
        "repeat",
        "sourcesByEfficiency",
      ],
    },
    {
      source: "thisMatch",
      signals: [
        "thisMatchRounds",
        "opponentLeanThisMatch",
        "preparedTactics",
        "repeat",
        "distribution",
        "dominant",
      ],
    },
  ];
}

export function isSignalValidForSource(
  catalog: IntelCatalogEntry[],
  source: MoveIntelSource,
  signal: MoveIntelSignal,
): boolean {
  const entry = catalog.find((e) => e.source === source);
  return entry?.signals.includes(signal) ?? false;
}

const SIGNAL_FALLBACKS: Partial<Record<MoveIntelSignal, MoveIntelSignal[]>> = {
  repeat: ["repeatRate", "recentSeq", "dominant"],
  repeatRate: ["repeat", "dominant"],
  recentSeq: ["repeat", "dominant"],
  h2hRecord: ["dominant", "openWith"],
  crossOpponent: ["distribution", "dominant"],
  priorMatches: ["h2hRecord", "dominant"],
  opponentLifetime: ["distribution", "dominant"],
};

/** Map invalid model citations to the nearest allowed signal on the same source. */
export function coerceCitationForCatalog(
  catalog: IntelCatalogEntry[],
  source: MoveIntelSource,
  signal: MoveIntelSignal,
): { source: MoveIntelSource; signal: MoveIntelSignal } | null {
  if (isSignalValidForSource(catalog, source, signal)) {
    return { source, signal };
  }
  const entry = catalog.find((e) => e.source === source);
  if (!entry) return null;
  const candidates: MoveIntelSignal[] = [
    ...(SIGNAL_FALLBACKS[signal] ?? []),
    "openWith",
    "dominant",
    "distribution",
    ...entry.signals,
  ];
  for (const alt of candidates) {
    if (entry.signals.includes(alt)) return { source, signal: alt };
  }
  return null;
}
