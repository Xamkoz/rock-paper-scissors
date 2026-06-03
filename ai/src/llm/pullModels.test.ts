import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { llmServerRoot } from "./pullModels.js";

describe("llmServerRoot", () => {
  it("strips /v1 from OpenAI-compatible base URL", () => {
    assert.equal(llmServerRoot("http://127.0.0.1:11434/v1"), "http://127.0.0.1:11434");
    assert.equal(llmServerRoot("http://127.0.0.1:11434/v1/"), "http://127.0.0.1:11434");
  });
});
