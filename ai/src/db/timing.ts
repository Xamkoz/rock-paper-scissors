export interface RoundTimingRecord {
  matchId: string;
  roundNumber: number;
  choice?: string;
  pickReason?: string;
  pickIntelSource?: string;
  pickIntelSignal?: string;
  llmModel?: string;
  contextMs: number;
  pickMs: number;
  submitMs: number;
  totalMs: number;
  ok: boolean;
}
