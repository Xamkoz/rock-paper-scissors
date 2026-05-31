/** Matches client [PresenceRepository.ONLINE_PRESENCE_WINDOW_MS]. */
export const ONLINE_PRESENCE_WINDOW_MS = 120_000;

export function countOnlinePresenceDocs(
  docs: Array<{ lastSeenMs: number | null }>,
  nowMs: number,
  onlineWindowMs: number = ONLINE_PRESENCE_WINDOW_MS,
): number {
  return docs.filter(
    (doc) => doc.lastSeenMs != null && doc.lastSeenMs >= nowMs - onlineWindowMs,
  ).length;
}
