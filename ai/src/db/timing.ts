export interface RoundTimingRecord {
  matchId: string;
  roundNumber: number;
  choice?: string;
  contextMs: number;
  pickMs: number;
  submitMs: number;
  totalMs: number;
  ok: boolean;
}
