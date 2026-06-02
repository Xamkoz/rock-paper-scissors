# RPS Online

Online rock–paper–scissors for Android. Match with another player in real time, play **best of 3**, **5**, or **10** series with match clocks, climb an ELO leaderboard, and review match history on player profiles.

## UI showcase

| Home — matchmaking | Active match |
|:---:|:---:|
| ![Home screen while in queue](docs/screenshots/home-queue.png) | ![Live match with round clocks and score](docs/screenshots/game-active.png) |
| **Leaderboard** | **Match result** |
| ![Ranked players with online highlights](docs/screenshots/leaderboard.png) | ![Post-match recap and rating change](docs/screenshots/match-result.png) |

## How it works

- **Matchmaking** — on Home, select any combination of **Best of 3**, **Best of 5**, and **Best of 10** (saved for next time). Queue until an opponent is found; pairing requires at least one shared format (chosen at random) and prefers players within **±300 ELO**.
- **Rounds** — each player picks rock, paper, or scissors before the **60s** round deadline. Same move is a draw and the round is replayed with no point awarded. A short pre-game countdown runs before the first round.
- **Match formats** — **BO3** (first to 2), **BO5** (first to 3), or **BO10** (first to 6; if still tied 5–5 after 10 rounds, the series is a draw).
- **Match clocks** — each player starts with **50s** of thinking time (up to **90s** via **+5s** per completed round). Running out of match clock forfeits the series (separate from the per-round deadline).
- **Rating** — ELO updates after each completed match.
- **Profiles** — ELO, W/L/D, round and throw stats, a **7-day ELO chart**, and paginated match history (10 per page) with per-round recaps. Your profile lists your recent matches; other players’ profiles list matches you played together. Open profiles from Home, the leaderboard, or the result screen.
- **Leaderboard** — ranked players with win/loss and throw tendencies; tap a row to open their profile.
- **Reconnect** — if you leave mid-match, Home offers **Reconnect to Game** while the match is still active.
- **Sign-in** — Google, email/password, or guest (see [scripts/ENABLE_AUTH.md](scripts/ENABLE_AUTH.md) for Firebase setup).
- **Updates** — release builds can check [GitHub Releases](https://github.com/melkor217/rock-paper-scissors/releases) from Home, install a newer APK in-app, and open release notes from the version footer (debug builds skip update checks).

## Download

Pre-built APKs are attached to [GitHub Releases](https://github.com/melkor217/rock-paper-scissors/releases).

## Project layout

| Path | Purpose |
|------|---------|
| `app/` | Android client (Kotlin, Jetpack Compose) |
| `ai/` | Self-hosted matchmaking bot (Node); docs in [ai/docs/](ai/docs/README.md) |
| `functions/` | Firebase Cloud Functions (match logic, timeouts, ELO) |
| `firestore.rules` | Firestore security rules |
| `firestore.indexes.json` | Composite indexes for queries |
| `docs/` | Architecture notes, UI screenshots, and GitHub Pages assets (e.g. App Links) |
| `shared/` | `game-rules.json` — round timeout, clocks, match formats (used by app + functions) |
| `scripts/` | Deploy, auth, and environment helpers |

See [docs/STRUCTURE.md](docs/STRUCTURE.md) for architecture and Firestore flow; [docs/GAME_RULES.md](docs/GAME_RULES.md) for full gameplay rules; [docs/UI.md](docs/UI.md) for UI conventions; [docs/LOCALIZATION.md](docs/LOCALIZATION.md) for translation rules.

## Build the app (local)

Requirements: **JDK 21**, **Android SDK (API 35)**, and a `google-services.json` from your Firebase project in `app/`.

`JAVA_HOME` is optional: `./gradlew` auto-detects Android Studio’s bundled JBR on macOS/Windows (default install locations) before falling back to `java` on `PATH`.

```bash
./gradlew :app:assembleDebug
```

Release builds need a signing keystore; CI uses repository secrets (see [.github/workflows/android-release.yml](.github/workflows/android-release.yml)). Pushing a version tag on `main` (e.g. `v1.0.0`) publishes a signed release APK.

Optional: [scripts/setup-android-env.sh](scripts/setup-android-env.sh) installs SDK packages for CI-like local builds.

## AI bot (optional)

Self-hosted matchmaking bot — see [ai/docs/README.md](ai/docs/README.md). From repo root after `cd ai && npm install` and configuring `ai/.env`:

```bash
npm start
```

## Backend

Deploy Firestore rules, indexes, and Cloud Functions:

```bash
./scripts/deploy-backend.sh
```

**Deploy order for match-format changes:** deploy **Cloud Functions and Firestore rules before** (or together with) shipping an app build that writes `matchModes` on the queue. Older functions ignore `matchModes` and treat everyone as BO3 until updated.

Edit [shared/game-rules.json](shared/game-rules.json) for timing and match-format constants (app and functions read the same file). After rule changes, redeploy functions and ship an app update.

Run Cloud Functions unit tests:

```bash
cd functions && npm test
```

Manual smoke checklist (two signed-in clients): [scripts/run-integration-checklist.sh](scripts/run-integration-checklist.sh).

## CI (pull requests)

The [Android APK](.github/workflows/android-apk.yml) workflow runs on every push and pull request: debug APK build, `:app:testDebugUnitTest`, and a **14-day** debug APK artifact. The required check name on GitHub is **build**.

| PR source | Behavior |
|-----------|----------|
| Branch on this repo | Uses `GOOGLE_SERVICES_JSON_BASE64` secret when set. |
| Fork | GitHub may require a maintainer to **approve** the workflow run first. Secrets are not exposed to forks; CI uses `app/google-services.json.example` so compile still runs. |

To block merges until CI passes, add a **required status check** for `build` on `main` (Settings → Rules → rulesets → *main*, or branch protection).

## Tests

Android unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Cloud Functions (round resolution, ELO):

```bash
cd functions && npm test
```

## License

MIT — see [LICENSE](LICENSE).
