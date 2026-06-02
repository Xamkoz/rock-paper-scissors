import { log } from "../log.js";

export function logRankedSection(tag: string, lines: string[]): void {
  if (lines.length === 0) {
    log(`[${tag}] (empty)`);
    return;
  }
  log(`[${tag}]`);
  for (const line of lines) {
    log(`[${tag}]   ${line}`);
  }
}

/** Fixed-width columns; each row must match headers.length. */
export function formatTableLines(headers: string[], rows: string[][]): string[] {
  if (rows.length === 0) return ["(no rows)"];
  const widths = headers.map((h, i) => {
    let w = h.length;
    for (const row of rows) {
      w = Math.max(w, String(row[i] ?? "").length);
    }
    return w;
  });
  const fmt = (cells: string[]) =>
    cells.map((c, i) => c.padEnd(widths[i]!)).join("  ");
  const rule = widths.map((w) => "─".repeat(w)).join("  ");
  return [fmt(headers), rule, ...rows.map((r) => fmt(r))];
}

export function formatPctHits(hits: number, total: number, unit: string): string {
  if (total <= 0) return "—";
  const pct = Math.round((hits / total) * 1000) / 10;
  return `${pct}% (${hits}/${total} ${unit})`;
}

export function formatMs(ms: number): string {
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms}ms`;
}
