# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

RPS Online — real-time online rock-paper-scissors for Android (Kotlin/Compose), backed by Firebase
(Firestore + Cloud Functions). It's a monorepo with three independently-built parts:

| Path | What | Language |
|------|------|----------|
| `app/` | Android client | Kotlin / Jetpack Compose |
| `functions/` | Firebase Cloud Functions (match logic, ELO, timeouts) | TypeScript |
| `ai/` | Self-hosted matchmaking bot + intel/ranking tools | TypeScript (Node, ESM) |
| `shared/game-rules.json` | Single source of truth for timing/format constants, read by both `app` and `functions` | JSON |

Full architecture: [docs/STRUCTURE.md](docs/STRUCTURE.md). Gameplay rules: [docs/GAME_RULES.md](docs/GAME_RULES.md).
UI conventions: [docs/UI.md](docs/UI.md). String/localization rules: [docs/LOCALIZATION.md](docs/LOCALIZATION.md).

## Commands

### Android app (`app/`)
```bash
./gradlew :app:assembleDebug              # build debug APK
./gradlew :app:testDebugUnitTest          # run all unit tests
./gradlew :app:testDebugUnitTest --tests "com.rpsonline.app.data.model.MatchResolutionTest"   # single test class
```
Requires JDK 21, Android SDK API 35, and `app/google-services.json` (from a Firebase project).
`JAVA_HOME` is optional — `./gradlew` auto-detects Android Studio's bundled JBR.

### Cloud Functions (`functions/`)
```bash
cd functions
npm test                  # builds (tsc + copies shared/game-rules.json) then runs node --test on lib/*.test.js
npm run lint
node --test lib/game.test.js                    # single test file (after `npm run build`)
firebase deploy --only functions,firestore:rules,firestore:indexes   # or ./scripts/deploy-backend.sh
```

### AI bot (`ai/`)
```bash
cd ai
npm install && npm run build && npm start   # or `npm run dev` (build+start); from repo root: `npm start`
npm run rank                                 # intel/leaderboard stats, no Firebase queue join
npm test                                     # builds then `node --test lib/**/*.test.js`
```
Requires `ai/.env` (copy from `.env.example`) with `FIREBASE_*` and `BOT_*` values, and a dedicated
bot user with email/password sign-in.

### Shared game rules
After editing `shared/game-rules.json`, run `./scripts/sync-game-rules.sh` (regenerates
`app/.../domain/GeneratedGameRules.kt` and the test resource copy), then
`./gradlew :app:testDebugUnitTest` and `cd functions && npm test`.

**Deploy order matters for match-format changes**: ship Cloud Functions + Firestore rules
*before or together with* an app build that writes `matchModes` on the queue — older functions
ignore `matchModes` and treat everyone as BO3.

## Architecture

### Android app (`app/`, package `com.rpsonline.app`)
- Entry: `MainActivity.kt`, `RpsApplication.kt`. Navigation: `navigation/NavGraph.kt` (`Routes`, `RpsNavGraph`).
- `ui/` — Compose screens by feature (`auth/`, `home/`, `game/`, `result/`, `leaderboard/`, `profile/`,
  `changelog/`, shared pieces in `components/`). `RpsApp` wraps the nav graph with the ping meter,
  queue/match chip, theme, and sound mute.
- `viewmodel/` — screen state, built on repositories.
- `data/repository/`, `data/model/` — Firebase access and DTOs. Key repos: `AuthRepository`,
  `UserRepository`, `MatchRepository`, `PresenceRepository`, `AppUpdateRepository`.
- **`MatchSessionMonitor`** is the single listener for queue + active-match state — Home and global
  UI read from it rather than opening parallel Firestore listeners.
- `domain/` — `MatchMode`, `GameRules`, display helpers, plus generated `GeneratedGameRules.kt`.
- `data/monitoring/` — callable `ping` for the RTT meter. `data/update/` — GitHub release checks,
  in-app APK install, changelog.

New screens: add a `Routes` entry + `composable {}` in `NavGraph`, a file under `ui/<feature>/`,
and a `ViewModel` if state is non-trivial. Root columns use `Modifier.rpsScreenPadding()`.

### Cloud Functions (`functions/src/`)
- `index.ts` — triggers, schedulers, match lifecycle. `game.ts` — round/series resolution, ELO.
  `gameRules.ts` — loads `shared/game-rules.json`. `clockControl.ts` — match clocks.
  `moveTiming.ts` — round deadline/timing.
- Triggers: `onQueueEntry` (pairs players), `onPlayerChoice` (applies a move), `onRoundTimeout`
  (client-reported expiry), `resolveTimedOutRounds` (1-min scheduled backstop),
  `cleanupStale` (5-min scheduled), `ping` (HTTPS callable RTT probe).
- **Data flow invariant**: clients *write* to `choices`/`timeoutRequests` subcollections; only
  functions *update* the match document, which clients read (embedded `rounds[]`). Subcollections
  are not a second source of truth — `applyPlayerChoice` merges pending choice docs before
  resolving timeouts.
- Firestore paths: `users/{uid}`, `queue/{uid}` (client writes + 30s heartbeat), `matches/{id}`
  (function-written, client-read), `matches/{id}/rounds/{n}/choices/{uid}`,
  `matches/{id}/rounds/{n}/timeoutRequests/{id}`, `intel/matches/items/{id}` (bot sync).
  Security rules: `firestore.rules`.

### AI bot (`ai/src/`)
History-based player (no LLM) that mirrors the client's matchmaking flow using the same callables
(`joinMatchmakingQueue`, `confirmMatchReady`, `submitMatchMove`, plus `pullIntelMatches` for
population-history sync):
- `player/PlayerAgent.ts` — queue, match listener, move selection.
- `intel/` — scenario trees (decay-weighted throw counts), Firebase intel sync, blend-weight
  optimization (global/personal/recent/h2h, grid-searched and frozen per match), EV-based counter-pick logic.
- `db/matchDatabase.ts` — SQLite store (`matches`/`rounds`/`match_descriptions` for the bot's own
  games, `intel_matches`/`intel_rounds`/`scenario_nodes`/`blend_weights_cache`/`round_timings` for synced data).
- `firebase/` — client SDK + callable wrappers. `analysis/`, `narrative/`, `log/` — supporting tools.

## Conventions

- **Game rules are data, not constants**: timing and BO3/BO5/BO10 format definitions live only in
  `shared/game-rules.json`; both the app (via generated Kotlin) and functions read from it. Don't
  hardcode these values elsewhere.
- **Localization** (`docs/LOCALIZATION.md`): translate every user-facing string key into all locale
  `strings.xml` files; never translate `app_name` (must stay `RPS Online`); preserve placeholders
  (`%1$s`, `%2$d`); don't translate compact gameplay tokens `W`/`L`/`D`/`vs`; the app always runs in
  English at runtime regardless of translated resources (see `AppLocale`).
- Debug builds skip update checks; release builds need a signing keystore (CI uses repo secrets).
