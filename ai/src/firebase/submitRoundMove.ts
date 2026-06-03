import { FirebaseError } from "firebase/app";
import type { Functions } from "firebase/functions";
import type { Firestore } from "firebase/firestore";
import { submitMatchMove } from "./callables.js";
import { getMatch, submitMoveDirect } from "./firestoreApi.js";
import { selfSubmitted } from "./matchDoc.js";
import { warn } from "../log.js";
import type { Match, Move } from "../types.js";

const SUBMIT_CONFIRM_DELAY_MS = 500;
const SUBMIT_CONFIRM_ATTEMPTS = 6;

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function errorMessage(err: unknown): string {
  if (err instanceof FirebaseError) return err.message;
  if (err instanceof Error) return err.message;
  return String(err);
}

function isPermissionDenied(err: unknown): boolean {
  if (err instanceof FirebaseError) return err.code === "permission-denied";
  return errorMessage(err).toLowerCase().includes("permission");
}

export function isStaleRoundError(err: unknown): boolean {
  const msg = errorMessage(err).toLowerCase();
  return msg.includes("no longer open") || msg.includes("not active");
}

export function isMoveNotRecordedError(err: unknown): boolean {
  return errorMessage(err).toLowerCase().includes("not recorded");
}

async function waitUntilSubmitted(
  db: Firestore,
  matchId: string,
  uid: string,
  roundNumber: number,
): Promise<boolean> {
  for (let i = 0; i < SUBMIT_CONFIRM_ATTEMPTS; i++) {
    await delay(SUBMIT_CONFIRM_DELAY_MS);
    const fresh = await getMatch(db, matchId);
    if (!fresh) continue;
    if (fresh.currentRound !== roundNumber) return true;
    if (selfSubmitted(fresh, uid)) return true;
    if (fresh.status === "completed" || fresh.status === "abandoned") {
      return selfSubmitted(fresh, uid);
    }
  }
  return false;
}

function canSubmitToRound(match: Match, uid: string, roundNumber: number): boolean {
  return (
    match.status === "active" &&
    match.currentRound === roundNumber &&
    !selfSubmitted(match, uid)
  );
}

/**
 * Submits one move for the open round. Returns true when the server shows our submission
 * (or the round advanced). Idempotent — safe if the listener fires multiple times.
 */
export async function submitRoundMove(
  db: Firestore,
  functions: Functions,
  match: Match,
  uid: string,
  choice: Move,
): Promise<boolean> {
  const roundNumber = match.currentRound;
  if (!canSubmitToRound(match, uid, roundNumber)) return false;

  try {
    await submitMatchMove(functions, match.id, roundNumber, choice);
    return true;
  } catch (callableErr) {
    if (isStaleRoundError(callableErr)) {
      return waitUntilSubmitted(db, match.id, uid, roundNumber);
    }
    if (isMoveNotRecordedError(callableErr)) {
      if (await waitUntilSubmitted(db, match.id, uid, roundNumber)) return true;
    }

    const fresh = await getMatch(db, match.id);
    if (!fresh || !canSubmitToRound(fresh, uid, roundNumber)) {
      return waitUntilSubmitted(db, match.id, uid, roundNumber);
    }

    try {
      await submitMoveDirect(db, match.id, roundNumber, uid, choice);
      return true;
    } catch (directErr) {
      if (isPermissionDenied(directErr) || isStaleRoundError(directErr)) {
        warn(
          `[move] direct choice write skipped (match ${match.id} r${roundNumber}): ${errorMessage(directErr)}`,
        );
        return waitUntilSubmitted(db, match.id, uid, roundNumber);
      }
      throw directErr;
    }
  }
}
