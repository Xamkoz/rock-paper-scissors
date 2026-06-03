import type { DatabaseSync } from "node:sqlite";
import type { RoundSignalScoreRow } from "../llm/intelSignalRoundScoring.js";
import type { MoveIntelSignal, MoveIntelSource } from "../llm/parse.js";
import { normalizeIntelSignal } from "../llm/parse.js";

export interface SignalLeanStats {
  source: MoveIntelSource;
  signal: MoveIntelSignal;
  opportunities: number;
  leanHits: number;
  leanPct: number;
}

export function saveRoundSignalScores(
  db: DatabaseSync,
  matchId: string,
  rows: RoundSignalScoreRow[],
): void {
  db.prepare(`DELETE FROM round_signal_scores WHERE match_id = ?`).run(matchId);
  if (rows.length === 0) return;

  const insert = db.prepare(
    `INSERT INTO round_signal_scores (match_id, round_number, source, signal, lean_hit)
     VALUES (?, ?, ?, ?, ?)`,
  );
  for (const row of rows) {
    insert.run(
      row.matchId,
      row.roundNumber,
      row.source,
      row.signal,
      row.leanHit ? 1 : 0,
    );
  }
}

export function getSignalLeanStats(db: DatabaseSync): SignalLeanStats[] {
  const rows = db
    .prepare(
      `SELECT
        source,
        signal,
        COUNT(*) AS opportunities,
        SUM(lean_hit) AS lean_hits
      FROM round_signal_scores
      GROUP BY source, signal`,
    )
    .all() as Array<{
    source: string;
    signal: string;
    opportunities: number;
    lean_hits: number;
  }>;

  const out: SignalLeanStats[] = [];
  for (const row of rows) {
    const signal = normalizeIntelSignal(row.signal);
    if (!signal) continue;
    const opportunities = row.opportunities ?? 0;
    const leanHits = row.lean_hits ?? 0;
    out.push({
      source: row.source as MoveIntelSource,
      signal,
      opportunities,
      leanHits,
      leanPct:
        opportunities > 0 ? Math.round((leanHits / opportunities) * 1000) / 10 : 0,
    });
  }
  return out;
}
