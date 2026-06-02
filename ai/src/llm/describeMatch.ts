import type { Match } from "../types.js";
import { chatComplete } from "./chat.js";
import type { MatchDbContext } from "./matchContext.js";
import { formatMatchDbContextForPrompt } from "./matchContext.js";

const SYSTEM_PROMPT = `You write one short sentence (max 25 words) summarizing a rock-paper-scissors match for a log.
Use the match database context for background. No quotes, no markdown.`;

function buildDescribeUserPrompt(match: Match, selfUid: string, ctx: MatchDbContext): string {
  return `Summarize this match for the bot (uid=${selfUid}).

ENDED_MATCH:
${JSON.stringify(match, null, 2)}

MATCH_DATABASE:
${formatMatchDbContextForPrompt(ctx)}`;
}

export async function describeMatchWithLlm(
  match: Match,
  selfUid: string,
  ctx: MatchDbContext,
): Promise<string> {
  const { text } = await chatComplete(
    SYSTEM_PROMPT,
    buildDescribeUserPrompt(match, selfUid, ctx),
    {
      maxTokens: 80,
      temperature: 0.5,
      logLabel: `describe ${match.id}`,
      logSummary: `match=${match.id}`,
    },
  );

  const description = text.trim().replace(/\s+/g, " ");
  if (description.length === 0 || description.length > 200) {
    throw new Error(`LLM returned invalid match description: ${description.slice(0, 200)}`);
  }
  return description;
}
