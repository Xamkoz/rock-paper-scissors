import type { Match } from "../types.js";
import { opponentId } from "../firebase/matchDoc.js";
import type { MovePatternSummary } from "../types.js";

function opponentName(match: Match, selfUid: string): string {
  const opp = opponentId(match, selfUid);
  if (match.player1 === opp) return match.player1Name;
  if (match.player2 === opp) return match.player2Name;
  return "opponent";
}

function selfScore(match: Match, selfUid: string): { mine: number; theirs: number } {
  if (match.player1 === selfUid) {
    return { mine: match.player1Wins, theirs: match.player2Wins };
  }
  return { mine: match.player2Wins, theirs: match.player1Wins };
}

function outcomeWord(match: Match, selfUid: string): string {
  if (match.status === "abandoned") return "abandoned";
  if (match.resolution === "draw") return "drew";
  if (!match.winnerId) return "finished";
  return match.winnerId === selfUid ? "won" : "lost";
}

/** One-line recap for logs, dashboards, or chat. */
export function describeMatch(
  match: Match,
  selfUid: string,
  pattern?: MovePatternSummary,
): string {
  const name = opponentName(match, selfUid);
  const { mine, theirs } = selfScore(match, selfUid);
  const outcome = outcomeWord(match, selfUid);
  const mode = match.matchMode;
  const roundsPlayed = match.rounds.filter((r) => r.resolvedAt).length;

  let line = `${outcome} ${mode} vs ${name} (${mine}-${theirs}, ${roundsPlayed} rounds)`;

  if (match.player1EloDelta != null && match.player2EloDelta != null) {
    const delta = match.player1 === selfUid ? match.player1EloDelta : match.player2EloDelta;
    if (delta !== 0) {
      line += delta > 0 ? `, +${delta} ELO` : `, ${delta} ELO`;
    }
  }

  if (pattern && pattern.sampleRounds > 0) {
    line += `; opponent leans ${pattern.dominantMove.toLowerCase()} (${Math.round(pattern.rockRate * 100)}/${Math.round(pattern.paperRate * 100)}/${Math.round(pattern.scissorsRate * 100)} R/P/S)`;
  }

  return line;
}
