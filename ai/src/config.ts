import { readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import dotenv from "dotenv";

const here = dirname(fileURLToPath(import.meta.url));
/** `ai/` package root — load `.env` here regardless of process cwd. */
export const packageRoot = resolve(here, "..");

dotenv.config({ path: resolve(packageRoot, ".env") });

const DEFAULT_REQUEUE_DELAY_MS = 60_000;

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
  /** Ms to wait after a game before re-joining queue (`BOT_REQUEUE_DELAY_MS`). 0 = immediate. */
  requeueDelayMs: number;
  /** OpenAI-compatible LLM base URL (Ollama default: http://127.0.0.1:11434/v1). */
  llmBaseUrl: string;
  /** Primary / default model (first in `llmModels` when unset). */
  llmModel: string;
  /** Up to three models for startup warmup and success ranking (`LLM_MODELS`). */
  llmModels: string[];
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

/** Post-game re-queue pause. Value is always milliseconds (60000 = 60s, 1200000 = 20m). */
function parseRequeueDelayMs(): number {
  const raw = process.env.BOT_REQUEUE_DELAY_MS?.trim();
  if (!raw) return DEFAULT_REQUEUE_DELAY_MS;

  const ms = Number(raw);
  if (!Number.isFinite(ms) || ms < 0) {
    console.warn(
      `[config] invalid BOT_REQUEUE_DELAY_MS="${raw}" — using ${DEFAULT_REQUEUE_DELAY_MS}ms`,
    );
    return DEFAULT_REQUEUE_DELAY_MS;
  }
  return ms;
}

/** Comma-separated `LLM_MODELS`, else `[llmModel]`; deduped, max 3. */
export function parseLlmModels(llmModel: string, raw?: string): string[] {
  const parts = (raw ?? "")
    .split(",")
    .map((m) => m.trim())
    .filter(Boolean);
  const models = parts.length > 0 ? parts : [llmModel];
  const seen = new Set<string>();
  const unique: string[] = [];
  for (const m of models) {
    if (seen.has(m)) continue;
    seen.add(m);
    unique.push(m);
    if (unique.length >= 3) break;
  }
  return unique;
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
    requeueDelayMs: parseRequeueDelayMs(),
    llmBaseUrl: process.env.LLM_BASE_URL?.trim() || "http://127.0.0.1:11434/v1",
    llmModel: process.env.LLM_MODEL?.trim() || "gemma3:4b",
    llmModels: parseLlmModels(
      process.env.LLM_MODEL?.trim() || "gemma3:4b",
      process.env.LLM_MODELS?.trim(),
    ),
    llmApiKey: process.env.LLM_API_KEY?.trim() || undefined,
    llmTimeoutMs: Number(process.env.LLM_TIMEOUT_MS ?? 60_000),
  };
}
