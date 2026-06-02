import type { IntelCitationPickStats } from "../db/tacticalIntelCitationDb.js";
import type { LeanAccuracyRow, PrimarySourceLeaderboardRow } from "./tacticalIntelTracking.js";
import { buildStaticIntelCatalog, type IntelCatalogEntry } from "./moveIntelCatalog.js";
import type { MoveIntelSignal, MoveIntelSource } from "./parse.js";
import { formatPctHits, formatTableLines } from "../log/startupRankLog.js";
import { rankHistoricalIntelSourcesByEfficiency } from "./tacticalIntelRanking.js";

export interface IntelCitationEfficiencyRow {
  source: MoveIntelSource;
  signal: MoveIntelSignal;
  rank: number;
  picks: number;
  roundWins: number;
  roundWinPct: number;
  efficiencyScore: number;
  hasPickData: boolean;
}

function pickConfidence(picks: number): number {
  if (picks <= 0) return 0;
  return 1 - Math.exp(-picks / 15);
}

function sourceLeanPct(source: MoveIntelSource, leanRows: LeanAccuracyRow[]): number {
  return leanRows.find((r) => r.source === source)?.leanPct ?? 0;
}

function scoreCitation(
  picks: number,
  roundWinPct: number,
  sourceLean: number,
): number {
  if (picks > 0) {
    const conf = pickConfidence(picks);
    return Math.round((roundWinPct * 0.72 * conf + Math.min(8, Math.sqrt(picks) * 1.8)) * 10) / 10;
  }
  if (sourceLean > 0) {
    return Math.round(sourceLean * 0.22 * 10) / 10;
  }
  return 0;
}

function statsFor(
  pickStats: IntelCitationPickStats[],
  source: MoveIntelSource,
  signal: MoveIntelSignal,
): IntelCitationPickStats | undefined {
  return pickStats.find((s) => s.source === source && s.signal === signal);
}

/** Every valid source/signal pair, ranked by pick round-win rate (then source lean proxy). */
export function rankAllIntelCitationsByEfficiency(
  catalog: IntelCatalogEntry[],
  pickStats: IntelCitationPickStats[],
  historicalLean: LeanAccuracyRow[],
  historicalPrimary: PrimarySourceLeaderboardRow[],
): IntelCitationEfficiencyRow[] {
  const sourceRank = rankHistoricalIntelSourcesByEfficiency(
    historicalLean,
    historicalPrimary,
  );
  const sourceOrder = new Map<MoveIntelSource, number>();
  for (const r of sourceRank) {
    sourceOrder.set(r.source, r.rank);
  }

  const pairs: IntelCitationEfficiencyRow[] = [];
  for (const entry of catalog) {
    for (const signal of entry.signals) {
      const stat = statsFor(pickStats, entry.source, signal);
      const picks = stat?.picks ?? 0;
      const roundWins = stat?.roundWins ?? 0;
      const roundWinPct = stat?.roundWinPct ?? 0;
      pairs.push({
        source: entry.source,
        signal,
        rank: 0,
        picks,
        roundWins,
        roundWinPct,
        efficiencyScore: scoreCitation(
          picks,
          roundWinPct,
          sourceLeanPct(entry.source, historicalLean),
        ),
        hasPickData: picks > 0,
      });
    }
  }

  pairs.sort((a, b) => {
    if (b.efficiencyScore !== a.efficiencyScore) {
      return b.efficiencyScore - a.efficiencyScore;
    }
    const sa = sourceOrder.get(a.source) ?? 99;
    const sb = sourceOrder.get(b.source) ?? 99;
    if (sa !== sb) return sa - sb;
    return a.signal.localeCompare(b.signal);
  });

  return pairs.map((r, i) => ({ ...r, rank: i + 1 }));
}

const CATALOG_SOURCE_ORDER: MoveIntelSource[] = [
  "h2h",
  "lifetime",
  "recentVsOpponent",
  "thisMatch",
];

function citationLabel(source: MoveIntelSource, signal: MoveIntelSignal): string {
  return `${source} · ${signal}`;
}

export function formatIntelCitationCatalogLines(
  rows: IntelCitationEfficiencyRow[],
): string[] {
  if (rows.length === 0) return ["No catalog entries."];

  const lines: string[] = [
    `Intel catalog: ${rows.length} source/signal pairs ranked by pick round-win rate`,
  ];

  const withPicks = rows.filter((r) => r.hasPickData);
  if (withPicks.length > 0) {
    const pickRows = withPicks.map((r) => [
      `#${r.rank}`,
      citationLabel(r.source, r.signal),
      String(r.efficiencyScore),
      String(r.picks),
      formatPctHits(r.roundWins, r.picks, "picks"),
    ]);
    lines.push(
      "",
      `Citations with pick history (${withPicks.length}):`,
      ...formatTableLines(
        ["Rank", "Citation", "Score", "Picks", "Round wins"],
        pickRows,
      ),
    );
  } else {
    lines.push("", "No citation pick history yet — scores are source-lean proxies only.");
  }

  const withoutPicks = rows.filter((r) => !r.hasPickData);
  if (withoutPicks.length > 0) {
    lines.push("", `By source (${withoutPicks.length} without picks, top signals per source):`);
    const bySource = new Map<MoveIntelSource, IntelCitationEfficiencyRow[]>();
    for (const r of withoutPicks) {
      const list = bySource.get(r.source) ?? [];
      list.push(r);
      bySource.set(r.source, list);
    }
    for (const source of CATALOG_SOURCE_ORDER) {
      const group = bySource.get(source);
      if (!group?.length) continue;
      const top = group.slice(0, 4);
      const summary = top
        .map((r) => `${r.signal} (${r.efficiencyScore})`)
        .join(", ");
      const extra = group.length > top.length ? ` · +${group.length - top.length} more` : "";
      lines.push(`  ${source}: ${summary}${extra}`);
    }
  }

  return lines;
}

export function formatIntelCitationCatalogLog(rows: IntelCitationEfficiencyRow[]): string {
  return formatIntelCitationCatalogLines(rows).join("\n");
}

export function buildDefaultIntelCitationCatalog(): IntelCatalogEntry[] {
  return buildStaticIntelCatalog();
}
