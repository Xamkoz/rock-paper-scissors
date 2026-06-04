import type { Move } from "../types.js";

const MOVES: Move[] = ["ROCK", "PAPER", "SCISSORS"];

/** Intel slice cited in the move-pick JSON (matches tactical intel sources + in-match adapt). */
export type MoveIntelSource =
  | "lifetime"
  | "h2h"
  | "recentVsOpponent"
  | "global"
  | "thisMatch";

/** Data parameter cited within that source (distribution, transitions, etc.). */
export type MoveIntelSignal =
  | "dominant"
  | "distribution"
  | "openWith"
  | "skew"
  | "secondary"
  | "repeatRate"
  | "alternationRate"
  | "lastWindow"
  | "transitions"
  | "secondOrderTransition"
  | "responseToBot"
  | "afterBotWin"
  | "afterBotLoss"
  | "streakBreakBias"
  | "repeat"
  | "recentSeq"
  | "h2hRecord"
  | "opponentLeanThisMatch"
  | "thisMatchRounds"
  | "matchScore"
  | "clinchPressure"
  | "preparedTactics"
  | "opponentLifetime"
  | "priorMatches"
  | "crossOpponent"
  | "sourcesByEfficiency";

export interface MovePickParsed {
  choice: Move;
  reason: string;
  thoughtProcess?: string;
  intelSource: MoveIntelSource;
  intelSignal: MoveIntelSignal;
}

export const MOVE_PICK_JSON_SHAPE =
  '{"choice":"PAPER","intelSource":"h2h","intelSignal":"dominant","reason":"H2H Rock lean — Paper beats Rock.","thoughtProcess":"H2H Rock lean at 60%. citeHints favor dominant. Counter with Paper."}';

export const MOVE_PICK_JSON_EXAMPLE_TRANSITIONS =
  '{"choice":"SCISSORS","intelSource":"h2h","intelSignal":"transitions","reason":"After Paper they throw Rock — Scissors.","thoughtProcess":"After their Paper, transitions favor Rock. Scissors beats Rock."}';

export function movePickJsonExample(round: number): string {
  return round % 2 === 0 ? MOVE_PICK_JSON_EXAMPLE_TRANSITIONS : MOVE_PICK_JSON_SHAPE;
}

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
  global: "global",
  population: "global",
  meta: "global",
  allopponents: "global",
  allopponent: "global",
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
  alternationrate: "alternationRate",
  alternation: "alternationRate",
  switches: "alternationRate",
  lastwindow: "lastWindow",
  recentwindow: "lastWindow",
  transitions: "transitions",
  transition: "transitions",
  aftermove: "transitions",
  secondordertransition: "secondOrderTransition",
  secondorder: "secondOrderTransition",
  aftersequence: "secondOrderTransition",
  afterseq: "secondOrderTransition",
  responsetobot: "responseToBot",
  responsetobotmove: "responseToBot",
  whenbot: "responseToBot",
  afterbotwin: "afterBotWin",
  afterwin: "afterBotWin",
  whenbotwon: "afterBotWin",
  afterbotloss: "afterBotLoss",
  afterloss: "afterBotLoss",
  whenbotlost: "afterBotLoss",
  streakbreakbias: "streakBreakBias",
  streakbias: "streakBreakBias",
  streakbreak: "streakBreakBias",
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
  matchscore: "matchScore",
  score: "matchScore",
  seriesscore: "matchScore",
  clinchpressure: "clinchPressure",
  clinch: "clinchPressure",
  matchpoint: "clinchPressure",
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
/** Longer cap for log lines so reasoning is not truncated as aggressively. */
const REASON_LOG_MAX_CHARS = 320;

function normalizeMove(value: string | undefined): Move | null {
  if (!value) return null;
  const upper = value.toUpperCase();
  return MOVES.includes(upper as Move) ? (upper as Move) : null;
}

function normalizeIntelSource(value: string | undefined): MoveIntelSource | null {
  if (!value?.trim()) return null;
  const trimmed = value.trim();
  if (trimmed.includes("/")) {
    return normalizeIntelSource(trimmed.split("/", 1)[0]);
  }
  const key = trimmed.replace(/[\s_-]/g, "").toLowerCase();
  return INTEL_SOURCE_ALIASES[key] ?? null;
}

/** Models cite JSON payload keys (tacticalIntel, preparedTactics) instead of catalog sources. */
function normalizePayloadIntelSource(
  value: string | undefined,
  primarySource?: MoveIntelSource,
): MoveIntelSource | null {
  const direct = normalizeIntelSource(value);
  if (direct) return direct;
  if (!value?.trim()) return null;
  const key = value.trim().replace(/[\s_-]/g, "").toLowerCase();
  if (key === "tacticalintel" || key === "tactical" || key === "primaryread") {
    return primarySource ?? null;
  }
  if (key === "preparedtactics" || key === "tactics" || key === "plan") {
    return "thisMatch";
  }
  return null;
}

/** Models often copy citeHints `source/signal` into intelSource only. */
function parseSlashCitation(value: string | undefined): {
  intelSource: MoveIntelSource | null;
  intelSignal: MoveIntelSignal | null;
} {
  if (!value?.includes("/")) {
    return { intelSource: null, intelSignal: null };
  }
  const [srcPart, sigPart] = value.split("/", 2);
  return {
    intelSource: normalizeIntelSource(srcPart),
    intelSignal: normalizeIntelSignal(sigPart),
  };
}

function parseCombinedIntelFields(json: LoosePickJson): {
  intelSource: MoveIntelSource | null;
  intelSignal: MoveIntelSignal | null;
} {
  for (const raw of [json.intelSource, json.intel, json.source]) {
    const parsed = parseSlashCitation(raw);
    if (parsed.intelSource && parsed.intelSignal) return parsed;
  }
  return { intelSource: null, intelSignal: null };
}

export function normalizeIntelSignal(value: string | undefined): MoveIntelSignal | null {
  if (!value?.trim()) return null;
  const key = value.trim().replace(/[\s_-]/g, "").toLowerCase();
  return INTEL_SIGNAL_ALIASES[key] ?? null;
}

function clampReason(text: string, maxChars = REASON_MAX_CHARS): string {
  const oneLine = text.replace(/\s+/g, " ").trim();
  if (!oneLine) return "";
  if (oneLine.length <= maxChars) return oneLine;
  return `${oneLine.slice(0, maxChars - 1)}…`;
}

/** Strip citation key/value junk models paste into reason; keep any prose. */
function proseFromReasonDump(rawReason: string): string {
  let t = rawReason
    .replace(/intelSource\s*[:=]\s*["']?[a-zA-Z][a-zA-Z0-9]*/gi, "")
    .replace(/intelSignal\s*[:=]\s*["']?[a-zA-Z][a-zA-Z0-9\- ]*/gi, "")
    .replace(/\blean\s*[:=]\s*["']?(ROCK|PAPER|SCISSORS)/gi, "")
    .replace(/\bscore\s*[:=]\s*[\d.]+/gi, "")
    .replace(/^[,\s\-–—:]+|[,\s\-–—:]+$/g, "")
    .replace(/,\s*,+/g, ",")
    .replace(/\s+/g, " ")
    .trim();
  return t;
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
  { re: /\bglobal\b/i, source: "global" },
  { re: /\bpopulation\s+(prior|lean|read)\b/i, source: "global" },
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
    } else if (/transition/i.test(blob)) {
      intelSignal = "transitions";
    } else if (/second.?order|afterseq|after sequence/i.test(blob)) {
      intelSignal = "secondOrderTransition";
    } else if (/afterbotwin|after bot win|when bot won/i.test(blob)) {
      intelSignal = "afterBotWin";
    } else if (/afterbotloss|after bot loss|when bot lost/i.test(blob)) {
      intelSignal = "afterBotLoss";
    } else if (/streakbreak|break streak|continue streak/i.test(blob)) {
      intelSignal = "streakBreakBias";
    } else if (/clinch|match point|one win from/i.test(blob)) {
      intelSignal = "clinchPressure";
    } else if (/match score|series score|leading|trailing/i.test(blob)) {
      intelSignal = "matchScore";
    } else if (/alternation|switch(es)? throw/i.test(blob)) {
      intelSignal = "alternationRate";
    } else if (/responsetobot|response\s+to\s+bot/i.test(blob)) {
      intelSignal = "responseToBot";
    } else if (/repeatrate|repeat\s+rate/i.test(blob)) {
      intelSignal = "repeatRate";
    } else if (
      /\brepeat\b|streak|in a row|twice|thrown\s+\w+\s+twice|×\d|\bx\d+\b/i.test(
        blob,
      )
    ) {
      intelSignal = "repeat";
    } else if (/lastwindow|last\s+window/i.test(blob)) {
      intelSignal = "lastWindow";
    } else if (/secondary\b/i.test(blob)) {
      intelSignal = "secondary";
    } else if (/skew/i.test(blob)) {
      intelSignal = "skew";
    } else if (/distribution|mix\b/i.test(blob)) {
      intelSignal = "distribution";
    } else if (/lean|dominant|read\b/i.test(blob)) {
      intelSignal = "dominant";
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
  if (rawReason) {
    const prose = proseFromReasonDump(rawReason);
    if (prose.length >= 12) return clampReason(prose);
  }
  const signalLabel = signal === "dominant" ? "lean" : signal;
  return clampReason(`Using ${source} ${signalLabel} for this pick.`);
}

type LoosePickJson = {
  choice?: string;
  reason?: string;
  thoughtProcess?: string;
  intelSource?: string;
  intelSignal?: string;
  signal?: string;
  parameter?: string;
  intel?: string;
  source?: string;
};

function parseStrictMovePick(
  json: LoosePickJson,
  options?: ParseMovePickOptions,
): MovePickParsed | null {
  const choice = normalizeMove(json.choice);
  if (!choice) return null;

  const slashCitation = parseCombinedIntelFields(json);

  let intelSource =
    slashCitation.intelSource ??
    normalizePayloadIntelSource(json.intelSource, options?.primarySource) ??
    normalizePayloadIntelSource(json.intel, options?.primarySource) ??
    normalizePayloadIntelSource(json.source, options?.primarySource);

  let intelSignal =
    slashCitation.intelSignal ??
    normalizeIntelSignal(json.intelSignal) ??
    normalizeIntelSignal(json.signal) ??
    normalizeIntelSignal(json.parameter);

  const reasonRaw = String(json.reason ?? "");
  const signalHintRaw = String(json.intelSignal ?? "");

  if (!intelSignal && signalHintRaw.trim() && !normalizeIntelSignal(signalHintRaw)) {
    const fromHint = inferCitationFromProse(signalHintRaw);
    intelSignal = fromHint.intelSignal ?? intelSignal;
    intelSource = intelSource ?? fromHint.intelSource;
  }

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

  const thoughtRaw = String(json.thoughtProcess ?? "").trim();
  const thoughtProcess = thoughtRaw ? clampReason(thoughtRaw, REASON_LOG_MAX_CHARS) : undefined;

  return { choice, reason, thoughtProcess, intelSource, intelSignal };
}

function extractJsonStringField(text: string, field: string): string {
  const closed = text.match(new RegExp(`"${field}"\\s*:\\s*"([^"]*)"`, "i"));
  if (closed?.[1]) return closed[1].replace(/\\"/g, '"');
  const open = text.match(new RegExp(`"${field}"\\s*:\\s*"([\\s\\S]*)$`, "i"));
  return open?.[1]?.replace(/\\"/g, '"').trim() ?? "";
}

function extractReasonFromPartialJson(text: string): string {
  return extractJsonStringField(text, "reason");
}

function extractThoughtProcessFromPartialJson(text: string): string {
  return extractJsonStringField(text, "thoughtProcess");
}

function loosePickFromPartialJson(
  text: string,
  choice: Move,
  options?: ParseMovePickOptions,
): MovePickParsed | null {
  const json: LoosePickJson = {
    choice,
    intelSource: extractJsonStringField(text, "intelSource"),
    intelSignal: extractJsonStringField(text, "intelSignal"),
    reason: extractJsonStringField(text, "reason"),
    thoughtProcess: extractJsonStringField(text, "thoughtProcess"),
  };
  return parseStrictMovePick(json, options);
}

function salvageFromText(text: string, options?: ParseMovePickOptions): MovePickParsed | null {
  const choiceMatch =
    text.match(/"choice"\s*:\s*"(ROCK|PAPER|SCISSORS)"/i) ??
    text.match(/\bchoice\s*[:=]\s*["']?(ROCK|PAPER|SCISSORS)/i);
  const choice = normalizeMove(choiceMatch?.[1]);
  if (!choice) return null;

  try {
    const json = JSON.parse(text.trim()) as LoosePickJson;
    const fromFields = parseStrictMovePick(json, options);
    if (fromFields) return fromFields;
  } catch {
    const fromPartial = loosePickFromPartialJson(text, choice, options);
    if (fromPartial) return fromPartial;
  }

  const citation = inferCitationFromProse(text);
  if (!citation.intelSource || !citation.intelSignal) return null;

  const reasonRaw = extractReasonFromPartialJson(text);
  const thoughtRaw = extractThoughtProcessFromPartialJson(text);

  return {
    choice,
    reason: humanReasonFromCitation(
      citation.intelSource,
      citation.intelSignal,
      reasonRaw,
    ),
    thoughtProcess: thoughtRaw
      ? clampReason(thoughtRaw, REASON_LOG_MAX_CHARS)
      : undefined,
    intelSource: citation.intelSource,
    intelSignal: citation.intelSignal,
  };
}

export interface ParseMovePickOptions {
  /** Pre-match primary source — maps model citations like tacticalIntel → h2h. */
  primarySource?: MoveIntelSource;
}

export function parseMovePick(text: string, options?: ParseMovePickOptions): MovePickParsed | null {
  const trimmed = text.trim();

  try {
    const parsed = parseStrictMovePick(JSON.parse(trimmed) as LoosePickJson, options);
    if (parsed) return parsed;
  } catch {
    // not valid JSON — try salvage below
  }

  return salvageFromText(trimmed, options);
}

/** Choice only (legacy / plain-text fallback); move picks should use parseMovePick. */
export function parseMoveChoice(text: string): Move | null {
  const pick = parseMovePick(text);
  if (pick) return pick.choice;

  const trimmed = text.trim();
  const match = trimmed.toUpperCase().match(/\b(ROCK|PAPER|SCISSORS)\b/);
  return match ? normalizeMove(match[1]) : null;
}

export function formatMovePickLogLine(
  pick: MovePickParsed,
  maxReasonChars = REASON_LOG_MAX_CHARS,
): string {
  const reason = clampReason(pick.reason, maxReasonChars);
  return `cite=${pick.intelSource}/${pick.intelSignal} reason="${reason}"`;
}
