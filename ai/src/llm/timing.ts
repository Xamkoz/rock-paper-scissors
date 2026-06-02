/** Ms reserved after the LLM returns before the round deadline (submit + margin). */
export function pickSubmitReserveMs(): number {
  const raw = process.env.LLM_PICK_SUBMIT_RESERVE_MS?.trim();
  const ms = raw ? Number(raw) : 2500;
  return Number.isFinite(ms) && ms >= 1000 ? ms : 2500;
}

/** Cap on move-pick LLM timeout (ms). Honors legacy LLM_PICK_MIN_BUDGET_MS env name. */
export function pickMoveTimeoutCapMs(): number {
  const raw =
    process.env.LLM_PICK_TIMEOUT_CAP_MS?.trim() ??
    process.env.LLM_PICK_MIN_BUDGET_MS?.trim();
  const ms = raw ? Number(raw) : 30_000;
  return Number.isFinite(ms) && ms >= 3000 ? ms : 30_000;
}

/** @deprecated Use pickMoveTimeoutCapMs */
export const pickMinBudgetMs = pickMoveTimeoutCapMs;

/** Max tokens for move-pick LLM call (small JSON; lower = faster generation). */
export function pickMoveMaxTokens(): number {
  const raw = process.env.LLM_PICK_MAX_TOKENS?.trim();
  const n = raw ? Number(raw) : 128;
  return Number.isFinite(n) && n >= 16 ? n : 128;
}

/** Max characters stored for a post-game LLM recap. */
export function describeMaxChars(): number {
  const raw = process.env.LLM_DESCRIBE_MAX_CHARS?.trim();
  const n = raw ? Number(raw) : 240;
  return Number.isFinite(n) && n >= 40 ? n : 240;
}

/** Max tokens for describe LLM call. */
export function describeMaxTokens(): number {
  const raw = process.env.LLM_DESCRIBE_MAX_TOKENS?.trim();
  const n = raw ? Number(raw) : 96;
  return Number.isFinite(n) && n >= 16 ? n : 96;
}

/** Max words in a post-game recap. */
export function describeMaxWords(): number {
  const raw = process.env.LLM_DESCRIBE_MAX_WORDS?.trim();
  const n = raw ? Number(raw) : 36;
  return Number.isFinite(n) && n >= 8 ? n : 36;
}

/** Max sentences before word/char clamp. */
export function describeMaxSentences(): number {
  const raw = process.env.LLM_DESCRIBE_MAX_SENTENCES?.trim();
  const n = raw ? Number(raw) : 2;
  return Number.isFinite(n) && n >= 1 ? n : 2;
}

/** Max characters for pre-match tactical plan text. */
export function tacticsMaxChars(): number {
  const raw = process.env.LLM_TACTICS_MAX_CHARS?.trim();
  const n = raw ? Number(raw) : 960;
  return Number.isFinite(n) && n >= 80 ? n : 960;
}

/** Max tokens for tactics LLM call (round 1, before first pick). */
export function tacticsMaxTokens(): number {
  const raw = process.env.LLM_TACTICS_MAX_TOKENS?.trim();
  const n = raw ? Number(raw) : 128;
  return Number.isFinite(n) && n >= 32 ? n : 128;
}

/** When false (default), use deterministic tactics text — skips one LLM call on round 1. */
export function tacticsUseLlm(): boolean {
  const raw = process.env.LLM_TACTICS_USE_LLM?.trim().toLowerCase();
  return raw === "true" || raw === "1" || raw === "yes";
}

/** Timeout for the pre-match tactics LLM call. */
export function tacticsBudgetMs(): number {
  const raw = process.env.LLM_TACTICS_BUDGET_MS?.trim();
  const n = raw ? Number(raw) : 30_000;
  return Number.isFinite(n) && n >= 3000 ? n : 30_000;
}
