import type { Move } from "../types.js";

const MOVES: Move[] = ["ROCK", "PAPER", "SCISSORS"];

/** Intel slice cited in the move-pick JSON (matches tactical intel sources + in-match adapt). */
export type MoveIntelSource =
  | "lifetime"
  | "h2h"
  | "recentVsOpponent"
  | "thisMatch";

/** Data parameter cited within that source (distribution, transitions, etc.). */
export type MoveIntelSignal =
  | "dominant"
  | "distribution"
  | "openWith"
  | "skew"
  | "secondary"
  | "repeatRate"
  | "lastWindow"
  | "transitions"
  | "responseToBot"
  | "repeat"
  | "recentSeq"
  | "h2hRecord"
  | "opponentLeanThisMatch"
  | "thisMatchRounds"
  | "preparedTactics"
  | "opponentLifetime"
  | "priorMatches"
  | "crossOpponent"
  | "sourcesByEfficiency";

export interface MovePickParsed {
  choice: Move;
  reason: string;
  intelSource: MoveIntelSource;
  intelSignal: MoveIntelSignal;
}

export const MOVE_PICK_JSON_SHAPE =
  '{"choice":"PAPER","intelSource":"h2h","intelSignal":"dominant","reason":"Paper lean — open Paper."}';

export const MOVE_PICK_JSON_EXAMPLE = MOVE_PICK_JSON_SHAPE;

const INTEL_SOURCE_ALIASES: Record<string, MoveIntelSource> = {
  lifetime: "lifetime",
  life: "lifetime",
  lifetimetendency: "lifetime",
  opponentlifetime: "lifetime",
  h2h: "h2h",
  headtohead: "h2h",
  head2head: "h2h",
  recentvsopponent: "recentVsOpponent",
  recent: "recentVsOpponent",
  recentopponent: "recentVsOpponent",
  thismatch: "thisMatch",
  match: "thisMatch",
  thismatchrounds: "thisMatch",
};

const INTEL_SIGNAL_ALIASES: Record<string, MoveIntelSignal> = {
  dominant: "dominant",
  lean: "dominant",
  read: "dominant",
  primary: "dominant",
  distribution: "distribution",
  throwmix: "distribution",
  mix: "distribution",
  percentages: "distribution",
  openwith: "openWith",
  opening: "openWith",
  counter: "openWith",
  suggestedopening: "openWith",
  skew: "skew",
  secondary: "secondary",
  repeatrate: "repeatRate",
  repeatratepct: "repeatRate",
  lastwindow: "lastWindow",
  recentwindow: "lastWindow",
  transitions: "transitions",
  transition: "transitions",
  aftermove: "transitions",
  responsetobot: "responseToBot",
  responsetobotmove: "responseToBot",
  whenbot: "responseToBot",
  repeat: "repeat",
  repeatstreak: "repeat",
  opponentrepeat: "repeat",
  recentseq: "recentSeq",
  sequence: "recentSeq",
  recentthrows: "recentSeq",
  h2hrecord: "h2hRecord",
  seriesrecord: "h2hRecord",
  opponentleanthismatch: "opponentLeanThisMatch",
  matchlean: "opponentLeanThisMatch",
  thismatchlean: "opponentLeanThisMatch",
  thismatchrounds: "thisMatchRounds",
  matchrounds: "thisMatchRounds",
  preparedtactics: "preparedTactics",
  tactics: "preparedTactics",
  plan: "preparedTactics",
  opponentlifetime: "opponentLifetime",
  lifetimetotals: "opponentLifetime",
  priormatches: "priorMatches",
  crossopponent: "crossOpponent",
  crosspatterns: "crossOpponent",
  sourcesbyefficiency: "sourcesByEfficiency",
  efficiency: "sourcesByEfficiency",
  efficiencystats: "sourcesByEfficiency",
};

const REASON_MAX_CHARS = 160;

function normalizeMove(value: string | undefined): Move | null {
  if (!value) return null;
  const upper = value.toUpperCase();
  return MOVES.includes(upper as Move) ? (upper as Move) : null;
}

function normalizeIntelSource(value: string | undefined): MoveIntelSource | null {
  if (!value?.trim()) return null;
  const key = value.trim().replace(/[\s_-]/g, "").toLowerCase();
  return INTEL_SOURCE_ALIASES[key] ?? null;
}

export function normalizeIntelSignal(value: string | undefined): MoveIntelSignal | null {
  if (!value?.trim()) return null;
  const key = value.trim().replace(/[\s_-]/g, "").toLowerCase();
  return INTEL_SIGNAL_ALIASES[key] ?? null;
}

function clampReason(text: string): string {
  const oneLine = text.replace(/\s+/g, " ").trim();
  if (!oneLine) return "";
  if (oneLine.length <= REASON_MAX_CHARS) return oneLine;
  return `${oneLine.slice(0, REASON_MAX_CHARS - 1)}…`;
}

function isReasonFieldDump(text: string): boolean {
  return /intelSource\s*[:=]/i.test(text) || /intelSignal\s*[:=]/i.test(text);
}

function extractCitationFromBlob(blob: string): {
  intelSource: MoveIntelSource | null;
  intelSignal: MoveIntelSignal | null;
} {
  const src = blob.match(/intelSource\s*[:=]\s*["']?([a-zA-Z][a-zA-Z0-9]*)/i);
  const sig = blob.match(/intelSignal\s*[:=]\s*["']?([a-zA-Z][a-zA-Z0-9]*)/i);
  return {
    intelSource: normalizeIntelSource(src?.[1]),
    intelSignal: normalizeIntelSignal(sig?.[1]),
  };
}

const SOURCE_IN_PROSE: Array<{ re: RegExp; source: MoveIntelSource }> = [
  { re: /\bh2h\b/i, source: "h2h" },
  { re: /\brecentvsopponent\b/i, source: "recentVsOpponent" },
  { re: /\brecent\s+(throws|seq|sequence)\b/i, source: "recentVsOpponent" },
  { re: /\blifetime\b/i, source: "lifetime" },
  { re: /\bthismatch\b/i, source: "thisMatch" },
  { re: /\bthis\s+match\b/i, source: "thisMatch" },
];

/** Infer citation when the model only narrates intel in reason (or JSON was truncated). */
export function inferCitationFromProse(blob: string): {
  intelSource: MoveIntelSource | null;
  intelSignal: MoveIntelSignal | null;
} {
  const explicit = extractCitationFromBlob(blob);
  if (explicit.intelSource && explicit.intelSignal) return explicit;

  let intelSource = explicit.intelSource;
  if (!intelSource) {
    for (const { re, source } of SOURCE_IN_PROSE) {
      if (re.test(blob)) {
        intelSource = source;
        break;
      }
    }
  }

  let intelSignal = explicit.intelSignal;
  if (!intelSignal) {
    if (/h2h\s+record|series\s+(wins|record)|botserieswins/i.test(blob)) {
      intelSignal = "h2hRecord";
    } else if (/recentseq|recent\s+seq/i.test(blob)) {
      intelSignal = "recentSeq";
    } else if (
      /open(ing)?\s+with|recommended\s+opening|suggestedopening|openwith/i.test(blob)
    ) {
      intelSignal = "openWith";
    } else if (/preparedtactics|tactical\s+plan/i.test(blob)) {
      intelSignal = "preparedTactics";
    } else if (/lean|dominant|read\b/i.test(blob)) {
      intelSignal = "dominant";
    } else if (/transition/i.test(blob)) {
      intelSignal = "transitions";
    } else if (/distribution|mix\b/i.test(blob)) {
      intelSignal = "distribution";
    }
  }

  return { intelSource, intelSignal };
}

function humanReasonFromCitation(
  source: MoveIntelSource,
  signal: MoveIntelSignal,
  rawReason?: string,
): string {
  if (rawReason && !isReasonFieldDump(rawReason)) {
    return clampReason(rawReason);
  }
  const signalLabel = signal === "dominant" ? "lean" : signal;
  return clampReason(`Using ${source} ${signalLabel} for this pick.`);
}

type LoosePickJson = {
  choice?: string;
  reason?: string;
  intelSource?: string;
  intelSignal?: string;
  signal?: string;
  parameter?: string;
  intel?: string;
  source?: string;
};

function parseStrictMovePick(json: LoosePickJson): MovePickParsed | null {
  const choice = normalizeMove(json.choice);
  if (!choice) return null;

  let intelSource =
    normalizeIntelSource(json.intelSource) ??
    normalizeIntelSource(json.intel) ??
    normalizeIntelSource(json.source);

  let intelSignal =
    normalizeIntelSignal(json.intelSignal) ??
    normalizeIntelSignal(json.signal) ??
    normalizeIntelSignal(json.parameter);

  const reasonRaw = String(json.reason ?? "");

  if (!intelSource || !intelSignal) {
    const fromBlob = isReasonFieldDump(reasonRaw)
      ? extractCitationFromBlob(reasonRaw)
      : inferCitationFromProse(reasonRaw);
    intelSource = intelSource ?? fromBlob.intelSource;
    intelSignal = intelSignal ?? fromBlob.intelSignal;
  }

  if (!intelSource || !intelSignal) {
    const inferred = inferCitationFromProse(
      `${reasonRaw} ${JSON.stringify(json)}`,
    );
    intelSource = intelSource ?? inferred.intelSource;
    intelSignal = intelSignal ?? inferred.intelSignal;
  }

  if (!intelSource || !intelSignal) return null;

  const reason = humanReasonFromCitation(intelSource, intelSignal, reasonRaw);
  if (!reason) return null;

  return { choice, reason, intelSource, intelSignal };
}

function extractReasonFromPartialJson(text: string): string {
  const closed = text.match(/"reason"\s*:\s*"([^"]*)"/i);
  if (closed?.[1]) return closed[1];
  const open = text.match(/"reason"\s*:\s*"([\s\S]*)$/i);
  return open?.[1]?.replace(/\\"/g, '"').trim() ?? "";
}

function salvageFromText(text: string): MovePickParsed | null {
  const choiceMatch =
    text.match(/"choice"\s*:\s*"(ROCK|PAPER|SCISSORS)"/i) ??
    text.match(/\bchoice\s*[:=]\s*["']?(ROCK|PAPER|SCISSORS)/i);
  const choice = normalizeMove(choiceMatch?.[1]);
  if (!choice) return null;

  const citation = inferCitationFromProse(text);
  if (!citation.intelSource || !citation.intelSignal) return null;

  const reasonRaw = extractReasonFromPartialJson(text);

  return {
    choice,
    reason: humanReasonFromCitation(
      citation.intelSource,
      citation.intelSignal,
      reasonRaw,
    ),
    intelSource: citation.intelSource,
    intelSignal: citation.intelSignal,
  };
}

export function parseMovePick(text: string): MovePickParsed | null {
  const trimmed = text.trim();

  try {
    const parsed = parseStrictMovePick(JSON.parse(trimmed) as LoosePickJson);
    if (parsed) return parsed;
  } catch {
    // not JSON — try salvage below
  }

  const choiceOnly = trimmed.match(/^\s*\{\s*"choice"\s*:\s*"(ROCK|PAPER|SCISSORS)"/i);
  if (choiceOnly) {
    const salvaged = salvageFromText(trimmed);
    if (salvaged) return salvaged;
  }

  return salvageFromText(trimmed);
}

/** Choice only (legacy / plain-text fallback); move picks should use parseMovePick. */
export function parseMoveChoice(text: string): Move | null {
  const pick = parseMovePick(text);
  if (pick) return pick.choice;

  const trimmed = text.trim();
  const match = trimmed.toUpperCase().match(/\b(ROCK|PAPER|SCISSORS)\b/);
  return match ? normalizeMove(match[1]) : null;
}

export function formatMovePickLogLine(pick: MovePickParsed): string {
  return `${pick.intelSource}/${pick.intelSignal}: ${pick.reason}`;
}
