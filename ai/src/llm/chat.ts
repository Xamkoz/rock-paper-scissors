import { getLlmConfig } from "./client.js";
import { error, log, msSince } from "../log.js";
import { MOVE_SYSTEM_PROMPT } from "./movePrompt.js";
import { parseMoveChoice } from "./parse.js";

/** Tiny payload for post-start chat verification. */
const POST_START_WARMUP_USER = '{"r":1,"sc":[0,0],"vs":"warmup","prior":[],"h2h":[]}';

function truncateForLog(text: string, maxChars: number): string {
  if (text.length <= maxChars) return text;
  return `${text.slice(0, maxChars)}\n… (${text.length - maxChars} more chars)`;
}

function previewLines(text: string, maxLines: number, maxLineChars: number): string {
  const lines = text.split("\n").slice(0, maxLines);
  const shown = lines
    .map((line) => {
      const trimmed = line.trimEnd();
      if (trimmed.length <= maxLineChars) return trimmed;
      return `${trimmed.slice(0, maxLineChars)}…`;
    })
    .join("\n");
  const totalLines = text.split("\n").length;
  if (totalLines > maxLines) {
    return `${shown}\n… (${totalLines - maxLines} more lines, ${text.length} chars)`;
  }
  if (text.length > shown.length) {
    return `${shown}\n… (${text.length} chars)`;
  }
  return shown;
}

function logLlmRequest(
  tag: string,
  model: string,
  systemPrompt: string,
  userPrompt: string,
  summary?: string,
): void {
  if (process.env.LLM_LOG_REQUESTS === "false") return;

  const header = `[llm:${tag}:req] model=${model}${summary ? ` ${summary}` : ""}`;

  if (process.env.LLM_LOG_PROMPT_BODY === "true") {
    const maxChars = Number(process.env.LLM_LOG_MAX_CHARS ?? 4000);
    log(header);
    log(`[llm:${tag}:req] system:\n${truncateForLog(systemPrompt, maxChars)}`);
    log(`[llm:${tag}:req] user:\n${truncateForLog(userPrompt, maxChars)}`);
    return;
  }

  const previewLinesN = Number(process.env.LLM_LOG_PREVIEW_LINES ?? 8);
  const previewLineChars = Number(process.env.LLM_LOG_PREVIEW_LINE_CHARS ?? 160);

  log(header);
  const systemPreview = previewLines(systemPrompt, previewLinesN, previewLineChars);
  if (systemPreview) log(`[llm:${tag}:req] system:\n${systemPreview}`);
  const userPreview = previewLines(userPrompt, previewLinesN, previewLineChars);
  if (userPreview) log(`[llm:${tag}:req] user:\n${userPreview}`);
}

interface ChatCompletionResponse {
  choices?: Array<{ message?: { content?: string } }>;
  error?: { message?: string };
}

export interface ChatResult {
  text: string;
  durationMs: number;
}

export async function chatComplete(
  systemPrompt: string,
  userPrompt: string,
  options: {
    maxTokens?: number;
    temperature?: number;
    json?: boolean;
    logLabel?: string;
    /** Short line in request logs (full prompts omitted unless LLM_LOG_PROMPT_BODY=true). */
    logSummary?: string;
    /** Per-call timeout (e.g. round deadline budget); defaults to config llmTimeoutMs. */
    timeoutMs?: number;
  } = {},
): Promise<ChatResult> {
  const { baseUrl, model, apiKey, timeoutMs: configTimeoutMs } = getLlmConfig();
  const timeoutMs = options.timeoutMs ?? configTimeoutMs;
  const url = `${baseUrl}/chat/completions`;

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (apiKey) headers.Authorization = `Bearer ${apiKey}`;

  const body: Record<string, unknown> = {
    model,
    messages: [
      { role: "system", content: systemPrompt },
      { role: "user", content: userPrompt },
    ],
    temperature: options.temperature ?? 0.7,
    max_tokens: options.maxTokens ?? 128,
    stream: false,
  };
  if (options.json) {
    body.response_format = { type: "json_object" };
  }

  const tag = options.logLabel ?? "chat";
  logLlmRequest(tag, model, systemPrompt, userPrompt, options.logSummary);

  const startedAt = Date.now();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const res = await fetch(url, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      signal: controller.signal,
    });

    const data = (await res.json()) as ChatCompletionResponse;
    if (!res.ok) {
      const msg = data.error?.message ?? res.statusText;
      throw new Error(`LLM HTTP ${res.status}: ${msg}`);
    }

    const text = data.choices?.[0]?.message?.content?.trim() ?? "";
    if (!text) throw new Error("LLM returned empty content");

    const durationMs = msSince(startedAt);
    log(`[llm:${tag}:res] ${durationMs}ms ${text}`);
    return { text, durationMs };
  } catch (err) {
    error(`[llm:${tag}:res] ${msSince(startedAt)}ms failed`, err);
    throw err;
  } finally {
    clearTimeout(timer);
  }
}

/** After bot start: one real chat/completions call to confirm the model loads and returns a move. */
export async function verifyLlmAfterStart(): Promise<boolean> {
  const { timeoutMs } = getLlmConfig();
  const startedAt = Date.now();
  try {
    const { text, durationMs } = await chatComplete(
      MOVE_SYSTEM_PROMPT,
      POST_START_WARMUP_USER,
      {
        maxTokens: 16,
        temperature: 0,
        json: true,
        logLabel: "post-start",
        logSummary: "warmup",
        timeoutMs: Math.min(timeoutMs, 30_000),
      },
    );
    const choice = parseMoveChoice(text);
    if (!choice) {
      error(`[ai] llm post-start invalid response (${durationMs}ms): ${text.slice(0, 120)}`);
      return false;
    }
    log(`[ai] llm post-start ok ${durationMs}ms choice=${choice}`);
    return true;
  } catch (err) {
    error(`[ai] llm post-start failed ${msSince(startedAt)}ms`, err);
    return false;
  }
}

/** Pre-start: HTTP reachability via GET /models. */
export async function probeLlm(): Promise<boolean> {
  const { baseUrl, apiKey, timeoutMs } = getLlmConfig();
  const headers: Record<string, string> = {};
  if (apiKey) headers.Authorization = `Bearer ${apiKey}`;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.min(timeoutMs, 10_000));

  try {
    const res = await fetch(`${baseUrl}/models`, {
      headers,
      signal: controller.signal,
    });
    return res.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}
