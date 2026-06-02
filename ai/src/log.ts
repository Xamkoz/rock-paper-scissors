import { appendFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";

export function logStamp(): string {
  return new Date().toISOString();
}

/** Append one line (no trailing newline in `message`; a newline is added). */
export function appendLogFile(filePath: string, message: string): void {
  mkdirSync(dirname(filePath), { recursive: true });
  appendFileSync(filePath, `${logStamp()} ${message}\n`, "utf8");
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
