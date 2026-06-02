# RPS self-hosted AI player

Headless Node process that plays RPS Online like the Android client: matchmaking queue, lobby ready, and round moves via Firebase Auth + Cloud Functions (with Firestore fallbacks).

It keeps a **local SQLite match database** (no Firestore reads for history), runs **bounded queries** (head-to-head + recent games), and sends those results to a **self-hosted LLM** (Ollama, vLLM, etc.) to pick moves and write a **one-line match description** after each game.

## Prerequisites

1. Firebase project with deployed functions (`europe-west1`) and Firestore rules.
2. **Email/password** sign-in enabled in Firebase Auth.
3. A dedicated bot user (create in Auth console or sign up once).
4. Web API key and project id from Firebase / `google-services.json`.
5. A **local OpenAI-compatible LLM server** (see below).

## Self-hosted LLM (Ollama + Gemma)

The bot talks to any server that implements `POST /v1/chat/completions` (Ollama, vLLM, LocalAI, LM Studio, etc.). No Google cloud API key.

**Example with Ollama:**

```bash
# Install: https://ollama.com
ollama pull gemma3:12b
ollama serve   # listens on http://127.0.0.1:11434
```

In `ai/.env`:

```bash
LLM_BASE_URL=http://127.0.0.1:11434/v1
LLM_MODEL=gemma3:12b
```

Use `ollama list` to see exact model names on your machine (`gemma3:4b` for a lighter GPU, etc.).

## Setup

```bash
cd ai
cp .env.example .env
# fill FIREBASE_* and BOT_* values
npm install
npm run build
npm run start
```

On startup the bot probes `GET {LLM_BASE_URL}/models` and **exits** if the LLM is unreachable. Moves and descriptions require a working model — no offline fallback.

## What it does

| Concern | Behavior |
|--------|----------|
| **Queue** | `joinMatchmakingQueue` (or direct `queue/{uid}` write); heartbeat every `BOT_QUEUE_INTERVAL_MS` (default 30s). |
| **Lobby** | `confirmMatchReady` when paired. |
| **Moves** | One pick at a time. Minified one-line JSON prompt (2 H2H games, no recent-list); `max_tokens=16`, low temperature. Use `gemma3:4b`. |
| **Database** | `MATCH_DB_PATH` (default `data/matches.db`) — SQLite `matches` + `match_descriptions` tables; indexed by player and activity time. |
| **Description** | LLM one-liner using the same query bundle; stored in `match_descriptions`. |
| **LLM logs** | `[llm:<tag>:req]` first 8 lines of system + user prompts; `[llm:<tag>:res]` reply + ms. Full body if `LLM_LOG_PROMPT_BODY=true`. |

## SQLite schema (`MATCH_DB_PATH`)

Single file (WAL, foreign keys). Normalized tables — no JSON blob.

**`matches`** — one row per concluded game

| Column | Notes |
|--------|--------|
| `id` | Primary key |
| `player1`, `player2`, `player1_name`, `player2_name` | Participants |
| `pair_key` | `uidA\|uidB` sorted — head-to-head index |
| `match_mode`, `status`, wins, `winner_id`, `resolution` | Series outcome |
| `player1_elo_delta`, `player2_elo_delta` | Optional |
| `created_at`, `last_activity_at`, `saved_at` | Unix ms |

Indexes: `(pair_key, last_activity_at)`, `(player1, last_activity_at)`, `(player2, last_activity_at)`.

**`rounds`** — one row per round (`match_id`, `round_number` PK)

| Column | Notes |
|--------|--------|
| `player1_choice`, `player2_choice` | `ROCK` / `PAPER` / `SCISSORS` when both submitted |
| `winner_id`, `resolved_at` | Round result |

**`match_descriptions`** — LLM recap (`match_id` → `matches.id`)

**`round_timings`** — per bot move: `context_ms`, `pick_ms`, `submit_ms`, `total_ms`, `choice`, `ok` (PK: `match_id` + `round_number`). LLM latency is only in logs, not a separate table.

Prompts use **summaries** (bot/opponent moves per round, scores, description) — not full Firestore-shaped match docs. Queries: H2H via `pair_key` (15), recent bot games (10).

## Running alongside humans

The bot is a normal Firebase user. Matchmaking pairs it with anyone in the same ELO band and shared BO3/BO5/BO10 formats. Use a separate test project or a clearly named bot account in production.

## Development

```bash
npm test
```

Integration smoke: run Ollama + the bot + one Android client on shared match modes.

## Troubleshooting move / queue errors

### LLM unreachable

The process exits on startup if the probe fails. Before running the bot:

- Confirm `curl http://127.0.0.1:11434/v1/models` returns JSON.
- Match `LLM_MODEL` to `ollama list`.
- Increase `LLM_TIMEOUT_MS` on slow hardware.

### `Move was not recorded` or duplicate `[move]` lines

The callable can succeed on the server while the client still sees an error. The bot waits for submission flags before retrying, and skips a direct Firestore write if the choice doc already exists.

### `PERMISSION_DENIED` on queue heartbeat

Firestore returns this when the bot tries to **update a queue doc that no longer exists** (matched and server deleted `queue/{uid}`).

1. **Matched but heartbeat still running** — you should see `[queue-heartbeat] queue doc gone, stopping heartbeat`.
2. **App Check enforced** — Firebase Console → App Check → Firestore = **Monitoring**, not Enforced.
3. **Rules not deployed** — `./scripts/deploy-backend.sh`
4. **Bot Auth** — `BOT_EMAIL` / `BOT_PASSWORD` must match Authentication → Users.

## Source layout

| Path | Role |
|------|------|
| `src/player/PlayerAgent.ts` | Main loop: queue, match listener |
| `src/db/matchDatabase.ts` | SQLite store + queries |
| `src/llm/matchContext.ts` | Bounded DB queries for LLM prompts |
| `src/llm/chat.ts` | OpenAI-compatible HTTP client (system + user messages) |
| `src/llm/pickMove.ts` | LLM move selection |
| `src/llm/describeMatch.ts` | LLM match recap |
| `src/firebase/` | Client SDK, callables, Firestore helpers |
