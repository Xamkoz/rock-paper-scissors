import type { Match } from "../types.js";

/** What the bot should throw to beat each opponent throw. */
export const COUNTER_TO_OPPONENT: Record<"ROCK" | "PAPER" | "SCISSORS", "ROCK" | "PAPER" | "SCISSORS"> = {
  ROCK: "PAPER",
  PAPER: "SCISSORS",
  SCISSORS: "ROCK",
};

/** True when [winner] beats [loser] in standard RPS (same as COUNTER_TO_OPPONENT). */
export function moveBeats(winner: "ROCK" | "PAPER" | "SCISSORS", loser: "ROCK" | "PAPER" | "SCISSORS"): boolean {
  return COUNTER_TO_OPPONENT[loser] === winner;
}

export function counterToOpponentThrow(opponent: "ROCK" | "PAPER" | "SCISSORS"): "ROCK" | "PAPER" | "SCISSORS" {
  return COUNTER_TO_OPPONENT[opponent];
}

function parseRpsMove(word: string | undefined): "ROCK" | "PAPER" | "SCISSORS" | null {
  if (!word) return null;
  const u = word.toUpperCase();
  if (u === "ROCK" || u === "PAPER" || u === "SCISSORS") return u;
  return null;
}

/** Opponent's last resolved throw in the current match. */
export function lastOpponentThrowFromMatch(match: Match | null, botUid: string): "ROCK" | "PAPER" | "SCISSORS" | null {
  if (!match) return null;
  const resolved = match.rounds
    .filter((r) => r.resolvedAt != null && r.player1Choice && r.player2Choice)
    .sort((a, b) => a.roundNumber - b.roundNumber);
  const last = resolved.at(-1);
  if (!last) return null;
  const raw = botUid === match.player1 ? last.player2Choice : last.player1Choice;
  return parseRpsMove(raw);
}

/** All resolved opponent throws in the current match, in order. */
export function opponentThrowsFromMatch(
  match: Match | null,
  botUid: string,
): ("ROCK" | "PAPER" | "SCISSORS")[] {
  if (!match) return [];
  const botIsP1 = match.player1 === botUid;
  return match.rounds
    .filter((r) => r.resolvedAt != null && r.player1Choice && r.player2Choice)
    .sort((a, b) => a.roundNumber - b.roundNumber)
    .map((r) => {
      const raw = botIsP1 ? r.player2Choice : r.player1Choice;
      return parseRpsMove(raw);
    })
    .filter((m): m is "ROCK" | "PAPER" | "SCISSORS" => m != null);
}

export interface ThrowDistribution {
  rockPct: number;
  paperPct: number;
  scissorsPct: number;
}

export interface OpponentTendency {
  dominant: "ROCK" | "PAPER" | "SCISSORS";
  dominantPct: number;
  distribution: ThrowDistribution;
  /** Bot opening that beats opponent's dominant lean. */
  openWith: "ROCK" | "PAPER" | "SCISSORS";
}

export function tendencyFromCounts(counts: {
  rock: number;
  paper: number;
  scissors: number;
}): OpponentTendency | null {
  const total = counts.rock + counts.paper + counts.scissors;
  if (total <= 0) return null;

  const ranked = [
    { move: "ROCK" as const, n: counts.rock },
    { move: "PAPER" as const, n: counts.paper },
    { move: "SCISSORS" as const, n: counts.scissors },
  ].sort((a, b) => b.n - a.n);

  const dominant = ranked[0]!.move;
  return {
    dominant,
    dominantPct: Math.round((ranked[0]!.n / total) * 100),
    distribution: {
      rockPct: Math.round((counts.rock / total) * 100),
      paperPct: Math.round((counts.paper / total) * 100),
      scissorsPct: Math.round((counts.scissors / total) * 100),
    },
    openWith: COUNTER_TO_OPPONENT[dominant],
  };
}
