import type { Move } from "../types.js";

const MOVES: Move[] = ["ROCK", "PAPER", "SCISSORS"];

export function parseMoveChoice(text: string): Move | null {
  const trimmed = text.trim();
  try {
    const json = JSON.parse(trimmed) as { choice?: string };
    const fromJson = normalizeMove(json.choice);
    if (fromJson) return fromJson;
  } catch {
    // not JSON
  }
  const match = trimmed.toUpperCase().match(/\b(ROCK|PAPER|SCISSORS)\b/);
  return match ? normalizeMove(match[1]) : null;
}

function normalizeMove(value: string | undefined): Move | null {
  if (!value) return null;
  const upper = value.toUpperCase();
  return MOVES.includes(upper as Move) ? (upper as Move) : null;
}
