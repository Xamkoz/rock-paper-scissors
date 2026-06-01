import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import type { Match } from "../types.js";

const CACHE_VERSION = 1;
const MAX_MATCHES_PER_USER = 200;
const MAX_HEAD_TO_HEAD_IDS = 50;

interface CacheIndex {
  version: number;
  /** uid -> match ids, newest first */
  byUser: Record<string, string[]>;
  /** sorted "uidA|uidB" -> match ids */
  headToHead: Record<string, string[]>;
  updatedAt: number;
}

function headToHeadKey(a: string, b: string): string {
  return [a, b].sort().join("|");
}

function isConcluded(match: Match): boolean {
  return match.status === "completed" || match.status === "abandoned";
}

export class MatchCache {
  private index: CacheIndex = {
    version: CACHE_VERSION,
    byUser: {},
    headToHead: {},
    updatedAt: 0,
  };

  private readonly matchesDir: string;
  private readonly descriptionsDir: string;
  private readonly indexPath: string;

  constructor(private readonly dir: string) {
    this.matchesDir = join(dir, "matches");
    this.descriptionsDir = join(dir, "descriptions");
    this.indexPath = join(dir, "index.json");
  }

  async load(): Promise<void> {
    await mkdir(this.matchesDir, { recursive: true });
    await mkdir(this.descriptionsDir, { recursive: true });
    try {
      const raw = await readFile(this.indexPath, "utf8");
      const parsed = JSON.parse(raw) as CacheIndex;
      if (parsed.version === CACHE_VERSION) {
        this.index = parsed;
      }
    } catch {
      // fresh cache
    }
  }

  private async persistIndex(): Promise<void> {
    this.index.updatedAt = Date.now();
    await writeFile(this.indexPath, JSON.stringify(this.index, null, 2));
  }

  private matchPath(matchId: string): string {
    return join(this.matchesDir, `${matchId}.json`);
  }

  private descriptionPath(matchId: string): string {
    return join(this.descriptionsDir, `${matchId}.json`);
  }

  async readMatch(matchId: string): Promise<Match | null> {
    try {
      const raw = await readFile(this.matchPath(matchId), "utf8");
      return JSON.parse(raw) as Match;
    } catch {
      return null;
    }
  }

  private async writeMatchFile(match: Match): Promise<void> {
    await writeFile(this.matchPath(match.id), JSON.stringify(match, null, 2));
  }

  private updateIndexForMatch(match: Match): void {
    for (const uid of [match.player1, match.player2]) {
      if (!uid) continue;
      const ids = this.index.byUser[uid] ?? [];
      this.index.byUser[uid] = [match.id, ...ids.filter((id) => id !== match.id)].slice(
        0,
        MAX_MATCHES_PER_USER,
      );
    }
    const key = headToHeadKey(match.player1, match.player2);
    const h2h = this.index.headToHead[key] ?? [];
    this.index.headToHead[key] = [match.id, ...h2h.filter((id) => id !== match.id)].slice(
      0,
      MAX_HEAD_TO_HEAD_IDS,
    );
  }

  /** Persist a concluded match and optional description (local JSON only). */
  async saveConcluded(match: Match, description?: string): Promise<void> {
    if (!isConcluded(match)) return;
    await this.writeMatchFile(match);
    this.updateIndexForMatch(match);
    await this.persistIndex();
    if (description) {
      await this.setDescription(match.id, description);
    }
  }

  async setDescription(matchId: string, text: string): Promise<void> {
    await writeFile(
      this.descriptionPath(matchId),
      JSON.stringify({ matchId, description: text, updatedAt: Date.now() }, null, 2),
    );
  }

  async getDescription(matchId: string): Promise<string | undefined> {
    try {
      const raw = await readFile(this.descriptionPath(matchId), "utf8");
      const parsed = JSON.parse(raw) as { description?: string };
      return parsed.description;
    } catch {
      return undefined;
    }
  }

  async getMatchesForUser(uid: string): Promise<Match[]> {
    const ids = this.index.byUser[uid] ?? [];
    const matches: Match[] = [];
    for (const id of ids) {
      const m = await this.readMatch(id);
      if (m) matches.push(m);
    }
    return matches;
  }

  async getHeadToHead(uidA: string, uidB: string): Promise<Match[]> {
    const ids = this.index.headToHead[headToHeadKey(uidA, uidB)] ?? [];
    const matches: Match[] = [];
    for (const id of ids) {
      const m = await this.readMatch(id);
      if (m) matches.push(m);
    }
    return matches;
  }
}
