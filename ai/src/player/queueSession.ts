/** Matches Android [PresenceRepository.HEARTBEAT_INTERVAL_MS] and functions [QUEUE_STALE_MS] / 3. */
export const SESSION_HEARTBEAT_INTERVAL_MS = 30_000;

/** @deprecated Use SESSION_HEARTBEAT_INTERVAL_MS */
export const QUEUE_HEARTBEAT_INTERVAL_MS = SESSION_HEARTBEAT_INTERVAL_MS;

export const QUEUE_HEARTBEAT_VERIFY_EVERY = 3;

/** Consecutive "doc missing" heartbeats before leaving queue. */
export const QUEUE_HEARTBEAT_MAX_FAILURES = 3;

/** Transient Firestore errors before optional recovery re-queue. */
export const QUEUE_HEARTBEAT_MAX_TRANSIENT = 12;

/** Delay before auto re-join after queue session dropped (ms). */
export const QUEUE_RECOVER_DELAY_MS = 15_000;
