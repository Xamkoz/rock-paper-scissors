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
# Optional: up to three models — warmed at startup, ranked by match win rate + ELO delta, best becomes active
# LLM_MODELS=gemma3:4b,qwen2.5:3b,llama3.2:3b
```

Use `ollama list` to see exact model names on your machine (`gemma3:4b` for a lighter GPU, etc.). With `LLM_MODELS`, startup logs `[bot-start:llm-models]` with rank, historical ok%, and warmup latency per model.

## Setup

```bash
cd ai
cp .env.example .env
# fill FIREBASE_* and BOT_* values
npm install
npm run build
npm run start
```

From the **repo root** (after `npm install` in `ai/` once):

```bash
npm start
```

This runs `build` + `start` in `ai/`. Pull missing Ollama models: `npm run pull-models` (root) or `npm run pull-models` in `ai/`.

On startup the bot probes `GET {LLM_BASE_URL}/models`, opens SQLite, warms each configured model (see `LLM_MODELS`), ranks them by **match win rate and average ELO change** using archived rows (`matches.bot_uid`, `round_timings.llm_model`), sets the winner as the active model, logs intel leaderboards from SQLite, then signs into Firebase. Post-start **exits** if the active model fails warmup chat. No offline fallback for moves.

## What it does

| Concern | Behavior |
|--------|----------|
| **Queue** | `joinMatchmakingQueue` (or direct `queue/{uid}` write); **30s queue doc heartbeat + `touchPresence`** while waiting. Transient `ECONNRESET` / `functions/internal` errors are retried; the bot re-joins after ~15s if the queue session drops while `BOT_AUTO_QUEUE=true`. |
| **Match** | **30s `touchPresence`** for lobby and active play (no queue doc updates). Heartbeats stop when the match ends. |
| **Re-queue** | After a game ends, waits `BOT_REQUEUE_DELAY_MS` (default 60000 = 60s, **milliseconds**) then re-queues. Boot log shows `requeueDelay=…ms`. |
| **Lobby** | `confirmMatchReady` when paired. |
| **Moves** | JSON: `choice`, `reason`, `intelSource`, `intelSignal` (e.g. `h2h` + `transitions`). Prompt includes `intelCatalog` per source. Stored in `round_timings.pick_*`. |
| **LLM speed** | Default `gemma3:4b`, `LLM_PICK_MAX_TOKENS=96`, deterministic tactics (`LLM_TACTICS_USE_LLM=false` skips round-1 tactics LLM). Set `LLM_TACTICS_USE_LLM=true` for prose plans. |
| **Database** | `MATCH_DB_PATH` (default `data/matches.db`) — SQLite `matches` + `match_descriptions` tables; indexed by player and activity time. |
| **Description** | Short recap (~36 words, up to 2 sentences); stored in `match_descriptions`. Tune with `LLM_DESCRIBE_MAX_*`. |
| **LLM logs** | `[llm:<tag>:req]` full system + user prompts; `[llm:<tag>:res]` reply + ms. Preview only if `LLM_LOG_PROMPT_PREVIEW=true`; cap with `LLM_LOG_MAX_CHARS`. |

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

**`round_timings`** — per bot move: `context_ms`, `pick_ms`, `submit_ms`, `total_ms`, `choice`, `ok`, `llm_model`, `pick_*` citations (PK: `match_id` + `round_number`). `llm_model` is rolled up per match (mode of ok picks) for LLM ranking vs `matches` ELO deltas.

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
| `src/llm/prepareTactics.ts` | Pre-match tactical plan (round 1, before first pick) |
| `src/llm/tacticalIntelTracking.ts` | Score which intel source predicted opponent lean best; SQLite + `[tactics-score]` logs |
| `src/llm/pickMove.ts` | LLM move selection |
| `src/llm/describeMatch.ts` | LLM match recap |
| `src/firebase/` | Client SDK, callables, Firestore helpers |
