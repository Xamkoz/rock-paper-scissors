import type { HistoricalMatchSummary } from "../db/matchRows.js";
import type { Match, UserProfile } from "../types.js";
import { chatComplete } from "./chat.js";
import type { MatchDbContext } from "./matchContext.js";
import { moveDisplayName } from "./movePrompt.js";
import type { TacticalIntel } from "./tacticalIntel.js";
import type { TacticalIntelOutcome } from "./tacticalIntelTracking.js";
import {
  describeMaxChars,
  describeMaxSentences,
  describeMaxTokens,
  describeMaxWords,
} from "./timing.js";

function buildDescribeSystemPrompt(
  botName: string,
  opponentName: string,
  seriesScoreLine: string,
  maxWords: number,
  maxSentences: number,
): string {
  return [
    `Up to ${maxSentences} short sentences, at most ${maxWords} words total, for bot "${botName}" vs "${opponentName}".`,
    `The series score is ONLY ${seriesScoreLine} (bot series wins vs opponent series wins).`,
    `You MUST state that exact score (e.g. "${seriesScoreLine}"). roundsPlayed is throw count — never use it as the score.`,
    `Example: ${botName} won ${seriesScoreLine} vs ${opponentName} in 10 rounds, leaning on Paper vs their Scissors.`,
    "result win = bot won the series; result loss = opponent won. roundResults counts individual rounds, not series points.",
    "Cover winner, series score, one throw pattern, optional clinch round — do not repeat the bot name as 'the bot'.",
    "When intelReasoning is present, include exactly one short sentence on whether the pre-match intel lean matched opponent throws.",
    "Use Rock, Paper, Scissors only. Plain ASCII text, no emoji, no JSON labels.",
  ].join(" ");
}

export interface DescribeMatchExtras {
  tacticalIntel?: TacticalIntel | null;
  intelOutcome?: TacticalIntelOutcome | null;
}

export interface DescribeIntelReasoning {
  primarySource: string;
  preMatchLean: MoveDisplayName | null;
  suggestedOpening: MoveDisplayName | null;
  leanHits: number;
  leanRounds: number;
  leanPct: number;
  opponentDominant: MoveDisplayName | null;
  bestLeanSource: string | null;
  primaryMatchedBest: boolean;
}

function leanStatsForPrimary(
  source: TacticalIntel["primarySource"],
  outcome: TacticalIntelOutcome,
): { hits: number; rounds: number } {
  switch (source) {
    case "h2h":
      return { hits: outcome.h2hLeanHits, rounds: outcome.h2hLeanRounds };
    case "recentVsOpponent":
      return { hits: outcome.recentLeanHits, rounds: outcome.recentLeanRounds };
    case "lifetime":
      return { hits: outcome.lifetimeLeanHits, rounds: outcome.lifetimeLeanRounds };
    default:
      return { hits: 0, rounds: 0 };
  }
}

export function buildDescribeIntelReasoning(
  intel: TacticalIntel,
  outcome: TacticalIntelOutcome,
  opponentDominant: MoveDisplayName | null,
): DescribeIntelReasoning {
  const { hits, rounds } = leanStatsForPrimary(intel.primarySource, outcome);
  const leanPct = rounds > 0 ? Math.round((hits / rounds) * 100) : 0;
  const preMatchLean = intel.primary?.dominant
    ? (moveDisplayName(intel.primary.dominant) as MoveDisplayName | undefined) ?? null
    : null;
  const suggestedOpening = intel.primary?.openWith
    ? (moveDisplayName(intel.primary.openWith) as MoveDisplayName | undefined) ?? null
    : null;

  return {
    primarySource: intel.primarySource,
    preMatchLean,
    suggestedOpening,
    leanHits: hits,
    leanRounds: rounds,
    leanPct,
    opponentDominant,
    bestLeanSource: outcome.bestLeanSource,
    primaryMatchedBest: outcome.primaryMatchedBest,
  };
}

/** One sentence summarizing how pre-match intel performed this series. */
export function formatIntelReasoningSentence(r: DescribeIntelReasoning): string {
  const src = r.primarySource === "none" ? "intel" : r.primarySource;
  const read = r.preMatchLean ?? "no clear lean";
  const actual = r.opponentDominant ?? "mixed throws";
  const hit =
    r.leanRounds > 0
      ? `${r.leanHits}/${r.leanRounds} opponent throws (${r.leanPct}%)`
      : "no throws to score";

  if (r.preMatchLean && r.opponentDominant && r.preMatchLean === r.opponentDominant) {
    const best =
      r.primaryMatchedBest && r.bestLeanSource
        ? `, matching the best ${r.bestLeanSource} read`
        : "";
    return `Pre-match ${src} read called ${read} and it held — ${hit}${best}.`;
  }

  if (r.preMatchLean && r.opponentDominant) {
    return `Pre-match ${src} read leaned ${read} but they played ${actual} most (${hit} on the lean).`;
  }

  return `Pre-match ${src} read leaned ${read} (${hit}).`;
}

function descriptionMentionsIntel(text: string): boolean {
  return /\b(h2h|lifetime|recentvsopponent|recent\s+vs|pre-?match\s+read|intel\s+read)\b/i.test(
    text,
  );
}

function appendIntelReasoningSentence(
  description: string,
  reasoning: DescribeIntelReasoning,
  limits: { maxChars: number; maxWords: number; maxSentences: number },
): string {
  if (descriptionMentionsIntel(description)) return description;
  const intelLine = formatIntelReasoningSentence(reasoning);
  const base = description.replace(/\s+$/, "").replace(/[.!?]+$/, "");
  return clampDescription(`${base}. ${intelLine}`, limits.maxChars + 72, limits.maxWords + 14, limits.maxSentences + 1);
}

export interface DescribeRoundSummary {
  n: number;
  bot?: string;
  opponent?: string;
  outcome: "bot_won" | "bot_lost" | "tie";
}

export interface ThrowCounts {
  rock: number;
  paper: number;
  scissors: number;
}

export type MoveDisplayName = "Rock" | "Paper" | "Scissors";

export interface DescribeAnalytics {
  botThrows: ThrowCounts;
  opponentThrows: ThrowCounts;
  roundOutcomes: { botWon: number; botLost: number; tie: number };
  opponentDominant: MoveDisplayName | null;
  botDominant: MoveDisplayName | null;
  turningRound: number | null;
  clinchRound: number | null;
  botWinStreak: number;
  botRepeated: boolean;
}

export interface OpponentStyleSnapshot {
  dominant: MoveDisplayName | null;
  throws: ThrowCounts;
}

export interface DescribeOpponentStyle {
  thisMatch: OpponentStyleSnapshot;
  /** Lifetime throw distribution from their profile (when available). */
  lifetime?: OpponentStyleSnapshot;
  /** Opponent throws across prior bot vs opponent matches in cache. */
  priorVsBot?: OpponentStyleSnapshot & { matches: number };
}

export interface DescribeFacts {
  bot: string;
  opponent: string;
  score: { bot: number; opponent: number };
  result: "win" | "loss" | "draw";
  mode: Match["matchMode"];
  roundsResolved: number;
  rounds: DescribeRoundSummary[];
  analysis: DescribeAnalytics;
  opponentStyle: DescribeOpponentStyle;
  h2h: number;
}

function winsToFinish(mode: Match["matchMode"]): number {
  if (mode === "BO5") return 3;
  if (mode === "BO10") return 6;
  return 2;
}

function countThrows(rounds: DescribeRoundSummary[], who: "bot" | "opponent"): ThrowCounts {
  const counts = { rock: 0, paper: 0, scissors: 0 };
  for (const r of rounds) {
    const c = r[who];
    if (c === "Rock") counts.rock++;
    else if (c === "Paper") counts.paper++;
    else if (c === "Scissors") counts.scissors++;
  }
  return counts;
}

function dominantThrow(counts: ThrowCounts): MoveDisplayName | null {
  const total = counts.rock + counts.paper + counts.scissors;
  if (total === 0) return null;
  const ranked = [
    { c: "Rock" as const, n: counts.rock },
    { c: "Paper" as const, n: counts.paper },
    { c: "Scissors" as const, n: counts.scissors },
  ].sort((a, b) => b.n - a.n);
  return ranked[0]!.n > 0 ? ranked[0]!.c : null;
}

function throwCountsFromProfile(profile: UserProfile): ThrowCounts {
  return {
    rock: profile.throwsRock,
    paper: profile.throwsPaper,
    scissors: profile.throwsScissors,
  };
}

function opponentThrowsFromSummaries(games: HistoricalMatchSummary[]): ThrowCounts {
  const counts = { rock: 0, paper: 0, scissors: 0 };
  for (const g of games) {
    for (const r of g.rounds) {
      const name = moveDisplayName(r.opponentMove);
      if (name === "Rock") counts.rock++;
      else if (name === "Paper") counts.paper++;
      else if (name === "Scissors") counts.scissors++;
    }
  }
  return counts;
}

function styleSnapshot(throws: ThrowCounts): OpponentStyleSnapshot {
  return { throws, dominant: dominantThrow(throws) };
}

export function buildOpponentStyle(
  analysis: DescribeAnalytics,
  profile: UserProfile | null,
  headToHead: HistoricalMatchSummary[],
): DescribeOpponentStyle {
  const style: DescribeOpponentStyle = {
    thisMatch: {
      dominant: analysis.opponentDominant,
      throws: analysis.opponentThrows,
    },
  };

  if (profile) {
    const lifetimeThrows = throwCountsFromProfile(profile);
    if (lifetimeThrows.rock + lifetimeThrows.paper + lifetimeThrows.scissors > 0) {
      style.lifetime = styleSnapshot(lifetimeThrows);
    }
  }

  const priorGames = headToHead.filter((g) => g.rounds.length > 0);
  if (priorGames.length > 0) {
    const priorThrows = opponentThrowsFromSummaries(priorGames);
    if (priorThrows.rock + priorThrows.paper + priorThrows.scissors > 0) {
      style.priorVsBot = { matches: priorGames.length, ...styleSnapshot(priorThrows) };
    }
  }

  return style;
}

export function buildMatchAnalytics(
  rounds: DescribeRoundSummary[],
  mode: Match["matchMode"],
): DescribeAnalytics {
  const roundOutcomes = { botWon: 0, botLost: 0, tie: 0 };
  for (const r of rounds) {
    if (r.outcome === "bot_won") roundOutcomes.botWon++;
    else if (r.outcome === "bot_lost") roundOutcomes.botLost++;
    else roundOutcomes.tie++;
  }

  let botWinStreak = 0;
  let maxBotWinStreak = 0;
  let botRepeated = false;
  for (let i = 0; i < rounds.length; i++) {
    const r = rounds[i]!;
    if (r.outcome === "bot_won") {
      botWinStreak++;
      maxBotWinStreak = Math.max(maxBotWinStreak, botWinStreak);
    } else {
      botWinStreak = 0;
    }
    if (i > 0 && r.bot && rounds[i - 1]!.bot && r.bot === rounds[i - 1]!.bot) {
      botRepeated = true;
    }
  }

  const target = winsToFinish(mode);
  let seriesBot = 0;
  let seriesOpp = 0;
  let leader: "bot" | "opponent" | "tie" = "tie";
  let turningRound: number | null = null;
  let clinchRound: number | null = null;

  for (const r of rounds) {
    if (r.outcome === "bot_won") seriesBot++;
    else if (r.outcome === "bot_lost") seriesOpp++;

    const newLeader =
      seriesBot > seriesOpp ? "bot" : seriesOpp > seriesBot ? "opponent" : "tie";
    if (leader !== "tie" && newLeader !== "tie" && newLeader !== leader) {
      turningRound = r.n;
    }
    leader = newLeader;

    if (clinchRound == null && (seriesBot >= target || seriesOpp >= target)) {
      clinchRound = r.n;
    }
  }

  const botThrows = countThrows(rounds, "bot");
  const opponentThrows = countThrows(rounds, "opponent");

  return {
    botThrows,
    opponentThrows,
    roundOutcomes,
    opponentDominant: dominantThrow(opponentThrows),
    botDominant: dominantThrow(botThrows),
    turningRound,
    clinchRound,
    botWinStreak: maxBotWinStreak,
    botRepeated,
  };
}

function roundOutcome(
  match: Match,
  selfUid: string,
  round: { winner?: string },
): DescribeRoundSummary["outcome"] {
  if (!round.winner || round.winner === "tie") return "tie";
  return round.winner === selfUid ? "bot_won" : "bot_lost";
}

export function buildDescribeFacts(
  match: Match,
  selfUid: string,
  ctx: MatchDbContext,
): DescribeFacts {
  const botIsP1 = match.player1 === selfUid;
  const botScore = botIsP1 ? match.player1Wins : match.player2Wins;
  const oppScore = botIsP1 ? match.player2Wins : match.player1Wins;
  const botName = botIsP1 ? match.player1Name : match.player2Name;
  const opponentName = botIsP1 ? match.player2Name : match.player1Name;

  let result: DescribeFacts["result"];
  if (match.status === "abandoned" || match.resolution === "draw") {
    result = "draw";
  } else if (!match.winnerId) {
    result = "draw";
  } else {
    result = match.winnerId === selfUid ? "win" : "loss";
  }

  const resolved = match.rounds.filter(
    (r) => r.resolvedAt && r.player1Choice && r.player2Choice,
  );

  const rounds: DescribeRoundSummary[] = resolved.map((r) => {
    const botMove = botIsP1 ? r.player1Choice : r.player2Choice;
    const oppMove = botIsP1 ? r.player2Choice : r.player1Choice;
    return {
      n: r.roundNumber,
      bot: moveDisplayName(botMove),
      opponent: moveDisplayName(oppMove),
      outcome: roundOutcome(match, selfUid, r),
    };
  });

  const analysis = buildMatchAnalytics(rounds, match.matchMode);

  return {
    bot: botName,
    opponent: opponentName || ctx.opponentName,
    score: { bot: botScore, opponent: oppScore },
    result,
    mode: match.matchMode,
    roundsResolved: rounds.length,
    rounds,
    analysis,
    opponentStyle: buildOpponentStyle(analysis, ctx.opponentProfile, ctx.headToHead),
    h2h: ctx.headToHead.length,
  };
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** First token of a display name (e.g. "Daniil" from "Daniil (melkor217)"). */
function nameTokens(displayName: string): string[] {
  const tokens = [displayName.trim()];
  const paren = displayName.match(/^([^(]+)/);
  if (paren?.[1]) tokens.push(paren[1].trim());
  return [...new Set(tokens.filter((t) => t.length > 1))];
}

/** Opponent credited with winning the series (wrong when result is win). */
export function opponentClaimsVictory(text: string, opponentName: string): boolean {
  for (const token of nameTokens(opponentName)) {
    const name = escapeRegex(token);
    if (
      new RegExp(
        `\\b${name}(?:\\s*\\([^)]+\\))?\\s+(won|secured|clinched)\\b`,
        "i",
      ).test(text)
    ) {
      return true;
    }
    if (new RegExp(`\\bdefeated\\s+${name}\\b`, "i").test(text)) return true;
    if (new RegExp(`\\bvictory\\s+(?:for|over)\\s+${name}\\b`, "i").test(text)) {
      return true;
    }
  }
  return false;
}

/** Bot credited with winning when result is loss. */
export function botClaimsVictory(text: string, botName: string): boolean {
  if (/\b(you won|you secured a \d|your victory|you defeated)\b/i.test(text)) {
    return true;
  }
  for (const token of nameTokens(botName)) {
    const name = escapeRegex(token);
    if (
      new RegExp(`\\b${name}(?:\\s*\\([^)]+\\))?\\s+(won|secured|clinched)\\b`, "i").test(text)
    ) {
      return true;
    }
    if (new RegExp(`\\bdefeated\\s+${name}\\b`, "i").test(text)) return true;
  }
  return false;
}

export function descriptionStatesResult(
  text: string,
  result: DescribeFacts["result"],
  botName: string,
  opponentName: string,
): boolean {
  if (result === "draw") return true;
  if (result === "win") return !opponentClaimsVictory(text, opponentName);
  return !botClaimsVictory(text, botName);
}

const FIELD_LEAK_PATTERN = /\b(?:end|score)\.(?:bot|opponent|you|opp)\b/gi;

const BAD_MOVE_WORDING =
  /\b(payload|["'][RPS]["'](?:\s+payload)?|\b[RPS]\s+throw\b|\bthe\s+[RPS]\s+payload\b)/i;

export function sanitizeDescription(text: string): string {
  return text
    .replace(FIELD_LEAK_PATTERN, "")
    .replace(/\b["']R["']\s+payload\b/gi, "Rock")
    .replace(/\b["']P["']\s+payload\b/gi, "Paper")
    .replace(/\b["']S["']\s+payload\b/gi, "Scissors")
    .replace(/\bthe\s+["']R["']\s+payload\b/gi, "Rock")
    .replace(/\bthe\s+["']P["']\s+payload\b/gi, "Paper")
    .replace(/\bthe\s+["']S["']\s+payload\b/gi, "Scissors")
    .replace(/[^\x20-\x7E]/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

export function descriptionUsesMoveNames(text: string): boolean {
  return !BAD_MOVE_WORDING.test(text);
}

export function descriptionLooksClean(text: string): boolean {
  if (!text || FIELD_LEAK_PATTERN.test(text)) return false;
  if (/[{}\[\]"]/.test(text)) return false;
  if (!descriptionUsesMoveNames(text)) return false;
  return true;
}

/** True when text states the bot's series score as bot-opponent (e.g. 3-1). */
export function descriptionWithinLimits(
  text: string,
  maxWords: number,
  maxChars: number,
): boolean {
  const words = text.split(/\s+/).filter(Boolean);
  return words.length <= maxWords && text.length <= maxChars;
}

function takeLeadSentences(text: string, maxSentences: number): string {
  const trimmed = text.trim();
  if (maxSentences <= 1) {
    return trimmed.split(/[.!?]/)[0]?.split(";")[0]?.trim() ?? trimmed;
  }
  const parts = trimmed.match(/[^.!?]+[.!?]+/g);
  if (parts && parts.length > 0) {
    return parts.slice(0, maxSentences).join(" ").trim();
  }
  return trimmed.split(";")[0]?.trim() ?? trimmed;
}

/** Trim to allowed sentences, then enforce word/char caps. */
export function clampDescription(
  text: string,
  maxChars: number,
  maxWords: number,
  maxSentences = describeMaxSentences(),
): string {
  let t = takeLeadSentences(sanitizeDescription(text), maxSentences);

  const words = t.split(/\s+/).filter(Boolean);
  if (words.length > maxWords) {
    t = words.slice(0, maxWords).join(" ");
    if (!/[.!?]$/.test(t)) t += ".";
  }

  if (t.length > maxChars) {
    t = t.slice(0, maxChars).trim();
    const lastSpace = t.lastIndexOf(" ");
    if (lastSpace > Math.floor(maxChars * 0.55)) {
      t = t.slice(0, lastSpace);
    }
    if (!/[.!?]$/.test(t)) t += ".";
  }

  return t;
}

/** All N-M score phrases in text must match series score (not rounds played). */
export function descriptionStatesScore(
  text: string,
  botScore: number,
  opponentScore: number,
  roundsPlayed?: number,
): boolean {
  const normalized = text.replace(/–/g, "-");
  const mentions = [...normalized.matchAll(/\b(\d{1,2})\s*-\s*(\d{1,2})\b/g)];
  if (mentions.length === 0) return false;

  for (const [, a, b] of mentions) {
    const x = Number(a);
    const y = Number(b);
    if (x === botScore && y === opponentScore) continue;
    if (x === opponentScore && y === botScore && botScore !== opponentScore) return false;
    return false;
  }

  if (roundsPlayed != null && roundsPlayed > 0) {
    for (const [, a, b] of mentions) {
      const x = Number(a);
      const y = Number(b);
      if (x === roundsPlayed || y === roundsPlayed) return false;
    }
  }

  return true;
}

/** One-line recap with correct series score (fallback when LLM fails validation). */
export function buildDeterministicDescribe(facts: DescribeFacts): string {
  const scoreStr = `${facts.score.bot}-${facts.score.opponent}`;
  const outcome =
    facts.result === "win" ? "won" : facts.result === "loss" ? "lost" : "drew";
  const dom = facts.analysis.opponentDominant;
  const throwNote = dom
    ? ` Opponent leaned ${dom} across ${facts.roundsResolved} rounds.`
    : "";
  return (
    `${facts.bot} ${outcome} ${facts.mode} vs ${facts.opponent} ${scoreStr}` +
    ` (${facts.roundsResolved} rounds).${throwNote}`
  ).trim();
}

export function isAcceptableDescription(
  text: string,
  facts: Pick<
    DescribeFacts,
    "score" | "result" | "bot" | "opponent" | "roundsResolved"
  >,
  limits?: { maxWords: number; maxChars: number },
): boolean {
  const clean = sanitizeDescription(text);
  const maxWords = limits?.maxWords ?? describeMaxWords();
  const maxChars = limits?.maxChars ?? describeMaxChars();
  return (
    descriptionLooksClean(clean) &&
    descriptionWithinLimits(clean, maxWords, maxChars) &&
    descriptionStatesScore(
      clean,
      facts.score.bot,
      facts.score.opponent,
      facts.roundsResolved,
    ) &&
    descriptionStatesResult(clean, facts.result, facts.bot, facts.opponent) &&
    clean.length > 0
  );
}

/** Compact facts — series score vs round count spelled out for the model. */
function buildDescribeUserPrompt(
  facts: DescribeFacts,
  intelReasoning?: DescribeIntelReasoning,
): string {
  const scoreToCite = `${facts.score.bot}-${facts.score.opponent}`;
  const payload: Record<string, unknown> = {
    bot: facts.bot,
    opponent: facts.opponent,
    result: facts.result,
    mode: facts.mode,
    seriesScore: {
      bot: facts.score.bot,
      opponent: facts.score.opponent,
      citeAs: scoreToCite,
      note: "series wins only — this is the match result",
    },
    roundsPlayed: facts.roundsResolved,
    roundResults: facts.analysis.roundOutcomes,
    botDominant: facts.analysis.botDominant,
    opponentDominant: facts.analysis.opponentDominant,
    clinchRound: facts.analysis.clinchRound,
    opponentLean: facts.opponentStyle.thisMatch.dominant,
    opponentLifetimeLean: facts.opponentStyle.lifetime?.dominant,
    requiredScoreInText: scoreToCite,
  };
  if (intelReasoning) {
    payload.intelReasoning = {
      primarySource: intelReasoning.primarySource,
      preMatchLean: intelReasoning.preMatchLean,
      suggestedOpening: intelReasoning.suggestedOpening,
      leanHitRate: `${intelReasoning.leanHits}/${intelReasoning.leanRounds} (${intelReasoning.leanPct}%)`,
      opponentActually: intelReasoning.opponentDominant,
      bestLeanSource: intelReasoning.bestLeanSource,
      primaryMatchedBest: intelReasoning.primaryMatchedBest,
    };
  }
  return JSON.stringify(payload);
}

export async function describeMatchWithLlm(
  match: Match,
  selfUid: string,
  ctx: MatchDbContext,
  extras?: DescribeMatchExtras,
): Promise<string> {
  const facts = buildDescribeFacts(match, selfUid, ctx);
  const intelReasoning =
    extras?.tacticalIntel && extras.intelOutcome
      ? buildDescribeIntelReasoning(
          extras.tacticalIntel,
          extras.intelOutcome,
          facts.analysis.opponentDominant,
        )
      : undefined;

  const fallback = buildDeterministicDescribe(facts);
  const maxChars = describeMaxChars();
  const maxWords = describeMaxWords();
  const maxSentences = describeMaxSentences();
  const limits = { maxChars, maxWords, maxSentences };
  const scoreToCite = `${facts.score.bot}-${facts.score.opponent}`;

  const { text } = await chatComplete(
    buildDescribeSystemPrompt(
      facts.bot,
      facts.opponent,
      scoreToCite,
      maxWords,
      maxSentences,
    ),
    buildDescribeUserPrompt(facts, intelReasoning),
    {
      maxTokens: describeMaxTokens(),
      temperature: 0.25,
      logLabel: `describe ${match.id}`,
      logSummary: `match=${match.id} score=${facts.score.bot}-${facts.score.opponent} ${facts.result}${intelReasoning ? ` intel=${intelReasoning.primarySource}` : ""}`,
      timeoutMs: 45_000,
    },
  );

  let description = clampDescription(text, maxChars, maxWords, maxSentences);
  if (description.length === 0) {
    throw new Error(`LLM returned invalid match description: ${text.slice(0, 200)}`);
  }

  if (!isAcceptableDescription(description, facts, limits)) {
    description = clampDescription(fallback, maxChars, maxWords, maxSentences);
  }

  if (intelReasoning) {
    description = appendIntelReasoningSentence(description, intelReasoning, limits);
  }

  return description;
}
