export interface LlmConfig {
  /** OpenAI-compatible base URL, e.g. http://127.0.0.1:11434/v1 (Ollama). */
  baseUrl: string;
  /** Up to three models probed at startup; active `model` is chosen by success ranking. */
  models: string[];
  /** Active model for move picks and describe (updated after startup ranking). */
  model: string;
  /** Optional Bearer token for secured local gateways. */
  apiKey?: string;
  timeoutMs: number;
}

let config: LlmConfig | null = null;

export function initLlm(cfg: LlmConfig): void {
  const models = cfg.models.length > 0 ? cfg.models : [cfg.model];
  config = {
    ...cfg,
    models,
    model: cfg.model || models[0]!,
    baseUrl: cfg.baseUrl.replace(/\/$/, ""),
  };
}

export function getLlmConfig(): LlmConfig {
  if (!config) throw new Error("LLM not initialized");
  return config;
}

export function getLlmModels(): string[] {
  return getLlmConfig().models;
}

export function setActiveLlmModel(model: string): void {
  if (!config) throw new Error("LLM not initialized");
  config = { ...config, model };
}
