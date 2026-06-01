import { readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

export const FUNCTIONS_REGION = "europe-west1";

export interface AgentConfig {
  apiKey: string;
  authDomain: string;
  projectId: string;
  storageBucket?: string;
  botEmail: string;
  botPassword: string;
  botDisplayName: string;
  matchModes: ("BO3" | "BO5" | "BO10")[];
  /** When true, joins matchmaking when idle. */
  autoQueue: boolean;
  cacheDir: string;
  /** Interval for queue heartbeat while waiting for a match (ms). */
  queueIntervalMs: number;
}

export interface GameRules {
  roundTimeoutMs: number;
  initialClockMs: number;
}

export function loadGameRules(): GameRules {
  const path = resolve(here, "../../shared/game-rules.json");
  const raw = JSON.parse(readFileSync(path, "utf8")) as {
    roundTimeoutMs: number;
    initialClockMs: number;
  };
  return {
    roundTimeoutMs: raw.roundTimeoutMs,
    initialClockMs: raw.initialClockMs,
  };
}

function requireEnv(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`Missing required env: ${name}`);
  return value;
}

function parseMatchModes(raw: string | undefined): AgentConfig["matchModes"] {
  const modes = (raw ?? "BO3,BO5")
    .split(",")
    .map((m) => m.trim().toUpperCase())
    .filter((m): m is "BO3" | "BO5" | "BO10" =>
      m === "BO3" || m === "BO5" || m === "BO10",
    );
  if (modes.length === 0) return ["BO3"];
  return modes;
}

export function loadConfig(): AgentConfig {
  return {
    apiKey: requireEnv("FIREBASE_API_KEY"),
    authDomain: requireEnv("FIREBASE_AUTH_DOMAIN"),
    projectId: requireEnv("FIREBASE_PROJECT_ID"),
    storageBucket: process.env.FIREBASE_STORAGE_BUCKET?.trim(),
    botEmail: requireEnv("BOT_EMAIL"),
    botPassword: requireEnv("BOT_PASSWORD"),
    botDisplayName: process.env.BOT_DISPLAY_NAME?.trim() || "RPS Bot",
    matchModes: parseMatchModes(process.env.BOT_MATCH_MODES),
    autoQueue: process.env.BOT_AUTO_QUEUE !== "false",
    cacheDir: process.env.AI_CACHE_DIR?.trim() || ".ai-cache",
    queueIntervalMs: Number(
      process.env.BOT_QUEUE_INTERVAL_MS ?? process.env.QUEUE_HEARTBEAT_MS ?? 30_000,
    ),
  };
}
