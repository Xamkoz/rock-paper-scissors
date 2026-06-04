import {
  deleteDoc,
  doc,
  getDoc,
  getDocFromServer,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  type Firestore,
} from "firebase/firestore";
import { matchFromSnapshot } from "./matchDoc.js";
import { isQueueDocAccessDenied, isTransientFirebaseError } from "./errors.js";
import type { Match, MatchMode, Move, UserProfile } from "../types.js";

export async function ensureUserProfile(
  db: Firestore,
  uid: string,
  displayName: string,
): Promise<UserProfile> {
  const ref = doc(db, "users", uid);
  const snap = await getDoc(ref);
  if (snap.exists()) {
    const data = snap.data();
    return {
      uid,
      displayName: String(data.displayName ?? displayName),
      elo: Number(data.elo ?? 1000),
      throwsRock: Number(data.throwsRock ?? 0),
      throwsPaper: Number(data.throwsPaper ?? 0),
      throwsScissors: Number(data.throwsScissors ?? 0),
    };
  }
  const now = Timestamp.now();
  await setDoc(ref, {
    displayName,
    elo: 1000,
    wins: 0,
    losses: 0,
    draws: 0,
    createdAt: now,
    lastSeen: now,
    throwsRock: 0,
    throwsPaper: 0,
    throwsScissors: 0,
    moveTimeMs: 0,
    moveCount: 0,
  });
  return {
    uid,
    displayName,
    elo: 1000,
    throwsRock: 0,
    throwsPaper: 0,
    throwsScissors: 0,
  };
}

export async function getUserProfile(db: Firestore, uid: string): Promise<UserProfile | null> {
  const snap = await getDoc(doc(db, "users", uid));
  if (!snap.exists()) return null;
  const data = snap.data();
  return {
    uid,
    displayName: String(data.displayName ?? "Player"),
    elo: Number(data.elo ?? 1000),
    throwsRock: Number(data.throwsRock ?? 0),
    throwsPaper: Number(data.throwsPaper ?? 0),
    throwsScissors: Number(data.throwsScissors ?? 0),
  };
}

export async function getMatch(db: Firestore, matchId: string): Promise<Match | null> {
  const snap = await getDoc(doc(db, "matches", matchId));
  if (!snap.exists()) return null;
  return matchFromSnapshot(snap.id, snap.data());
}

export async function getActiveMatchId(db: Firestore, uid: string): Promise<string | null> {
  const snap = await getDoc(doc(db, "users", uid));
  const id = snap.get("activeMatchId") as string | undefined;
  return id?.trim() ? id : null;
}

export async function writeQueueEntry(
  db: Firestore,
  uid: string,
  profile: UserProfile,
  matchModes: MatchMode[],
): Promise<number> {
  const now = Timestamp.now();
  const clientJoinedAt = Date.now();
  await setDoc(doc(db, "queue", uid), {
    joinedAt: now,
    lastHeartbeatAt: now,
    clientJoinedAt,
    elo: profile.elo,
    displayName: profile.displayName,
    matchModes,
  });
  return clientJoinedAt;
}

export type QueueHeartbeatResult = "ok" | "missing" | "transient";

/** Updates queue heartbeat; distinguishes gone doc vs network blip. */
export async function sendQueueHeartbeat(
  db: Firestore,
  uid: string,
): Promise<QueueHeartbeatResult> {
  const ref = doc(db, "queue", uid);
  try {
    const snap = await getDoc(ref);
    if (!snap.exists()) return "missing";
    await updateDoc(ref, {
      lastHeartbeatAt: Timestamp.now(),
    });
    return "ok";
  } catch (err) {
    if (isQueueDocAccessDenied(err)) return "missing";
    if (isTransientFirebaseError(err)) return "transient";
    throw err;
  }
}

export async function queueEntryExists(db: Firestore, uid: string): Promise<boolean> {
  const snap = await getDoc(doc(db, "queue", uid));
  return snap.exists();
}

/** Server read for queue recovery (null = transient failure). */
export async function queueEntryExistsOnServer(
  db: Firestore,
  uid: string,
): Promise<boolean | null> {
  try {
    const snap = await getDocFromServer(doc(db, "queue", uid));
    return snap.exists();
  } catch {
    return null;
  }
}

export async function leaveQueue(db: Firestore, uid: string): Promise<void> {
  await deleteDoc(doc(db, "queue", uid));
}

export async function submitMoveDirect(
  db: Firestore,
  matchId: string,
  roundNumber: number,
  uid: string,
  choice: Move,
): Promise<void> {
  const choiceRef = doc(
    db,
    "matches",
    matchId,
    "rounds",
    String(roundNumber),
    "choices",
    uid,
  );
  const existing = await getDoc(choiceRef);
  if (existing.exists()) return;
  await setDoc(choiceRef, { choice, submittedAt: serverTimestamp() });
}
