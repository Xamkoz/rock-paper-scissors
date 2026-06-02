import { getLlmConfig } from "./client.js";
import { error, log, msSince } from "../log.js";
import { MOVE_PICK_JSON_SHAPE, parseMoveChoice, parseMovePick } from "./parse.js";

/** Minimal prompts — full MOVE_SYSTEM_PROMPT is too large; 16 tokens truncated JSON. */
export const POST_START_SYSTEM_PROMPT = [
  "Pick one rock-paper-scissors move for warmup.",
  `Reply JSON only: ${MOVE_PICK_JSON_SHAPE}.`,
].join(" ");

export const POST_START_WARMUP_USER =
  '{"bot":"warmup","opponent":"warmup","round":1,"score":{"bot":0,"opponent":0},"thisMatchRounds":[],"intelCatalog":[{"source":"thisMatch","signals":["thisMatchRounds"]}]}';

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
  log(header);

  if (process.env.LLM_LOG_PROMPT_PREVIEW === "true") {
    const previewLinesN = Number(process.env.LLM_LOG_PREVIEW_LINES ?? 8);
    const previewLineChars = Number(process.env.LLM_LOG_PREVIEW_LINE_CHARS ?? 160);
    const systemPreview = previewLines(systemPrompt, previewLinesN, previewLineChars);
    if (systemPreview) log(`[llm:${tag}:req] system:\n${systemPreview}`);
    const userPreview = previewLines(userPrompt, previewLinesN, previewLineChars);
    if (userPreview) log(`[llm:${tag}:req] user:\n${userPreview}`);
    return;
  }

  const maxChars = process.env.LLM_LOG_MAX_CHARS
    ? Number(process.env.LLM_LOG_MAX_CHARS)
    : undefined;
  const systemBody = maxChars ? truncateForLog(systemPrompt, maxChars) : systemPrompt;
  const userBody = maxChars ? truncateForLog(userPrompt, maxChars) : userPrompt;
  log(`[llm:${tag}:req] system:\n${systemBody}`);
  log(`[llm:${tag}:req] user:\n${userBody}`);
}

interface ChatCompletionResponse {
  choices?: Array<{ message?: { content?: string } }>;
  error?: { message?: string };
}

export interface ChatResult {
  text: string;
  durationMs: number;
}

/** Ollama/vLLM extras: LLM_OPTIONS_JSON object merge; LLM_NUM_CTX sets options.num_ctx. */
function applyLlmServerOptions(body: Record<string, unknown>, maxTokens: number): void {
  const rawJson = process.env.LLM_OPTIONS_JSON?.trim();
  if (rawJson) {
    try {
      const extra = JSON.parse(rawJson) as Record<string, unknown>;
      Object.assign(body, extra);
    } catch {
      // ignore invalid JSON
    }
  }
  const rawCtx = process.env.LLM_NUM_CTX?.trim();
  if (!rawCtx) return;
  const numCtx = Number(rawCtx);
  if (!Number.isFinite(numCtx) || numCtx < 512) return;
  const existing = (body.options as Record<string, unknown> | undefined) ?? {};
  body.options = { ...existing, num_ctx: numCtx, num_predict: maxTokens };
}

export async function chatComplete(
  systemPrompt: string,
  userPrompt: string,
  options: {
    maxTokens?: number;
    temperature?: number;
    json?: boolean;
    logLabel?: string;
    /** Extra metadata appended to the request log header. */
    logSummary?: string;
    /** Per-call timeout (e.g. round deadline budget); defaults to config llmTimeoutMs. */
    timeoutMs?: number;
    /** Override active model (startup warmup / A-B without changing config). */
    model?: string;
  } = {},
): Promise<ChatResult> {
  const { baseUrl, model: activeModel, apiKey, timeoutMs: configTimeoutMs } = getLlmConfig();
  const model = options.model ?? activeModel;
  const timeoutMs = options.timeoutMs ?? configTimeoutMs;
  const url = `${baseUrl}/chat/completions`;

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (apiKey) headers.Authorization = `Bearer ${apiKey}`;

  const maxTokens = options.maxTokens ?? 128;
  const body: Record<string, unknown> = {
    model,
    messages: [
      { role: "system", content: systemPrompt },
      { role: "user", content: userPrompt },
    ],
    temperature: options.temperature ?? 0.7,
    max_tokens: maxTokens,
    stream: false,
  };
  if (options.json) {
    body.response_format = { type: "json_object" };
  }
  applyLlmServerOptions(body, maxTokens);

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
      POST_START_SYSTEM_PROMPT,
      POST_START_WARMUP_USER,
      {
        maxTokens: 64,
        temperature: 0,
        json: true,
        logLabel: "post-start",
        logSummary: "warmup",
        timeoutMs: Math.min(timeoutMs, 30_000),
      },
    );
    const pick = parseMovePick(text);
    if (!pick) {
      const choiceOnly = parseMoveChoice(text);
      if (choiceOnly) {
        log(
          `[ai] llm post-start ok ${durationMs}ms choice=${choiceOnly} (partial JSON, len=${text.length})`,
        );
        return true;
      }
      error(
        `[ai] llm post-start invalid response (${durationMs}ms, ${text.length} chars): ${text.slice(0, 200)}`,
      );
      return false;
    }
    log(
      `[ai] llm post-start ok ${durationMs}ms choice=${pick.choice} (${pick.intelSource}/${pick.intelSignal})`,
    );
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
