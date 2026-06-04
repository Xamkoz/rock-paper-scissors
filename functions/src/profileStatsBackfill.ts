import { FieldPath, FieldValue, Firestore, QueryDocumentSnapshot } from "firebase-admin/firestore";
import { inferMatchResolution, isValidMove } from "./game";
import { computeLeaderboardVisible } from "./leaderboardVisibility";

export const PROFILE_STATS_BACKFILL_MAINTENANCE_DOC = "maintenance/profileMatchStatsBackfill";

export interface ProfileMatchStats {
  wins: number;
  losses: number;
  draws: number;
  roundsWon: number;
  roundsLost: number;
  roundsDraw: number;
  throwsRock: number;
  throwsPaper: number;
  throwsScissors: number;
  moveTimeMs: number;
  moveCount: number;
}

export interface MatchStatsInput {
  status?: string;
  player1?: string;
  player2?: string;
  winnerId?: string;
  player1Wins?: number;
  player2Wins?: number;
  resolution?: string;
  rounds?: Array<{
    resolvedAt?: unknown;
    winner?: string;
    endReason?: string;
    player1Submitted?: boolean;
    player2Submitted?: boolean;
    player1Choice?: string;
    player2Choice?: string;
    player1MoveMs?: number;
    player2MoveMs?: number;
  }>;
}

export interface ProfileMatchStatsBackfillSummary {
  matchesScanned: number;
  usersScanned: number;
  usersUpdated: number;
  mismatches: number;
  dryRun: boolean;
}

export function emptyProfileMatchStats(): ProfileMatchStats {
  return {
    wins: 0,
    losses: 0,
    draws: 0,
    roundsWon: 0,
    roundsLost: 0,
    roundsDraw: 0,
    throwsRock: 0,
    throwsPaper: 0,
    throwsScissors: 0,
    moveTimeMs: 0,
    moveCount: 0,
  };
}

function asInt(value: unknown): number {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
}

function bumpStats(
  map: Map<string, ProfileMatchStats>,
  uid: string,
  patch: Partial<ProfileMatchStats>,
): void {
  const current = map.get(uid) ?? emptyProfileMatchStats();
  map.set(uid, {
    wins: current.wins + (patch.wins ?? 0),
    losses: current.losses + (patch.losses ?? 0),
    draws: current.draws + (patch.draws ?? 0),
    roundsWon: current.roundsWon + (patch.roundsWon ?? 0),
    roundsLost: current.roundsLost + (patch.roundsLost ?? 0),
    roundsDraw: current.roundsDraw + (patch.roundsDraw ?? 0),
    throwsRock: current.throwsRock + (patch.throwsRock ?? 0),
    throwsPaper: current.throwsPaper + (patch.throwsPaper ?? 0),
    throwsScissors: current.throwsScissors + (patch.throwsScissors ?? 0),
    moveTimeMs: current.moveTimeMs + (patch.moveTimeMs ?? 0),
    moveCount: current.moveCount + (patch.moveCount ?? 0),
  });
}

function bumpThrow(
  map: Map<string, ProfileMatchStats>,
  uid: string,
  choice: string | undefined,
): void {
  if (!choice || !isValidMove(choice)) return;
  if (choice === "ROCK") bumpStats(map, uid, { throwsRock: 1 });
  if (choice === "PAPER") bumpStats(map, uid, { throwsPaper: 1 });
  if (choice === "SCISSORS") bumpStats(map, uid, { throwsScissors: 1 });
}

function bumpMoveStats(
  map: Map<string, ProfileMatchStats>,
  uid: string,
  round: NonNullable<MatchStatsInput["rounds"]>[number],
  slot: "player1" | "player2",
): void {
  const submitted = slot === "player1" ? round.player1Submitted : round.player2Submitted;
  const choice = slot === "player1" ? round.player1Choice : round.player2Choice;
  const moveMs = slot === "player1" ? round.player1MoveMs : round.player2MoveMs;
  if (!submitted && !choice) return;
  bumpStats(map, uid, {
    moveCount: 1,
    moveTimeMs: moveMs != null && moveMs > 0 ? moveMs : 0,
  });
}

/** Recompute per-user match stats from one completed match doc. */
export function accumulateProfileStatsFromMatch(
  map: Map<string, ProfileMatchStats>,
  match: MatchStatsInput,
): void {
  if (match.status !== "completed") return;
  const resolution = inferMatchResolution(match);
  if (!resolution || resolution === "abandoned") return;

  const player1 = match.player1;
  const player2 = match.player2;
  if (!player1 || !player2) return;

  if (resolution === "draw") {
    bumpStats(map, player1, { draws: 1 });
    bumpStats(map, player2, { draws: 1 });
  } else if (resolution === "player1_win") {
    bumpStats(map, player1, { wins: 1 });
    bumpStats(map, player2, { losses: 1 });
  } else if (resolution === "player2_win") {
    bumpStats(map, player2, { wins: 1 });
    bumpStats(map, player1, { losses: 1 });
  }

  for (const round of match.rounds ?? []) {
    if (round.resolvedAt == null) continue;
    if (round.endReason === "cancelled") continue;

    const winner = round.winner;
    if (winner === "tie") {
      bumpStats(map, player1, { roundsDraw: 1 });
      bumpStats(map, player2, { roundsDraw: 1 });
    } else if (winner === player1) {
      bumpStats(map, player1, { roundsWon: 1 });
      bumpStats(map, player2, { roundsLost: 1 });
    } else if (winner === player2) {
      bumpStats(map, player2, { roundsWon: 1 });
      bumpStats(map, player1, { roundsLost: 1 });
    }

    bumpThrow(map, player1, round.player1Choice);
    bumpThrow(map, player2, round.player2Choice);
    bumpMoveStats(map, player1, round, "player1");
    bumpMoveStats(map, player2, round, "player2");
  }
}

function statsMatchStored(
  stored: Record<string, unknown>,
  computed: ProfileMatchStats,
): boolean {
  return asInt(stored.wins) === computed.wins
    && asInt(stored.losses) === computed.losses
    && asInt(stored.draws) === computed.draws
    && asInt(stored.roundsWon) === computed.roundsWon
    && asInt(stored.roundsLost) === computed.roundsLost
    && asInt(stored.roundsDraw) === computed.roundsDraw
    && asInt(stored.throwsRock) === computed.throwsRock
    && asInt(stored.throwsPaper) === computed.throwsPaper
    && asInt(stored.throwsScissors) === computed.throwsScissors
    && asInt(stored.moveTimeMs) === computed.moveTimeMs
    && asInt(stored.moveCount) === computed.moveCount
    && stored.leaderboardVisible === computeLeaderboardVisible(
      stored,
      computed.wins,
      computed.losses,
      computed.draws,
    );
}

function profileStatsPatch(
  stored: Record<string, unknown>,
  computed: ProfileMatchStats,
): Record<string, number | boolean> | null {
  const patch: Record<string, number | boolean> = {};
  if (asInt(stored.wins) !== computed.wins) patch.wins = computed.wins;
  if (asInt(stored.losses) !== computed.losses) patch.losses = computed.losses;
  if (asInt(stored.draws) !== computed.draws) patch.draws = computed.draws;
  if (asInt(stored.roundsWon) !== computed.roundsWon) patch.roundsWon = computed.roundsWon;
  if (asInt(stored.roundsLost) !== computed.roundsLost) patch.roundsLost = computed.roundsLost;
  if (asInt(stored.roundsDraw) !== computed.roundsDraw) patch.roundsDraw = computed.roundsDraw;
  if (asInt(stored.throwsRock) !== computed.throwsRock) patch.throwsRock = computed.throwsRock;
  if (asInt(stored.throwsPaper) !== computed.throwsPaper) patch.throwsPaper = computed.throwsPaper;
  if (asInt(stored.throwsScissors) !== computed.throwsScissors) {
    patch.throwsScissors = computed.throwsScissors;
  }
  if (asInt(stored.moveTimeMs) !== computed.moveTimeMs) patch.moveTimeMs = computed.moveTimeMs;
  if (asInt(stored.moveCount) !== computed.moveCount) patch.moveCount = computed.moveCount;

  const leaderboardVisible = computeLeaderboardVisible(
    stored,
    computed.wins,
    computed.losses,
    computed.draws,
  );
  if (stored.leaderboardVisible !== leaderboardVisible) {
    patch.leaderboardVisible = leaderboardVisible;
  }

  return Object.keys(patch).length > 0 ? patch : null;
}

async function loadComputedStatsFromMatches(
  db: Firestore,
): Promise<{ computedByUid: Map<string, ProfileMatchStats>; matchesScanned: number }> {
  const computedByUid = new Map<string, ProfileMatchStats>();
  let matchesScanned = 0;
  let lastDoc: QueryDocumentSnapshot | undefined;

  while (true) {
    let query = db.collection("matches")
      .where("status", "==", "completed")
      .orderBy("lastActivityAt")
      .limit(500);
    if (lastDoc) query = query.startAfter(lastDoc);

    const snap = await query.get();
    if (snap.empty) break;

    for (const doc of snap.docs) {
      matchesScanned += 1;
      accumulateProfileStatsFromMatch(computedByUid, doc.data() as MatchStatsInput);
    }

    lastDoc = snap.docs[snap.docs.length - 1];
    if (snap.size < 500) break;
  }

  return { computedByUid, matchesScanned };
}

export async function runProfileMatchStatsBackfill(
  db: Firestore,
  dryRun: boolean,
): Promise<ProfileMatchStatsBackfillSummary> {
  const { computedByUid, matchesScanned } = await loadComputedStatsFromMatches(db);

  let usersScanned = 0;
  let usersUpdated = 0;
  let mismatches = 0;
  let batch = db.batch();
  let batchOps = 0;

  const commitBatch = async () => {
    if (dryRun || batchOps === 0) return;
    await batch.commit();
    batch = db.batch();
    batchOps = 0;
  };

  let lastDoc: QueryDocumentSnapshot | undefined;
  while (true) {
    let query = db.collection("users")
      .orderBy(FieldPath.documentId())
      .limit(500);
    if (lastDoc) query = query.startAfter(lastDoc);

    const snap = await query.get();
    if (snap.empty) break;

    for (const doc of snap.docs) {
      usersScanned += 1;
      const stored = doc.data() as Record<string, unknown>;
      const computed = computedByUid.get(doc.id) ?? emptyProfileMatchStats();
      if (statsMatchStored(stored, computed)) continue;

      mismatches += 1;
      const patch = profileStatsPatch(stored, computed);
      if (!patch) continue;

      usersUpdated += 1;
      if (!dryRun) {
        batch.update(doc.ref, patch);
        batchOps += 1;
        if (batchOps >= 400) await commitBatch();
      }
    }

    lastDoc = snap.docs[snap.docs.length - 1];
    if (snap.size < 500) break;
  }

  await commitBatch();

  return {
    matchesScanned,
    usersScanned,
    usersUpdated,
    mismatches,
    dryRun,
  };
}

export async function profileStatsBackfillAlreadyCompleted(db: Firestore): Promise<boolean> {
  const snap = await db.doc(PROFILE_STATS_BACKFILL_MAINTENANCE_DOC).get();
  return snap.exists && snap.get("completedAt") != null;
}

export async function markProfileStatsBackfillComplete(
  db: Firestore,
  summary: ProfileMatchStatsBackfillSummary,
): Promise<void> {
  await db.doc(PROFILE_STATS_BACKFILL_MAINTENANCE_DOC).set({
    completedAt: FieldValue.serverTimestamp(),
    matchesScanned: summary.matchesScanned,
    usersScanned: summary.usersScanned,
    usersUpdated: summary.usersUpdated,
    mismatches: summary.mismatches,
  });
}
