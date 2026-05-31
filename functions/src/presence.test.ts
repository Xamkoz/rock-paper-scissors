import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { countOnlinePresenceDocs, ONLINE_PRESENCE_WINDOW_MS } from "./presence";

describe("presence", () => {
  it("counts docs within the online window", () => {
    const nowMs = 1_700_000_000_000;
    assert.equal(
      countOnlinePresenceDocs(
        [
          { lastSeenMs: nowMs - 30_000 },
          { lastSeenMs: nowMs - 130_000 },
        ],
        nowMs,
      ),
      1,
    );
  });

  it("drops docs after the online window", () => {
    const nowMs = 1_700_000_000_000;
    assert.equal(
      countOnlinePresenceDocs(
        [{ lastSeenMs: nowMs - ONLINE_PRESENCE_WINDOW_MS - 1 }],
        nowMs,
      ),
      0,
    );
  });
});
