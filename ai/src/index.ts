import "dotenv/config";
import { loadConfig } from "./config.js";
import { initFirebase } from "./firebase/client.js";
import { initLlm } from "./llm/client.js";
import { probeLlm } from "./llm/chat.js";
import { MatchDatabase } from "./db/matchDatabase.js";
import { error, log, msSince } from "./log.js";
import { PlayerAgent } from "./player/PlayerAgent.js";

async function main(): Promise<void> {
  const config = loadConfig();
  initLlm({
    baseUrl: config.llmBaseUrl,
    model: config.llmModel,
    apiKey: config.llmApiKey,
    timeoutMs: config.llmTimeoutMs,
  });
  const bootStartedAt = Date.now();
  const probeStartedAt = Date.now();
  const llmOk = await probeLlm();
  log(`[ai] llm probe ${msSince(probeStartedAt)}ms ok=${llmOk}`);
  if (!llmOk) {
    error(
      `[ai] LLM unreachable at ${config.llmBaseUrl} — start Ollama/vLLM and ensure model "${config.llmModel}" is pulled`,
    );
    process.exit(1);
  }

  const dbOpenStartedAt = Date.now();
  const db = await MatchDatabase.open(config.matchDbPath);
  log(`[ai] db open ${msSince(dbOpenStartedAt)}ms path=${config.matchDbPath}`);

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
  log(
    `[ai] running ${msSince(bootStartedAt)}ms boot — ${config.botDisplayName} (${config.projectId}, llm=${config.llmModel}) auto-queue=${config.autoQueue} modes=${config.matchModes.join(",")}`,
  );
}

main().catch((err) => {
  error("[ai] fatal:", err);
  process.exit(1);
});
