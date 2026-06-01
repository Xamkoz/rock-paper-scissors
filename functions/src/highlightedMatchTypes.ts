import type { MatchMode, MatchResolution } from "./game";
import type { Timestamp } from "firebase-admin/firestore";

export interface RoundDoc {
  roundNumber: number;
  player1Submitted?: boolean;
  player2Submitted?: boolean;
  player1Choice?: string;
  player2Choice?: string;
  winner?: string;
  resolvedAt?: Timestamp;
  startedAt?: Timestamp;
  deadline?: Timestamp;
  player1MoveMs?: number;
  player2MoveMs?: number;
  endReason?: string;
}

/** Subset of match fields used by highlighted-match queries. */
export interface MatchDoc {
  player1: string;
  player2: string;
  player1Name: string;
  player2Name: string;
  matchMode: MatchMode;
  status: "lobby" | "active" | "completed" | "abandoned";
  player1Wins: number;
  player2Wins: number;
  rounds: RoundDoc[];
  winnerId?: string;
  resolution?: MatchResolution;
  player1EloDelta?: number;
  player2EloDelta?: number;
  player1Elo?: number;
  player2Elo?: number;
  lastActivityAt: Timestamp;
}
