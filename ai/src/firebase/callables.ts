import { httpsCallable, type Functions } from "firebase/functions";
import type { Move } from "../types.js";
import type { MatchMode } from "../types.js";

export async function joinMatchmakingQueue(
  functions: Functions,
  params: {
    matchModes: MatchMode[];
    displayName: string;
    elo: number;
  },
): Promise<{ clientJoinedAtMs: number; activeMatchId?: string }> {
  const fn = httpsCallable<
    { matchModes: MatchMode[]; displayName: string; elo: number },
    { clientJoinedAtMs: number; activeMatchId?: string }
  >(functions, "joinMatchmakingQueue");
  const result = await fn({
    matchModes: params.matchModes,
    displayName: params.displayName,
    elo: params.elo,
  });
  return result.data;
}

export async function confirmMatchReady(
  functions: Functions,
  matchId: string,
): Promise<void> {
  const fn = httpsCallable<{ matchId: string }, { ok: boolean }>(
    functions,
    "confirmMatchReady",
  );
  await fn({ matchId });
}

export async function submitMatchMove(
  functions: Functions,
  matchId: string,
  roundNumber: number,
  choice: Move,
): Promise<void> {
  const fn = httpsCallable<
    { matchId: string; roundNumber: number; choice: Move },
    { ok: boolean }
  >(functions, "submitMatchMove");
  await fn({ matchId, roundNumber, choice });
}
