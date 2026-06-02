import { loadConfig } from "./config.js";
import { initFirebase } from "./firebase/client.js";
import { getLlmConfig, initLlm } from "./llm/client.js";
import { probeLlm, verifyLlmAfterStart } from "./llm/chat.js";
import { pullMissingLlmModels } from "./llm/pullModels.js";
import {
  bootstrapLlmModels,
  logLlmModelsRankedAtStart,
} from "./llm/llmModelRanking.js";
import { MatchDatabase } from "./db/matchDatabase.js";
import {
  getTacticsIntelEfficiencyLogPath,
  logBotStartIntelEfficiency,
} from "./llm/tacticalIntel.js";
import { error, log, msSince } from "./log.js";
import { PlayerAgent } from "./player/PlayerAgent.js";

async function main(): Promise<void> {
  const config = loadConfig();
  initLlm({
    baseUrl: config.llmBaseUrl,
    models: config.llmModels,
    model: config.llmModels[0] ?? config.llmModel,
    apiKey: config.llmApiKey,
    timeoutMs: config.llmTimeoutMs,
  });
  const bootStartedAt = Date.now();
  const probeStartedAt = Date.now();
  const llmOk = await probeLlm();
  log(`[ai] llm probe ${msSince(probeStartedAt)}ms ok=${llmOk}`);
  if (!llmOk) {
    error(
      `[ai] LLM unreachable at ${config.llmBaseUrl} — start Ollama/vLLM and ensure models are pulled (${config.llmModels.join(", ")})`,
    );
    process.exit(1);
  }

  const pullStartedAt = Date.now();
  await pullMissingLlmModels();
  log(`[ai] llm pull check ${msSince(pullStartedAt)}ms`);

  const dbOpenStartedAt = Date.now();
  const db = await MatchDatabase.open(config.matchDbPath);
  log(`[ai] db open ${msSince(dbOpenStartedAt)}ms path=${config.matchDbPath}`);

  const rankStartedAt = Date.now();
  const ranked = await bootstrapLlmModels(db.getSqlite());
  logLlmModelsRankedAtStart(ranked);
  log(`[ai] llm model ranking ${msSince(rankStartedAt)}ms (sqlite)`);

  const tacticsIntelLog = getTacticsIntelEfficiencyLogPath();
  if (tacticsIntelLog) {
    log(`[ai] tactics intel efficiency log → ${tacticsIntelLog}`);
  }
  logBotStartIntelEfficiency(
    db.getTacticalIntelLeanAccuracy(),
    db.getTacticalIntelPrimaryLeaderboard(),
    db.getPickIntelCitationStats(),
  );

  const firebaseStartedAt = Date.now();
  const ctx = await initFirebase(config);
  log(`[ai] firebase init ${msSince(firebaseStartedAt)}ms`);
  const player = new PlayerAgent(ctx, db);

  const shutdown = async () => {
    log("[ai] shutting down");
    await player.stop();
    db.close();
    process.exit(0);
  };
  process.on("SIGINT", () => void shutdown());
  process.on("SIGTERM", () => void shutdown());

  await player.start();

  const postStartStartedAt = Date.now();
  const llmChatOk = await verifyLlmAfterStart();
  if (!llmChatOk) {
    error(
      `[ai] LLM chat check failed for model "${getLlmConfig().model}" at ${config.llmBaseUrl} — ensure the model is pulled and responds to chat/completions`,
    );
    await player.stop();
    db.close();
    process.exit(1);
  }
  log(`[ai] llm post-start verify ${msSince(postStartStartedAt)}ms`);

  log(
    `[ai] running ${msSince(bootStartedAt)}ms boot — ${config.botDisplayName} (${config.projectId}, llm=${getLlmConfig().model}, pool=${config.llmModels.join(",")}) auto-queue=${config.autoQueue} requeueDelay=${config.requeueDelayMs}ms modes=${config.matchModes.join(",")}`,
  );
}

main().catch((err) => {
  error("[ai] fatal:", err);
  process.exit(1);
});
