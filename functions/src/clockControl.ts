import { Timestamp } from "firebase-admin/firestore";
import { CLOCK_INCREMENT_MS, INITIAL_CLOCK_MS, MAX_CLOCK_MS } from "./gameRules";

export { CLOCK_INCREMENT_MS, INITIAL_CLOCK_MS, MAX_CLOCK_MS };

export interface ClockFields {
  player1ClockMs?: number;
  player2ClockMs?: number;
  clocksUpdatedAt?: Timestamp;
}

/** Per-player submission state for an open round (choices may live only in subcollections). */
export interface OpenRoundClockState {
  player1Submitted?: boolean;
  player2Submitted?: boolean;
  /** Legacy: choices were written to the match doc before blind-play. */
  player1Choice?: string;
  player2Choice?: string;
}

export function player1HasSubmitted(round: OpenRoundClockState): boolean {
  return round.player1Submitted === true || !!round.player1Choice;
}

export function player2HasSubmitted(round: OpenRoundClockState): boolean {
  return round.player2Submitted === true || !!round.player2Choice;
}

/** True when [uid] submitted in [roundNumber], even if that round has since resolved. */
export function playerSubmittedInRound(
  match: {
    player1: string;
    player2: string;
    rounds: Array<OpenRoundClockState & { roundNumber: number }>;
  },
  roundNumber: number,
  uid: string,
): boolean {
  const round = match.rounds.find((entry) => entry.roundNumber === roundNumber);
  if (!round) return false;
  return uid === match.player1 ? player1HasSubmitted(round) : player2HasSubmitted(round);
}

export function clockMs(value: number | undefined): number {
  return value ?? INITIAL_CLOCK_MS;
}

export function initialClockFields(now: Timestamp): Required<Pick<ClockFields, "player1ClockMs" | "player2ClockMs" | "clocksUpdatedAt">> {
  return {
    player1ClockMs: INITIAL_CLOCK_MS,
    player2ClockMs: INITIAL_CLOCK_MS,
    clocksUpdatedAt: now,
  };
}

/**
 * Deduct elapsed time from each player who has not submitted this round.
 * Submitted players' clocks are frozen until the next round starts.
 */
export function tickClocks(
  clocks: ClockFields,
  round: OpenRoundClockState,
  now: Timestamp,
): Required<Pick<ClockFields, "player1ClockMs" | "player2ClockMs" | "clocksUpdatedAt">> {
  const lastTick = clocks.clocksUpdatedAt ?? now;
  const elapsed = Math.max(0, now.toMillis() - lastTick.toMillis());

  let player1ClockMs = clockMs(clocks.player1ClockMs);
  let player2ClockMs = clockMs(clocks.player2ClockMs);

  if (!player1HasSubmitted(round)) {
    player1ClockMs = Math.max(0, player1ClockMs - elapsed);
  }
  if (!player2HasSubmitted(round)) {
    player2ClockMs = Math.max(0, player2ClockMs - elapsed);
  }

  return { player1ClockMs, player2ClockMs, clocksUpdatedAt: now };
}

export function applyClockIncrement(player1ClockMs: number, player2ClockMs: number): {
  player1ClockMs: number;
  player2ClockMs: number;
} {
  return {
    player1ClockMs: Math.min(MAX_CLOCK_MS, player1ClockMs + CLOCK_INCREMENT_MS),
    player2ClockMs: Math.min(MAX_CLOCK_MS, player2ClockMs + CLOCK_INCREMENT_MS),
  };
}

export type ClockExpiry = "player1" | "player2" | "both" | null;

/** Who ran out of thinking time without locking in a move this round. */
export function clockExpiry(
  player1ClockMs: number,
  player2ClockMs: number,
  round: OpenRoundClockState,
): ClockExpiry {
  const p1Out = player1ClockMs <= 0 && !player1HasSubmitted(round);
  const p2Out = player2ClockMs <= 0 && !player2HasSubmitted(round);
  if (p1Out && p2Out) return "both";
  if (p1Out) return "player1";
  if (p2Out) return "player2";
  return null;
}
