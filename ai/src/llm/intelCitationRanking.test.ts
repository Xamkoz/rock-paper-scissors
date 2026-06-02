import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { buildStaticIntelCatalog } from "./moveIntelCatalog.js";
import {
  formatIntelCitationCatalogLog,
  rankAllIntelCitationsByEfficiency,
} from "./intelCitationRanking.js";

describe("rankAllIntelCitationsByEfficiency", () => {
  it("lists every static source/signal pair sorted by pick win rate", () => {
    const catalog = buildStaticIntelCatalog();
    const totalPairs = catalog.reduce((n, e) => n + e.signals.length, 0);

    const ranked = rankAllIntelCitationsByEfficiency(
      catalog,
      [
        {
          source: "h2h",
          signal: "transitions",
          picks: 10,
          roundWins: 7,
          roundWinPct: 70,
        },
        {
          source: "h2h",
          signal: "dominant",
          picks: 5,
          roundWins: 2,
          roundWinPct: 40,
        },
      ],
      [{ source: "h2h", leanHits: 8, leanRounds: 10, leanPct: 80 }],
      [{ source: "h2h", matches: 4, wins: 3, winPct: 75 }],
    );

    assert.equal(ranked.length, totalPairs);
    assert.equal(ranked[0]!.source, "h2h");
    assert.equal(ranked[0]!.signal, "transitions");
    assert.ok(ranked[0]!.efficiencyScore > ranked[1]!.efficiencyScore);
    const block = formatIntelCitationCatalogLog(ranked);
    assert.match(block, /h2h · transitions/);
    assert.match(block, /Citations with pick history/);
  });
});
