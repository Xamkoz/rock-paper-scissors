import { loadConfig } from "./config.js";
import { initLlm } from "./llm/client.js";
import { probeLlm } from "./llm/chat.js";
import { pullMissingLlmModels } from "./llm/pullModels.js";
import { error, log } from "./log.js";

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
    error(`[llm:pull] server unreachable at ${config.llmBaseUrl}`);
    process.exit(1);
  }

  const pulled = await pullMissingLlmModels();
  log(`[llm:pull] done — pulled ${pulled.length} model(s)`);
}

main().catch((err) => {
  error("[llm:pull] fatal:", err);
  process.exit(1);
});
