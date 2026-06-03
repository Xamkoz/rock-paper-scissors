import type { Match } from "../types.js";

/** Small snapshot for move prompts (avoids full match JSON). */
export function compactMatchForPick(match: Match, botUid: string): Record<string, unknown> {
  const botIsP1 = match.player1 === botUid;
  const open = match.rounds.find((r) => r.roundNumber === match.currentRound);

  return {
    id: match.id,
    mode: match.matchMode,
    round: match.currentRound,
    botName: botIsP1 ? match.player1Name : match.player2Name,
    botWins: botIsP1 ? match.player1Wins : match.player2Wins,
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
        bot: botIsP1 ? r.player1Choice : r.player2Choice,
        opponent: botIsP1 ? r.player2Choice : r.player1Choice,
        winner: r.winner,
      })),
  };
}

import { pickMoveTimeoutCapMs, pickSubmitReserveMs } from "./timing.js";

/** Ms for the move LLM call: round deadline minus submit reserve, capped by pickMoveTimeoutCapMs. */
export function pickTimeBudgetMs(match: Match): number | undefined {
  const open = match.rounds.find((r) => r.roundNumber === match.currentRound);
  if (!open?.deadline) return undefined;
  const submitReserve = pickSubmitReserveMs();
  const cap = pickMoveTimeoutCapMs();
  const remaining = open.deadline - Date.now() - submitReserve;
  return Math.max(3000, Math.min(cap, remaining));
}
