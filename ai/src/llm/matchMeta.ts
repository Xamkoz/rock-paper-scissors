import type { Match } from "../types.js";

export function winsToFinish(mode: Match["matchMode"]): number {
  if (mode === "BO5") return 3;
  if (mode === "BO10") return 6;
  return 2;
}

export interface MatchScoreMeta {
  botWins: number;
  opponentWins: number;
  winsToFinish: number;
  botWinsNeeded: number;
  opponentWinsNeeded: number;
  clinchPressure: boolean;
  leader: "bot" | "opponent" | "tie";
}

export function buildMatchScoreMeta(
  mode: Match["matchMode"],
  botWins: number,
  opponentWins: number,
): MatchScoreMeta {
  const target = winsToFinish(mode);
  const botWinsNeeded = Math.max(0, target - botWins);
  const opponentWinsNeeded = Math.max(0, target - opponentWins);
  const leader =
    botWins > opponentWins ? "bot" : opponentWins > botWins ? "opponent" : "tie";
  return {
    botWins,
    opponentWins,
    winsToFinish: target,
    botWinsNeeded,
    opponentWinsNeeded,
    clinchPressure: botWinsNeeded === 1 || opponentWinsNeeded === 1,
    leader,
  };
}
