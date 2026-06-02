import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  buildDescribeFacts,
  buildDescribeIntelReasoning,
  buildDeterministicDescribe,
  buildMatchAnalytics,
  buildOpponentStyle,
  botClaimsVictory,
  descriptionStatesResult,
  descriptionStatesScore,
  clampDescription,
  descriptionUsesMoveNames,
  formatIntelReasoningSentence,
  isAcceptableDescription,
  opponentClaimsVictory,
  sanitizeDescription,
} from "./describeMatch.js";
import type { TacticalIntel } from "./tacticalIntel.js";
import type { TacticalIntelOutcome } from "./tacticalIntelTracking.js";
import type { DescribeRoundSummary } from "./describeMatch.js";
import type { Match } from "../types.js";
import type { MatchDbContext } from "./matchContext.js";

const ctx = (name: string): MatchDbContext => ({
  botUid: "bot",
  opponentUid: "opp",
  opponentName: name,
  opponentProfile: null,
  currentMatch: null,
  headToHead: [],
  recentBotMatches: [],
  queryLimits: { headToHead: 0, recentBot: 0 },
});

describe("describeMatch facts", () => {
  it("uses series wins not tie count", () => {
    const match: Match = {
      id: "m1",
      player1: "bot",
      player2: "opp",
      player1Name: "Bot",
      player2Name: "Daniil",
      matchMode: "BO3",
      status: "completed",
      player1Ready: true,
      player2Ready: true,
      readyDeadlineAt: 0,
      currentRound: 5,
      player1Wins: 1,
      player2Wins: 2,
      rounds: [
        { roundNumber: 1, player1Submitted: true, player2Submitted: true, player1Choice: "ROCK", player2Choice: "PAPER", winner: "opp", resolvedAt: 1 },
        { roundNumber: 2, player1Submitted: true, player2Submitted: true, player1Choice: "SCISSORS", player2Choice: "PAPER", winner: "bot", resolvedAt: 2 },
        { roundNumber: 3, player1Submitted: true, player2Submitted: true, player1Choice: "ROCK", player2Choice: "SCISSORS", winner: "opp", resolvedAt: 3 },
        { roundNumber: 4, player1Submitted: true, player2Submitted: true, player1Choice: "PAPER", player2Choice: "ROCK", winner: "bot", resolvedAt: 4 },
        { roundNumber: 5, player1Submitted: true, player2Submitted: true, player1Choice: "ROCK", player2Choice: "PAPER", winner: "opp", resolvedAt: 5 },
      ],
      winnerId: "opp",
      resolution: "player2_win",
      createdAt: 0,
      lastActivityAt: 0,
    };
    const facts = buildDescribeFacts(match, "bot", ctx("Daniil"));
    assert.deepEqual(facts.score, { bot: 1, opponent: 2 });
    assert.equal(facts.result, "loss");
    assert.equal(facts.bot, "Bot");
    assert.equal(facts.opponent, "Daniil");
    assert.equal(facts.roundsResolved, 5);
    assert.equal(facts.analysis.opponentDominant, "Paper");
    assert.equal(facts.rounds[0]?.bot, "Rock");
    assert.equal(facts.analysis.roundOutcomes.botLost, 3);
    assert.equal(facts.analysis.clinchRound, 3);
    assert.equal(facts.opponentStyle.thisMatch.dominant, "Paper");
  });
});

describe("buildOpponentStyle", () => {
  it("includes lifetime and h2h when available", () => {
    const analysis = buildMatchAnalytics(
      [{ n: 1, bot: "Rock", opponent: "Paper", outcome: "bot_lost" }],
      "BO3",
    );
    const style = buildOpponentStyle(analysis, {
      uid: "opp",
      displayName: "Daniil",
      elo: 1200,
      throwsRock: 10,
      throwsPaper: 50,
      throwsScissors: 5,
    }, [
      {
        id: "old",
        opponentUid: "opp",
        opponentName: "Daniil",
        matchMode: "BO3",
        botWins: 1,
        opponentWins: 2,
        rounds: [{ roundNumber: 1, opponentMove: "PAPER" }],
      },
    ]);
    assert.equal(style.lifetime?.dominant, "Paper");
    assert.equal(style.priorVsBot?.matches, 1);
    assert.equal(style.priorVsBot?.dominant, "Paper");
  });
});

describe("buildMatchAnalytics", () => {
  it("detects clinch and throw skew", () => {
    const rounds: DescribeRoundSummary[] = [
      { n: 1, bot: "Rock", opponent: "Paper", outcome: "bot_lost" },
      { n: 2, bot: "Paper", opponent: "Rock", outcome: "bot_won" },
      { n: 3, bot: "Scissors", opponent: "Scissors", outcome: "tie" },
      { n: 4, bot: "Rock", opponent: "Paper", outcome: "bot_lost" },
    ];
    const a = buildMatchAnalytics(rounds, "BO3");
    assert.equal(a.roundOutcomes.tie, 1);
    assert.equal(a.opponentDominant, "Paper");
    assert.equal(a.clinchRound, 4);
  });
});

describe("descriptionStatesResult", () => {
  it("rejects opponent win when bot won", () => {
    const bad =
      "Daniil (melkor217) won the BO5 series 3–1. Analysis reveals a strong preference for Paper throws by you.";
    assert.equal(opponentClaimsVictory(bad, "Daniil"), true);
    assert.equal(
      descriptionStatesResult(bad, "win", "Azzy", "Daniil"),
      false,
    );
  });

  it("accepts bot win phrasing", () => {
    const good =
      "Azzy won the BO5 series 3-1 against Daniil, leaning on Paper in six of twelve throws.";
    assert.equal(descriptionStatesResult(good, "win", "Azzy", "Daniil"), true);
  });
});

describe("descriptionStatesScore", () => {
  it("accepts correct bot-perspective score", () => {
    assert.equal(descriptionStatesScore("Bot lost 1-2 vs Daniil in BO3.", 1, 2), true);
  });

  it("rejects hallucinated tie score", () => {
    assert.equal(
      descriptionStatesScore("Daniil tied Azzy 2-2 in a chaotic BO3 battle.", 1, 2),
      false,
    );
  });

  it("rejects rounds played confused as score", () => {
    assert.equal(
      descriptionStatesScore(
        "Nagibator2000 dominated 10-0, utilizing Paper vs Scissors.",
        6,
        2,
        10,
      ),
      false,
    );
    assert.equal(
      descriptionStatesScore(
        "Nagibator2000 won 6-2 vs Daniil in 10 rounds.",
        6,
        2,
        10,
      ),
      true,
    );
  });
});

describe("intel reasoning sentence", () => {
  it("formats lean hit when read matched opponent", () => {
    const intel = {
      primarySource: "h2h",
      primary: {
        dominant: "PAPER",
        dominantPct: 60,
        distribution: { rockPct: 20, paperPct: 60, scissorsPct: 20 },
        openWith: "SCISSORS",
      },
    } as unknown as TacticalIntel;
    const outcome = {
      primarySource: "h2h",
      h2hLeanHits: 4,
      h2hLeanRounds: 5,
      bestLeanSource: "h2h",
      primaryMatchedBest: true,
    } as unknown as TacticalIntelOutcome;
    const reasoning = buildDescribeIntelReasoning(intel, outcome, "Paper");
    const line = formatIntelReasoningSentence(reasoning);
    assert.match(line, /h2h/i);
    assert.match(line, /Paper/i);
    assert.match(line, /4\/5/);
  });
});

describe("buildDeterministicDescribe", () => {
  it("uses series score not round count", () => {
    const facts = buildDescribeFacts(
      {
        id: "m",
        player1: "bot",
        player2: "opp",
        player1Name: "Nagibator2000",
        player2Name: "Daniil",
        matchMode: "BO10",
        status: "completed",
        player1Ready: true,
        player2Ready: true,
        readyDeadlineAt: 0,
        currentRound: 10,
        player1Wins: 6,
        player2Wins: 2,
        winnerId: "bot",
        rounds: Array.from({ length: 10 }, (_, i) => ({
          roundNumber: i + 1,
          player1Submitted: true,
          player2Submitted: true,
          player1Choice: "PAPER",
          player2Choice: "SCISSORS",
          winner: "bot",
          resolvedAt: i + 1,
        })),
        createdAt: 0,
        lastActivityAt: 0,
      },
      "bot",
      ctx("Daniil"),
    );
    const line = buildDeterministicDescribe(facts);
    assert.match(line, /6-2/);
    assert.match(line, /10 rounds/);
    assert.ok(!/10-0/.test(line));
  });
});

describe("clampDescription", () => {
  it("allows two sentences within word cap", () => {
    const long =
      "Nagibator2000 won 2-0 vs Daniil. Rock throws dominated. Daniil primarily utilized Paper, showcasing a consistent strategy that proved insufficient against the bot.";
    const out = clampDescription(long, 240, 36, 2);
    assert.ok(out.split(/\s+/).length <= 36);
    assert.match(out, /2-0/);
    assert.ok(!out.includes("insufficient"));
  });
});

describe("sanitizeDescription", () => {
  it("strips leaked field names", () => {
    const raw =
      "I decisively won the match against Xamkoz, 6-4. end.you end.opp";
    assert.equal(sanitizeDescription(raw), "I decisively won the match against Xamkoz, 6-4.");
    assert.equal(
      isAcceptableDescription(raw, {
        score: { bot: 6, opponent: 4 },
        result: "win",
        bot: "Bot",
        opponent: "Xamkoz",
        roundsResolved: 10,
      }),
      true,
    );
  });

  it("rejects when leakage remains after sanitize without score", () => {
    assert.equal(
      isAcceptableDescription("end.you end.opp", {
        score: { bot: 1, opponent: 2 },
        result: "loss",
        bot: "Bot",
        opponent: "Daniil",
        roundsResolved: 3,
      }),
      false,
    );
  });

  it("rejects R/P/S payload wording", () => {
    const raw =
      'You used the "R" payload while Daniil favored "P" payload across both rounds.';
    assert.equal(descriptionUsesMoveNames(raw), false);
    assert.match(sanitizeDescription(raw), /Rock/);
    assert.equal(
      isAcceptableDescription(sanitizeDescription(raw), {
        score: { bot: 2, opponent: 0 },
        result: "win",
        bot: "Bot",
        opponent: "Daniil",
        roundsResolved: 2,
      }),
      false,
    );
  });

  it("rejects flipped winner on bot win", () => {
    const raw =
      "Daniil won the BO5 series 3-1. Paper throws by you accounted for six of twelve.";
    assert.equal(
      isAcceptableDescription(raw, {
        score: { bot: 3, opponent: 1 },
        result: "win",
        bot: "Azzy",
        opponent: "Daniil",
        roundsResolved: 4,
      }),
      false,
    );
    assert.equal(botClaimsVictory("You secured a 3-1 victory.", "Azzy"), true);
  });
});
