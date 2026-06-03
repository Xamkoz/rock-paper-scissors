import type { MatchDbContext } from "./matchContext.js";
import type { TendencySlice } from "./tacticalIntel.js";
import type { ThrowPatternProfile } from "./throwPatternIntel.js";
import type { MoveIntelSignal, MoveIntelSource } from "./parse.js";
import type { MoveThrowPair } from "./movePrompt.js";

export interface IntelCatalogEntry {
  source: MoveIntelSource;
  signals: MoveIntelSignal[];
}

export interface BuildIntelCatalogOpts {
  opponentLeanThisMatch?: string;
  thisMatchPatterns?: ThrowPatternProfile | null;
  thisMatchRepeat?: { move: string; streak: number };
}

/** Short labels for each citable data parameter (shown in move-pick prompts). */
export const INTEL_SIGNAL_GLOSSARY: Record<MoveIntelSignal, string> = {
  dominant: "opponent's most common throw from this source",
  distribution: "rock/paper/scissors mix percentages",
  openWith: "bot opening that beats the source's dominant lean",
  skew: "how concentrated throws are on one move (high/medium/low)",
  secondary: "second-most common opponent throw",
  repeatRate: "how often opponent repeats the same throw back-to-back",
  alternationRate: "how often opponent switches throws vs repeating",
  lastWindow: "distribution over the last N throws in this source",
  transitions: "what opponent throws after ROCK/PAPER/SCISSORS",
  secondOrderTransition: "what opponent throws after a two-throw sequence",
  responseToBot: "opponent's next throw distribution after each bot throw",
  afterBotWin: "opponent throw mix on rounds after bot won the prior round",
  afterBotLoss: "opponent throw mix on rounds after bot lost the prior round",
  streakBreakBias: "when on a repeat streak, how often they continue vs switch",
  repeat: "current opponent repeat streak (move + length)",
  recentSeq: "recent opponent throw sequence (cross-game)",
  h2hRecord: "series win record vs this opponent",
  opponentLeanThisMatch: "dominant opponent throw in the current match only",
  thisMatchRounds: "bot/opponent throws so far this match",
  matchScore: "deprecated — series score is prompt context (seriesScore), not citable",
  clinchPressure: "deprecated — clinch is prompt context (seriesScore), not citable",
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
  if (p.alternationRatePct > 0) out.push("alternationRate");
  if (p.lastWindow.size > 0) out.push("lastWindow");
  if (p.transitions.length > 0) out.push("transitions");
  if ((p.secondOrderTransitions?.length ?? 0) > 0) out.push("secondOrderTransition");
  if (p.responseToBot.length > 0) out.push("responseToBot");
  if (p.outcomeThrows && p.outcomeThrows.afterBotWin.sample >= 2) {
    out.push("afterBotWin");
  }
  if (p.outcomeThrows && p.outcomeThrows.afterBotLoss.sample >= 2) {
    out.push("afterBotLoss");
  }
  if (p.streakBreakBias && p.streakBreakBias.sample >= 2) {
    out.push("streakBreakBias");
  }
  return out;
}

function signalsFromSlice(slice: TendencySlice): MoveIntelSignal[] {
  const base: MoveIntelSignal[] = ["dominant", "distribution", "openWith"];
  const fromPatterns = signalsFromPattern(slice.patterns);
  const merged = new Set<MoveIntelSignal>([...base, ...fromPatterns]);
  return [...merged];
}

function mergeSignals(entry: IntelCatalogEntry | undefined, signals: MoveIntelSignal[]): void {
  if (!entry) return;
  entry.signals.push(...signals);
}

/** Which intel sources + data parameters are available for this pick. */
export function buildMoveIntelCatalog(
  ctx: MatchDbContext,
  opts?: BuildIntelCatalogOpts,
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

  if (intel?.global) {
    catalog.push({ source: "global", signals: signalsFromSlice(intel.global) });
  }

  const thisMatchSignals: MoveIntelSignal[] = ["thisMatchRounds"];
  if (opts?.opponentLeanThisMatch) thisMatchSignals.push("opponentLeanThisMatch");
  if (opts?.thisMatchPatterns) {
    thisMatchSignals.push(...signalsFromPattern(opts.thisMatchPatterns));
  }
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

  if (intel?.opponentRepeat) {
    for (const source of ["h2h", "recentVsOpponent", "global", "thisMatch"] as const) {
      mergeSignals(
        catalog.find((e) => e.source === source),
        ["repeat"],
      );
    }
  }

  if (opts?.thisMatchRepeat) {
    mergeSignals(catalog.find((e) => e.source === "thisMatch"), ["repeat"]);
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

const PATTERN_SIGNALS: MoveIntelSignal[] = [
  "dominant",
  "distribution",
  "openWith",
  "skew",
  "secondary",
  "repeatRate",
  "alternationRate",
  "lastWindow",
  "transitions",
  "secondOrderTransition",
  "responseToBot",
  "afterBotWin",
  "afterBotLoss",
  "streakBreakBias",
];

/** Every citable signal (startup leaderboard + exploration pool). */
export function allIntelSignals(): MoveIntelSignal[] {
  const signals = new Set<MoveIntelSignal>();
  for (const entry of buildStaticIntelCatalog()) {
    for (const s of entry.signals) signals.add(s);
  }
  return [...signals].sort();
}

/** Full grid of citable source/signal pairs (startup leaderboard). */
export function buildStaticIntelCatalog(): IntelCatalogEntry[] {
  return [
    {
      source: "lifetime",
      signals: [...PATTERN_SIGNALS, "opponentLifetime", "sourcesByEfficiency"],
    },
    {
      source: "h2h",
      signals: [
        ...PATTERN_SIGNALS,
        "h2hRecord",
        "priorMatches",
        "repeat",
        "sourcesByEfficiency",
      ],
    },
    {
      source: "recentVsOpponent",
      signals: [
        ...PATTERN_SIGNALS,
        "recentSeq",
        "crossOpponent",
        "repeat",
        "sourcesByEfficiency",
      ],
    },
    {
      source: "global",
      signals: [...PATTERN_SIGNALS, "sourcesByEfficiency"],
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
        "alternationRate",
        "transitions",
        "secondOrderTransition",
        "responseToBot",
        "afterBotWin",
        "afterBotLoss",
        "streakBreakBias",
        "repeatRate",
        "lastWindow",
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
  repeat: ["repeatRate", "streakBreakBias", "recentSeq", "dominant"],
  repeatRate: ["repeat", "alternationRate", "dominant"],
  alternationRate: ["repeatRate", "repeat", "dominant"],
  recentSeq: ["repeat", "dominant"],
  secondOrderTransition: ["transitions", "dominant"],
  afterBotWin: ["responseToBot", "dominant"],
  afterBotLoss: ["responseToBot", "dominant"],
  streakBreakBias: ["repeat", "repeatRate"],
  matchScore: ["thisMatchRounds", "opponentLeanThisMatch", "dominant"],
  clinchPressure: ["thisMatchRounds", "opponentLeanThisMatch", "dominant"],
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
