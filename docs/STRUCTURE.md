# Project structure

High-level map of the repository. User-facing overview: [README.md](../README.md). UI conventions: [UI.md](UI.md).

## Repository

```
rock-paper-scissors/
├── app/                 # Android client (Kotlin, Jetpack Compose)
├── ai/                  # Self-hosted player bot (Node); ai/docs/ for setup and architecture
├── functions/           # Firebase Cloud Functions (TypeScript)
├── shared/              # game-rules.json — timing + match formats (client + server)
├── firestore.rules
├── docs/
├── scripts/
└── README.md
```

## Shared game rules (`shared/game-rules.json`)

Single source for round timeout, match clocks, and BO3/BO5/BO10 win targets. After edits, run `./scripts/sync-game-rules.sh`, then `./gradlew :app:testDebugUnitTest` and `cd functions && npm test` (functions `npm run build` copies the JSON into `lib/` for deploy).

## Android app (`app/`)

Package root: `com.rpsonline.app`.

| Layer | Path | Role |
|-------|------|------|
| Entry | `MainActivity.kt`, `RpsApplication.kt` | Activity, Firebase init |
| Navigation | `navigation/NavGraph.kt` | Routes and screen flow |
| UI | `ui/` | Compose screens — see [UI.md](UI.md) |
| ViewModel | `viewmodel/` | Screen state |
| Data | `data/repository/`, `data/model/` | Firebase access, DTOs |
| Domain | `domain/` | `MatchMode`, `GameRules`, display helpers |
| Session | `data/repository/MatchSessionMonitor.kt` | One listener for queue + active match |
| Monitoring | `data/monitoring/` | Callable `ping` for RTT meter |
| Update | `data/update/` | GitHub releases, APK install, changelog |

`RpsApp` wraps the nav graph with ping meter, queue/match chip, theme, and sound mute. Round-resolution sounds use `RoundResolutionSoundEffect` on the same active-match flow.

ViewModels use repositories (`AuthRepository`, `UserRepository`, `MatchRepository`, `PresenceRepository`, `AppUpdateRepository`). Home and global UI read queue/active match from `MatchSessionMonitor` only.

## Player bot (`ai/`)

Headless bot that mirrors client matchmaking flows (see [ai/docs/README.md](../ai/docs/README.md)):

| Module | Role |
|--------|------|
| `src/player/PlayerAgent.ts` | Presence, queue, match listener, ready + moves |
| `src/cache/matchCache.ts` | Local JSON files (`index.json`, `matches/`, `descriptions/`) |
| `src/analysis/movePattern.ts` | Opponent R/P/S tendencies |
| `src/narrative/matchDescription.ts` | One-line post-match recap |

Uses the same callables as the app: `joinMatchmakingQueue`, `confirmMatchReady`, `submitMatchMove`.

## Backend (`functions/`)

| File | Role |
|------|------|
| `src/index.ts` | Triggers, schedulers, match lifecycle |
| `src/game.ts` | Round/series resolution, ELO |
| `src/gameRules.ts` | Loads `shared/game-rules.json` |
| `src/clockControl.ts` | Match clocks |
| `src/moveTiming.ts` | Round deadline + move timing |

### Cloud Functions

| Export | Trigger | Purpose |
|--------|---------|---------|
| `onQueueEntry` | `queue/{userId}` created | Pair players, create match |
| `onPlayerChoice` | `matches/.../rounds/{n}/choices/{uid}` created | Apply move to match doc |
| `onRoundTimeout` | `matches/.../rounds/{n}/timeoutRequests/{id}` created | Resolve when client reports expiry |
| `resolveTimedOutRounds` | Scheduled (1 min) | Backstop if no timeout request |
| `cleanupStale` | Scheduled (5 min) | Stale queue / abandoned matches |
| `ping` | HTTPS Callable | Auth-gated RTT probe |

Clients **write** choice and timeout-request subcollections; functions **update** the match document. Clients **read** only the match doc (embedded `rounds[]`). Subcollections are not a second source of truth—`applyPlayerChoice` merges any pending choice docs before timeout resolution.

## Firestore paths

| Path | Writer | Reader |
|------|--------|--------|
| `users/{uid}` | Client + server | Client, functions |
| `queue/{uid}` | Client (join + 30s heartbeat on `lastHeartbeatAt`) | Functions |
| `matches/{id}` | Functions | Clients |
| `matches/{id}/rounds/{n}/choices/{uid}` | Client (move) | Functions |
| `matches/{id}/rounds/{n}/timeoutRequests/{id}` | Client | Functions |

Security: [firestore.rules](../firestore.rules).

## Related docs

- [GAME_RULES.md](GAME_RULES.md) — Gameplay, clocks, matchmaking, ELO
- [scripts/ENABLE_AUTH.md](../scripts/ENABLE_AUTH.md) — Auth providers; App Check is Monitoring-only in Firebase (not in the app)
- [scripts/deploy-backend.sh](../scripts/deploy-backend.sh) — Deploy functions + rules
