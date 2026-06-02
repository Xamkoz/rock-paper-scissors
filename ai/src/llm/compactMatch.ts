import type { Match } from "../types.js";

/** Small snapshot for move prompts (avoids full match JSON). */
export function compactMatchForPick(match: Match, botUid: string): Record<string, unknown> {
  const botIsP1 = match.player1 === botUid;
  const open = match.rounds.find((r) => r.roundNumber === match.currentRound);

  return {
    id: match.id,
    mode: match.matchMode,
    round: match.currentRound,
    you: botIsP1 ? match.player1Name : match.player2Name,
    opponent: botIsP1 ? match.player2Name : match.player1Name,
    yourWins: botIsP1 ? match.player1Wins : match.player2Wins,
    opponentWins: botIsP1 ? match.player2Wins : match.player1Wins,
    roundDeadlineMs: open?.deadline,
    priorRounds: match.rounds
      .filter(
        (r) =>
          r.roundNumber < match.currentRound &&
          r.player1Choice &&
          r.player2Choice,
      )
      .map((r) => ({
        n: r.roundNumber,
        you: botIsP1 ? r.player1Choice : r.player2Choice,
        opp: botIsP1 ? r.player2Choice : r.player1Choice,
        winner: r.winner,
      })),
  };
}

/** Ms until round deadline minus reserve for submit + margin (min 5s). */
export function pickTimeBudgetMs(match: Match, reserveMs = 4000): number | undefined {
  const open = match.rounds.find((r) => r.roundNumber === match.currentRound);
  if (!open?.deadline) return undefined;
  return Math.max(5000, open.deadline - Date.now() - reserveMs);
}
