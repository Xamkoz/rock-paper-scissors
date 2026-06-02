import { describe, it, afterEach } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, rmSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import { tmpdir } from "node:os";
import {
  buildTacticalIntel,
  logBotStartIntelEfficiency,
  logIntelEfficiencyToFile,
} from "./tacticalIntel.js";
import type { MatchDbContext } from "./matchContext.js";
import type { Match } from "../types.js";
import { attachIntelEfficiencyRankings } from "./tacticalIntel.js";
import { rankIntelSourcesByEfficiency } from "./tacticalIntelRanking.js";

const logPath = resolve(tmpdir(), `tactics-intel-test-${process.pid}.log`);

afterEach(() => {
  if (existsSync(logPath)) rmSync(logPath);
  delete process.env.TACTICS_INTEL_LOG_PATH;
});

describe("logIntelEfficiencyToFile", () => {
  it("appends ranked sources before match start", () => {
    process.env.TACTICS_INTEL_LOG_PATH = logPath;

    const intel = attachIntelEfficiencyRankings(
      buildTacticalIntel(
        {
          id: "m-test",
          player1: "bot",
          player2: "opp",
          player1Name: "Bot",
          player2Name: "Daniil",
          matchMode: "BO3",
          status: "active",
          player1Ready: true,
          player2Ready: true,
          readyDeadlineAt: 0,
          currentRound: 1,
          player1Wins: 0,
          player2Wins: 0,
          rounds: [],
          createdAt: 0,
          lastActivityAt: 0,
        } satisfies Match,
        {
          botUid: "bot",
          opponentUid: "opp",
          opponentName: "Daniil",
          opponentProfile: {
            uid: "opp",
            displayName: "Daniil",
            elo: 900,
            throwsRock: 100,
            throwsPaper: 400,
            throwsScissors: 100,
          },
          currentMatch: null,
          headToHead: [],
          recentBotMatches: [],
          queryLimits: { headToHead: 0, recentBot: 0 },
        } satisfies MatchDbContext,
      ),
      [],
      [],
    );
    intel.sourcesByEfficiency = rankIntelSourcesByEfficiency(intel, [], []);

    logIntelEfficiencyToFile(intel, { matchId: "m-test" });

    const body = readFileSync(logPath, "utf8");
    assert.match(body, /\[bot-start:intel-efficiency\]/);
    assert.match(body, /match=m-test/);
    assert.match(body, /vs=Daniil/);
    assert.match(body, /1\.lifetime/);
  });
});

describe("logBotStartIntelEfficiency", () => {
  it("appends historical source ranking at process startup", () => {
    process.env.TACTICS_INTEL_LOG_PATH = logPath;

    logBotStartIntelEfficiency(
      [
        { source: "lifetime", leanHits: 45, leanRounds: 100, leanPct: 45 },
        { source: "h2h", leanHits: 18, leanRounds: 20, leanPct: 90 },
      ],
      [
        { source: "h2h", matches: 8, wins: 7, winPct: 87.5 },
        { source: "lifetime", matches: 20, wins: 9, winPct: 45 },
      ],
    );

    const body = readFileSync(logPath, "utf8");
    assert.match(body, /\[bot-start:intel-sources\]/);
    assert.match(body, /\[bot-start:intel-catalog\]/);
    assert.match(body, /Head-to-head/);
    assert.match(body, /h2h: distribution/);
  });
});
