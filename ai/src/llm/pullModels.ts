import { getLlmConfig } from "./client.js";
import { log, warn } from "../log.js";

/** OpenAI base URL → Ollama host root (strip `/v1`). */
export function llmServerRoot(baseUrl: string): string {
  return baseUrl.replace(/\/$/, "").replace(/\/v1$/, "");
}

export async function listInstalledLlmModels(
  baseUrl: string,
  apiKey?: string,
): Promise<string[]> {
  const headers: Record<string, string> = {};
  if (apiKey) headers.Authorization = `Bearer ${apiKey}`;

  const res = await fetch(`${baseUrl.replace(/\/$/, "")}/models`, { headers });
  if (!res.ok) return [];
  const data = (await res.json()) as { data?: Array<{ id?: string }> };
  return data.data?.map((m) => m.id).filter((id): id is string => Boolean(id)) ?? [];
}

function isOllamaPullSupported(baseUrl: string): boolean {
  if (process.env.LLM_AUTO_PULL === "false" || process.env.LLM_AUTO_PULL === "0") {
    return false;
  }
  const root = llmServerRoot(baseUrl);
  return (
    root.includes("11434") ||
    root.includes("ollama") ||
    process.env.LLM_AUTO_PULL === "true" ||
    process.env.LLM_AUTO_PULL === "1"
  );
}

/** `POST /api/pull` (Ollama); logs download progress. */
export async function pullOllamaModel(
  baseUrl: string,
  model: string,
  apiKey?: string,
): Promise<boolean> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (apiKey) headers.Authorization = `Bearer ${apiKey}`;

  const res = await fetch(`${llmServerRoot(baseUrl)}/api/pull`, {
    method: "POST",
    headers,
    body: JSON.stringify({ name: model, stream: true }),
  });

  if (!res.ok) {
    warn(`[llm:pull] ${model} HTTP ${res.status} ${res.statusText}`);
    return false;
  }

  const body = res.body;
  if (!body) {
    warn(`[llm:pull] ${model} empty response`);
    return false;
  }

  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let lastLog = "";
  let ok = false;

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      try {
        const msg = JSON.parse(trimmed) as {
          status?: string;
          error?: string;
          completed?: number;
          total?: number;
        };
        if (msg.error) {
          warn(`[llm:pull] ${model} error: ${msg.error}`);
          return false;
        }
        const status = msg.status ?? "";
        if (status === "success") {
          ok = true;
          continue;
        }
        let detail = status;
        if (
          status === "downloading" &&
          typeof msg.total === "number" &&
          msg.total > 0 &&
          typeof msg.completed === "number"
        ) {
          const pct = Math.round((msg.completed / msg.total) * 100);
          detail = `downloading ${pct}%`;
        }
        if (detail && detail !== lastLog) {
          log(`[llm:pull] ${model} ${detail}`);
          lastLog = detail;
        }
      } catch {
        // ignore non-JSON chunks
      }
    }
  }

  if (ok) {
    log(`[llm:pull] ${model} ready`);
  } else {
    warn(`[llm:pull] ${model} finished without success status`);
  }
  return ok;
}

/** Pull any configured models missing from the local Ollama (or compatible) server. */
export async function pullMissingLlmModels(): Promise<string[]> {
  const { baseUrl, models, apiKey } = getLlmConfig();
  if (!isOllamaPullSupported(baseUrl)) {
    return [];
  }

  const installed = new Set(await listInstalledLlmModels(baseUrl, apiKey));
  const missing = models.filter((m) => !installed.has(m));
  if (missing.length === 0) {
    log(`[llm:pull] all configured models present (${models.join(", ")})`);
    return [];
  }

  log(`[llm:pull] missing on server: ${missing.join(", ")}`);
  const pulled: string[] = [];
  for (const model of missing) {
    log(`[llm:pull] pulling ${model}…`);
    const ok = await pullOllamaModel(baseUrl, model, apiKey);
    if (ok) pulled.push(model);
  }
  return pulled;
}
