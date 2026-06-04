import { FirebaseError } from "firebase/app";

const TRANSIENT_CODES = new Set([
  "unavailable",
  "deadline-exceeded",
  "resource-exhausted",
  "internal",
  "cancelled",
  "aborted",
]);

const TRANSIENT_MESSAGE = /ECONNRESET|ETIMEDOUT|ENOTFOUND|EAI_AGAIN|UNAVAILABLE|network/i;

function errorMessage(err: unknown): string {
  if (err instanceof FirebaseError) return `${err.code} ${err.message}`;
  if (err instanceof Error) return err.message;
  return String(err);
}

/** Firestore / Functions blips that should not be treated as "queue doc missing". */
function firebaseErrorCodes(err: FirebaseError): string[] {
  const codes = [err.code];
  const slash = err.code.lastIndexOf("/");
  if (slash >= 0) codes.push(err.code.slice(slash + 1));
  return codes;
}

export function isTransientFirebaseError(err: unknown): boolean {
  if (err instanceof FirebaseError) {
    if (firebaseErrorCodes(err).some((c) => TRANSIENT_CODES.has(c))) return true;
    if (TRANSIENT_MESSAGE.test(err.message)) return true;
  }
  return TRANSIENT_MESSAGE.test(errorMessage(err));
}

/** Firestore often returns permission-denied when updating a deleted queue/{uid} doc. */
export function isQueueDocAccessDenied(err: unknown): boolean {
  if (err instanceof FirebaseError) {
    return firebaseErrorCodes(err).some((c) => c === "permission-denied");
  }
  return /permission[- ]denied/i.test(errorMessage(err));
}

export async function withRetries<T>(
  fn: () => Promise<T>,
  opts: { attempts?: number; baseDelayMs?: number; label?: string } = {},
): Promise<T> {
  const attempts = opts.attempts ?? 3;
  const baseDelayMs = opts.baseDelayMs ?? 400;
  let lastErr: unknown;
  for (let i = 0; i < attempts; i++) {
    try {
      return await fn();
    } catch (err) {
      lastErr = err;
      if (!isTransientFirebaseError(err) || i === attempts - 1) throw err;
      const delay = baseDelayMs * (i + 1);
      await new Promise((r) => setTimeout(r, delay));
    }
  }
  throw lastErr;
}
