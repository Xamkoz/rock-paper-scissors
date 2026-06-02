import {
  doc,
  onSnapshot,
  type Unsubscribe,
} from "firebase/firestore";
import type { FirebaseContext } from "../firebase/client.js";
import {
  confirmMatchReady,
  joinMatchmakingQueue,
} from "../firebase/callables.js";
import {
  ensureUserProfile,
  getActiveMatchId,
  getMatch,
  getUserProfile,
  leaveQueue,
  queueEntryExists,
  sendQueueHeartbeat,
  writeQueueEntry,
} from "../firebase/firestoreApi.js";
import { loadConcludedMatchForArchive } from "../firebase/matchArchive.js";
import { submitRoundMove } from "../firebase/submitRoundMove.js";
import {
  isParticipant,
  matchFromSnapshot,
  openRoundNeedsMove,
  opponentId,
  selfSubmitted,
} from "../firebase/matchDoc.js";
import type { MatchDatabase } from "../db/matchDatabase.js";
import { buildMatchDbContext } from "../llm/matchContext.js";
import { pickMoveWithLlm } from "../llm/pickMove.js";
import { pickMoveContextLimits } from "../llm/movePrompt.js";
import { pickTimeBudgetMs } from "../llm/compactMatch.js";
import { describeMatchWithLlm } from "../llm/describeMatch.js";
import { error, log, msSince, warn } from "../log.js";
import type { Match } from "../types.js";

type AgentPhase = "idle" | "queued" | "lobby" | "active";

export class PlayerAgent {
  private phase: AgentPhase = "idle";
  private activeMatchId: string | null = null;
  private matchUnsub: Unsubscribe | null = null;
  private userUnsub: Unsubscribe | null = null;
  private queueHeartbeatTimer: ReturnType<typeof setTimeout> | null = null;
  private queueHeartbeatStopped = true;
  private requeueTimer: ReturnType<typeof setTimeout> | null = null;
  /** One pick pipeline at a time (LLM + submit); never overlap across rounds. */
  private pickInProgress = false;
  private matchEndHandled: string | null = null;

  constructor(
    private readonly ctx: FirebaseContext,
    private readonly db: MatchDatabase,
  ) {}

  async start(): Promise<void> {
    const uid = this.ctx.user.uid;
    await ensureUserProfile(this.ctx.db, uid, this.ctx.config.botDisplayName);

    this.userUnsub = onSnapshot(doc(this.ctx.db, "users", uid), (snap) => {
      const matchId = snap.get("activeMatchId") as string | undefined;
      if (matchId?.trim()) {
        this.attachMatch(matchId.trim());
      } else if (this.phase !== "queued") {
        this.detachMatch();
        if (this.ctx.config.autoQueue && this.phase === "idle") {
          this.scheduleRequeue();
        }
      }
    });

    const existing = await getActiveMatchId(this.ctx.db, uid);
    if (existing) {
      this.attachMatch(existing);
    } else if (this.ctx.config.autoQueue) {
      await this.joinQueue();
    }
  }

  async stop(): Promise<void> {
    this.cancelRequeue();
    this.stopQueueHeartbeat();
    this.matchUnsub?.();
    this.userUnsub?.();
    const uid = this.ctx.user.uid;
    await leaveQueue(this.ctx.db, uid).catch(() => {});
  }

  private cancelRequeue(): void {
    if (this.requeueTimer) {
      clearTimeout(this.requeueTimer);
      this.requeueTimer = null;
    }
  }

  /** Delay before auto-queue when idle after a game (not used on initial boot). */
  private scheduleRequeue(): void {
    this.cancelRequeue();
    const delayMs = this.ctx.config.requeueDelayMs;
    if (delayMs <= 0) {
      void this.joinQueue();
      return;
    }
    log(`[queue] re-queue in ${delayMs}ms`);
    this.requeueTimer = setTimeout(() => {
      this.requeueTimer = null;
      if (
        this.ctx.config.autoQueue &&
        this.phase === "idle" &&
        !this.activeMatchId
      ) {
        void this.joinQueue();
      }
    }, delayMs);
  }

  private async joinQueue(): Promise<void> {
    if (this.phase !== "idle") return;
    this.cancelRequeue();
    const uid = this.ctx.user.uid;
    const profile = await ensureUserProfile(
      this.ctx.db,
      uid,
      this.ctx.config.botDisplayName,
    );

    this.phase = "queued";
    try {
      const result = await joinMatchmakingQueue(this.ctx.functions, {
        matchModes: this.ctx.config.matchModes,
        displayName: profile.displayName,
        elo: profile.elo,
      });
      if (result.activeMatchId) {
        this.phase = "idle";
        this.attachMatch(result.activeMatchId);
        return;
      }
    } catch (err) {
      warn("[queue] callable failed, direct write:", err);
      await writeQueueEntry(
        this.ctx.db,
        uid,
        profile,
        this.ctx.config.matchModes,
      );
    }

    const inQueue = await queueEntryExists(this.ctx.db, uid);
    if (!inQueue) {
      warn("[queue] not in queue after join — check Auth, App Check, and Firestore rules");
      this.phase = "idle";
      return;
    }
    this.startQueueHeartbeat(uid);
  }

  private startQueueHeartbeat(uid: string): void {
    this.stopQueueHeartbeat();
    this.queueHeartbeatStopped = false;
    const intervalMs = this.ctx.config.queueIntervalMs;
    log(`[queue] heartbeat every ${intervalMs}ms`);

    const scheduleNext = () => {
      if (this.queueHeartbeatStopped || this.phase !== "queued") return;
      this.queueHeartbeatTimer = setTimeout(() => void beat(), intervalMs);
    };

    const beat = async () => {
      if (this.queueHeartbeatStopped || this.phase !== "queued") return;
      try {
        const ok = await sendQueueHeartbeat(this.ctx.db, uid);
        if (!ok) {
          log("[queue-heartbeat] queue doc gone, stopping heartbeat");
          this.stopQueueHeartbeat();
          return;
        }
      } catch (e) {
        warn("[queue-heartbeat]", e);
        this.stopQueueHeartbeat();
        return;
      }
      scheduleNext();
    };

    scheduleNext();
  }

  private stopQueueHeartbeat(): void {
    this.queueHeartbeatStopped = true;
    if (this.queueHeartbeatTimer) {
      clearTimeout(this.queueHeartbeatTimer);
      this.queueHeartbeatTimer = null;
    }
  }

  private detachMatch(): void {
    this.matchUnsub?.();
    this.matchUnsub = null;
    this.activeMatchId = null;
    this.pickInProgress = false;
    this.matchEndHandled = null;
    if (this.phase === "lobby" || this.phase === "active") {
      this.phase = "idle";
    }
  }

  private attachMatch(matchId: string): void {
    if (this.activeMatchId === matchId && this.matchUnsub) return;
    this.cancelRequeue();
    this.matchUnsub?.();
    this.activeMatchId = matchId;
    this.stopQueueHeartbeat();
    void leaveQueue(this.ctx.db, this.ctx.user.uid).catch(() => {});

    this.matchUnsub = onSnapshot(doc(this.ctx.db, "matches", matchId), (snap) => {
      if (!snap.exists()) return;
      const match = matchFromSnapshot(snap.id, snap.data());
      void this.onMatchUpdate(match).catch((err) => {
        error("[match]", err);
      });
    });
  }

  private async onMatchUpdate(match: Match): Promise<void> {
    const uid = this.ctx.user.uid;
    if (!isParticipant(match, uid)) return;

    if (match.status === "lobby") {
      this.phase = "lobby";
      const ready = match.player1 === uid ? match.player1Ready : match.player2Ready;
      if (!ready) {
        await confirmMatchReady(this.ctx.functions, match.id);
      }
      return;
    }

    if (match.status === "active") {
      this.phase = "active";
      if (!openRoundNeedsMove(match, uid)) return;
      if (this.pickInProgress) return;

      const opp = opponentId(match, uid);
      if (!opp) return;

      const roundNumber = match.currentRound;
      const matchId = match.id;
      this.pickInProgress = true;
      const moveStartedAt = Date.now();
      try {
        const oppName =
          match.player1 === opp ? match.player1Name : match.player2Name;
        const profile = await getUserProfile(this.ctx.db, opp);
        const contextStartedAt = Date.now();
        const dbCtx = await buildMatchDbContext(
          this.db,
          uid,
          opp,
          oppName,
          match,
          profile,
          pickMoveContextLimits,
        );
        const contextMs = msSince(contextStartedAt);
        const budgetMs = pickTimeBudgetMs(match);
        const { choice, pickMs } = await pickMoveWithLlm(match, dbCtx, budgetMs);
        const fresh = await getMatch(this.ctx.db, matchId);
        if (
          !fresh ||
          fresh.status !== "active" ||
          fresh.currentRound !== roundNumber ||
          selfSubmitted(fresh, uid)
        ) {
          warn(
            `[move] round ${roundNumber} stale after pick (${pickMs}ms) — skip submit (now r${fresh?.currentRound ?? "?"})`,
          );
          return;
        }
        const submitStartedAt = Date.now();
        const ok = await submitRoundMove(
          this.ctx.db,
          this.ctx.functions,
          fresh,
          uid,
          choice,
        );
        const submitMs = msSince(submitStartedAt);
        const totalMs = msSince(moveStartedAt);
        this.db.recordRoundTiming({
          matchId,
          roundNumber,
          choice,
          contextMs,
          pickMs,
          submitMs,
          totalMs,
          ok,
        });
        if (ok) {
          log(
            `[move] ${choice} round ${roundNumber} vs ${oppName} ${totalMs}ms (ctx=${contextMs} pick=${pickMs} submit=${submitMs})`,
          );
        }
      } catch (err) {
        const totalMs = msSince(moveStartedAt);
        this.db.recordRoundTiming({
          matchId,
          roundNumber,
          contextMs: 0,
          pickMs: 0,
          submitMs: 0,
          totalMs,
          ok: false,
        });
        warn(`[move] round ${roundNumber} failed ${totalMs}ms:`, err);
      } finally {
        this.pickInProgress = false;
      }
      return;
    }

    if (match.status === "completed" || match.status === "abandoned") {
      if (this.matchEndHandled === match.id) return;
      await this.waitForPickPipeline();
      if (this.matchEndHandled === match.id) return;
      this.matchEndHandled = match.id;
      await this.onMatchEnded(match);
      this.detachMatch();
    }
  }

  /** Let an in-flight rN pick finish before match-end work (avoids stale direct writes). */
  private async waitForPickPipeline(maxMs = 45_000): Promise<void> {
    const deadline = Date.now() + maxMs;
    while (this.pickInProgress && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 200));
    }
    if (this.pickInProgress) {
      warn("[match-end] pick pipeline still running — proceeding anyway");
    }
  }

  private async onMatchEnded(match: Match): Promise<void> {
    const endedStartedAt = Date.now();
    const uid = this.ctx.user.uid;
    const opp = opponentId(match, uid);
    const oppName = opp
      ? match.player1 === opp
        ? match.player1Name
        : match.player2Name
      : "opponent";

    const archived = await loadConcludedMatchForArchive(
      this.ctx.db,
      match.id,
      match,
    );
    const resolvedRounds = archived.rounds.filter(
      (r) => r.player1Choice && r.player2Choice,
    ).length;
    this.db.saveConcluded(archived);
    log(`[match-end] saved ${archived.id} (${resolvedRounds} rounds with throws)`);

    try {
      const profile = opp ? await getUserProfile(this.ctx.db, opp) : null;
      const dbCtx = await buildMatchDbContext(
        this.db,
        uid,
        opp ?? "",
        oppName,
        null,
        profile,
      );
      const description = await describeMatchWithLlm(archived, uid, dbCtx);
      this.db.saveConcluded(archived, description);
      log(`[match-end] ${msSince(endedStartedAt)}ms ${description}`);
    } catch (err) {
      warn(`[match-end] describe failed (${msSince(endedStartedAt)}ms), match archived`, err);
    }
  }
}
