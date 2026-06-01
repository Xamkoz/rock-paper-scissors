export type Move = "ROCK" | "PAPER" | "SCISSORS";

export type MatchMode = "BO3" | "BO5" | "BO10";

export type MatchStatus = "lobby" | "active" | "completed" | "abandoned";

export type MatchResolution =
  | "player1_win"
  | "player2_win"
  | "draw"
  | "abandoned";

export interface RoundResult {
  roundNumber: number;
  player1Submitted: boolean;
  player2Submitted: boolean;
  player1Choice?: string;
  player2Choice?: string;
  winner?: string;
  resolvedAt?: number;
  startedAt?: number;
  deadline?: number;
}

export interface Match {
  id: string;
  player1: string;
  player2: string;
  player1Name: string;
  player2Name: string;
  matchMode: MatchMode;
  status: MatchStatus;
  player1Ready: boolean;
  player2Ready: boolean;
  readyDeadlineAt: number;
  currentRound: number;
  player1Wins: number;
  player2Wins: number;
  rounds: RoundResult[];
  winnerId?: string;
  resolution?: MatchResolution;
  player1EloDelta?: number;
  player2EloDelta?: number;
  createdAt: number;
  lastActivityAt: number;
}

export interface UserProfile {
  uid: string;
  displayName: string;
  elo: number;
  throwsRock: number;
  throwsPaper: number;
  throwsScissors: number;
}

export interface MovePatternSummary {
  opponentId: string;
  sampleRounds: number;
  rockRate: number;
  paperRate: number;
  scissorsRate: number;
  /** Most common throw in the sample. */
  dominantMove: Move;
  /** Suggested counter to [dominantMove]. */
  counterMove: Move;
  notes: string[];
}
