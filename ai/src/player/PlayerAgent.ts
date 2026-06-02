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
  private queueTimer: ReturnType<typeof setInterval> | null = null;
  /** One pick pipeline at a time (LLM + submit); never overlap across rounds. */
  private pickInProgress = false;

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
          void this.joinQueue();
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
    this.stopQueueHeartbeat();
    this.matchUnsub?.();
    this.userUnsub?.();
    const uid = this.ctx.user.uid;
    await leaveQueue(this.ctx.db, uid).catch(() => {});
  }

  private async joinQueue(): Promise<void> {
    if (this.phase !== "idle") return;
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
    this.queueTimer = setInterval(() => {
      void sendQueueHeartbeat(this.ctx.db, uid)
        .then((ok) => {
          if (!ok) {
            log("[queue-heartbeat] queue doc gone, stopping heartbeat");
            this.stopQueueHeartbeat();
          }
        })
        .catch((e) => {
          warn("[queue-heartbeat]", e);
          this.stopQueueHeartbeat();
        });
    }, this.ctx.config.queueIntervalMs);
  }

  private stopQueueHeartbeat(): void {
    if (this.queueTimer) {
      clearInterval(this.queueTimer);
      this.queueTimer = null;
    }
  }

  private detachMatch(): void {
    this.matchUnsub?.();
    this.matchUnsub = null;
    this.activeMatchId = null;
    this.pickInProgress = false;
    if (this.phase === "lobby" || this.phase === "active") {
      this.phase = "idle";
    }
  }

  private attachMatch(matchId: string): void {
    if (this.activeMatchId === matchId && this.matchUnsub) return;
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
      await this.onMatchEnded(match);
      this.detachMatch();
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
    const profile = opp ? await getUserProfile(this.ctx.db, opp) : null;
    const dbCtx = await buildMatchDbContext(
      this.db,
      uid,
      opp ?? "",
      oppName,
      match,
      profile,
    );
    const description = await describeMatchWithLlm(match, uid, dbCtx);
    this.db.saveConcluded(match, description);
    log(`[match-end] ${msSince(endedStartedAt)}ms ${description}`);
  }
}
