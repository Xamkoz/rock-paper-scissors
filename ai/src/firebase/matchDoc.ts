import type { DocumentData, Timestamp } from "firebase/firestore";
import type { Match, MatchMode, MatchResolution, MatchStatus, RoundResult } from "../types.js";

function tsMs(value: unknown): number | undefined {
  if (value == null) return undefined;
  if (typeof value === "object" && value !== null && "toMillis" in value) {
    return (value as Timestamp).toMillis();
  }
  return undefined;
}

function parseMatchMode(value: unknown): MatchMode {
  if (value === "BO5") return "BO5";
  if (value === "BO10") return "BO10";
  return "BO3";
}

function parseStatus(value: unknown): MatchStatus {
  if (value === "lobby" || value === "active" || value === "completed" || value === "abandoned") {
    return value;
  }
  return "abandoned";
}

function parseResolution(value: unknown): MatchResolution | undefined {
  if (
    value === "player1_win" ||
    value === "player2_win" ||
    value === "draw" ||
    value === "abandoned"
  ) {
    return value;
  }
  return undefined;
}

export function matchFromSnapshot(id: string, data: DocumentData): Match {
  const roundsRaw = (data.rounds as DocumentData[] | undefined) ?? [];
  const rounds: RoundResult[] = roundsRaw.map((map) => ({
    roundNumber: Number(map.roundNumber ?? 0),
    player1Submitted: Boolean(map.player1Submitted),
    player2Submitted: Boolean(map.player2Submitted),
    player1Choice: map.player1Choice as string | undefined,
    player2Choice: map.player2Choice as string | undefined,
    winner: map.winner as string | undefined,
    resolvedAt: tsMs(map.resolvedAt),
    startedAt: tsMs(map.startedAt),
    deadline: tsMs(map.deadline),
  }));

  return {
    id,
    player1: String(data.player1 ?? ""),
    player2: String(data.player2 ?? ""),
    player1Name: String(data.player1Name ?? "Player 1"),
    player2Name: String(data.player2Name ?? "Player 2"),
    matchMode: parseMatchMode(data.matchMode),
    status: parseStatus(data.status),
    player1Ready: Boolean(data.player1Ready),
    player2Ready: Boolean(data.player2Ready),
    readyDeadlineAt: tsMs(data.readyDeadlineAt) ?? 0,
    currentRound: Number(data.currentRound ?? 1),
    player1Wins: Number(data.player1Wins ?? 0),
    player2Wins: Number(data.player2Wins ?? 0),
    rounds,
    winnerId: data.winnerId as string | undefined,
    resolution: parseResolution(data.resolution),
    player1EloDelta: data.player1EloDelta as number | undefined,
    player2EloDelta: data.player2EloDelta as number | undefined,
    createdAt: tsMs(data.createdAt) ?? 0,
    lastActivityAt: tsMs(data.lastActivityAt) ?? 0,
  };
}

export function isParticipant(match: Match, uid: string): boolean {
  return match.player1 === uid || match.player2 === uid;
}

export function opponentId(match: Match, selfUid: string): string | null {
  if (match.player1 === selfUid) return match.player2;
  if (match.player2 === selfUid) return match.player1;
  return null;
}

export function selfSubmitted(match: Match, selfUid: string): boolean {
  const round = match.rounds.find((r) => r.roundNumber === match.currentRound);
  if (!round) return false;
  if (match.player1 === selfUid) return round.player1Submitted;
  if (match.player2 === selfUid) return round.player2Submitted;
  return false;
}

export function openRoundNeedsMove(match: Match, selfUid: string): boolean {
  if (match.status !== "active") return false;
  return !selfSubmitted(match, selfUid);
}
