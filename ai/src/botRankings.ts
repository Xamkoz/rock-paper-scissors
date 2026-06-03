import type { MatchDatabase } from "./db/matchDatabase.js";
import {
  bootstrapLlmModels,
  logLlmModelsRankedAtStart,
  type LlmModelRanked,
} from "./llm/llmModelRanking.js";
import {
  getTacticsIntelEfficiencyLogPath,
  logBotStartIntelEfficiency,
} from "./llm/tacticalIntel.js";
import { log } from "./log.js";

export interface BotRankingsOptions {
  /** Suppress warmup/pull noise; print only ranked tables. */
  quiet?: boolean;
}

/** LLM model ranking + intel source/signal leaderboards (console + optional file). */
export function logBotRankings(db: MatchDatabase, ranked: LlmModelRanked[]): void {
  logLlmModelsRankedAtStart(ranked);
  logBotStartIntelEfficiency(
    db.getTacticalIntelLeanAccuracy(),
    db.getTacticalIntelPrimaryLeaderboard(),
    db.getPickIntelCitationStats(),
  );
}

export async function runBotRankings(
  db: MatchDatabase,
  opts?: BotRankingsOptions,
): Promise<LlmModelRanked[]> {
  const ranked = await bootstrapLlmModels(db.getSqlite(), { quiet: opts?.quiet });
  logBotRankings(db, ranked);
  if (!opts?.quiet) {
    const filePath = getTacticsIntelEfficiencyLogPath();
    if (filePath) {
      log(`[bot-rank] tactics intel efficiency log → ${filePath}`);
    }
  }
  return ranked;
}
