import { readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import dotenv from "dotenv";

const here = dirname(fileURLToPath(import.meta.url));
/** `ai/` package root — load `.env` here regardless of process cwd. */
export const packageRoot = resolve(here, "..");

dotenv.config({ path: resolve(packageRoot, ".env") });

const DEFAULT_QUEUE_INTERVAL_MS = 30_000;
const MIN_QUEUE_INTERVAL_MS = 5_000;
const MAX_QUEUE_INTERVAL_MS = 300_000;
const DEFAULT_REQUEUE_DELAY_MS = 30_000;
const MAX_REQUEUE_DELAY_MS = 600_000;

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
  /** SQLite path for local match history. */
  matchDbPath: string;
  /** Interval for queue heartbeat while waiting for a match (ms). */
  queueIntervalMs: number;
  /** Wait before re-joining queue after a game ends (ms). */
  requeueDelayMs: number;
  /** OpenAI-compatible LLM base URL (Ollama default: http://127.0.0.1:11434/v1). */
  llmBaseUrl: string;
  /** Model name on the local server, e.g. gemma3:12b (Ollama). */
  llmModel: string;
  /** Optional Bearer token for a secured local gateway. */
  llmApiKey?: string;
  llmTimeoutMs: number;
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

function parseQueueIntervalMs(): number {
  const raw =
    process.env.BOT_QUEUE_INTERVAL_MS?.trim() ??
    process.env.QUEUE_HEARTBEAT_MS?.trim();
  if (!raw) return DEFAULT_QUEUE_INTERVAL_MS;

  let ms = Number(raw);
  if (!Number.isFinite(ms) || ms <= 0) {
    console.warn(
      `[config] invalid BOT_QUEUE_INTERVAL_MS="${raw}" — using ${DEFAULT_QUEUE_INTERVAL_MS}ms`,
    );
    return DEFAULT_QUEUE_INTERVAL_MS;
  }

  // Common mistake: "30" meaning 30 seconds, not 30ms.
  if (ms < 1000) ms *= 1000;

  if (ms < MIN_QUEUE_INTERVAL_MS) {
    console.warn(
      `[config] BOT_QUEUE_INTERVAL_MS=${ms}ms below minimum — using ${MIN_QUEUE_INTERVAL_MS}ms`,
    );
    return MIN_QUEUE_INTERVAL_MS;
  }
  if (ms > MAX_QUEUE_INTERVAL_MS) {
    console.warn(
      `[config] BOT_QUEUE_INTERVAL_MS=${ms}ms above maximum — using ${MAX_QUEUE_INTERVAL_MS}ms`,
    );
    return MAX_QUEUE_INTERVAL_MS;
  }
  return ms;
}

function parseRequeueDelayMs(): number {
  const raw = process.env.BOT_REQUEUE_DELAY_MS?.trim();
  if (!raw) return DEFAULT_REQUEUE_DELAY_MS;

  let ms = Number(raw);
  if (!Number.isFinite(ms) || ms < 0) {
    console.warn(
      `[config] invalid BOT_REQUEUE_DELAY_MS="${raw}" — using ${DEFAULT_REQUEUE_DELAY_MS}ms`,
    );
    return DEFAULT_REQUEUE_DELAY_MS;
  }
  if (ms > 0 && ms < 1000) ms *= 1000;
  if (ms > MAX_REQUEUE_DELAY_MS) {
    console.warn(
      `[config] BOT_REQUEUE_DELAY_MS=${ms}ms above maximum — using ${MAX_REQUEUE_DELAY_MS}ms`,
    );
    return MAX_REQUEUE_DELAY_MS;
  }
  return ms;
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
    matchDbPath: process.env.MATCH_DB_PATH?.trim() || "data/matches.db",
    queueIntervalMs: parseQueueIntervalMs(),
    requeueDelayMs: parseRequeueDelayMs(),
    llmBaseUrl: process.env.LLM_BASE_URL?.trim() || "http://127.0.0.1:11434/v1",
    llmModel: process.env.LLM_MODEL?.trim() || "gemma3:12b",
    llmApiKey: process.env.LLM_API_KEY?.trim() || undefined,
    llmTimeoutMs: Number(process.env.LLM_TIMEOUT_MS ?? 60_000),
  };
}
