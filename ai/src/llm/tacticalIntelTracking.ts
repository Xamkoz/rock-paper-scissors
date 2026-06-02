import type { Match } from "../types.js";
import { moveCode } from "./movePrompt.js";
import type { TacticalIntel, TendencySlice } from "./tacticalIntel.js";

export type IntelSource = "lifetime" | "h2h" | "recentVsOpponent";

export interface SourceLeanScore {
  source: IntelSource;
  leanHits: number;
  leanRounds: number;
  leanPct: number;
  openHit: boolean | null;
}

export interface TacticalIntelOutcome {
  matchId: string;
  botWon: boolean;
  primarySource: TacticalIntel["primarySource"];
  roundsPlayed: number;
  lifetimeLeanHits: number;
  lifetimeLeanRounds: number;
  h2hLeanHits: number;
  h2hLeanRounds: number;
  recentLeanHits: number;
  recentLeanRounds: number;
  lifetimeOpenHit: boolean | null;
  h2hOpenHit: boolean | null;
  recentOpenHit: boolean | null;
  bestLeanSource: IntelSource | null;
  primaryMatchedBest: boolean;
}

export interface PrimarySourceLeaderboardRow {
  source: string;
  matches: number;
  wins: number;
  winPct: number;
}

export interface LeanAccuracyRow {
  source: IntelSource;
  leanHits: number;
  leanRounds: number;
  leanPct: number;
}

function scoreSlice(
  slice: TendencySlice | undefined,
  opponentThrows: Array<"ROCK" | "PAPER" | "SCISSORS">,
  round1Bot?: "ROCK" | "PAPER" | "SCISSORS",
): { leanHits: number; leanRounds: number; openHit: boolean | null } {
  if (!slice || opponentThrows.length === 0) {
    return { leanHits: 0, leanRounds: 0, openHit: null };
  }
  let leanHits = 0;
  for (const o of opponentThrows) {
    if (o === slice.dominant) leanHits++;
  }
  const openHit =
    round1Bot != null ? round1Bot === slice.openWith : null;
  return { leanHits, leanRounds: opponentThrows.length, openHit };
}

function bestLeanSource(scores: SourceLeanScore[]): IntelSource | null {
  let best: SourceLeanScore | null = null;
  for (const s of scores) {
    if (s.leanRounds === 0) continue;
    if (!best || s.leanPct > best.leanPct) best = s;
    else if (s.leanPct === best.leanPct && s.leanHits > best.leanHits) best = s;
  }
  return best?.source ?? null;
}

export function evaluateTacticalIntelOutcome(
  match: Match,
  botUid: string,
  intel: TacticalIntel,
): TacticalIntelOutcome {
  const botIsP1 = match.player1 === botUid;
  const opponentThrows: Array<"ROCK" | "PAPER" | "SCISSORS"> = [];
  let round1Bot: "ROCK" | "PAPER" | "SCISSORS" | undefined;

  for (const r of match.rounds) {
    if (!r.resolvedAt || !r.player1Choice || !r.player2Choice) continue;
    const oppRaw = botIsP1 ? r.player2Choice : r.player1Choice;
    const botRaw = botIsP1 ? r.player1Choice : r.player2Choice;
    const oppMove = moveCode(oppRaw) as "ROCK" | "PAPER" | "SCISSORS" | undefined;
    const bot = moveCode(botRaw) as "ROCK" | "PAPER" | "SCISSORS" | undefined;
    if (oppMove) opponentThrows.push(oppMove);
    if (r.roundNumber === 1 && bot) round1Bot = bot;
  }

  const life = scoreSlice(intel.lifetime, opponentThrows, round1Bot);
  const h2h = scoreSlice(intel.h2h, opponentThrows, round1Bot);
  const recent = scoreSlice(intel.recentVsOpponent, opponentThrows, round1Bot);

  const leanScores: SourceLeanScore[] = [
    {
      source: "lifetime",
      leanHits: life.leanHits,
      leanRounds: life.leanRounds,
      leanPct: life.leanRounds > 0 ? Math.round((life.leanHits / life.leanRounds) * 100) : 0,
      openHit: life.openHit,
    },
    {
      source: "h2h",
      leanHits: h2h.leanHits,
      leanRounds: h2h.leanRounds,
      leanPct: h2h.leanRounds > 0 ? Math.round((h2h.leanHits / h2h.leanRounds) * 100) : 0,
      openHit: h2h.openHit,
    },
    {
      source: "recentVsOpponent",
      leanHits: recent.leanHits,
      leanRounds: recent.leanRounds,
      leanPct:
        recent.leanRounds > 0 ? Math.round((recent.leanHits / recent.leanRounds) * 100) : 0,
      openHit: recent.openHit,
    },
  ];

  const best = bestLeanSource(leanScores);
  const primary = intel.primarySource;
  const primaryMatchedBest = best != null && primary === best;

  const botWon =
    match.status === "completed" &&
    !!match.winnerId &&
    match.winnerId === botUid;

  return {
    matchId: match.id,
    botWon,
    primarySource: primary,
    roundsPlayed: opponentThrows.length,
    lifetimeLeanHits: life.leanHits,
    lifetimeLeanRounds: life.leanRounds,
    h2hLeanHits: h2h.leanHits,
    h2hLeanRounds: h2h.leanRounds,
    recentLeanHits: recent.leanHits,
    recentLeanRounds: recent.leanRounds,
    lifetimeOpenHit: life.openHit,
    h2hOpenHit: h2h.openHit,
    recentOpenHit: recent.openHit,
    bestLeanSource: best,
    primaryMatchedBest,
  };
}

export function formatMatchTacticalScoreLog(
  outcome: TacticalIntelOutcome,
): string {
  const lean = (hits: number, rounds: number) =>
    rounds > 0 ? `${hits}/${rounds}` : "—";
  return [
    `match=${outcome.matchId}`,
    `primary=${outcome.primarySource}`,
    `win=${outcome.botWon ? "Y" : "N"}`,
    `life-lean=${lean(outcome.lifetimeLeanHits, outcome.lifetimeLeanRounds)}`,
    `h2h-lean=${lean(outcome.h2hLeanHits, outcome.h2hLeanRounds)}`,
    `recent-lean=${lean(outcome.recentLeanHits, outcome.recentLeanRounds)}`,
    outcome.bestLeanSource ? `best=${outcome.bestLeanSource}` : null,
    outcome.primaryMatchedBest ? "primary=best" : null,
  ]
    .filter(Boolean)
    .join(" ");
}

export function formatPrimaryLeaderboardLog(
  rows: PrimarySourceLeaderboardRow[],
): string {
  if (rows.length === 0) return "no data";
  return rows
    .map((r) => `${r.source} ${r.wins}-${r.matches - r.wins} (${r.winPct}%)`)
    .join(" | ");
}

export function formatLeanAccuracyLog(rows: LeanAccuracyRow[]): string {
  if (rows.length === 0) return "no data";
  return rows
    .map((r) =>
      r.leanRounds > 0
        ? `${r.source} ${r.leanHits}/${r.leanRounds} (${r.leanPct}%)`
        : `${r.source} —`,
    )
    .join(" | ");
}
