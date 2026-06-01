# RPS self-hosted AI player

Headless Node process that plays RPS Online like the Android client: matchmaking queue, lobby ready, and round moves via Firebase Auth + Cloud Functions (with Firestore fallbacks).

It also keeps a **local JSON match history** (no Firestore reads for cache), **analyzes opponent throw patterns**, and logs a **one-line match description** after each game.

## Prerequisites

1. Firebase project with deployed functions (`europe-west1`) and Firestore rules.
2. **Email/password** sign-in enabled in Firebase Auth.
3. A dedicated bot user (create in Auth console or sign up once).
4. Web API key and project id from Firebase / `google-services.json`.

## Setup

```bash
cd ai
cp .env.example .env
# fill FIREBASE_* and BOT_* values
npm install
npm run build
npm run start
```

## What it does

| Concern | Behavior |
|--------|----------|
| **Queue** | `joinMatchmakingQueue` (or direct `queue/{uid}` write); heartbeat every `BOT_QUEUE_INTERVAL_MS` (default 30s). |
| **Lobby** | `confirmMatchReady` when paired. |
| **Moves** | Pattern-based pick + `submitMatchMove` (or direct `choices` subdoc). |
| **Cache** | `AI_CACHE_DIR/` — `index.json`, `matches/{id}.json`, `descriptions/{id}.json`; filled from live match snapshots when games end. |
| **Analysis** | R/P/S rates from cached games + current match rounds + opponent profile throw stats. |
| **Description** | Short recap on match end, e.g. `won BO3 vs Alice (2-1, 3 rounds), +8 ELO`. |

## Running alongside humans

The bot is a normal Firebase user. Matchmaking pairs it with anyone in the same ELO band and shared BO3/BO5/BO10 formats. Use a separate test project or a clearly named bot account in production.

## Development

```bash
npm test
```

Integration smoke: run the bot and one Android client; both queue on the same formats and verify pairing, ready, and moves in the Functions logs.

## Troubleshooting move / queue errors

### `Move was not recorded` or duplicate `[move]` lines

The callable can succeed on the server while the client still sees an error. The bot now waits for `player1Submitted` / `player2Submitted` on the match doc before retrying, and skips a direct Firestore write if your choice doc already exists (avoids `PERMISSION_DENIED` on the second attempt).

### `PERMISSION_DENIED` on queue heartbeat

Firestore returns this when the bot tries to **update a queue doc that no longer exists** (you were matched and the server deleted `queue/{uid}`) or when the client is not allowed to write.

1. **Matched but heartbeat still running** — harmless after the fix in `sendQueueHeartbeat`; you should see `[queue-heartbeat] queue doc gone, stopping heartbeat`.
2. **App Check enforced** — Firebase Console → App Check → set Firestore to **Monitoring**, not Enforced (the Android app does not send App Check tokens today).
3. **Rules not deployed** — run `./scripts/deploy-backend.sh` from the repo root.
4. **Bot Auth user** — `BOT_EMAIL` / `BOT_PASSWORD` must match a user in Authentication → Users; enable Email/Password sign-in.
5. **API key** — use a **Web** API key for the Node bot, not an Android-restricted key (see project docs on bot API keys).

## Source layout

| Path | Role |
|------|------|
| `src/player/PlayerAgent.ts` | Main loop: queue, match listener |
| `src/cache/matchCache.ts` | Local JSON cache |
| `src/analysis/movePattern.ts` | Opponent throw tendencies |
| `src/narrative/matchDescription.ts` | Post-match one-liner |
| `src/firebase/` | Client SDK, callables, Firestore helpers |
