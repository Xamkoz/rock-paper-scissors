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
} from "../firebase/matchDoc.js";
import { MatchCache } from "../cache/matchCache.js";
import {
  analyzeMovePattern,
  matchesForOpponentAnalysis,
} from "../analysis/movePattern.js";
import { pickMove } from "../strategy/movePicker.js";
import { describeMatch } from "../narrative/matchDescription.js";
import type { Match } from "../types.js";

type AgentPhase = "idle" | "queued" | "lobby" | "active";

export class PlayerAgent {
  private phase: AgentPhase = "idle";
  private activeMatchId: string | null = null;
  private matchUnsub: Unsubscribe | null = null;
  private userUnsub: Unsubscribe | null = null;
  private queueTimer: ReturnType<typeof setInterval> | null = null;
  /** Prevents duplicate submits while the listener fires or callable is slow. */
  private moveInFlight: number | null = null;
  private trackedRound = 0;

  constructor(
    private readonly ctx: FirebaseContext,
    private readonly cache: MatchCache,
  ) {}

  async start(): Promise<void> {
    const uid = this.ctx.user.uid;
    await this.cache.load();
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
      console.warn("[queue] callable failed, direct write:", err);
      await writeQueueEntry(
        this.ctx.db,
        uid,
        profile,
        this.ctx.config.matchModes,
      );
    }

    const inQueue = await queueEntryExists(this.ctx.db, uid);
    if (!inQueue) {
      console.warn("[queue] not in queue after join — check Auth, App Check, and Firestore rules");
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
            console.log("[queue-heartbeat] queue doc gone, stopping heartbeat");
            this.stopQueueHeartbeat();
          }
        })
        .catch((e) => {
          console.warn("[queue-heartbeat]", e);
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
    this.moveInFlight = null;
    this.trackedRound = 0;
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
        console.error("[match]", err);
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
      if (match.currentRound !== this.trackedRound) {
        this.trackedRound = match.currentRound;
        this.moveInFlight = null;
      }
      if (!openRoundNeedsMove(match, uid)) return;
      if (this.moveInFlight === match.currentRound) return;

      const opp = opponentId(match, uid);
      if (!opp) return;

      const roundNumber = match.currentRound;
      this.moveInFlight = roundNumber;
      try {
        const pattern = await this.buildPattern(uid, opp, match);
        const choice = pickMove(pattern);
        const ok = await submitRoundMove(
          this.ctx.db,
          this.ctx.functions,
          match,
          uid,
          choice,
        );
        if (ok) {
          console.log(
            `[move] ${choice} round ${roundNumber} vs ${opp} (${pattern.counterMove} counters ${pattern.dominantMove})`,
          );
        }
      } catch (err) {
        console.warn(`[move] round ${roundNumber} failed:`, err);
      } finally {
        if (this.moveInFlight === roundNumber) {
          this.moveInFlight = null;
        }
      }
      return;
    }

    if (match.status === "completed" || match.status === "abandoned") {
      await this.onMatchEnded(match);
      this.detachMatch();
    }
  }

  private async buildPattern(
    selfUid: string,
    opponentUid: string,
    currentMatch?: Match,
  ) {
    const [cached, h2h] = await Promise.all([
      this.cache.getMatchesForUser(selfUid),
      this.cache.getHeadToHead(selfUid, opponentUid),
    ]);
    const matches = matchesForOpponentAnalysis(selfUid, opponentUid, cached, h2h);
    if (
      currentMatch &&
      currentMatch.status === "active" &&
      opponentId(currentMatch, selfUid) === opponentUid
    ) {
      matches.unshift(currentMatch);
    }
    const profile = await getUserProfile(this.ctx.db, opponentUid);
    return analyzeMovePattern(selfUid, opponentUid, matches, profile);
  }

  private async onMatchEnded(match: Match): Promise<void> {
    const uid = this.ctx.user.uid;
    const opp = opponentId(match, uid);
    const pattern = opp ? await this.buildPattern(uid, opp) : undefined;
    const description = describeMatch(match, uid, pattern);
    await this.cache.saveConcluded(match, description);
    console.log(`[match-end] ${description}`);
  }
}
