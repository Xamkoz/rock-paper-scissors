import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { FirebaseError } from "firebase/app";
import { isQueueDocAccessDenied, isTransientFirebaseError } from "./errors.js";

describe("isTransientFirebaseError", () => {
  it("detects functions internal and ECONNRESET", () => {
    assert.equal(
      isTransientFirebaseError(new FirebaseError("functions/internal", "internal")),
      true,
    );
    assert.equal(
      isTransientFirebaseError(new Error("14 UNAVAILABLE: read ECONNRESET")),
      true,
    );
  });

  it("does not treat permission errors as transient", () => {
    assert.equal(
      isTransientFirebaseError(new FirebaseError("permission-denied", "denied")),
      false,
    );
  });
});

describe("isQueueDocAccessDenied", () => {
  it("detects Firestore permission-denied on queue heartbeat", () => {
    assert.equal(
      isQueueDocAccessDenied(
        new FirebaseError("permission-denied", "Missing or insufficient permissions."),
      ),
      true,
    );
  });
});
