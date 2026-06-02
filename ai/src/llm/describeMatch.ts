import type { Match } from "../types.js";
import { chatComplete } from "./chat.js";
import { compactMatchForPick } from "./compactMatch.js";
import type { MatchDbContext } from "./matchContext.js";

const DESCRIBE_SYSTEM = `One sentence (max 20 words) summarizing an RPS match. Plain text only.`;

function buildDescribeUserPrompt(match: Match, selfUid: string, ctx: MatchDbContext): string {
  const snap = compactMatchForPick(match, selfUid);
  const payload = {
    end: [snap.yourWins, snap.opponentWins],
    vs: ctx.opponentName,
    mode: snap.mode,
    prior: snap.priorRounds,
    h2h: ctx.headToHead.length,
  };
  return JSON.stringify(payload);
}

export async function describeMatchWithLlm(
  match: Match,
  selfUid: string,
  ctx: MatchDbContext,
): Promise<string> {
  const { text } = await chatComplete(
    DESCRIBE_SYSTEM,
    buildDescribeUserPrompt(match, selfUid, ctx),
    {
      maxTokens: 48,
      temperature: 0.2,
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
