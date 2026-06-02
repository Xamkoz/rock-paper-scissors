import type { DatabaseSync } from "node:sqlite";
import type { MoveIntelSignal, MoveIntelSource } from "../llm/parse.js";
import { normalizeIntelSignal } from "../llm/parse.js";

export interface IntelCitationPickStats {
  source: MoveIntelSource;
  signal: MoveIntelSignal;
  picks: number;
  roundWins: number;
  roundWinPct: number;
}

/** Citation pick stats from SQLite (`matches.bot_uid` on archive, not live Firebase). */
export function getPickIntelCitationStats(db: DatabaseSync): IntelCitationPickStats[] {
  const rows = db
    .prepare(
      `SELECT
        rt.pick_intel_source AS source,
        rt.pick_intel_signal AS signal,
        COUNT(*) AS picks,
        SUM(
          CASE
            WHEN r.winner_id = m.bot_uid AND r.winner_id IS NOT NULL AND r.winner_id != 'tie'
            THEN 1 ELSE 0
          END
        ) AS round_wins
      FROM round_timings rt
      INNER JOIN rounds r
        ON r.match_id = rt.match_id AND r.round_number = rt.round_number
      INNER JOIN matches m ON m.id = rt.match_id
      WHERE rt.ok = 1
        AND rt.pick_intel_source IS NOT NULL
        AND rt.pick_intel_signal IS NOT NULL
        AND m.bot_uid IS NOT NULL AND m.bot_uid != ''
      GROUP BY rt.pick_intel_source, rt.pick_intel_signal`,
    )
    .all() as Array<{
    source: string;
    signal: string;
    picks: number;
    round_wins: number;
  }>;

  const out: IntelCitationPickStats[] = [];
  for (const row of rows) {
    const source = row.source as MoveIntelSource;
    const signal = normalizeIntelSignal(row.signal);
    if (!signal) continue;
    const picks = row.picks ?? 0;
    const roundWins = row.round_wins ?? 0;
    out.push({
      source,
      signal,
      picks,
      roundWins,
      roundWinPct: picks > 0 ? Math.round((roundWins / picks) * 1000) / 10 : 0,
    });
  }
  return out;
}
