import type { Match, Move, RoundResult } from "../types.js";
import { pairKey } from "./schema.js";
export interface MatchRow {
  id: string;
  player1: string;
  player2: string;
  player1_name: string;
  player2_name: string;
  pair_key: string;
  match_mode: string;
  status: string;
  player1_wins: number;
  player2_wins: number;
  winner_id: string | null;
  resolution: string | null;
  player1_elo_delta: number | null;
  player2_elo_delta: number | null;
  created_at: number;
  last_activity_at: number;
  saved_at: number;
}

export interface RoundRow {
  match_id: string;
  round_number: number;
  player1_choice: string | null;
  player2_choice: string | null;
  winner_id: string | null;
  resolved_at: number | null;
}

export function matchToRow(match: Match, savedAt: number): MatchRow {
  return {
    id: match.id,
    player1: match.player1,
    player2: match.player2,
    player1_name: match.player1Name,
    player2_name: match.player2Name,
    pair_key: pairKey(match.player1, match.player2),
    match_mode: match.matchMode,
    status: match.status,
    player1_wins: match.player1Wins,
    player2_wins: match.player2Wins,
    winner_id: match.winnerId ?? null,
    resolution: match.resolution ?? null,
    player1_elo_delta: match.player1EloDelta ?? null,
    player2_elo_delta: match.player2EloDelta ?? null,
    created_at: match.createdAt,
    last_activity_at: match.lastActivityAt,
    saved_at: savedAt,
  };
}

export function roundsToRows(matchId: string, rounds: RoundResult[]): RoundRow[] {
  return rounds.map((r) => ({
    match_id: matchId,
    round_number: r.roundNumber,
    player1_choice: normalizeChoice(r.player1Choice),
    player2_choice: normalizeChoice(r.player2Choice),
    winner_id: r.winner ?? null,
    resolved_at: r.resolvedAt ?? null,
  }));
}

function normalizeChoice(raw: string | undefined): string | null {
  if (!raw) return null;
  const u = raw.toUpperCase();
  if (u === "ROCK" || u === "PAPER" || u === "SCISSORS") return u;
  return null;
}

export function rowsToMatch(row: MatchRow, rounds: RoundRow[]): Match {
  return {
    id: row.id,
    player1: row.player1,
    player2: row.player2,
    player1Name: row.player1_name,
    player2Name: row.player2_name,
    matchMode: row.match_mode as Match["matchMode"],
    status: row.status as Match["status"],
    player1Ready: true,
    player2Ready: true,
    readyDeadlineAt: 0,
    currentRound: rounds.length > 0 ? rounds[rounds.length - 1]!.round_number : 0,
    player1Wins: row.player1_wins,
    player2Wins: row.player2_wins,
    rounds: rounds.map(roundRowToResult),
    winnerId: row.winner_id ?? undefined,
    resolution: (row.resolution as Match["resolution"]) ?? undefined,
    player1EloDelta: row.player1_elo_delta ?? undefined,
    player2EloDelta: row.player2_elo_delta ?? undefined,
    createdAt: row.created_at,
    lastActivityAt: row.last_activity_at,
  };
}

function roundRowToResult(r: RoundRow): RoundResult {
  return {
    roundNumber: r.round_number,
    player1Submitted: r.player1_choice != null,
    player2Submitted: r.player2_choice != null,
    player1Choice: r.player1_choice ?? undefined,
    player2Choice: r.player2_choice ?? undefined,
    winner: r.winner_id ?? undefined,
    resolvedAt: r.resolved_at ?? undefined,
  };
}

/** Compact history row for LLM prompts (per-round throws from bot's perspective). */
export interface HistoricalMatchSummary {
  id: string;
  opponentUid: string;
  opponentName: string;
  matchMode: Match["matchMode"];
  botWins: number;
  opponentWins: number;
  resolution?: string;
  description?: string;
  rounds: Array<{
    roundNumber: number;
    botMove?: Move;
    opponentMove?: Move;
    roundWinnerId?: string;
  }>;
}

export function matchToSummary(
  match: Match,
  botUid: string,
  description?: string,
): HistoricalMatchSummary {
  const opponentUid = match.player1 === botUid ? match.player2 : match.player1;
  const opponentName = match.player1 === botUid ? match.player2Name : match.player1Name;
  const botIsP1 = match.player1 === botUid;

  return {
    id: match.id,
    opponentUid,
    opponentName,
    matchMode: match.matchMode,
    botWins: botIsP1 ? match.player1Wins : match.player2Wins,
    opponentWins: botIsP1 ? match.player2Wins : match.player1Wins,
    resolution: match.resolution,
    description,
    rounds: match.rounds
      .filter((r) => r.player1Choice && r.player2Choice)
      .map((r) => {
        const botMove = (botIsP1 ? r.player1Choice : r.player2Choice)?.toUpperCase() as
          | Move
          | undefined;
        const opponentMove = (botIsP1 ? r.player2Choice : r.player1Choice)?.toUpperCase() as
          | Move
          | undefined;
        return {
          roundNumber: r.roundNumber,
          botMove,
          opponentMove,
          roundWinnerId: r.winner,
        };
      }),
  };
}
