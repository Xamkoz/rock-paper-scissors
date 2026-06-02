export function logStamp(): string {
  return new Date().toISOString();
}

export function log(message: string): void {
  console.log(`${logStamp()} ${message}`);
}

export function warn(message: string, ...detail: unknown[]): void {
  console.warn(`${logStamp()} ${message}`, ...detail);
}

export function error(message: string, ...detail: unknown[]): void {
  console.error(`${logStamp()} ${message}`, ...detail);
}

export function msSince(startMs: number): number {
  return Date.now() - startMs;
}
