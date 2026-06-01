import "dotenv/config";
import { loadConfig } from "./config.js";
import { initFirebase } from "./firebase/client.js";
import { MatchCache } from "./cache/matchCache.js";
import { PlayerAgent } from "./player/PlayerAgent.js";

async function main(): Promise<void> {
  const config = loadConfig();
  console.log(`[ai] starting as ${config.botDisplayName} (${config.projectId})`);

  const ctx = await initFirebase(config);
  const cache = new MatchCache(config.cacheDir);
  const player = new PlayerAgent(ctx, cache);

  const shutdown = async () => {
    console.log("[ai] shutting down");
    await player.stop();
    process.exit(0);
  };
  process.on("SIGINT", () => void shutdown());
  process.on("SIGTERM", () => void shutdown());

  await player.start();
  console.log("[ai] running — auto-queue:", config.autoQueue, "modes:", config.matchModes.join(","));
}

main().catch((err) => {
  console.error("[ai] fatal:", err);
  process.exit(1);
});
