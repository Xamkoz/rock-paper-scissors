import type { IntelCitationPickStats } from "../db/tacticalIntelCitationDb.js";
import type { SignalLeanStats } from "../db/roundSignalScoresDb.js";
import { allIntelSignals } from "./moveIntelCatalog.js";
import type { MoveIntelSignal } from "./parse.js";
import { formatPctHits, formatTableLines } from "../log/startupRankLog.js";

export interface IntelSignalEfficiencyRow {
  signal: MoveIntelSignal;
  rank: number;
  picks: number;
  roundWins: number;
  roundWinPct: number;
  efficiencyScore: number;
}

function pickConfidence(picks: number): number {
  if (picks <= 0) return 0;
  return 1 - Math.exp(-picks / 15);
}

function scoreCitation(picks: number, roundWinPct: number): number {
  if (picks <= 0) return 0;
  const conf = pickConfidence(picks);
  return Math.round((roundWinPct * 0.72 * conf + Math.min(8, Math.sqrt(picks) * 1.8)) * 10) / 10;
}

export function aggregatePicksBySignal(
  pickStats: IntelCitationPickStats[],
): Map<MoveIntelSignal, { picks: number; roundWins: number }> {
  const bySignal = new Map<MoveIntelSignal, { picks: number; roundWins: number }>();
  for (const row of pickStats) {
    const cur = bySignal.get(row.signal) ?? { picks: 0, roundWins: 0 };
    cur.picks += row.picks;
    cur.roundWins += row.roundWins;
    bySignal.set(row.signal, cur);
  }
  return bySignal;
}

/** Pool citation picks with counterfactual lean opportunities per signal. */
export function aggregateSignalSamples(
  pickStats: IntelCitationPickStats[] | undefined,
  leanStats: SignalLeanStats[] | undefined,
): Map<MoveIntelSignal, { picks: number; roundWins: number; leanOpps: number; leanHits: number }> {
  const bySignal = new Map<
    MoveIntelSignal,
    { picks: number; roundWins: number; leanOpps: number; leanHits: number }
  >();
  for (const row of pickStats ?? []) {
    const cur = bySignal.get(row.signal) ?? {
      picks: 0,
      roundWins: 0,
      leanOpps: 0,
      leanHits: 0,
    };
    cur.picks += row.picks;
    cur.roundWins += row.roundWins;
    bySignal.set(row.signal, cur);
  }
  for (const row of leanStats ?? []) {
    const cur = bySignal.get(row.signal) ?? {
      picks: 0,
      roundWins: 0,
      leanOpps: 0,
      leanHits: 0,
    };
    cur.leanOpps += row.opportunities;
    cur.leanHits += row.leanHits;
    bySignal.set(row.signal, cur);
  }
  return bySignal;
}

function signalSampleCount(
  stats: { picks?: number; leanOpps?: number } | undefined,
): number {
  if (!stats) return 0;
  return Math.max(stats.picks ?? 0, stats.leanOpps ?? 0);
}

/** Min pick citations per signal before defaulting to top-ranked signals in citeHints. */
export function intelSignalMinPicksExploration(
  signalCount: number,
  bySignal: Map<MoveIntelSignal, { picks?: number; leanOpps?: number }>,
): number {
  const raw = process.env.INTEL_SIGNAL_MIN_PICKS?.trim();
  if (raw !== undefined && raw !== "") {
    const n = Number(raw);
    if (Number.isFinite(n) && n >= 0) return Math.floor(n);
  }
  if (signalCount <= 1) return 0;
  const total = [...bySignal.values()].reduce(
    (sum, s) => sum + signalSampleCount(s),
    0,
  );
  return Math.max(5, Math.ceil(total / signalCount));
}

/** Signals in this pick's catalog (excluding meta-only). */
export function explorationSignalPool(catalog: { signals: MoveIntelSignal[] }[]): MoveIntelSignal[] {
  const signals = new Set<MoveIntelSignal>();
  for (const entry of catalog) {
    for (const s of entry.signals) {
      if (s !== "sourcesByEfficiency") signals.add(s);
    }
  }
  return [...signals];
}

/**
 * Prefer under-sampled signals for citeHints; rotate by round once all meet fair share.
 */
export function pickExplorationSignals(
  round: number,
  count: number,
  catalog: { signals: MoveIntelSignal[] }[],
  pickStats: IntelCitationPickStats[] | undefined,
  fallbackSignals: MoveIntelSignal[],
  leanStats?: SignalLeanStats[],
): MoveIntelSignal[] {
  if ((!pickStats || pickStats.length === 0) && (!leanStats || leanStats.length === 0)) {
    return pickRotatingFromList(round, count, fallbackSignals);
  }

  const bySignal = aggregateSignalSamples(pickStats, leanStats);
  const pool = explorationSignalPool(catalog);
  if (pool.length === 0) return pickRotatingFromList(round, count, fallbackSignals);

  const minPicks = intelSignalMinPicksExploration(pool.length, bySignal);
  const underSampled = pool.filter((s) => signalSampleCount(bySignal.get(s)) < minPicks);
  if (underSampled.length > 0) {
    underSampled.sort(
      (a, b) => signalSampleCount(bySignal.get(a)) - signalSampleCount(bySignal.get(b)),
    );
    return pickRotatingFromList(round, count, underSampled);
  }

  const ranked = rankIntelSignalsByPickEfficiency(pickStats ?? []);
  const withData = ranked.filter((r) => r.picks > 0).map((r) => r.signal);
  if (withData.length > 0) {
    return pickRotatingFromList(round, count, withData);
  }
  return pickRotatingFromList(round, count, fallbackSignals);
}

function pickRotatingFromList(
  round: number,
  count: number,
  signals: MoveIntelSignal[],
): MoveIntelSignal[] {
  if (signals.length === 0) return [];
  const start = (Math.max(1, round) - 1) % signals.length;
  const out: MoveIntelSignal[] = [];
  for (let i = 0; i < count; i++) {
    out.push(signals[(start + i) % signals.length]!);
  }
  return out;
}

/** Aggregate pick stats by signal name (all sources combined). */
export function rankIntelSignalsByPickEfficiency(
  pickStats: IntelCitationPickStats[],
): IntelSignalEfficiencyRow[] {
  const bySignal = aggregatePicksBySignal(pickStats);
  const totalPicks = pickStats.reduce((sum, r) => sum + r.picks, 0);
  if (totalPicks === 0) return [];

  const rows: IntelSignalEfficiencyRow[] = allIntelSignals().map((signal) => {
    const stats = bySignal.get(signal) ?? { picks: 0, roundWins: 0 };
    const roundWinPct =
      stats.picks > 0 ? Math.round((stats.roundWins / stats.picks) * 1000) / 10 : 0;
    return {
      signal,
      rank: 0,
      picks: stats.picks,
      roundWins: stats.roundWins,
      roundWinPct,
      efficiencyScore: scoreCitation(stats.picks, roundWinPct),
    };
  });

  rows.sort((a, b) => {
    const aData = a.picks > 0;
    const bData = b.picks > 0;
    if (aData !== bData) return aData ? -1 : 1;
    if (b.efficiencyScore !== a.efficiencyScore) return b.efficiencyScore - a.efficiencyScore;
    if (b.roundWinPct !== a.roundWinPct) return b.roundWinPct - a.roundWinPct;
    if (b.picks !== a.picks) return b.picks - a.picks;
    return a.signal.localeCompare(b.signal);
  });

  return rows.map((r, i) => ({ ...r, rank: i + 1 }));
}

/** Ranked table: one row per signal, stats pooled across all sources. */
export function formatIntelSignalsRankedLines(
  rows: IntelSignalEfficiencyRow[],
  maxRows = 50,
): string[] {
  if (rows.length === 0) {
    return [
      "No cited intel signals yet — ranks appear after the bot cites source+signal in move picks.",
    ];
  }

  const cited = rows.filter((r) => r.picks > 0).length;
  const shown = rows.slice(0, maxRows);
  const tableRows = shown.map((r) => [
    `#${r.rank}`,
    r.signal,
    String(r.efficiencyScore),
    r.picks > 0 ? String(r.picks) : "—",
    r.picks > 0 ? formatPctHits(r.roundWins, r.picks, "picks") : "—",
  ]);

  const lines: string[] = [
    `Intel signals ranked by pick round-win rate (${cited}/${rows.length} cited; citeHints explore under-sampled signals; counterfactual lean measured each round)`,
    ...formatTableLines(
      ["Rank", "Signal", "Score", "Picks", "Round wins"],
      tableRows,
    ),
  ];
  if (rows.length > maxRows) {
    lines.push(`… ${rows.length - maxRows} lower-ranked signals omitted`);
  }
  return lines;
}

export function formatIntelSignalsRankedLog(rows: IntelSignalEfficiencyRow[]): string {
  return formatIntelSignalsRankedLines(rows).join("\n");
}
