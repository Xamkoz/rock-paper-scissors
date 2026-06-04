/** Full AI gameplay logs (LLM req/res, move timing, intel dumps). Default: minimal. */
export function aiLogVerbose(): boolean {
  return process.env.AI_LOG_VERBOSE === "true";
}

export function llmLogRequestsEnabled(): boolean {
  return aiLogVerbose() || process.env.LLM_LOG_REQUESTS === "true";
}

export function llmLogResponsesEnabled(): boolean {
  return aiLogVerbose() || process.env.LLM_LOG_RESPONSES === "true";
}

export function gameplayDetailLogEnabled(): boolean {
  return aiLogVerbose() || process.env.AI_LOG_GAMEPLAY === "true";
}
