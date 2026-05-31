export type Move = "ROCK" | "PAPER" | "SCISSORS";

import { GAME_RULES, type MatchModeName } from "./gameRules";

export type MatchMode = MatchModeName;

export type SeriesOutcome =
  | { kind: "continue" }
  | { kind: "draw" }
  | { kind: "winner"; player: "player1" | "player2" };

export function parseMatchMode(value: unknown): MatchMode {
  if (value === "BO5") return "BO5";
  if (value === "BO10") return "BO10";
  return "BO3";
}

export function winsToFinish(mode: MatchMode): number {
  return GAME_RULES.matchModes[mode].winsToFinish;
}

/** Count resolved rounds won by a player (excludes ties). */
export function countRoundWins(
  rounds: Array<{ resolvedAt?: unknown; winner?: string }>,
  playerId: string,
): number {
  return rounds.filter((round) => round.resolvedAt != null && round.winner === playerId).length;
}

export function bestOfRounds(mode: MatchMode): number {
  return GAME_RULES.matchModes[mode].bestOfRounds;
}

/** BO10 can end tied when all rounds are played with equal wins. */
export function seriesOutcomeAfterRound(
  mode: MatchMode,
  player1Wins: number,
  player2Wins: number,
  _completedRoundNumber: number,
): SeriesOutcome {
  const need = winsToFinish(mode);
  if (player1Wins >= need) return { kind: "winner", player: "player1" };
  if (player2Wins >= need) return { kind: "winner", player: "player2" };

  const rules = GAME_RULES.matchModes[mode];
  const totalAwardedPoints = player1Wins + player2Wins;
  // Resolve once the configured point budget is exhausted (BO3/5/10...).
  if (totalAwardedPoints >= rules.bestOfRounds) {
    if (player1Wins > player2Wins) return { kind: "winner", player: "player1" };
    if (player2Wins > player1Wins) return { kind: "winner", player: "player2" };
    return { kind: "draw" };
  }

  // Drawn rounds award no points and do not end the series.
  // Match otherwise finishes when a player reaches the mode target score.
  return { kind: "continue" };
}

export function parseMatchModes(value: unknown, legacyMode?: unknown): MatchMode[] {
  if (Array.isArray(value)) {
    const modes = value.filter(
      (entry): entry is MatchMode => entry === "BO3" || entry === "BO5" || entry === "BO10",
    );
    if (modes.length > 0) return modes;
  }
  return [parseMatchMode(legacyMode)];
}

const SHARED_MODE_ORDER: MatchMode[] = ["BO3", "BO5", "BO10"];

/** Picks uniformly at random among formats both players queued for. */
export function pickSharedMatchMode(
  modesA: MatchMode[],
  modesB: MatchMode[],
  random: () => number = Math.random,
): MatchMode | null {
  const shared = SHARED_MODE_ORDER.filter((mode) => modesA.includes(mode) && modesB.includes(mode));
  if (shared.length === 0) return null;
  return shared[Math.floor(random() * shared.length)];
}

export function resolveRound(p1: Move, p2: Move): "player1" | "player2" | "tie" {
  if (p1 === p2) return "tie";
  if (
    (p1 === "ROCK" && p2 === "SCISSORS") ||
    (p1 === "PAPER" && p2 === "ROCK") ||
    (p1 === "SCISSORS" && p2 === "PAPER")
  ) {
    return "player1";
  }
  return "player2";
}

export function calculateElo(
  ratingA: number,
  ratingB: number,
  scoreA: number,
  k = 32,
): { newA: number; newB: number; deltaA: number; deltaB: number } {
  const expectedA = 1 / (1 + Math.pow(10, (ratingB - ratingA) / 400));
  const expectedB = 1 - expectedA;
  const deltaA = Math.round(k * (scoreA - expectedA));
  const deltaB = Math.round(k * (1 - scoreA - expectedB));
  return {
    newA: ratingA + deltaA,
    newB: ratingB + deltaB,
    deltaA,
    deltaB,
  };
}

export type MatchEndReason = "normal" | "round_timeout" | "clock_timeout";

export const ELO_MODE_MULTIPLIERS: Record<MatchMode, number> = {
  BO3: 0.2,
  BO5: 0.35,
  BO10: 0.8,
};

export const ELO_DOMINATION_BONUS = 2;

export interface MatchEloContext {
  matchMode: MatchMode;
  endReason: MatchEndReason;
  winnerId: string;
  player1: string;
  player2: string;
  player1Wins: number;
  player2Wins: number;
}

/** Mode weight × optional 2× domination bonus when the opponent won no rounds. */
export function eloMultiplierForMatch(ctx: MatchEloContext): number {
  let multiplier = ELO_MODE_MULTIPLIERS[ctx.matchMode] ?? ELO_MODE_MULTIPLIERS.BO3;
  const loserWins =
    ctx.winnerId === ctx.player1 ? ctx.player2Wins : ctx.player1Wins;
  if (loserWins === 0) {
    multiplier *= ELO_DOMINATION_BONUS;
  }
  return multiplier;
}

export function calculateMatchElo(
  ratingA: number,
  ratingB: number,
  scoreA: number,
  ctx: MatchEloContext,
  k = 32,
): { newA: number; newB: number; deltaA: number; deltaB: number; multiplier: number } {
  const base = calculateElo(ratingA, ratingB, scoreA, k);
  const multiplier = eloMultiplierForMatch(ctx);
  const deltaA = Math.round(base.deltaA * multiplier);
  const deltaB = Math.round(base.deltaB * multiplier);
  return {
    newA: ratingA + deltaA,
    newB: ratingB + deltaB,
    deltaA,
    deltaB,
    multiplier,
  };
}

export function isValidMove(value: string): value is Move {
  return value === "ROCK" || value === "PAPER" || value === "SCISSORS";
}

export type MatchResolution = "player1_win" | "player2_win" | "draw" | "abandoned";

export type MatchResolutionInput = {
  status?: string;
  player1?: string;
  player2?: string;
  winnerId?: string;
  player1Wins?: number;
  player2Wins?: number;
};

export function matchResolutionForWinner(player1: string, winnerId: string): MatchResolution {
  return winnerId === player1 ? "player1_win" : "player2_win";
}

/** Infer stored match resolution from legacy match fields (backfill + consistency checks). */
export function inferMatchResolution(data: MatchResolutionInput): MatchResolution | null {
  const status = data.status;
  if (status === "abandoned") return "abandoned";
  if (status !== "completed") return null;

  const player1 = data.player1;
  const player2 = data.player2;
  if (!player1 || !player2) return null;

  const winnerId = data.winnerId;
  const player1Wins = Number(data.player1Wins ?? 0);
  const player2Wins = Number(data.player2Wins ?? 0);

  if (winnerId === player1) return "player1_win";
  if (winnerId === player2) return "player2_win";
  if (winnerId != null) return null;

  if (player1Wins === player2Wins) return "draw";
  if (player1Wins > player2Wins) return "player1_win";
  return "player2_win";
}
