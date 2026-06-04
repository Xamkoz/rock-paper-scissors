import * as admin from "firebase-admin";
import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onDocumentUpdated } from "firebase-functions/v2/firestore";
import { setGlobalOptions } from "firebase-functions/v2";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { defineSecret } from "firebase-functions/params";
import {
  calculateMatchElo,
  isValidMove,
  MatchMode,
  Move,
  parseMatchMode,
  parseMatchModes,
  pickSharedMatchMode,
  resolveRound,
  seriesOutcomeAfterRound,
  countRoundWins,
  matchResolutionForWinner,
  type MatchResolution,
} from "./game";
import { isQueueEntryActive, QUEUE_STALE_MS, shouldDropQueueForLiveMatch } from "./queue";
import { ONLINE_PRESENCE_WINDOW_MS } from "./presence";
import {
  applyClockIncrement,
  clockExpiry,
  initialClockFields,
  player1HasSubmitted,
  playerSubmittedInRound,
  player2HasSubmitted,
  tickClocks,
} from "./clockControl";
import {
  computeMoveMs,
  existingMoveMs,
  MoveTimingSlot,
  ROUND_TIMEOUT_MS,
  withMoveMs,
} from "./moveTiming";
import {
  guestCleanupAlreadyCompleted,
  markGuestCleanupComplete,
  runZeroMatchGuestCleanup,
} from "./guestCleanup";
import {
  leaderboardBackfillAlreadyCompleted,
  leaderboardVisibleAfterMatch,
  markLeaderboardBackfillComplete,
  runLeaderboardVisibilityBackfill,
} from "./leaderboardVisibility";
import {
  markProfileStatsBackfillComplete,
  profileStatsBackfillAlreadyCompleted,
  runProfileMatchStatsBackfill,
} from "./profileStatsBackfill";
import { findHighlightedMatchId } from "./highlightedMatch";

admin.initializeApp();

const guestCleanupSecret = defineSecret("GUEST_CLEANUP_SECRET");

/** All Cloud Functions deploy to the same region as Firestore triggers (not us-central1). */
setGlobalOptions({ region: "europe-west1" });

const db = getFirestore();
db.settings({ ignoreUndefinedProperties: true });

const ELO_WINDOW = 300;

interface RoundDoc {
  roundNumber: number;
  /** Set when player1 locks in; actual move stays in choices subcollection until resolve. */
  player1Submitted?: boolean;
  player2Submitted?: boolean;
  player1Choice?: Move;
  player2Choice?: Move;
  winner?: string;
  resolvedAt?: Timestamp;
  startedAt?: Timestamp;
  deadline?: Timestamp;
  /** Ms from round start until each player locked in a move. */
  player1MoveMs?: number;
  player2MoveMs?: number;
  /** Set when throwsRock/Paper/Scissors have been incremented for this round. */
  throwStatsRecorded?: boolean;
  /** Set when roundsWon/Lost/Draw have been incremented for this round. */
  roundStatsRecorded?: boolean;
  endReason?: RoundEndReason;
}

type RoundEndReason = "normal" | "round_timeout" | "clock_timeout" | "cancelled";

const PLAYED_ROUND_END_REASON: RoundEndReason = "normal";
const ROUND_TIMEOUT_END_REASON: RoundEndReason = "round_timeout";
const CLOCK_TIMEOUT_END_REASON: RoundEndReason = "clock_timeout";
const CANCELLED_ROUND_END_REASON: RoundEndReason = "cancelled";
const LOBBY_READY_MS = 20_000;

interface MatchDoc {
  player1: string;
  player2: string;
  player1Name: string;
  player2Name: string;
  matchMode: MatchMode;
  status: "lobby" | "active" | "completed" | "abandoned";
  player1Ready?: boolean;
  player2Ready?: boolean;
  /** Lobby closes when both players have not readied by this time. */
  readyDeadlineAt?: Timestamp;
  currentRound: number;
  player1Wins: number;
  player2Wins: number;
  rounds: RoundDoc[];
  /** Sum of per-round move times (ms) for each player in this match. */
  player1MoveTimeMs?: number;
  player2MoveTimeMs?: number;
  player1MoveCount?: number;
  player2MoveCount?: number;
  /** Remaining match thinking time (ms); runs until move is submitted each round. */
  player1ClockMs?: number;
  player2ClockMs?: number;
  clocksUpdatedAt?: Timestamp;
  winnerId?: string;
  /** Absolute match outcome for UI and queries. */
  resolution?: MatchResolution;
  /** Why the match ended: normal series, round deadline forfeit, or match clock forfeit. */
  endReason?: "normal" | "round_timeout" | "clock_timeout";
  player1EloDelta?: number;
  player2EloDelta?: number;
  player1Elo?: number;
  player2Elo?: number;
  createdAt: Timestamp;
  lastActivityAt: Timestamp;
}

interface RecordedMoveTiming {
  uid: string;
  slot: MoveTimingSlot;
  ms: number;
}

const THROW_FIELDS: Record<Move, "throwsRock" | "throwsPaper" | "throwsScissors"> = {
  ROCK: "throwsRock",
  PAPER: "throwsPaper",
  SCISSORS: "throwsScissors",
};

function moveTimingSlot(match: MatchDoc, uid: string): MoveTimingSlot | null {
  if (uid === match.player1) return "player1";
  if (uid === match.player2) return "player2";
  return null;
}

/** Stamp per-round move ms and return match/user increment deltas (once per player per round). */
function recordMoveTiming(
  round: RoundDoc,
  match: MatchDoc,
  uid: string,
  now: Timestamp,
): { round: RoundDoc; timing: RecordedMoveTiming } | null {
  const slot = moveTimingSlot(match, uid);
  if (!slot || existingMoveMs(round, slot) != null) return null;
  const ms = computeMoveMs(round, now, now);
  return {
    round: withMoveMs(round, slot, ms) as RoundDoc,
    timing: { uid, slot, ms },
  };
}

function matchTimingIncrements(timings: RecordedMoveTiming[]): Record<string, FirebaseFirestore.FieldValue> {
  const out: Record<string, FirebaseFirestore.FieldValue> = {};
  for (const t of timings) {
    if (t.slot === "player1") {
      out.player1MoveTimeMs = FieldValue.increment(t.ms);
      out.player1MoveCount = FieldValue.increment(1);
    } else {
      out.player2MoveTimeMs = FieldValue.increment(t.ms);
      out.player2MoveCount = FieldValue.increment(1);
    }
  }
  return out;
}

function applyRecordedTimingsToTransaction(
  tx: FirebaseFirestore.Transaction,
  timings: RecordedMoveTiming[],
): void {
  for (const t of timings) {
    tx.update(db.collection("users").doc(t.uid), {
      moveTimeMs: FieldValue.increment(t.ms),
      moveCount: FieldValue.increment(1),
      lastSeen: FieldValue.serverTimestamp(),
    });
  }
}

async function recordMoveThrown(uid: string, move: Move): Promise<void> {
  await db.collection("users").doc(uid).update({
    [THROW_FIELDS[move]]: FieldValue.increment(1),
    lastSeen: FieldValue.serverTimestamp(),
  });
}

/** Record R/P/S counts once per resolved round (guarded by throwStatsRecorded). */
async function recordRoundMoveThrows(match: MatchDoc, round: RoundDoc): Promise<void> {
  if (round.throwStatsRecorded) return;
  const tasks: Promise<void>[] = [];
  if (round.player1Choice) {
    tasks.push(recordMoveThrown(match.player1, round.player1Choice));
  }
  if (round.player2Choice) {
    tasks.push(recordMoveThrown(match.player2, round.player2Choice));
  }
  if (tasks.length > 0) {
    await Promise.all(tasks);
  }
}

async function recordRoundOutcomeStats(
  player1: string,
  player2: string,
  winner: string,
): Promise<void> {
  const bump = (uid: string, patch: Record<string, FirebaseFirestore.FieldValue>) =>
    db.collection("users").doc(uid).update({
      ...patch,
      lastSeen: FieldValue.serverTimestamp(),
    });

  if (winner === "tie") {
    await Promise.all([
      bump(player1, { roundsDraw: FieldValue.increment(1) }),
      bump(player2, { roundsDraw: FieldValue.increment(1) }),
    ]);
    return;
  }

  const loser = winner === player1 ? player2 : player1;
  await Promise.all([
    bump(winner, { roundsWon: FieldValue.increment(1) }),
    bump(loser, { roundsLost: FieldValue.increment(1) }),
  ]);
}

/** Throw counts + per-player round W/L/D; marks stats recorded on returned round. */
async function recordRoundResolutionStats(
  match: MatchDoc,
  round: RoundDoc,
  winner: string,
): Promise<RoundDoc> {
  if (!round.throwStatsRecorded) {
    await recordRoundMoveThrows(match, round);
  }
  if (!round.roundStatsRecorded) {
    await recordRoundOutcomeStats(match.player1, match.player2, winner);
  }
  return {
    ...round,
    throwStatsRecorded: true,
    roundStatsRecorded: true,
  };
}

function profileFromSnapshot(
  uid: string,
  snap: FirebaseFirestore.DocumentSnapshot,
  queueData?: Record<string, unknown>,
) {
  return {
    uid,
    displayName: (snap.get("displayName") as string)
      ?? (queueData?.displayName as string)
      ?? "Player",
    elo: (snap.get("elo") as number) ?? (queueData?.elo as number) ?? 1000,
    activeMatchId: snap.get("activeMatchId") as string | undefined,
  };
}

async function getUserProfile(uid: string, queueData?: Record<string, unknown>) {
  const snap = await db.collection("users").doc(uid).get();
  if (!snap.exists) {
    return {
      uid,
      displayName: (queueData?.displayName as string) ?? "Player",
      elo: (queueData?.elo as number) ?? 1000,
      activeMatchId: undefined,
    };
  }
  return profileFromSnapshot(uid, snap, queueData);
}

async function createMatch(
  playerA: string,
  playerB: string,
  matchMode: MatchMode,
): Promise<string | null> {
  return db.runTransaction(async (transaction) => {
    const queueARef = db.collection("queue").doc(playerA);
    const queueBRef = db.collection("queue").doc(playerB);
    const queueASnap = await transaction.get(queueARef);
    const queueBSnap = await transaction.get(queueBRef);
    if (!queueASnap.exists || !queueBSnap.exists) return null;
    const queueAData = queueASnap.data()!;
    const queueBData = queueBSnap.data()!;
    if (!isQueueEntryActive(queueAData) || !isQueueEntryActive(queueBData)) return null;

    const userARef = db.collection("users").doc(playerA);
    const userBRef = db.collection("users").doc(playerB);
    const userASnap = await transaction.get(userARef);
    const userBSnap = await transaction.get(userBRef);
    const userA = userASnap.exists
      ? profileFromSnapshot(playerA, userASnap, queueAData)
      : {
        uid: playerA,
        displayName: (queueAData.displayName as string) ?? "Player",
        elo: (queueAData.elo as number) ?? 1000,
      };
    const userB = userBSnap.exists
      ? profileFromSnapshot(playerB, userBSnap, queueBData)
      : {
        uid: playerB,
        displayName: (queueBData.displayName as string) ?? "Player",
        elo: (queueBData.elo as number) ?? 1000,
      };

    const now = Timestamp.now();
    const matchRef = db.collection("matches").doc();
    const match: MatchDoc = {
      player1: playerA,
      player2: playerB,
      player1Name: userA.displayName,
      player2Name: userB.displayName,
      matchMode,
      player1Elo: userA.elo,
      player2Elo: userB.elo,
      status: "lobby",
      player1Ready: false,
      player2Ready: false,
      currentRound: 1,
      player1Wins: 0,
      player2Wins: 0,
      player1MoveTimeMs: 0,
      player2MoveTimeMs: 0,
      player1MoveCount: 0,
      player2MoveCount: 0,
      ...initialClockFields(now),
      rounds: [{ roundNumber: 1 }],
      readyDeadlineAt: Timestamp.fromMillis(now.toMillis() + LOBBY_READY_MS),
      createdAt: now,
      lastActivityAt: now,
    };

    transaction.set(matchRef, match);
    transaction.delete(queueARef);
    transaction.delete(queueBRef);
    transaction.set(userARef, {
      activeMatchId: matchRef.id,
      lastSeen: FieldValue.serverTimestamp(),
    }, { merge: true });
    transaction.set(userBRef, {
      activeMatchId: matchRef.id,
      lastSeen: FieldValue.serverTimestamp(),
    }, { merge: true });
    return matchRef.id;
  });
}

async function tryMatch(uid: string, elo: number, matchModes: MatchMode[]): Promise<string | null> {
  const queueSnap = await db.collection("queue").orderBy("joinedAt", "asc").get();
  for (const doc of queueSnap.docs) {
    if (!isQueueEntryActive(doc.data())) continue;
    const otherId = doc.id;
    if (otherId === uid) continue;
    const otherModes = parseMatchModes(doc.get("matchModes"), doc.get("matchMode"));
    const sharedMode = pickSharedMatchMode(matchModes, otherModes);
    if (!sharedMode) continue;
    const otherElo = (doc.get("elo") as number) ?? 1000;
    if (Math.abs(otherElo - elo) > ELO_WINDOW) continue;

    const [myQueue, theirQueue] = await Promise.all([
      db.collection("queue").doc(uid).get(),
      db.collection("queue").doc(otherId).get(),
    ]);
    if (!myQueue.exists || !theirQueue.exists) continue;
    if (!isQueueEntryActive(myQueue.data()!) || !isQueueEntryActive(theirQueue.data()!)) {
      continue;
    }

    const matchId = await createMatch(uid, otherId, sharedMode);
    if (matchId) return matchId;
  }
  return null;
}

async function clearStaleActiveMatchIfNeeded(uid: string, activeMatchId: string): Promise<void> {
  const active = await db.collection("matches").doc(activeMatchId).get();
  const userRef = db.collection("users").doc(uid);
  if (!active.exists) {
    await userRef.set({ activeMatchId: FieldValue.delete() }, { merge: true });
    return;
  }
  const match = active.data() as MatchDoc;
  const status = match.status;
  const isParticipant = match.player1 === uid || match.player2 === uid;
  if (!isParticipant) {
    await userRef.set({ activeMatchId: FieldValue.delete() }, { merge: true });
    return;
  }
  if (status === "lobby" && isLobbyReadyExpired(match)) {
    await abandonLobbyMatch(active.ref, match);
    return;
  }
  if (status !== "active" && status !== "lobby") {
    await userRef.set({ activeMatchId: FieldValue.delete() }, { merge: true });
  }
}

async function attemptQueueMatch(uid: string, data: Record<string, unknown>): Promise<void> {
  const userRef = db.collection("users").doc(uid);
  // update() fails when the profile doc is missing (common right after Google sign-in).
  await userRef.set({ lastSeen: FieldValue.serverTimestamp() }, { merge: true });

  const profile = await getUserProfile(uid, data);

  if (profile.activeMatchId) {
    const active = await db.collection("matches").doc(profile.activeMatchId).get();
    if (active.exists) {
      const match = active.data() as MatchDoc;
      const status = match.status;
      const player1 = match.player1;
      const player2 = match.player2;
      if (status === "lobby" && isLobbyReadyExpired(match)) {
        await abandonLobbyMatch(active.ref, match);
      } else if (shouldDropQueueForLiveMatch(uid, status, player1, player2)) {
        // Late queue join/heartbeat after pairing — keep the live match, drop stray queue doc.
        await db.collection("queue").doc(uid).delete();
        return;
      } else {
        await clearStaleActiveMatchIfNeeded(uid, profile.activeMatchId);
      }
    } else {
      await clearStaleActiveMatchIfNeeded(uid, profile.activeMatchId);
    }
  }

  if (!isQueueEntryActive(data)) return;
  const elo = (data.elo as number) ?? profile.elo;
  const matchModes = parseMatchModes(data.matchModes, data.matchMode);
  await tryMatch(uid, elo, matchModes);
}

function getOpenRound(match: MatchDoc): RoundDoc | undefined {
  return [...match.rounds].reverse().find((round) => !round.resolvedAt);
}

function roundClockState(round: RoundDoc) {
  return {
    player1Submitted: round.player1Submitted,
    player2Submitted: round.player2Submitted,
    player1Choice: round.player1Choice,
    player2Choice: round.player2Choice,
  };
}

async function loadRoundChoices(
  matchId: string,
  match: MatchDoc,
  roundNumber: number,
  round: RoundDoc,
): Promise<{ p1Choice?: Move; p2Choice?: Move }> {
  const choicesSnap = await db
    .collection("matches")
    .doc(matchId)
    .collection("rounds")
    .doc(String(roundNumber))
    .collection("choices")
    .get();

  let p1Choice = round.player1Choice;
  let p2Choice = round.player2Choice;
  for (const doc of choicesSnap.docs) {
    const choice = doc.get("choice") as string;
    if (!isValidMove(choice)) continue;
    if (doc.id === match.player1) p1Choice = choice as Move;
    else if (doc.id === match.player2) p2Choice = choice as Move;
  }
  return { p1Choice, p2Choice };
}

/** Both submission flags set but choice docs missing (legacy blind-play bug) — reopen the round. */
async function resetCorruptedOpenRound(
  matchRef: FirebaseFirestore.DocumentReference,
  match: MatchDoc,
  rounds: RoundDoc[],
  roundIndex: number,
  round: RoundDoc,
): Promise<void> {
  const now = Timestamp.now();
  const ticked = tickClocks(
    match,
    { player1Submitted: false, player2Submitted: false },
    now,
  );
  rounds[roundIndex] = sanitizeRound({
    roundNumber: round.roundNumber,
    startedAt: now,
    deadline: Timestamp.fromMillis(now.toMillis() + ROUND_TIMEOUT_MS),
  });
  await matchRef.update({
    rounds: sanitizeRounds(rounds),
    player1ClockMs: ticked.player1ClockMs,
    player2ClockMs: ticked.player2ClockMs,
    clocksUpdatedAt: ticked.clocksUpdatedAt,
    lastActivityAt: FieldValue.serverTimestamp(),
  });
  await clearChoicesForRound(matchRef.id, round.roundNumber);
}

/** Firestore rejects undefined field values; strip them from round payloads. */
function sanitizeRound(round: RoundDoc): RoundDoc {
  const clean: RoundDoc = { roundNumber: round.roundNumber };
  const resolved = !!round.resolvedAt;
  if (resolved) {
    if (round.player1Choice) clean.player1Choice = round.player1Choice;
    if (round.player2Choice) clean.player2Choice = round.player2Choice;
  } else {
    if (round.player1Submitted) clean.player1Submitted = true;
    if (round.player2Submitted) clean.player2Submitted = true;
  }
  if (round.winner) clean.winner = round.winner;
  if (round.resolvedAt) clean.resolvedAt = round.resolvedAt;
  if (round.startedAt) clean.startedAt = round.startedAt;
  if (round.deadline) clean.deadline = round.deadline;
  if (round.player1MoveMs != null) clean.player1MoveMs = round.player1MoveMs;
  if (round.player2MoveMs != null) clean.player2MoveMs = round.player2MoveMs;
  if (round.throwStatsRecorded) clean.throwStatsRecorded = true;
  if (round.roundStatsRecorded) clean.roundStatsRecorded = true;
  if (round.endReason) clean.endReason = round.endReason;
  return clean;
}

function sanitizeRounds(rounds: RoundDoc[]): RoundDoc[] {
  return rounds.map(sanitizeRound);
}

async function abandonLobbyMatch(
  matchRef: FirebaseFirestore.DocumentReference,
  match: MatchDoc,
): Promise<void> {
  const batch = db.batch();
  batch.update(matchRef, {
    status: "abandoned",
    resolution: "abandoned",
    lastActivityAt: FieldValue.serverTimestamp(),
  });
  batch.update(db.collection("users").doc(match.player1), { activeMatchId: FieldValue.delete() });
  batch.update(db.collection("users").doc(match.player2), { activeMatchId: FieldValue.delete() });
  await batch.commit();
}

function isLobbyReadyExpired(match: MatchDoc, nowMs: number = Date.now()): boolean {
  const deadlineMs = match.readyDeadlineAt?.toMillis() ?? 0;
  return deadlineMs > 0 && nowMs > deadlineMs;
}

async function abandonLobbyIfExpired(matchId: string): Promise<"ok" | "abandoned" | "missing"> {
  const matchRef = db.collection("matches").doc(matchId);
  const snap = await matchRef.get();
  if (!snap.exists) return "missing";
  const match = snap.data() as MatchDoc;
  if (match.status !== "lobby") return "ok";
  if (!isLobbyReadyExpired(match)) return "ok";
  await abandonLobbyMatch(matchRef, match);
  return "abandoned";
}

async function abandonMatch(
  matchRef: FirebaseFirestore.DocumentReference,
  match: MatchDoc,
  rounds: RoundDoc[],
  roundIndex: number,
  now: Timestamp,
): Promise<void> {
  rounds[roundIndex] = sanitizeRound({
    ...rounds[roundIndex],
    resolvedAt: now,
    endReason: CANCELLED_ROUND_END_REASON,
  });
  const batch = db.batch();
  batch.update(matchRef, {
    status: "abandoned",
    resolution: "abandoned",
    rounds: sanitizeRounds(rounds),
    lastActivityAt: FieldValue.serverTimestamp(),
  });
  batch.update(db.collection("users").doc(match.player1), { activeMatchId: FieldValue.delete() });
  batch.update(db.collection("users").doc(match.player2), { activeMatchId: FieldValue.delete() });
  await batch.commit();
}

type MatchEndReason = "normal" | "round_timeout" | "clock_timeout";

async function finalizeMatch(
  matchRef: FirebaseFirestore.DocumentReference,
  match: MatchDoc,
  winnerId: string,
  options?: { forfeit?: boolean; endReason?: MatchEndReason },
): Promise<void> {
  const endReason: MatchEndReason = options?.endReason ?? "normal";
  const player1Wins = countRoundWins(match.rounds, match.player1);
  const player2Wins = countRoundWins(match.rounds, match.player2);
  const p1Score = winnerId === match.player1 ? 1 : 0;

  await db.runTransaction(async (tx) => {
    const matchSnap = await tx.get(matchRef);
    if (!matchSnap.exists) return;
    const live = matchSnap.data() as MatchDoc;
    if (live.status !== "active") return;

    const p1Ref = db.collection("users").doc(match.player1);
    const p2Ref = db.collection("users").doc(match.player2);
    const [p1Snap, p2Snap] = await Promise.all([tx.get(p1Ref), tx.get(p2Ref)]);

    const profileP1Elo = (p1Snap.get("elo") as number) ?? 1000;
    const profileP2Elo = (p2Snap.get("elo") as number) ?? 1000;
    const p1Elo = match.player1Elo ?? profileP1Elo;
    const p2Elo = match.player2Elo ?? profileP2Elo;
    const elo = calculateMatchElo(p1Elo, p2Elo, p1Score, {
      matchMode: parseMatchMode(match.matchMode),
      endReason,
      winnerId,
      player1: match.player1,
      player2: match.player2,
      player1Wins,
      player2Wins,
    });

    tx.update(matchRef, {
      status: "completed",
      winnerId,
      endReason,
      resolution: matchResolutionForWinner(match.player1, winnerId),
      player1Wins,
      player2Wins,
      rounds: sanitizeRounds(match.rounds),
      player1Elo: p1Elo,
      player2Elo: p2Elo,
      player1EloDelta: elo.deltaA,
      player2EloDelta: elo.deltaB,
      lastActivityAt: FieldValue.serverTimestamp(),
    });

    tx.update(p1Ref, {
      elo: p1Elo + elo.deltaA,
      wins: FieldValue.increment(winnerId === match.player1 ? 1 : 0),
      losses: FieldValue.increment(winnerId === match.player1 ? 0 : 1),
      activeMatchId: FieldValue.delete(),
      lastSeen: FieldValue.serverTimestamp(),
      leaderboardVisible: leaderboardVisibleAfterMatch(
        p1Snap.data() as Record<string, unknown>,
        winnerId === match.player1 ? 1 : 0,
        winnerId === match.player1 ? 0 : 1,
        0,
      ),
    });
    tx.update(p2Ref, {
      elo: p2Elo + elo.deltaB,
      wins: FieldValue.increment(winnerId === match.player2 ? 1 : 0),
      losses: FieldValue.increment(winnerId === match.player2 ? 0 : 1),
      activeMatchId: FieldValue.delete(),
      lastSeen: FieldValue.serverTimestamp(),
      leaderboardVisible: leaderboardVisibleAfterMatch(
        p2Snap.data() as Record<string, unknown>,
        winnerId === match.player2 ? 1 : 0,
        winnerId === match.player2 ? 0 : 1,
        0,
      ),
    });
  });
}

async function finalizeMatchDraw(
  matchRef: FirebaseFirestore.DocumentReference,
  match: MatchDoc,
  player1Wins: number,
  player2Wins: number,
  rounds: RoundDoc[],
): Promise<void> {
  await db.runTransaction(async (tx) => {
    const matchSnap = await tx.get(matchRef);
    if (!matchSnap.exists) return;
    const live = matchSnap.data() as MatchDoc;
    if (live.status !== "active") return;

    const p1Ref = db.collection("users").doc(match.player1);
    const p2Ref = db.collection("users").doc(match.player2);
    const [p1Snap, p2Snap] = await Promise.all([tx.get(p1Ref), tx.get(p2Ref)]);

    const p1Elo = match.player1Elo ?? (p1Snap.get("elo") as number) ?? 1000;
    const p2Elo = match.player2Elo ?? (p2Snap.get("elo") as number) ?? 1000;

    tx.update(matchRef, {
      status: "completed",
      winnerId: FieldValue.delete(),
      endReason: PLAYED_ROUND_END_REASON,
      resolution: "draw",
      player1Wins,
      player2Wins,
      rounds: sanitizeRounds(rounds),
      player1Elo: p1Elo,
      player2Elo: p2Elo,
      player1EloDelta: 0,
      player2EloDelta: 0,
      lastActivityAt: FieldValue.serverTimestamp(),
    });
    tx.update(p1Ref, {
      draws: FieldValue.increment(1),
      activeMatchId: FieldValue.delete(),
      lastSeen: FieldValue.serverTimestamp(),
      leaderboardVisible: leaderboardVisibleAfterMatch(
        p1Snap.data() as Record<string, unknown>,
        0,
        0,
        1,
      ),
    });
    tx.update(p2Ref, {
      draws: FieldValue.increment(1),
      activeMatchId: FieldValue.delete(),
      lastSeen: FieldValue.serverTimestamp(),
      leaderboardVisible: leaderboardVisibleAfterMatch(
        p2Snap.data() as Record<string, unknown>,
        0,
        0,
        1,
      ),
    });
  });
}

async function applySeriesOutcome(
  matchRef: FirebaseFirestore.DocumentReference,
  match: MatchDoc,
  rounds: RoundDoc[],
  roundIndex: number,
  round: RoundDoc,
  player1Wins: number,
  player2Wins: number,
  now: Timestamp,
  p1Choice: Move,
  p2Choice: Move,
  winner: string,
): Promise<boolean> {
  const mode = parseMatchMode(match.matchMode);
  const outcome = seriesOutcomeAfterRound(mode, player1Wins, player2Wins, round.roundNumber);

  rounds[roundIndex] = sanitizeRound({
    ...round,
    winner,
    resolvedAt: now,
    player1Choice: p1Choice,
    player2Choice: p2Choice,
    throwStatsRecorded: true,
    roundStatsRecorded: true,
    endReason: PLAYED_ROUND_END_REASON,
  });

  if (outcome.kind === "winner") {
    const winnerId = outcome.player === "player1" ? match.player1 : match.player2;
    await finalizeMatch(
      matchRef,
      { ...match, rounds: sanitizeRounds(rounds), player1Wins, player2Wins },
      winnerId,
    );
    return true;
  }
  if (outcome.kind === "draw") {
    await finalizeMatchDraw(matchRef, match, player1Wins, player2Wins, sanitizeRounds(rounds));
    return true;
  }
  return false;
}

async function persistClocks(
  matchRef: FirebaseFirestore.DocumentReference,
  clocks: ReturnType<typeof tickClocks>,
): Promise<void> {
  await matchRef.update({
    player1ClockMs: clocks.player1ClockMs,
    player2ClockMs: clocks.player2ClockMs,
    clocksUpdatedAt: clocks.clocksUpdatedAt,
    lastActivityAt: FieldValue.serverTimestamp(),
  });
}

async function resolveRoundIfReady(
  matchRef: FirebaseFirestore.DocumentReference,
  match: MatchDoc,
  options?: { forceDeadline?: boolean },
): Promise<void> {
  const liveSnap = await matchRef.get();
  if (!liveSnap.exists) return;
  match = liveSnap.data() as MatchDoc;
  if (match.status !== "active") return;

  const round = getOpenRound(match);
  if (!round) return;

  const now = Timestamp.now();
  const clockRound = roundClockState(round);
  const ticked = tickClocks(match, clockRound, now);
  match = { ...match, ...ticked };

  const { p1Choice, p2Choice } = await loadRoundChoices(matchRef.id, match, round.roundNumber, round);
  const p1Submitted = player1HasSubmitted(round) || !!p1Choice;
  const p2Submitted = player2HasSubmitted(round) || !!p2Choice;
  const deadlinePassed =
    options?.forceDeadline === true ||
    (round.deadline ? round.deadline.toMillis() <= now.toMillis() : false);

  const rounds = [...match.rounds];
  const roundIndex = rounds.findIndex((r) => r.roundNumber === round.roundNumber && !r.resolvedAt);
  if (roundIndex < 0) return;

  const expiry = clockExpiry(ticked.player1ClockMs, ticked.player2ClockMs, clockRound);
  if (expiry === "player1") {
    const winner = match.player2;
    const pendingRound: RoundDoc = {
      ...round,
      ...(p2Choice ? { player2Choice: p2Choice } : {}),
      winner,
      resolvedAt: now,
      endReason: CLOCK_TIMEOUT_END_REASON,
    };
    const resolvedRound = await recordRoundResolutionStats(match, pendingRound, winner);
    rounds[roundIndex] = sanitizeRound(resolvedRound);
    await finalizeMatch(
      matchRef,
      { ...match, rounds: sanitizeRounds(rounds) },
      match.player2,
      { forfeit: true, endReason: "clock_timeout" },
    );
    return;
  }
  if (expiry === "player2") {
    const winner = match.player1;
    const pendingRound: RoundDoc = {
      ...round,
      ...(p1Choice ? { player1Choice: p1Choice } : {}),
      winner,
      resolvedAt: now,
      endReason: CLOCK_TIMEOUT_END_REASON,
    };
    const resolvedRound = await recordRoundResolutionStats(match, pendingRound, winner);
    rounds[roundIndex] = sanitizeRound(resolvedRound);
    await finalizeMatch(
      matchRef,
      { ...match, rounds: sanitizeRounds(rounds) },
      match.player1,
      { forfeit: true, endReason: "clock_timeout" },
    );
    return;
  }
  if (expiry === "both") {
    if (p1Submitted && !p2Submitted) {
      const winner = match.player1;
      const pendingRound: RoundDoc = {
        ...round,
        ...(p1Choice ? { player1Choice: p1Choice } : {}),
        winner,
        resolvedAt: now,
        endReason: CLOCK_TIMEOUT_END_REASON,
      };
      const resolvedRound = await recordRoundResolutionStats(match, pendingRound, winner);
      rounds[roundIndex] = sanitizeRound(resolvedRound);
      await finalizeMatch(
        matchRef,
        { ...match, rounds: sanitizeRounds(rounds) },
        match.player1,
        { forfeit: true, endReason: "clock_timeout" },
      );
      return;
    }
    if (!p1Submitted && p2Submitted) {
      const winner = match.player2;
      const pendingRound: RoundDoc = {
        ...round,
        player2Choice: p2Choice,
        winner,
        resolvedAt: now,
        endReason: CLOCK_TIMEOUT_END_REASON,
      };
      const resolvedRound = await recordRoundResolutionStats(match, pendingRound, winner);
      rounds[roundIndex] = sanitizeRound(resolvedRound);
      await finalizeMatch(
        matchRef,
        { ...match, rounds: sanitizeRounds(rounds) },
        match.player2,
        { forfeit: true, endReason: "clock_timeout" },
      );
      return;
    }
    await abandonMatch(matchRef, match, rounds, roundIndex, now);
    return;
  }

  // Both players submitted — resolve normally.
  if (p1Submitted && p2Submitted) {
    // fall through to winner resolution below
  } else if (deadlinePassed) {
    // Late player forfeits the entire series (not just the round).
    if (p1Submitted && !p2Submitted) {
      const winner = match.player1;
      const pendingRound: RoundDoc = {
        ...round,
        ...(p1Choice ? { player1Choice: p1Choice } : {}),
        winner,
        resolvedAt: now,
        endReason: ROUND_TIMEOUT_END_REASON,
      };
      const resolvedRound = await recordRoundResolutionStats(match, pendingRound, winner);
      rounds[roundIndex] = sanitizeRound(resolvedRound);
      await finalizeMatch(
        matchRef,
        { ...match, rounds: sanitizeRounds(rounds) },
        match.player1,
        { forfeit: true, endReason: "round_timeout" },
      );
      return;
    }
    if (!p1Submitted && p2Submitted) {
      const winner = match.player2;
      const pendingRound: RoundDoc = {
        ...round,
        player2Choice: p2Choice,
        winner,
        resolvedAt: now,
        endReason: ROUND_TIMEOUT_END_REASON,
      };
      const resolvedRound = await recordRoundResolutionStats(match, pendingRound, winner);
      rounds[roundIndex] = sanitizeRound(resolvedRound);
      await finalizeMatch(
        matchRef,
        { ...match, rounds: sanitizeRounds(rounds) },
        match.player2,
        { forfeit: true, endReason: "round_timeout" },
      );
      return;
    }
    // Neither player submitted in time — cancel the match (no Elo change).
    await abandonMatch(matchRef, match, rounds, roundIndex, now);
    return;
  } else {
    await persistClocks(matchRef, ticked);
    return;
  }

  let resolvedP1Choice = p1Choice;
  let resolvedP2Choice = p2Choice;
  if (p1Submitted && p2Submitted && (!resolvedP1Choice || !resolvedP2Choice)) {
    await syncPendingChoicesFromSubcollection(matchRef.id, round.roundNumber);
    const reloaded = await loadRoundChoices(matchRef.id, match, round.roundNumber, round);
    resolvedP1Choice = reloaded.p1Choice;
    resolvedP2Choice = reloaded.p2Choice;
  }

  if (!resolvedP1Choice || !resolvedP2Choice) {
    if (p1Submitted && p2Submitted) {
      await resetCorruptedOpenRound(matchRef, match, rounds, roundIndex, round);
      return;
    }
    await persistClocks(matchRef, ticked);
    return;
  }

  let winner: string;
  const result = resolveRound(resolvedP1Choice, resolvedP2Choice);
  if (result === "tie") {
    winner = "tie";
  } else if (result === "player1") {
    winner = match.player1;
  } else {
    winner = match.player2;
  }

  const pendingRound: RoundDoc = {
    ...round,
    player1Choice: resolvedP1Choice,
    player2Choice: resolvedP2Choice,
    winner,
    resolvedAt: now,
    endReason: PLAYED_ROUND_END_REASON,
  };
  const resolvedRound = await recordRoundResolutionStats(match, pendingRound, winner);

  let player1Wins = match.player1Wins;
  let player2Wins = match.player2Wins;
  if (winner === match.player1) player1Wins += 1;
  if (winner === match.player2) player2Wins += 1;

  if (
    await applySeriesOutcome(
      matchRef,
      match,
      rounds,
      roundIndex,
      round,
      player1Wins,
      player2Wins,
      now,
      resolvedP1Choice,
      resolvedP2Choice,
      winner,
    )
  ) {
    await clearChoicesForRound(matchRef.id, round.roundNumber);
    return;
  }

  const nextRoundNumber = match.currentRound + 1;
  const nextDeadline = Timestamp.fromMillis(now.toMillis() + ROUND_TIMEOUT_MS);
  const incremented = applyClockIncrement(ticked.player1ClockMs, ticked.player2ClockMs);
  rounds[roundIndex] = sanitizeRound(resolvedRound);
  rounds.push({ roundNumber: nextRoundNumber, deadline: nextDeadline, startedAt: now });
  await matchRef.update({
    rounds: sanitizeRounds(rounds),
    currentRound: nextRoundNumber,
    player1Wins,
    player2Wins,
    player1ClockMs: incremented.player1ClockMs,
    player2ClockMs: incremented.player2ClockMs,
    clocksUpdatedAt: now,
    lastActivityAt: FieldValue.serverTimestamp(),
  });
  await clearChoicesForRound(matchRef.id, round.roundNumber);
}

/** Client writes queue/{uid}; this trigger runs matchmaking (no Callable / Cloud Run IAM). */
export const onQueueEntry = onDocumentCreated(
  { document: "queue/{userId}" },
  async (event) => {
    const uid = event.params.userId;
    const data = event.data?.data();
    if (!data) return;
    await attemptQueueMatch(uid, data as Record<string, unknown>);
  },
);

/** Retry matchmaking when queued players update heartbeat/modes while waiting. */
export const onQueueEntryUpdated = onDocumentUpdated(
  { document: "queue/{userId}" },
  async (event) => {
    const uid = event.params.userId;
    const after = event.data?.after.data();
    if (!after) return;
    await attemptQueueMatch(uid, after as Record<string, unknown>);
  },
);

async function applyPlayerChoice(
  matchId: string,
  uid: string,
  choice: string,
  roundNumber: number,
) {
  if (!isValidMove(choice)) return;

  const matchRef = db.collection("matches").doc(matchId);

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(matchRef);
    if (!snap.exists) return;

    const match = snap.data() as MatchDoc;
    if (match.status !== "active") return;
    if (uid !== match.player1 && uid !== match.player2) return;

    const rounds = [...match.rounds];
    const idx = rounds.findIndex((r) => r.roundNumber === roundNumber && !r.resolvedAt);
    if (idx < 0) return;

    const now = Timestamp.now();
    let current = { ...rounds[idx] };
    const timings: RecordedMoveTiming[] = [];
    const ticked = tickClocks(match, roundClockState(current), now);

    if (uid === match.player1) {
      if (player1HasSubmitted(current)) return;
      const stamped = recordMoveTiming(current, match, uid, now);
      if (stamped) {
        current = stamped.round;
        timings.push(stamped.timing);
      }
      current.player1Submitted = true;
      delete current.player1Choice;
    } else {
      if (player2HasSubmitted(current)) return;
      const stamped = recordMoveTiming(current, match, uid, now);
      if (stamped) {
        current = stamped.round;
        timings.push(stamped.timing);
      }
      current.player2Submitted = true;
      delete current.player2Choice;
    }

    rounds[idx] = sanitizeRound(current);
    tx.update(matchRef, {
      rounds: sanitizeRounds(rounds),
      player1ClockMs: ticked.player1ClockMs,
      player2ClockMs: ticked.player2ClockMs,
      clocksUpdatedAt: ticked.clocksUpdatedAt,
      lastActivityAt: FieldValue.serverTimestamp(),
      ...matchTimingIncrements(timings),
    });
    applyRecordedTimingsToTransaction(tx, timings);
  });

  const updated = await matchRef.get();
  if (updated.exists) {
    await resolveRoundIfReady(matchRef, updated.data() as MatchDoc);
    const after = await matchRef.get();
    if (after.exists) {
      await clearChoicesIfRoundClosed(matchId, roundNumber, after.data() as MatchDoc);
    }
  }
}

async function clearChoicesForRound(matchId: string, roundNumber: number) {
  const choices = await db
    .collection("matches")
    .doc(matchId)
    .collection("rounds")
    .doc(String(roundNumber))
    .collection("choices")
    .get();
  const batch = db.batch();
  choices.docs.forEach((doc) => batch.delete(doc.ref));
  await batch.commit();
}

/** Choice subcollections are only needed while a round is open; drop them once it advances. */
async function clearChoicesIfRoundClosed(
  matchId: string,
  roundNumber: number,
  match: MatchDoc,
): Promise<void> {
  const open = getOpenRound(match);
  if (!open || open.roundNumber !== roundNumber) {
    await clearChoicesForRound(matchId, roundNumber);
  }
}

/**
 * Applies any pending choice docs still in the subcollection (trigger may lag).
 * Open-round moves live in subcollections; the match doc exposes submission flags only.
 */
async function syncPendingChoicesFromSubcollection(
  matchId: string,
  roundNumber: number,
): Promise<void> {
  const choicesSnap = await db
    .collection("matches")
    .doc(matchId)
    .collection("rounds")
    .doc(String(roundNumber))
    .collection("choices")
    .get();

  for (const doc of choicesSnap.docs) {
    const choice = doc.get("choice") as string;
    if (!isValidMove(choice)) continue;
    await applyPlayerChoice(matchId, doc.id, choice, roundNumber);
  }
}

async function loadActiveMatch(matchId: string): Promise<{ ref: FirebaseFirestore.DocumentReference; match: MatchDoc } | null> {
  const matchRef = db.collection("matches").doc(matchId);
  const snap = await matchRef.get();
  if (!snap.exists) return null;
  const match = snap.data() as MatchDoc;
  if (match.status !== "active") return null;
  return { ref: matchRef, match };
}

/** Resolve an open round when the deadline passed (scheduler backstop). */
async function resolveExpiredOpenRound(matchId: string, match: MatchDoc): Promise<void> {
  const open = getOpenRound(match);
  if (!open?.deadline) return;
  if (open.deadline.toMillis() > Timestamp.now().toMillis()) return;

  await syncPendingChoicesFromSubcollection(matchId, open.roundNumber);
  const refreshed = await db.collection("matches").doc(matchId).get();
  if (!refreshed.exists) return;
  const updated = refreshed.data() as MatchDoc;
  await resolveRoundIfReady(db.collection("matches").doc(matchId), updated, {
    forceDeadline: true,
  });
  const after = await db.collection("matches").doc(matchId).get();
  if (!after.exists) return;
  const afterMatch = after.data() as MatchDoc;
  await clearChoicesIfRoundClosed(matchId, open.roundNumber, afterMatch);
}

/** Client writes when the round timer hits zero; resolves immediately (scheduler is backup). */
export const onRoundTimeout = onDocumentCreated(
  {
    document: "matches/{matchId}/rounds/{roundNumber}/timeoutRequests/{requestId}",
  },
  async (event) => {
    const matchId = event.params.matchId;
    const roundNumber = parseInt(event.params.roundNumber, 10);
    if (Number.isNaN(roundNumber)) return;

    const loaded = await loadActiveMatch(matchId);
    if (!loaded) return;

    await syncPendingChoicesFromSubcollection(matchId, roundNumber);
    const refreshed = await loaded.ref.get();
    if (!refreshed.exists) return;

    await resolveRoundIfReady(loaded.ref, refreshed.data() as MatchDoc, { forceDeadline: true });

    const after = await loaded.ref.get();
    if (!after.exists) return;
    const match = after.data() as MatchDoc;
    await clearChoicesIfRoundClosed(matchId, roundNumber, match);
  },
);

/** Client writes matches/{id}/rounds/{round}/choices/{uid}; server applies move to the match doc. */
export const onPlayerChoice = onDocumentCreated(
  {
    document: "matches/{matchId}/rounds/{roundNumber}/choices/{userId}",
  },
  async (event) => {
    const matchId = event.params.matchId;
    const uid = event.params.userId;
    const roundNumber = parseInt(event.params.roundNumber, 10);
    const snap = event.data;
    if (!snap || Number.isNaN(roundNumber)) return;

    const choice = snap.get("choice") as string;
    await applyPlayerChoice(matchId, uid, choice, roundNumber);
  },
);

/** Backstop when no client writes a timeout request (offline / crashed app). */
export const resolveTimedOutRounds = onSchedule(
  { schedule: "every 1 minutes" },
  async () => {
    const activeMatches = await db.collection("matches")
      .where("status", "==", "active")
      .get();

    for (const doc of activeMatches.docs) {
      await resolveExpiredOpenRound(doc.id, doc.data() as MatchDoc);
    }
  },
);

/** Applies a move server-side (choice subcollection docs are deleted after processing). */
export const submitMatchMove = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Sign in required.");
  }

  const matchId = request.data?.matchId;
  const roundNumber = request.data?.roundNumber;
  const choice = request.data?.choice;
  if (typeof matchId !== "string" || !matchId) {
    throw new HttpsError("invalid-argument", "matchId is required.");
  }
  if (typeof roundNumber !== "number" || !Number.isFinite(roundNumber)) {
    throw new HttpsError("invalid-argument", "roundNumber is required.");
  }
  if (!isValidMove(choice)) {
    throw new HttpsError("invalid-argument", "choice must be ROCK, PAPER, or SCISSORS.");
  }

  const matchSnap = await db.collection("matches").doc(matchId).get();
  if (!matchSnap.exists) {
    throw new HttpsError("not-found", "Match not found.");
  }
  const match = matchSnap.data() as MatchDoc;
  if (match.status !== "active") {
    throw new HttpsError("failed-precondition", "Match is not active.");
  }
  if (uid !== match.player1 && uid !== match.player2) {
    throw new HttpsError("permission-denied", "You are not in this match.");
  }
  const openRound = getOpenRound(match);
  if (!openRound || openRound.roundNumber !== roundNumber) {
    throw new HttpsError("failed-precondition", "This round is no longer open.");
  }

  const choiceRef = db.collection("matches")
    .doc(matchId)
    .collection("rounds")
    .doc(String(roundNumber))
    .collection("choices")
    .doc(uid);
  await choiceRef.set({
    choice,
    submittedAt: FieldValue.serverTimestamp(),
  });
  await applyPlayerChoice(matchId, uid, choice, roundNumber);

  const updated = await db.collection("matches").doc(matchId).get();
  if (!updated.exists) {
    throw new HttpsError("internal", "Move was not recorded. Try again.");
  }
  let matchAfter = updated.data() as MatchDoc;
  let recorded = playerSubmittedInRound(matchAfter, roundNumber, uid);
  if (!recorded) {
    await syncPendingChoicesFromSubcollection(matchId, roundNumber);
    const retried = await db.collection("matches").doc(matchId).get();
    if (retried.exists) {
      matchAfter = retried.data() as MatchDoc;
      recorded = playerSubmittedInRound(matchAfter, roundNumber, uid);
    }
  }
  if (!recorded) {
    throw new HttpsError("internal", "Move was not recorded. Try again.");
  }

  return { ok: true };
});

/** Server-side queue join (admin write) for clients that cannot confirm Firestore queue writes. */
export const joinMatchmakingQueue = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Sign in required.");
  }

  const rawModes = request.data?.matchModes;
  if (!Array.isArray(rawModes) || rawModes.length === 0) {
    throw new HttpsError("invalid-argument", "Select at least one match mode.");
  }
  const matchModes = parseMatchModes(rawModes);
  if (matchModes.length === 0) {
    throw new HttpsError("invalid-argument", "Invalid match modes.");
  }

  const userSnap = await db.collection("users").doc(uid).get();
  const activeMatchId = userSnap.get("activeMatchId") as string | undefined;
  if (activeMatchId) {
    const active = await db.collection("matches").doc(activeMatchId).get();
    if (active.exists) {
      const match = active.data() as MatchDoc;
      const status = match.status;
      const player1 = match.player1;
      const player2 = match.player2;
      if (status === "lobby" && isLobbyReadyExpired(match)) {
        await abandonLobbyMatch(active.ref, match);
      } else if (shouldDropQueueForLiveMatch(uid, status, player1, player2)) {
        await db.collection("queue").doc(uid).delete();
        return {
          activeMatchId,
          clientJoinedAtMs: Date.now(),
        };
      } else {
        await clearStaleActiveMatchIfNeeded(uid, activeMatchId);
      }
    } else {
      await clearStaleActiveMatchIfNeeded(uid, activeMatchId);
    }
  }

  const displayName =
    (typeof request.data?.displayName === "string" && request.data.displayName.trim())
      ? request.data.displayName.trim()
      : (userSnap.get("displayName") as string | undefined)
        ?? "Player";
  const elo =
    typeof request.data?.elo === "number"
      ? request.data.elo
      : (userSnap.get("elo") as number | undefined)
        ?? 1000;

  const now = Timestamp.now();
  const clientJoinedAtMs = Date.now();
  const queueData = {
    joinedAt: now,
    lastHeartbeatAt: now,
    clientJoinedAt: clientJoinedAtMs,
    elo,
    displayName,
    matchModes,
  };

  await db.collection("queue").doc(uid).set(queueData);
  // Return immediately; onQueueEntry / onQueueEntryUpdated run matchmaking.
  void attemptQueueMatch(uid, queueData as Record<string, unknown>).catch((err) => {
    console.error("joinMatchmakingQueue matchmaking failed", uid, err);
  });

  return {
    clientJoinedAtMs,
    joinedAtMs: now.toMillis(),
  };
});

/** Marks a participant ready; starts the match when both players have confirmed. */
export const confirmMatchReady = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Sign in required.");
  }
  const matchId = request.data?.matchId;
  if (typeof matchId !== "string" || !matchId.trim()) {
    throw new HttpsError("invalid-argument", "matchId is required.");
  }

  const matchRef = db.collection("matches").doc(matchId);
  const preSnap = await matchRef.get();
  if (!preSnap.exists) {
    throw new HttpsError("not-found", "Match not found.");
  }
  const preMatch = preSnap.data() as MatchDoc;
  if (preMatch.status === "active") {
    return { ok: true, started: true };
  }
  if (preMatch.status !== "lobby") {
    throw new HttpsError("failed-precondition", "Match is not waiting for players.");
  }
  if (uid !== preMatch.player1 && uid !== preMatch.player2) {
    throw new HttpsError("permission-denied", "Not a participant.");
  }

  const expired = await abandonLobbyIfExpired(matchId);
  if (expired === "abandoned" || expired === "missing") {
    throw new HttpsError("failed-precondition", "Ready deadline passed.");
  }

  const started = await db.runTransaction(async (tx) => {
    const snap = await tx.get(matchRef);
    if (!snap.exists) return false;
    const match = snap.data() as MatchDoc;
    if (match.status === "active") return true;
    if (match.status !== "lobby") return false;
    if (uid !== match.player1 && uid !== match.player2) return false;
    if (isLobbyReadyExpired(match)) return false;

    const player1Ready = uid === match.player1 ? true : !!match.player1Ready;
    const player2Ready = uid === match.player2 ? true : !!match.player2Ready;

    if (player1Ready && player2Ready) {
      const now = Timestamp.now();
      const rounds = [...match.rounds];
      const firstIdx = rounds.findIndex((round) => round.roundNumber === 1);
      const firstRound: RoundDoc = firstIdx >= 0
        ? { ...rounds[firstIdx] }
        : { roundNumber: 1 };
      firstRound.startedAt = now;
      firstRound.deadline = Timestamp.fromMillis(now.toMillis() + ROUND_TIMEOUT_MS);
      if (firstIdx >= 0) {
        rounds[firstIdx] = sanitizeRound(firstRound);
      } else {
        rounds.unshift(sanitizeRound(firstRound));
      }

      tx.update(matchRef, {
        status: "active",
        player1Ready: true,
        player2Ready: true,
        rounds: sanitizeRounds(rounds),
        lastActivityAt: FieldValue.serverTimestamp(),
      });
      return true;
    }

    const readyUpdate: Record<string, unknown> = {
      lastActivityAt: FieldValue.serverTimestamp(),
    };
    if (uid === match.player1) readyUpdate.player1Ready = true;
    else readyUpdate.player2Ready = true;
    tx.update(matchRef, readyUpdate);
    return false;
  });

  if (!started) {
    const after = await matchRef.get();
    if (after.exists) {
      const afterMatch = after.data() as MatchDoc;
      if (afterMatch.status === "lobby" && isLobbyReadyExpired(afterMatch)) {
        await abandonLobbyMatch(matchRef, afterMatch);
        throw new HttpsError("failed-precondition", "Ready deadline passed.");
      }
    }
  }

  return { ok: true, started };
});

/** Lightweight RTT probe for in-app connection meter (requires auth). */
export const ping = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Sign in required.");
  }
  return { serverTimeMs: Date.now() };
});

/** Best positive ELO gain this week for the signed-in player (rolling 7-day window). */
export const getHighlightedMatch = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Sign in required.");
  }
  const windowStartMs = (request.data as { windowStartMs?: number } | undefined)?.windowStartMs;
  if (typeof windowStartMs !== "number" || !Number.isFinite(windowStartMs) || windowStartMs <= 0) {
    throw new HttpsError("invalid-argument", "windowStartMs is required.");
  }
  const matchId = await findHighlightedMatchId(db, uid, windowStartMs);
  return { matchId };
});

/** Server-authoritative presence heartbeat (Admin SDK — reliable for Google sign-in). */
export const touchPresence = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Sign in required.");
  }
  const includeOnlineCount =
    (request.data as { includeOnlineCount?: boolean } | undefined)?.includeOnlineCount === true;
  const now = FieldValue.serverTimestamp();
  await Promise.all([
    db.collection("presence").doc(uid).set({ lastSeen: now }),
    db.collection("users").doc(uid).set({ lastSeen: now }, { merge: true }),
  ]);
  const serverTimeMs = Date.now();
  if (!includeOnlineCount) {
    return { ok: true, serverTimeMs };
  }
  const presenceCutoff = Timestamp.fromMillis(serverTimeMs - ONLINE_PRESENCE_WINDOW_MS);
  const onlineSnap = await db.collection("presence")
    .where("lastSeen", ">=", presenceCutoff)
    .get();
  return { ok: true, serverTimeMs, onlineCount: onlineSnap.size };
});

export const cleanupStale = onSchedule(
  { schedule: "every 5 minutes" },
  async () => {
  const cutoff = Timestamp.fromMillis(Date.now() - QUEUE_STALE_MS);

  const staleByHeartbeat = await db.collection("queue")
    .where("lastHeartbeatAt", "<", cutoff)
    .get();
  const staleByJoin = await db.collection("queue")
    .where("joinedAt", "<", cutoff)
    .get();

  const queueBatch = db.batch();
  const staleQueueIds = new Set<string>();
  staleByHeartbeat.docs.forEach((doc) => staleQueueIds.add(doc.id));
  staleByJoin.docs.forEach((doc) => {
    if (doc.get("lastHeartbeatAt") == null) staleQueueIds.add(doc.id);
  });
  staleQueueIds.forEach((id) => queueBatch.delete(db.collection("queue").doc(id)));
  await queueBatch.commit();

  const lobbyCutoff = Timestamp.now();
  const expiredLobbies = await db.collection("matches")
    .where("status", "==", "lobby")
    .where("readyDeadlineAt", "<", lobbyCutoff)
    .get();

  for (const doc of expiredLobbies.docs) {
    const match = doc.data() as MatchDoc;
    const batch = db.batch();
    batch.update(doc.ref, { status: "abandoned", resolution: "abandoned" });
    batch.update(db.collection("users").doc(match.player1), { activeMatchId: FieldValue.delete() });
    batch.update(db.collection("users").doc(match.player2), { activeMatchId: FieldValue.delete() });
    await batch.commit();
  }

  const staleLobbies = await db.collection("matches")
    .where("status", "==", "lobby")
    .where("lastActivityAt", "<", Timestamp.fromMillis(Date.now() - 120_000))
    .get();

  for (const doc of staleLobbies.docs) {
    if (doc.get("readyDeadlineAt") != null) continue;
    const match = doc.data() as MatchDoc;
    const batch = db.batch();
    batch.update(doc.ref, { status: "abandoned" });
    batch.update(db.collection("users").doc(match.player1), { activeMatchId: FieldValue.delete() });
    batch.update(db.collection("users").doc(match.player2), { activeMatchId: FieldValue.delete() });
    await batch.commit();
  }

  const staleMatches = await db.collection("matches")
    .where("status", "==", "active")
    .where("lastActivityAt", "<", cutoff)
    .get();

  for (const doc of staleMatches.docs) {
    const match = doc.data() as MatchDoc;
    const batch = db.batch();
    batch.update(doc.ref, { status: "abandoned" });
    batch.update(db.collection("users").doc(match.player1), { activeMatchId: FieldValue.delete() });
    batch.update(db.collection("users").doc(match.player2), { activeMatchId: FieldValue.delete() });
    await batch.commit();
  }

  const presenceCutoff = Timestamp.fromMillis(Date.now() - 3 * 60 * 1000);
  const stalePresence = await db.collection("presence")
    .where("lastSeen", "<", presenceCutoff)
    .get();
  if (!stalePresence.empty) {
    const presenceBatch = db.batch();
    stalePresence.docs.forEach((doc) => presenceBatch.delete(doc.ref));
    await presenceBatch.commit();
  }
  },
);

/**
 * One-time admin cleanup: delete anonymous guests who never played a match.
 * Set GUEST_CLEANUP_SECRET in Functions config before invoking.
 */
export const cleanupZeroMatchGuests = onCall(
  { timeoutSeconds: 540, memory: "512MiB", secrets: [guestCleanupSecret] },
  async (request) => {
    const configuredSecret = guestCleanupSecret.value()?.trim();
    const providedSecret = typeof request.data?.secret === "string"
      ? request.data.secret.trim()
      : "";
    if (!configuredSecret || providedSecret !== configuredSecret) {
      throw new HttpsError("permission-denied", "Invalid cleanup secret.");
    }

    const dryRun = request.data?.dryRun !== false;
    const force = request.data?.force === true;
    if (!dryRun && !force && await guestCleanupAlreadyCompleted(db)) {
      throw new HttpsError(
        "failed-precondition",
        "Guest cleanup already completed. Pass force: true to run again.",
      );
    }

    const minAgeHours = Number(request.data?.minAgeHours ?? 1);
    const minAgeMs = Number.isFinite(minAgeHours) && minAgeHours >= 0
      ? minAgeHours * 60 * 60 * 1000
      : 60 * 60 * 1000;

    const summary = await runZeroMatchGuestCleanup({
      db,
      dryRun,
      minAgeMs,
    });

    if (!dryRun) {
      await markGuestCleanupComplete(db, summary);
    }

    return summary;
  },
);

/**
 * One-time backfill: set isGuest and leaderboardVisible on existing user profiles.
 * Uses the same secret as cleanupZeroMatchGuests.
 */
export const backfillLeaderboardVisibility = onCall(
  { timeoutSeconds: 540, memory: "512MiB", secrets: [guestCleanupSecret] },
  async (request) => {
    const configuredSecret = guestCleanupSecret.value()?.trim();
    const providedSecret = typeof request.data?.secret === "string"
      ? request.data.secret.trim()
      : "";
    if (!configuredSecret || providedSecret !== configuredSecret) {
      throw new HttpsError("permission-denied", "Invalid cleanup secret.");
    }

    const dryRun = request.data?.dryRun !== false;
    const force = request.data?.force === true;
    if (!dryRun && !force && await leaderboardBackfillAlreadyCompleted(db)) {
      throw new HttpsError(
        "failed-precondition",
        "Leaderboard visibility backfill already completed. Pass force: true to run again.",
      );
    }

    const summary = await runLeaderboardVisibilityBackfill(db, dryRun);
    if (!dryRun) {
      await markLeaderboardBackfillComplete(db, summary);
    }
    return summary;
  },
);

/**
 * One-time backfill: recompute wins/losses/draws and round stats from completed matches.
 * Uses the same secret as cleanupZeroMatchGuests.
 */
export const backfillProfileMatchStats = onCall(
  { timeoutSeconds: 540, memory: "512MiB", secrets: [guestCleanupSecret] },
  async (request) => {
    const configuredSecret = guestCleanupSecret.value()?.trim();
    const providedSecret = typeof request.data?.secret === "string"
      ? request.data.secret.trim()
      : "";
    if (!configuredSecret || providedSecret !== configuredSecret) {
      throw new HttpsError("permission-denied", "Invalid cleanup secret.");
    }

    const dryRun = request.data?.dryRun !== false;
    const force = request.data?.force === true;
    if (!dryRun && !force && await profileStatsBackfillAlreadyCompleted(db)) {
      throw new HttpsError(
        "failed-precondition",
        "Profile match stats backfill already completed. Pass force: true to run again.",
      );
    }

    const summary = await runProfileMatchStatsBackfill(db, dryRun);
    if (!dryRun) {
      await markProfileStatsBackfillComplete(db, summary);
    }
    return summary;
  },
);
