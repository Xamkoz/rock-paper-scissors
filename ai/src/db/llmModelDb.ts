import type { DatabaseSync } from "node:sqlite";

export interface LlmModelMatchRow {
  model: string;
  matches: number;
  wins: number;
  losses: number;
  draws: number;
  winPct: number;
  totalEloDelta: number;
  avgEloDelta: number;
}

/** Per-model match outcomes from local SQLite (`matches.bot_uid`, not live Firebase). */
export function getLlmModelMatchStats(db: DatabaseSync): LlmModelMatchRow[] {
  const rows = db
    .prepare(
      `WITH bot_picks AS (
        SELECT rt.match_id, rt.llm_model, COUNT(*) AS n
        FROM round_timings rt
        INNER JOIN matches m ON m.id = rt.match_id
        WHERE rt.llm_model IS NOT NULL AND rt.llm_model != ''
          AND rt.ok = 1
          AND m.bot_uid IS NOT NULL AND m.bot_uid != ''
          AND m.status = 'completed'
        GROUP BY rt.match_id, rt.llm_model
      ),
      primary_model AS (
        SELECT match_id, llm_model
        FROM (
          SELECT
            match_id,
            llm_model,
            ROW_NUMBER() OVER (
              PARTITION BY match_id
              ORDER BY n DESC, llm_model ASC
            ) AS rn
          FROM bot_picks
        )
        WHERE rn = 1
      )
      SELECT
        pm.llm_model AS model,
        COUNT(*) AS matches,
        SUM(CASE WHEN m.winner_id = m.bot_uid THEN 1 ELSE 0 END) AS wins,
        SUM(
          CASE
            WHEN m.winner_id IS NOT NULL
              AND m.winner_id != m.bot_uid
              AND m.winner_id != 'tie'
            THEN 1 ELSE 0
          END
        ) AS losses,
        SUM(
          CASE
            WHEN m.winner_id IS NULL OR m.winner_id = 'tie' THEN 1 ELSE 0
          END
        ) AS draws,
        SUM(
          CASE
            WHEN m.player1 = m.bot_uid THEN COALESCE(m.player1_elo_delta, 0)
            ELSE COALESCE(m.player2_elo_delta, 0)
          END
        ) AS total_elo_delta
      FROM primary_model pm
      INNER JOIN matches m ON m.id = pm.match_id
      GROUP BY pm.llm_model
      ORDER BY total_elo_delta * 1.0 / COUNT(*) DESC, wins * 1.0 / COUNT(*) DESC`,
    )
    .all() as Array<{
    model: string;
    matches: number;
    wins: number;
    losses: number;
    draws: number;
    total_elo_delta: number;
  }>;

  return rows.map((r) => {
    const matches = Number(r.matches);
    const wins = Number(r.wins);
    const totalEloDelta = Number(r.total_elo_delta);
    return {
      model: r.model,
      matches,
      wins,
      losses: Number(r.losses),
      draws: Number(r.draws),
      winPct: matches > 0 ? Math.round((wins / matches) * 1000) / 10 : 0,
      totalEloDelta,
      avgEloDelta:
        matches > 0 ? Math.round((totalEloDelta / matches) * 10) / 10 : 0,
    };
  });
}
