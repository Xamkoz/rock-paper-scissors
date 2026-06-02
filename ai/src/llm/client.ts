export interface LlmConfig {
  /** OpenAI-compatible base URL, e.g. http://127.0.0.1:11434/v1 (Ollama). */
  baseUrl: string;
  model: string;
  /** Optional Bearer token for secured local gateways. */
  apiKey?: string;
  timeoutMs: number;
}

let config: LlmConfig | null = null;

export function initLlm(cfg: LlmConfig): void {
  config = {
    ...cfg,
    baseUrl: cfg.baseUrl.replace(/\/$/, ""),
  };
}

export function getLlmConfig(): LlmConfig {
  if (!config) throw new Error("LLM not initialized");
  return config;
}
