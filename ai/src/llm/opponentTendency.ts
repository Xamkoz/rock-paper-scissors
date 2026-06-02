/** What the bot should throw to beat each opponent throw. */
export const COUNTER_TO_OPPONENT: Record<"ROCK" | "PAPER" | "SCISSORS", "ROCK" | "PAPER" | "SCISSORS"> = {
  ROCK: "PAPER",
  PAPER: "SCISSORS",
  SCISSORS: "ROCK",
};

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
