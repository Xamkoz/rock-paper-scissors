# Game rules

Authoritative gameplay reference for Rock Paper Scissors Online. Timing and format constants live in [`shared/game-rules.json`](../shared/game-rules.json) and are synced to the Android app and Cloud Functions.

## Core gameplay

Each **round**, both players secretly choose **Rock**, **Paper**, or **Scissors**:

| Your move | Beats |
|-----------|-------|
| Rock | Scissors |
| Paper | Rock |
| Scissors | Paper |

- **Same move** → round is a **tie**. Ties do not add to either player’s series score; the match continues.
- Moves are **blind**: each player’s choice is hidden in a private subcollection until both players have submitted (or a timeout resolves the round).

A **match** is a **series** of rounds in one of three formats:

| Format | First to win | Max awarded round wins | Notes |
|--------|--------------|------------------------|-------|
| **BO3** (Best of 3) | 2 rounds | 3 | |
| **BO5** (Best of 5) | 3 rounds | 5 | |
| **BO10** (Best of 10) | 6 rounds | 10 | If both players reach **5** round wins after **10** awarded wins total, the series is a **draw** |

**Awarded round wins** are rounds won by either player (ties excluded). A series ends when:

1. Either player reaches the **first-to** target, or
2. The total **awarded** round wins reaches the format’s **max** — the higher score wins; equal scores are a **draw** (BO10 only in practice at 5–5).

## Round timer

- Each round has a **60 second** deadline to submit a move.
- The UI shows a per-round countdown while you are choosing.

## Match clocks

Each player has a personal **thinking-time clock** for the whole series (similar to chess):

| Setting | Value |
|---------|-------|
| Starting time | **50 seconds** |
| Maximum time | **90 seconds** |
| Bonus per completed round | **+5 seconds** (capped at max) |

How clocks behave:

- Time **counts down** only while **you have not submitted** your move for the current round.
- After you submit, your clock **freezes** until the round is resolved and the next round starts.
- When a round completes normally (both moves in, or a timeout resolution), **both** players receive the increment.

## Timeouts and forfeits

### Round deadline (60 s)

When the round deadline passes:

| Situation | Result |
|-----------|--------|
| One player submitted, the other did not | The player who submitted **wins the entire series** (round recorded as a forfeit win). |
| Neither player submitted | Match is **abandoned** — **no ELO change**, no win/loss/draw recorded. |

### Match clock (runs out of thinking time)

When a player’s match clock hits **0** before they submit for the current round:

| Situation | Result |
|-----------|--------|
| Only one player is out of time | The opponent **wins the entire series**. |
| Both clocks are at 0 | If exactly one player had submitted, that player **wins the series**. If **neither** had submitted, the match is **abandoned** (no ELO change). |

Timeout wins still count toward series score and ELO; a shutout (opponent with **0** round wins) affects ELO (see below).

## Matchmaking

On **Home**, select **at least two** of BO3, BO5, and BO10 (selection is saved locally). When you join the queue:

- Pairing requires **at least one shared format**; one is chosen **uniformly at random** from the overlap.
- Opponents must be within **±300 ELO** of your rating.
- Default rating for new players is **1000 ELO**.
- The client sends a queue **heartbeat every 30 seconds**. Entries with no heartbeat for **90 seconds** are treated as stale and removed.

## Pre-game lobby

After pairing, both players enter a **lobby** before the first round:

- Each player must tap **Ready**.
- There is a **20 second** ready deadline from when the match is created.
- If the deadline passes before both players are ready, the match is **abandoned** (no ELO change).
- When both are ready, the match becomes **active** and round 1 starts.

## ELO rating

ELO updates apply only to **completed** series with a winner or a **draw**. **Abandoned** matches do not change anyone’s rating.

### Base formula

Standard ELO with **K = 32**:

- Expected score uses the 400-point scale: `1 / (1 + 10^((opponentElo - yourElo) / 400))`
- Win → actual score **1**; loss → **0**
- Rating change is rounded to the nearest integer.

### Format weights

The base delta is multiplied by a **format weight**:

| Format | Weight |
|--------|--------|
| BO3 | **25%** (×0.25) |
| BO5 | **40%** (×0.4) |
| BO10 | **90%** (×0.9) |

Longer series move ratings more than short ones.

### Domination bonus (×2)

If the **loser won zero rounds** in the series (a shutout), the format weight is **doubled**.

This applies to any end reason — including normal play, round-timeout forfeits, and clock-timeout wins — as long as the loser’s round-win count is **0**.

Examples at equal 1000 ELO (base win ≈ ±16):

| Outcome | Approx. winner Δ |
|---------|------------------|
| BO3 win, opponent took 1+ rounds | ±4 |
| BO3 shutout (2–0) | ±8 |
| BO5 win, opponent took rounds | ±6 |
| BO5 shutout | ±13 |
| BO10 win, opponent took rounds | ±14 |
| BO10 shutout | ±29 |

### Draws

A drawn series (BO10 at 5–5) records a **draw** on both profiles and changes ELO by **0** for both players.

## Online presence

A player is shown as **online** when their `lastSeen` timestamp is within the last **120 seconds**. The leaderboard and **My Opponents** screens can filter to online players only.

## Stale matches and reconnect

Scheduled cleanup and client logic handle disconnected or inactive sessions:

- **Active** matches with no activity for **90 seconds** may be marked **abandoned** (no ELO change).
- **Lobby** matches past the ready deadline, or inactive for **120 seconds**, are abandoned.
- Stale queue entries are removed on the same **90 second** window.

If you return with a valid in-progress match, the app should restore queue/match state via your profile’s `activeMatchId`.

## Leaderboard visibility

Players appear on the public leaderboard after they have played at least one rated match (win, loss, or draw).

## Source of truth

| Topic | Location |
|-------|----------|
| Timing & format constants | [`shared/game-rules.json`](../shared/game-rules.json) |
| Round/series logic, ELO | [`functions/src/game.ts`](../functions/src/game.ts) |
| Match lifecycle, timeouts | [`functions/src/index.ts`](../functions/src/index.ts) |
| Clock behavior | [`functions/src/clockControl.ts`](../functions/src/clockControl.ts) |
| Queue staleness | [`functions/src/queue.ts`](../functions/src/queue.ts) |

After editing `game-rules.json`, run `./scripts/sync-game-rules.sh`, then redeploy functions and ship an app update.
