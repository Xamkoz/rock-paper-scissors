import type { LeanAccuracyRow, PrimarySourceLeaderboardRow } from "./tacticalIntelTracking.js";
import type { TacticalIntel, TendencySlice } from "./tacticalIntel.js";
import type { RpsMove } from "./throwPatternIntel.js";
import { formatTableLines } from "../log/startupRankLog.js";

export type IntelSourceKey = "lifetime" | "h2h" | "recentVsOpponent";

export interface IntelSourceEfficiency {
  source: IntelSourceKey;
  rank: number;
  available: boolean;
  sampleThrows: number;
  dominant: RpsMove | null;
  openWith: RpsMove | null;
  dominantPct: number;
  skew: string | null;
  /** Historical lean prediction accuracy (%). */
  leanPctHistorical: number;
  leanRoundsHistorical: number;
  /** Series win % when this source was chosen as primary. */
  winPctAsPrimary: number;
  primaryMatches: number;
  efficiencyScore: number;
}

function historicalLeanFor(
  source: IntelSourceKey,
  rows: LeanAccuracyRow[],
): LeanAccuracyRow | undefined {
  return rows.find((r) => r.source === source);
}

function historicalPrimaryFor(
  source: IntelSourceKey,
  rows: PrimarySourceLeaderboardRow[],
): PrimarySourceLeaderboardRow | undefined {
  return rows.find((r) => r.source === source);
}

function sliceFor(intel: TacticalIntel, source: IntelSourceKey): TendencySlice | undefined {
  if (source === "lifetime") return intel.lifetime;
  if (source === "h2h") return intel.h2h;
  return intel.recentVsOpponent;
}

/** Confidence ramps with tracked outcomes, not raw throw volume. */
function historicalConfidence(roundsOrMatches: number, halfSaturation: number): number {
  if (roundsOrMatches <= 0) return 0;
  return 1 - Math.exp(-roundsOrMatches / halfSaturation);
}

function scoreSource(
  slice: TendencySlice | undefined,
  lean: LeanAccuracyRow | undefined,
  primary: PrimarySourceLeaderboardRow | undefined,
): number {
  if (!slice) return -1;

  let score = 0;

  const sample = slice.sampleThrows;
  score += Math.min(10, Math.sqrt(sample) * 2.2);

  if (slice.patterns.skew === "high") score += 6;
  else if (slice.patterns.skew === "medium") score += 3;

  if (lean && lean.leanRounds >= 3) {
    const conf = historicalConfidence(lean.leanRounds, 12);
    score += lean.leanPct * 0.42 * conf;
  }

  if (primary && primary.matches >= 1) {
    const conf = historicalConfidence(primary.matches, 6);
    score += primary.winPct * 0.28 * conf;
  }

  return Math.round(score * 10) / 10;
}

function scoreHistoricalSource(
  lean: LeanAccuracyRow | undefined,
  primary: PrimarySourceLeaderboardRow | undefined,
): number {
  let score = 0;
  if (lean && lean.leanRounds >= 1) {
    const conf = historicalConfidence(lean.leanRounds, 12);
    score += lean.leanPct * 0.42 * conf;
  }
  if (primary && primary.matches >= 1) {
    const conf = historicalConfidence(primary.matches, 6);
    score += primary.winPct * 0.28 * conf;
  }
  return Math.round(score * 10) / 10;
}

/** Rank sources from SQLite outcomes only (process startup / no opponent context). */
export function rankHistoricalIntelSourcesByEfficiency(
  historicalLean: LeanAccuracyRow[],
  historicalPrimary: PrimarySourceLeaderboardRow[],
): IntelSourceEfficiency[] {
  const keys: IntelSourceKey[] = ["lifetime", "h2h", "recentVsOpponent"];

  const ranked = keys
    .map((source) => {
      const lean = historicalLeanFor(source, historicalLean);
      const prim = historicalPrimaryFor(source, historicalPrimary);
      const hasLean = (lean?.leanRounds ?? 0) > 0;
      const hasPrimary = (prim?.matches ?? 0) > 0;
      const available = hasLean || hasPrimary;
      return {
        source,
        rank: 0,
        available,
        sampleThrows: 0,
        dominant: null,
        openWith: null,
        dominantPct: 0,
        skew: null,
        leanPctHistorical: lean?.leanPct ?? 0,
        leanRoundsHistorical: lean?.leanRounds ?? 0,
        winPctAsPrimary: prim?.winPct ?? 0,
        primaryMatches: prim?.matches ?? 0,
        efficiencyScore: scoreHistoricalSource(lean, prim),
      };
    })
    .filter((r) => r.available)
    .sort((a, b) => b.efficiencyScore - a.efficiencyScore);

  return ranked.map((r, i) => ({ ...r, rank: i + 1 }));
}

const SOURCE_LABEL: Record<IntelSourceKey, string> = {
  lifetime: "Lifetime",
  h2h: "Head-to-head",
  recentVsOpponent: "Recent vs opp",
};

export function formatHistoricalIntelSourcesRankedLines(
  rankings: IntelSourceEfficiency[],
): string[] {
  if (rankings.length === 0) {
    return ["No historical intel outcomes yet (play matches to populate)."];
  }
  const rows = rankings.map((r) => [
    `#${r.rank}`,
    SOURCE_LABEL[r.source] ?? r.source,
    String(r.efficiencyScore),
    r.leanRoundsHistorical > 0
      ? `${r.leanPctHistorical}% (${r.leanRoundsHistorical} rounds)`
      : "—",
    r.primaryMatches > 0
      ? `${r.winPctAsPrimary}% (${r.primaryMatches} matches)`
      : "—",
  ]);
  return [
    "Intel sources ranked by historical lean + primary-win efficiency",
    ...formatTableLines(
      ["Rank", "Source", "Score", "Lean accuracy", "Win as primary"],
      rows,
    ),
  ];
}

export function formatHistoricalIntelSourcesRankedLog(
  rankings: IntelSourceEfficiency[],
): string {
  return formatHistoricalIntelSourcesRankedLines(rankings).join("\n");
}

/** Rank intel sources by composite efficiency (historical + current sample). */
export function rankIntelSourcesByEfficiency(
  intel: TacticalIntel,
  historicalLean: LeanAccuracyRow[],
  historicalPrimary: PrimarySourceLeaderboardRow[],
): IntelSourceEfficiency[] {
  const keys: IntelSourceKey[] = ["lifetime", "h2h", "recentVsOpponent"];

  const ranked = keys
    .map((source) => {
      const slice = sliceFor(intel, source);
      const lean = historicalLeanFor(source, historicalLean);
      const prim = historicalPrimaryFor(source, historicalPrimary);
      const available = !!slice;
      return {
        source,
        rank: 0,
        available,
        sampleThrows: slice?.sampleThrows ?? 0,
        dominant: slice?.dominant ?? null,
        openWith: slice?.openWith ?? null,
        dominantPct: slice?.dominantPct ?? 0,
        skew: slice?.patterns.skew ?? null,
        leanPctHistorical: lean?.leanPct ?? 0,
        leanRoundsHistorical: lean?.leanRounds ?? 0,
        winPctAsPrimary: prim?.winPct ?? 0,
        primaryMatches: prim?.matches ?? 0,
        efficiencyScore: scoreSource(slice, lean, prim),
      };
    })
    .filter((r) => r.available)
    .sort((a, b) => b.efficiencyScore - a.efficiencyScore);

  return ranked.map((r, i) => ({ ...r, rank: i + 1 }));
}

export function formatIntelSourcesRankedLog(
  rankings: IntelSourceEfficiency[],
  chosenPrimary: TacticalIntel["primarySource"],
): string {
  if (rankings.length === 0) return "no sources";
  const lines = rankings.map((r) => {
    const lean =
      r.leanRoundsHistorical > 0
        ? `histLean=${r.leanPctHistorical}%(${r.leanRoundsHistorical}r)`
        : "histLean=—";
    const win =
      r.primaryMatches > 0
        ? `histWin=${r.winPctAsPrimary}%(${r.primaryMatches}m)`
        : "histWin=—";
    return (
      `${r.rank}.${r.source} score=${r.efficiencyScore} ` +
      `${r.dominant ?? "?"}→${r.openWith ?? "?"} n=${r.sampleThrows} ` +
      `${lean} ${win} skew=${r.skew ?? "-"}`
    );
  });
  const top = rankings[0]!.source;
  const note =
    chosenPrimary === top
      ? `primary=${chosenPrimary}(top)`
      : `primary=${chosenPrimary} top=${top}`;
  return `${lines.join(" | ")} → ${note}`;
}
