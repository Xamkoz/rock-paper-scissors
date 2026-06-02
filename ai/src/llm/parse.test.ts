import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { parseMoveChoice } from "./parse.js";

describe("parseMoveChoice", () => {
  it("parses JSON choice", () => {
    assert.equal(parseMoveChoice('{"choice":"PAPER"}'), "PAPER");
  });

  it("parses plain text", () => {
    assert.equal(parseMoveChoice("I pick SCISSORS"), "SCISSORS");
  });
});
