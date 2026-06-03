import { loadConfig } from "./config.js";
import { runBotRankings } from "./botRankings.js";
import { MatchDatabase } from "./db/matchDatabase.js";
import { initLlm } from "./llm/client.js";
import { probeLlm } from "./llm/chat.js";
import { error } from "./log.js";

async function main(): Promise<void> {
  const config = loadConfig();
  initLlm({
    baseUrl: config.llmBaseUrl,
    models: config.llmModels,
    model: config.llmModels[0] ?? config.llmModel,
    apiKey: config.llmApiKey,
    timeoutMs: config.llmTimeoutMs,
  });

  if (!(await probeLlm())) {
    error(`[bot-rank] LLM unreachable at ${config.llmBaseUrl}`);
    process.exit(1);
  }

  const db = await MatchDatabase.open(config.matchDbPath);
  try {
    await runBotRankings(db, { quiet: true });
  } finally {
    db.close();
  }
}

main().catch((err) => {
  error("[bot-rank] fatal:", err);
  process.exit(1);
});
