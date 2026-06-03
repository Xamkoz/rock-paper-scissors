import type { Match, Move, MovePatternSummary, UserProfile } from "../types.js";
import { opponentId } from "../firebase/matchDoc.js";

const MOVES: Move[] = ["ROCK", "PAPER", "SCISSORS"];

const COUNTERS: Record<Move, Move> = {
  ROCK: "PAPER",
  PAPER: "SCISSORS",
  SCISSORS: "ROCK",
};

function isMove(value: string | undefined): value is Move {
  return value === "ROCK" || value === "PAPER" || value === "SCISSORS";
}

/** Resolved throws by [opponentId] across matches the bot can see. */
export function collectOpponentThrows(
  selfUid: string,
  opponentUid: string,
  matches: Match[],
): Move[] {
  const throws: Move[] = [];
  for (const match of matches) {
    if (!match.rounds.length) continue;
    const oppIsP1 = match.player1 === opponentUid;
    for (const round of match.rounds) {
      if (!round.resolvedAt) continue;
      const choice = oppIsP1 ? round.player1Choice : round.player2Choice;
      if (isMove(choice)) throws.push(choice);
    }
  }
  return throws;
}

export function analyzeMovePattern(
  selfUid: string,
  opponentUid: string,
  matches: Match[],
  profile?: UserProfile | null,
): MovePatternSummary {
  const fromRounds = collectOpponentThrows(selfUid, opponentUid, matches);
  const notes: string[] = [];

  let rock = 0;
  let paper = 0;
  let scissors = 0;

  for (const m of fromRounds) {
    if (m === "ROCK") rock++;
    else if (m === "PAPER") paper++;
    else scissors++;
  }

  if (profile) {
    const totalThrows =
      profile.throwsRock + profile.throwsPaper + profile.throwsScissors;
    if (totalThrows > 0) {
      const weight = Math.min(fromRounds.length, 12) / 12;
      const priorWeight = 1 - weight;
      rock += profile.throwsRock * priorWeight;
      paper += profile.throwsPaper * priorWeight;
      scissors += profile.throwsScissors * priorWeight;
      notes.push(`Blended ${Math.round(priorWeight * 100)}% lifetime throw stats.`);
    }
  }

  const sample = rock + paper + scissors;
  const rockRate = sample > 0 ? rock / sample : 1 / 3;
  const paperRate = sample > 0 ? paper / sample : 1 / 3;
  const scissorsRate = sample > 0 ? scissors / sample : 1 / 3;

  const rates: { move: Move; rate: number }[] = [
    { move: "ROCK", rate: rockRate },
    { move: "PAPER", rate: paperRate },
    { move: "SCISSORS", rate: scissorsRate },
  ];
  rates.sort((a, b) => b.rate - a.rate);
  const dominantMove = rates[0].move;
  const counterMove = COUNTERS[dominantMove];

  if (fromRounds.length === 0) {
    notes.push("No resolved rounds vs this opponent yet; using profile prior or uniform.");
  } else {
    notes.push(
      `Last ${fromRounds.length} resolved throw(s): ${(rockRate * 100).toFixed(0)}% rock, ${(paperRate * 100).toFixed(0)}% paper, ${(scissorsRate * 100).toFixed(0)}% scissors.`,
    );
  }

  return {
    opponentId: opponentUid,
    sampleRounds: fromRounds.length,
    rockRate,
    paperRate,
    scissorsRate,
    dominantMove,
    counterMove,
    notes,
  };
}

/** Matches involving self + opponent from cache lists. */
export function matchesForOpponentAnalysis(
  selfUid: string,
  opponentUid: string,
  cachedForSelf: Match[],
  headToHead: Match[],
): Match[] {
  const seen = new Set<string>();
  const out: Match[] = [];
  for (const m of [...headToHead, ...cachedForSelf]) {
    const opp = opponentId(m, selfUid);
    if (opp !== opponentUid) continue;
    if (seen.has(m.id)) continue;
    seen.add(m.id);
    out.push(m);
  }
  return out;
}
