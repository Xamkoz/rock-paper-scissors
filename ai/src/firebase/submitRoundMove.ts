import { FirebaseError } from "firebase/app";
import type { Functions } from "firebase/functions";
import type { Firestore } from "firebase/firestore";
import { submitMatchMove } from "./callables.js";
import { getMatch, submitMoveDirect } from "./firestoreApi.js";
import { selfSubmitted } from "./matchDoc.js";
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
  }
  return false;
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
  if (match.status !== "active" || selfSubmitted(match, uid)) return false;

  const roundNumber = match.currentRound;

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

    try {
      await submitMoveDirect(db, match.id, roundNumber, uid, choice);
      return true;
    } catch (directErr) {
      const code = directErr instanceof FirebaseError ? directErr.code : "";
      if (code === "permission-denied") {
        return waitUntilSubmitted(db, match.id, uid, roundNumber);
      }
      if (isStaleRoundError(directErr)) {
        return waitUntilSubmitted(db, match.id, uid, roundNumber);
      }
      throw directErr;
    }
  }
}
