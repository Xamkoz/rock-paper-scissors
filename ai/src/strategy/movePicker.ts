import type { Move, MovePatternSummary } from "../types.js";

/** Counter [dominantMove] with light randomization so the bot is not perfectly predictable. */
export function pickMove(pattern: MovePatternSummary, random = Math.random): Move {
  const roll = random();
  if (roll < 0.65) return pattern.counterMove;
  if (roll < 0.82) return pattern.dominantMove;
  const others: Move[] = ["ROCK", "PAPER", "SCISSORS"].filter(
    (m) => m !== pattern.counterMove && m !== pattern.dominantMove,
  ) as Move[];
  return others[Math.floor(random() * others.length)] ?? "ROCK";
}
