import type { MoveThrowPair } from "./movePrompt.js";
import { COUNTER_TO_OPPONENT, type ThrowDistribution } from "./opponentTendency.js";

export type RpsMove = "ROCK" | "PAPER" | "SCISSORS";

export interface MoveCounts {
  rock: number;
  paper: number;
  scissors: number;
  total: number;
}

export interface RankedMove {
  move: RpsMove;
  count: number;
  pct: number;
}

export interface TransitionProfile {
  after: RpsMove;
  next: ThrowDistribution & { sample: number };
}

export interface ResponseToBotMove {
  whenBotThrew: RpsMove;
  opponentNext: ThrowDistribution & { sample: number };
}

export interface ThrowPatternProfile {
  counts: MoveCounts;
  distribution: ThrowDistribution;
  ranked: RankedMove[];
  dominant: RpsMove;
  dominantPct: number;
  secondary: RpsMove | null;
  secondaryPct: number;
  /** How concentrated throws are on one move. */
  skew: "high" | "medium" | "low";
  suggestedCounter: RpsMove;
  lastThrows: RpsMove[];
  lastWindow: { size: number; distribution: ThrowDistribution; counts: MoveCounts };
  repeatRatePct: number;
  alternationRatePct: number;
  transitions: TransitionProfile[];
  responseToBot: ResponseToBotMove[];
}

function emptyCounts(): MoveCounts {
  return { rock: 0, paper: 0, scissors: 0, total: 0 };
}

function countsFromThrows(throws: RpsMove[]): MoveCounts {
  const c = emptyCounts();
  for (const t of throws) {
    if (t === "ROCK") c.rock++;
    else if (t === "PAPER") c.paper++;
    else c.scissors++;
  }
  c.total = c.rock + c.paper + c.scissors;
  return c;
}

function distributionFromCounts(c: MoveCounts): ThrowDistribution {
  if (c.total === 0) return { rockPct: 33, paperPct: 34, scissorsPct: 33 };
  return {
    rockPct: Math.round((c.rock / c.total) * 100),
    paperPct: Math.round((c.paper / c.total) * 100),
    scissorsPct: Math.round((c.scissors / c.total) * 100),
  };
}

function rankedFromCounts(c: MoveCounts): RankedMove[] {
  const rows: RankedMove[] = [
    { move: "ROCK", count: c.rock, pct: 0 },
    { move: "PAPER", count: c.paper, pct: 0 },
    { move: "SCISSORS", count: c.scissors, pct: 0 },
  ];
  for (const r of rows) {
    r.pct = c.total > 0 ? Math.round((r.count / c.total) * 100) : 0;
  }
  return rows.sort((a, b) => b.count - a.count);
}

function skewLabel(dominantPct: number): ThrowPatternProfile["skew"] {
  if (dominantPct >= 45) return "high";
  if (dominantPct >= 35) return "medium";
  return "low";
}

function distWithSample(c: MoveCounts): ThrowDistribution & { sample: number } {
  return { ...distributionFromCounts(c), sample: c.total };
}

function buildTransitions(throws: RpsMove[]): TransitionProfile[] {
  if (throws.length < 3) return [];
  const buckets: Record<RpsMove, RpsMove[]> = {
    ROCK: [],
    PAPER: [],
    SCISSORS: [],
  };
  for (let i = 1; i < throws.length; i++) {
    buckets[throws[i - 1]!]!.push(throws[i]!);
  }
  const out: TransitionProfile[] = [];
  for (const after of ["ROCK", "PAPER", "SCISSORS"] as const) {
    const next = buckets[after];
    if (next.length === 0) continue;
    out.push({ after, next: distWithSample(countsFromThrows(next)) });
  }
  return out;
}

function buildResponseToBot(pairs: MoveThrowPair[]): ResponseToBotMove[] {
  const buckets: Record<RpsMove, RpsMove[]> = {
    ROCK: [],
    PAPER: [],
    SCISSORS: [],
  };
  for (let i = 1; i < pairs.length; i++) {
    const prev = pairs[i - 1]!;
    const cur = pairs[i]!;
    if (!prev.bot || !cur.opponent) continue;
    const bot = prev.bot as RpsMove;
    const opp = cur.opponent as RpsMove;
    buckets[bot]!.push(opp);
  }
  const out: ResponseToBotMove[] = [];
  for (const whenBotThrew of ["ROCK", "PAPER", "SCISSORS"] as const) {
    const seq = buckets[whenBotThrew];
    if (seq.length === 0) continue;
    out.push({ whenBotThrew, opponentNext: distWithSample(countsFromThrows(seq)) });
  }
  return out;
}

function repeatAndAlternation(throws: RpsMove[]): {
  repeatRatePct: number;
  alternationRatePct: number;
} {
  if (throws.length < 2) return { repeatRatePct: 0, alternationRatePct: 0 };
  let repeats = 0;
  let alternations = 0;
  for (let i = 1; i < throws.length; i++) {
    if (throws[i] === throws[i - 1]) repeats++;
    else alternations++;
  }
  const steps = throws.length - 1;
  return {
    repeatRatePct: Math.round((repeats / steps) * 100),
    alternationRatePct: Math.round((alternations / steps) * 100),
  };
}

/** Distribution-only profile when only aggregate counts exist (no throw sequence). */
export function analyzeDistributionOnly(counts: MoveCounts): ThrowPatternProfile | null {
  if (counts.total <= 0) return null;
  const distribution = distributionFromCounts(counts);
  const ranked = rankedFromCounts(counts);
  const dominant = ranked[0]!.move;
  const dominantPct = ranked[0]!.pct;
  const secondary = ranked[1] && ranked[1].count > 0 ? ranked[1].move : null;
  return {
    counts,
    distribution,
    ranked,
    dominant,
    dominantPct,
    secondary,
    secondaryPct: ranked[1]?.pct ?? 0,
    skew: skewLabel(dominantPct),
    suggestedCounter: COUNTER_TO_OPPONENT[dominant],
    lastThrows: [],
    lastWindow: {
      size: 0,
      distribution,
      counts: emptyCounts(),
    },
    repeatRatePct: 0,
    alternationRatePct: 0,
    transitions: [],
    responseToBot: [],
  };
}

/** Rich throw-pattern stats from an opponent throw sequence (optional bot pairs for adaptation). */
export function analyzeThrowPattern(
  opponentThrows: RpsMove[],
  pairs?: MoveThrowPair[],
): ThrowPatternProfile | null {
  if (opponentThrows.length === 0) return null;

  const counts = countsFromThrows(opponentThrows);
  const distribution = distributionFromCounts(counts);
  const ranked = rankedFromCounts(counts);
  const dominant = ranked[0]!.move;
  const dominantPct = ranked[0]!.pct;
  const secondary = ranked[1] && ranked[1].count > 0 ? ranked[1].move : null;
  const secondaryPct = ranked[1]?.pct ?? 0;

  const windowSize = Math.min(5, opponentThrows.length);
  const lastThrows = opponentThrows.slice(-windowSize);
  const lastCounts = countsFromThrows(lastThrows);

  const { repeatRatePct, alternationRatePct } = repeatAndAlternation(opponentThrows);

  return {
    counts,
    distribution,
    ranked,
    dominant,
    dominantPct,
    secondary,
    secondaryPct,
    skew: skewLabel(dominantPct),
    suggestedCounter: COUNTER_TO_OPPONENT[dominant],
    lastThrows,
    lastWindow: {
      size: windowSize,
      distribution: distributionFromCounts(lastCounts),
      counts: lastCounts,
    },
    repeatRatePct,
    alternationRatePct,
    transitions: buildTransitions(opponentThrows),
    responseToBot: pairs && pairs.length >= 2 ? buildResponseToBot(pairs) : [],
  };
}

export function analyzeThrowPatternFromPairs(
  pairs: MoveThrowPair[],
): ThrowPatternProfile | null {
  const opponentThrows = pairs
    .map((p) => p.opponent)
    .filter((o): o is RpsMove => o === "ROCK" || o === "PAPER" || o === "SCISSORS");
  return analyzeThrowPattern(opponentThrows, pairs);
}

/** Compact pattern summary for logs. */
export function formatPatternCompact(tag: string, p?: ThrowPatternProfile): string {
  if (!p) return `${tag}=—`;
  const last = p.lastThrows.map((m) => m[0]).join("");
  const trans =
    p.transitions.length > 0
      ? p.transitions
          .map((t) => {
            const top = [...["ROCK", "PAPER", "SCISSORS"] as const]
              .map((m) => ({
                m,
                pct:
                  m === "ROCK"
                    ? t.next.rockPct
                    : m === "PAPER"
                      ? t.next.paperPct
                      : t.next.scissorsPct,
              }))
              .sort((a, b) => b.pct - a.pct)[0];
            return `${t.after[0]}→${top?.m[0]}${top?.pct ?? 0}`;
          })
          .join(",")
      : "";
  return (
    `${tag}=${p.dominant[0]}${p.dominantPct}%` +
    `(2nd=${p.secondary?.[0] ?? "-"}${p.secondaryPct}%,skew=${p.skew})` +
    ` R${p.distribution.rockPct}/P${p.distribution.paperPct}/S${p.distribution.scissorsPct}` +
    ` last${p.lastWindow.size}=${last}` +
    ` rep${p.repeatRatePct}%` +
    (trans ? ` trans=${trans}` : "")
  );
}
