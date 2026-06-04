# One-time guest cleanup (zero matches)

Removes **anonymous guest accounts** that never finished a match:

- Firebase Auth anonymous user (no linked Google/email/phone)
- Firestore profile missing **or** `Guest …` display name with `wins`, `losses`, `draws`, and round stats all zero
- Not in an active match (`activeMatchId` unset)
- Last activity at least **1 hour** ago (configurable)

Deletes `users/{uid}`, `presence/{uid}`, `queue/{uid}`, and the Auth user.

## Deploy

```bash
cd functions
firebase functions:secrets:set GUEST_CLEANUP_SECRET
npm run deploy
```

Or set `GUEST_CLEANUP_SECRET` in Firebase Console → Functions → Environment variables.

## Run (dry run first)

From Firebase CLI (any signed-in user token is fine; access is gated by the secret):

```bash
firebase functions:call cleanupZeroMatchGuests --data '{"secret":"YOUR_SECRET","dryRun":true}'
```

Review `eligible` and `skipped` in the response, then execute:

```bash
firebase functions:call cleanupZeroMatchGuests --data '{"secret":"YOUR_SECRET","dryRun":false}'
```

Optional parameters:

| Field | Default | Meaning |
|-------|---------|---------|
| `dryRun` | `true` | Count only; no deletes |
| `minAgeHours` | `1` | Skip guests active within this window |
| `force` | `false` | Re-run after a completed cleanup |

Completion is recorded at `maintenance/oneTimeGuestCleanup`.

## Profile match stats backfill

Recomputes `wins`, `losses`, `draws`, round W/L/D, throw counts, and move stats from **completed** match history. Does **not** change ELO.

Dry run:

```bash
firebase functions:call backfillProfileMatchStats --data '{"secret":"YOUR_SECRET","dryRun":true}'
```

Apply:

```bash
firebase functions:call backfillProfileMatchStats --data '{"secret":"YOUR_SECRET","dryRun":false}'
```

Completion is recorded at `maintenance/profileMatchStatsBackfill`.

## After cleanup

You can remove `cleanupZeroMatchGuests` from `functions/src/index.ts` on a later deploy if you no longer need it.
