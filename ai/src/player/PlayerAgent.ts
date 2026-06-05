import {
  doc,
  onSnapshot,
  type Unsubscribe,
} from "firebase/firestore";
import type { FirebaseContext } from "../firebase/client.js";
import {
  confirmMatchReady,
  joinMatchmakingQueue,
  touchPresence,
} from "../firebase/callables.js";
import {
  ensureUserProfile,
  getActiveMatchId,
  getMatch,
  getUserProfile,
  leaveQueue,
  queueEntryExists,
  queueEntryExistsOnServer,
  sendQueueHeartbeat,
  writeQueueEntry,
} from "../firebase/firestoreApi.js";
import {
  QUEUE_HEARTBEAT_MAX_FAILURES,
  QUEUE_HEARTBEAT_MAX_TRANSIENT,
  QUEUE_HEARTBEAT_VERIFY_EVERY,
  QUEUE_RECOVER_DELAY_MS,
  SESSION_HEARTBEAT_INTERVAL_MS,
} from "./queueSession.js";
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
import { getLlmConfig, setActiveLlmModel } from "../llm/client.js";
import {
  getLlmModelRankings,
  selectLlmModelForMatch,
} from "../llm/llmModelRanking.js";
import {
  formatMovePickLogLine,
  isLlmPickTimeoutError,
  pickMoveDeterministic,
  pickMoveWithLlm,
} from "../llm/pickMove.js";
import { pickMoveContextLimits } from "../llm/movePrompt.js";
import { pickTimeBudgetMs } from "../llm/compactMatch.js";
import { pickMoveTimeoutCapMs } from "../llm/timing.js";
import { prepareTacticsForMatch } from "../llm/prepareTactics.js";
import {
  formatTacticalIntelCompact,
  type TacticalIntel,
} from "../llm/tacticalIntel.js";
import {
  evaluateTacticalIntelOutcome,
  formatLeanAccuracyLog,
  formatMatchTacticalScoreLog,
  formatPrimaryLeaderboardLog,
} from "../llm/tacticalIntelTracking.js";
import {
  formatRoundSignalScoreLog,
  scoreAllRoundsInMatch,
} from "../llm/intelSignalRoundScoring.js";
import {
  buildDescribeFacts,
  buildDescribeIntelReasoning,
  describeMatchWithLlm,
  formatIntelReasoningSentence,
} from "../llm/describeMatch.js";
import { error, log, msSince, warn } from "../log.js";
import { gameplayDetailLogEnabled } from "../logConfig.js";
import type { Match } from "../types.js";

type AgentPhase = "idle" | "queued" | "lobby" | "active";

type SessionHeartbeatMode = "queue" | "match";

export class PlayerAgent {
  private phase: AgentPhase = "idle";
  private activeMatchId: string | null = null;
  private matchUnsub: Unsubscribe | null = null;
  private userUnsub: Unsubscribe | null = null;
  private sessionHeartbeatStopped = true;
  private sessionHeartbeatRunId = 0;
  private sessionHeartbeatMode: SessionHeartbeatMode | null = null;
  private requeueTimer: ReturnType<typeof setTimeout> | null = null;
  /** One pick pipeline at a time (LLM + submit); never overlap across rounds. */
  private pickInProgress = false;
  private matchEndHandled: string | null = null;
  private matchTactics: string | null = null;
  private matchTacticsForId: string | null = null;
  private matchTacticalIntel: TacticalIntel | null = null;
  private matchTacticsFromFallback = false;
  private tacticsPrepMatchId: string | null = null;
  /** LLM model locked for the current match (all rounds + describe). */
  private matchLlmModelForId: string | null = null;
  /** Match id that needs a pick once the current pick pipeline finishes. */
  private pickDeferredMatchId: string | null = null;

  constructor(
    private readonly ctx: FirebaseContext,
    private readonly db: MatchDatabase,
  ) {}

  async start(): Promise<void> {
    const uid = this.ctx.user.uid;
    await ensureUserProfile(this.ctx.db, uid, this.ctx.config.botDisplayName);

    // Attach only — match listener owns end-of-game detach and re-queue (avoids
    // re-queue when activeMatchId clears before the match doc finishes updating).
    this.userUnsub = onSnapshot(doc(this.ctx.db, "users", uid), (snap) => {
      const matchId = snap.get("activeMatchId") as string | undefined;
      if (matchId?.trim()) {
        this.attachMatch(matchId.trim());
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
    this.stopSessionHeartbeat();
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

  /** Delay before auto-queue after a concluded game or transient queue drop. */
  private scheduleRequeue(delayMs = this.ctx.config.requeueDelayMs): void {
    if (this.requeueTimer) return;
    this.stopSessionHeartbeat();
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
    this.startSessionHeartbeat(uid, "queue");
  }

  private startSessionHeartbeat(uid: string, mode: SessionHeartbeatMode): void {
    this.stopSessionHeartbeat();
    this.sessionHeartbeatMode = mode;
    this.sessionHeartbeatStopped = false;
    const runId = ++this.sessionHeartbeatRunId;
    void this.runSessionHeartbeatLoop(uid, runId, mode).catch((e) =>
      warn(`[${mode}-heartbeat]`, e),
    );
  }

  private stopSessionHeartbeat(): void {
    this.sessionHeartbeatStopped = true;
    this.sessionHeartbeatRunId++;
    this.sessionHeartbeatMode = null;
  }

  private isSessionHeartbeatActive(mode: SessionHeartbeatMode): boolean {
    if (mode === "queue") return this.phase === "queued";
    return this.activeMatchId != null;
  }

  /** Queue: Firestore queue doc + presence. Match: presence only (30s, like Android). */
  private async runSessionHeartbeatLoop(
    uid: string,
    runId: number,
    mode: SessionHeartbeatMode,
  ): Promise<void> {
    let queueBeat = 0;
    let presenceBeat = 0;
    let consecutiveMissing = 0;
    let consecutiveTransient = 0;

    if (mode === "queue") {
      await this.verifyQueueOnServer(uid).catch(() => {});
    }

    const beat = async (): Promise<boolean> => {
      if (
        this.sessionHeartbeatStopped ||
        runId !== this.sessionHeartbeatRunId ||
        this.sessionHeartbeatMode !== mode ||
        !this.isSessionHeartbeatActive(mode)
      ) {
        return false;
      }

      presenceBeat++;
      const includeOnlineCount = presenceBeat === 1 || presenceBeat % 4 === 0;
      await touchPresence(this.ctx.functions, includeOnlineCount).catch((e) => {
        warn("[presence]", e);
      });

      if (mode === "queue") {
        queueBeat++;
        if (queueBeat % QUEUE_HEARTBEAT_VERIFY_EVERY === 0) {
          await this.verifyQueueOnServer(uid).catch(() => {});
        }

        const heartbeat = await sendQueueHeartbeat(this.ctx.db, uid);
        if (heartbeat === "ok") {
          consecutiveMissing = 0;
          consecutiveTransient = 0;
        } else if (heartbeat === "missing") {
          consecutiveMissing++;
          if (consecutiveMissing >= QUEUE_HEARTBEAT_MAX_FAILURES) {
            await this.dropQueueSession(
              uid,
              "[queue] queue doc missing after repeated heartbeats",
            );
            return false;
          }
        } else {
          consecutiveTransient++;
          warn(
            `[queue-heartbeat] transient Firestore error (${consecutiveTransient}/${QUEUE_HEARTBEAT_MAX_TRANSIENT})`,
          );
          if (consecutiveTransient >= QUEUE_HEARTBEAT_MAX_TRANSIENT) {
            await this.dropQueueSession(
              uid,
              "[queue] too many transient heartbeat failures — will re-join",
              true,
            );
            return false;
          }
        }
      }

      return true;
    };

    if (!(await beat())) return;
    log(
      mode === "queue"
        ? `[queue] heartbeat + presence every ${SESSION_HEARTBEAT_INTERVAL_MS}ms`
        : `[match] presence heartbeat every ${SESSION_HEARTBEAT_INTERVAL_MS}ms`,
    );

    while (
      !this.sessionHeartbeatStopped &&
      runId === this.sessionHeartbeatRunId &&
      this.sessionHeartbeatMode === mode &&
      this.isSessionHeartbeatActive(mode)
    ) {
      await new Promise((r) => setTimeout(r, SESSION_HEARTBEAT_INTERVAL_MS));
      if (!(await beat())) return;
    }
  }

  private async verifyQueueOnServer(uid: string): Promise<boolean> {
    const exists = await queueEntryExistsOnServer(this.ctx.db, uid);
    if (exists === false) {
      await this.dropQueueSession(uid, "[queue] server confirmed queue doc gone", true);
      return false;
    }
    if (exists === null) {
      warn("[queue] server verify skipped (transient read error)");
    }
    return exists === true;
  }

  private async dropQueueSession(
    uid: string,
    reason: string,
    scheduleRecover = false,
  ): Promise<void> {
    const exists = await queueEntryExistsOnServer(this.ctx.db, uid);
    if (exists === true) {
      warn(`${reason} — server still has queue doc, keeping session`);
      return;
    }
    this.stopSessionHeartbeat();
    if (this.phase === "queued") this.phase = "idle";
    log(reason);
    if (scheduleRecover && this.ctx.config.autoQueue && !this.activeMatchId) {
      log(`[queue] re-join in ${QUEUE_RECOVER_DELAY_MS}ms`);
      this.scheduleRequeue(QUEUE_RECOVER_DELAY_MS);
    }
  }

  private clearMatchTactics(): void {
    this.matchTactics = null;
    this.matchTacticsForId = null;
    this.matchTacticalIntel = null;
    this.matchTacticsFromFallback = false;
    this.matchLlmModelForId = null;
  }

  /** One model per match; under-sampled models get priority until fair share. */
  private ensureLlmModelForMatch(matchId: string): string {
    if (this.matchLlmModelForId === matchId) {
      return getLlmConfig().model;
    }
    const ranked = getLlmModelRankings();
    const historical = this.db.getLlmModelMatchStats();
    const model =
      ranked.length > 0
        ? selectLlmModelForMatch(ranked, historical)
        : getLlmConfig().model;
    setActiveLlmModel(model);
    this.matchLlmModelForId = matchId;
    if (gameplayDetailLogEnabled()) {
      const hist = historical.find((h) => h.model === model);
      log(
        `[llm] match=${matchId} model=${model}` +
          (hist ? ` (${hist.matches} archived matches)` : ""),
      );
    }
    return model;
  }

  private detachMatch(): void {
    this.stopSessionHeartbeat();
    this.matchUnsub?.();
    this.matchUnsub = null;
    this.activeMatchId = null;
    this.pickInProgress = false;
    this.pickDeferredMatchId = null;
    this.matchEndHandled = null;
    this.clearMatchTactics();
    if (this.phase === "lobby" || this.phase === "active") {
      this.phase = "idle";
    }
  }

  private attachMatch(matchId: string): void {
    if (this.activeMatchId === matchId && this.matchUnsub) return;
    this.cancelRequeue();
    this.matchUnsub?.();
    this.activeMatchId = matchId;
    void leaveQueue(this.ctx.db, this.ctx.user.uid).catch(() => {});
    this.startSessionHeartbeat(this.ctx.user.uid, "match");

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
      void this.prepareTacticsInLobby(match, uid);
      return;
    }

    if (match.status === "active") {
      this.phase = "active";
      if (!openRoundNeedsMove(match, uid)) return;
      if (this.pickInProgress) {
        this.pickDeferredMatchId = match.id;
        return;
      }

      const opp = opponentId(match, uid);
      if (!opp) return;

      const roundNumber = match.currentRound;
      const matchId = match.id;
      this.pickInProgress = true;
      const moveStartedAt = Date.now();
      let resumeAfterPick = false;
      let dbCtx: Awaited<ReturnType<typeof buildMatchDbContext>> | undefined;
      try {
        const oppName =
          match.player1 === opp ? match.player1Name : match.player2Name;
        if (roundNumber === 1) {
          this.ensureLlmModelForMatch(matchId);
        }
        const profile = await getUserProfile(this.ctx.db, opp);
        const contextStartedAt = Date.now();
        dbCtx = await buildMatchDbContext(
          this.db,
          uid,
          opp,
          oppName,
          match,
          profile,
          pickMoveContextLimits,
        );
        dbCtx = {
          ...dbCtx,
          signalPickStats: this.db.getPickIntelCitationStats(),
          signalLeanStats: this.db.getSignalLeanStats(),
        };
        const contextMs = msSince(contextStartedAt);
        let tacticsMs = 0;
        if (this.matchTacticsForId === matchId && this.matchTactics) {
          dbCtx = {
            ...dbCtx,
            tactics: this.matchTactics,
            tacticalIntel: this.matchTacticalIntel ?? undefined,
          };
        } else if (roundNumber === 1) {
          const { tactics, intel, durationMs, fromFallback } = await prepareTacticsForMatch(
            match,
            dbCtx,
            this.db,
          );
          tacticsMs = durationMs;
          this.matchTactics = tactics;
          this.matchTacticsForId = matchId;
          this.matchTacticalIntel = intel;
          this.matchTacticsFromFallback = fromFallback;
          this.db.saveTacticalIntelSnapshot(matchId, intel, fromFallback);
          dbCtx = { ...dbCtx, tactics, tacticalIntel: intel };
          log(`[thought-process] ${tactics}`);
          if (gameplayDetailLogEnabled()) {
            log(`[tactics:intel] ${formatTacticalIntelCompact(intel)}`);
            log(
              `[tactics] ${tacticsMs}ms${fromFallback ? (tacticsMs === 0 ? " deterministic" : " fallback") : ""} ${tactics}`,
            );
          }
        }
        const cap = pickMoveTimeoutCapMs();
        const budgetMs = pickTimeBudgetMs(match);
        const pickPhaseMs =
          budgetMs != null
            ? Math.min(cap, Math.max(3000, budgetMs - tacticsMs))
            : cap;
        const pickBudgetMs = pickPhaseMs;
        const { choice, reason, thoughtProcess, intelSource, intelSignal, pickMs, llmModel, llmResponse } =
          await pickMoveWithLlm(
          match,
          dbCtx,
          pickBudgetMs,
        );
        if (thoughtProcess?.trim()) {
          log(`[thought-process] ${thoughtProcess.trim()}`);
        }
        const pickLog = formatMovePickLogLine({ choice, reason, intelSource, intelSignal });
        log(`[move:reason] r${roundNumber} ${choice} vs ${oppName} ${pickLog}`);
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
          resumeAfterPick = Boolean(
            fresh?.status === "active" && (fresh.currentRound ?? 0) > roundNumber,
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
          pickReason: reason,
          pickIntelSource: intelSource,
          pickIntelSignal: intelSignal,
          llmModel,
          contextMs,
          pickMs,
          submitMs,
          totalMs,
          ok,
        });
        if (ok) {
          if (gameplayDetailLogEnabled()) {
            const intelLine = dbCtx.tacticalIntel
              ? ` | ${formatTacticalIntelCompact(dbCtx.tacticalIntel)}`
              : "";
            const rawLlm =
              process.env.LLM_LOG_RESPONSES === "true" ||
              process.env.AI_LOG_VERBOSE === "true" ||
              process.env.MOVE_LOG_RAW_LLM === "true";
            const llmSuffix = rawLlm
              ? ` llm=${llmResponse.replace(/\s+/g, " ").trim()}`
              : "";
            log(
              `[move] r${roundNumber} ${choice} vs ${oppName} ${totalMs}ms (ctx=${contextMs} tactics=${tacticsMs} pick=${pickMs} submit=${submitMs})${llmSuffix}${intelLine}`,
            );
          }
        } else {
          warn(
            `[move] r${roundNumber} ${choice} vs ${oppName} submit failed ${submitMs}ms (pick=${pickMs}ms)`,
          );
        }
      } catch (err) {
        const totalMs = msSince(moveStartedAt);
        const oppName =
          match.player1 === opp ? match.player1Name : match.player2Name;
        let ok = false;
        let pickMs = 0;
        let submitMs = 0;
        let llmModel = getLlmConfig().model;
        if (dbCtx && isLlmPickTimeoutError(err)) {
          try {
            const fallback = pickMoveDeterministic(match, dbCtx);
            pickMs = fallback.pickMs;
            llmModel = fallback.llmModel;
            if (fallback.thoughtProcess?.trim()) {
              log(`[thought-process] ${fallback.thoughtProcess.trim()}`);
            }
            log(
              `[move:reason] r${roundNumber} ${fallback.choice} vs ${oppName} ${formatMovePickLogLine(fallback)} (timeout-fallback)`,
            );
            const fresh = await getMatch(this.ctx.db, matchId);
            if (
              fresh &&
              fresh.status === "active" &&
              fresh.currentRound === roundNumber &&
              !selfSubmitted(fresh, uid)
            ) {
              const submitStartedAt = Date.now();
              ok = await submitRoundMove(
                this.ctx.db,
                this.ctx.functions,
                fresh,
                uid,
                fallback.choice,
              );
              submitMs = msSince(submitStartedAt);
              if (ok) {
                log(
                  `[move] r${roundNumber} ${fallback.choice} vs ${oppName} ${totalMs}ms (timeout-fallback submit=${submitMs}ms)`,
                );
                resumeAfterPick = Boolean(
                  fresh.currentRound != null && fresh.currentRound > roundNumber,
                );
              } else {
                warn(
                  `[move] r${roundNumber} ${fallback.choice} vs ${oppName} timeout-fallback submit failed ${submitMs}ms`,
                );
              }
            } else {
              warn(
                `[move] round ${roundNumber} stale after timeout-fallback — skip submit (now r${fresh?.currentRound ?? "?"})`,
              );
            }
          } catch (fallbackErr) {
            warn(`[move] round ${roundNumber} timeout-fallback failed:`, fallbackErr);
          }
        }
        this.db.recordRoundTiming({
          matchId,
          roundNumber,
          llmModel,
          contextMs: 0,
          pickMs,
          submitMs,
          totalMs,
          ok,
        });
        if (!ok) {
          warn(`[move] round ${roundNumber} failed ${totalMs}ms:`, err);
        }
      } finally {
        this.pickInProgress = false;
        const deferred = this.pickDeferredMatchId === matchId;
        if (deferred) this.pickDeferredMatchId = null;
        if (resumeAfterPick || deferred) void this.resumePickIfNeeded(matchId, uid);
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
      if (this.ctx.config.autoQueue) {
        this.scheduleRequeue();
      }
    }
  }

  /** Let an in-flight rN pick finish before match-end work (avoids stale direct writes). */
  /** Pre-match tactics while in lobby so round 1 does not wait on prep after the clock starts. */
  private prepareTacticsInLobby(match: Match, uid: string): void {
    const matchId = match.id;
    if (this.matchTacticsForId === matchId || this.tacticsPrepMatchId === matchId) {
      return;
    }
    const opp = opponentId(match, uid);
    if (!opp) return;

    this.tacticsPrepMatchId = matchId;
    void (async () => {
      try {
        this.ensureLlmModelForMatch(matchId);
        const oppName =
          match.player1 === opp ? match.player1Name : match.player2Name;
        const profile = await getUserProfile(this.ctx.db, opp);
        const dbCtx = await buildMatchDbContext(
          this.db,
          uid,
          opp,
          oppName,
          match,
          profile,
          pickMoveContextLimits,
        );
        const { tactics, intel, durationMs, fromFallback } =
          await prepareTacticsForMatch(match, dbCtx, this.db);
        if (this.activeMatchId !== matchId) return;
        this.matchTactics = tactics;
        this.matchTacticsForId = matchId;
        this.matchTacticalIntel = intel;
        this.matchTacticsFromFallback = fromFallback;
        this.db.saveTacticalIntelSnapshot(matchId, intel, fromFallback);
        log(`[thought-process] ${tactics}`);
        if (gameplayDetailLogEnabled()) {
          log(
            `[tactics] lobby prep ${durationMs}ms${fromFallback ? (durationMs === 0 ? " deterministic" : " fallback") : ""}`,
          );
        }
      } catch (err) {
        warn("[tactics] lobby prep failed:", err);
      } finally {
        if (this.tacticsPrepMatchId === matchId) this.tacticsPrepMatchId = null;
      }
    })();
  }

  /**
   * If a new round opened while a pick was in flight, pick again without waiting
   * for the opponent's submission to trigger another snapshot.
   */
  private async resumePickIfNeeded(matchId: string, uid: string): Promise<void> {
    if (this.activeMatchId !== matchId || this.pickInProgress) return;
    try {
      const fresh = await getMatch(this.ctx.db, matchId);
      if (!fresh || !isParticipant(fresh, uid)) return;
      if (openRoundNeedsMove(fresh, uid)) {
        await this.onMatchUpdate(fresh);
      }
    } catch (err) {
      warn("[move] resume after pick failed:", err);
    }
  }

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
    this.db.saveConcluded(archived, uid);
    log(`[match-end] saved ${archived.id} (${resolvedRounds} rounds with throws)`);

    let intelOutcome:
      | ReturnType<typeof evaluateTacticalIntelOutcome>
      | undefined;
    if (this.matchTacticalIntel && this.matchTacticsForId === match.id) {
      intelOutcome = evaluateTacticalIntelOutcome(archived, uid, this.matchTacticalIntel);
      this.db.saveTacticalIntelOutcome(intelOutcome);
      log(`[tactics-score] ${formatMatchTacticalScoreLog(intelOutcome)}`);
      const primaryBoard = this.db.getTacticalIntelPrimaryLeaderboard();
      const leanBoard = this.db.getTacticalIntelLeanAccuracy();
      const bestPick = this.db.getPrimaryMatchedBestStats();
      log(`[tactics-score:primary] ${formatPrimaryLeaderboardLog(primaryBoard)}`);
      log(`[tactics-score:lean] ${formatLeanAccuracyLog(leanBoard)}`);
      if (bestPick.matches > 0) {
        log(
          `[tactics-score:best-pick] primary=best-lean ${bestPick.wins}-${bestPick.matches - bestPick.wins} (${bestPick.winPct}% series wins)`,
        );
      }
    }

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
      if (
        this.matchTacticalIntel &&
        intelOutcome &&
        this.matchTacticsForId === match.id
      ) {
        const scoreCtx = { ...dbCtx, tacticalIntel: this.matchTacticalIntel };
        const signalRows = scoreAllRoundsInMatch(archived, uid, scoreCtx);
        this.db.saveRoundSignalScores(archived.id, signalRows);
        log(`[signal-score] ${formatRoundSignalScoreLog(signalRows, archived.id)}`);
        const facts = buildDescribeFacts(archived, uid, dbCtx);
        log(
          `[match-end:intel] ${formatIntelReasoningSentence(
            buildDescribeIntelReasoning(
              this.matchTacticalIntel,
              intelOutcome,
              facts.analysis.opponentDominant,
            ),
          )}`,
        );
      }
      const description = await describeMatchWithLlm(archived, uid, dbCtx, {
        tacticalIntel:
          this.matchTacticsForId === match.id ? this.matchTacticalIntel : null,
        intelOutcome,
      });
      this.db.saveConcluded(archived, uid, description);
      log(`[match-end] ${msSince(endedStartedAt)}ms ${description}`);
    } catch (err) {
      warn(`[match-end] describe failed (${msSince(endedStartedAt)}ms), match archived`, err);
    }
  }
}
