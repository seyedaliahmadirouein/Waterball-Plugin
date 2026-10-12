# WaterBallonZ

A competitive, round-based, two-team Minecraft minigame plugin built for **Paper 1.21.x** in **Kotlin**.

Players throw custom "Water Balls" (snowball items) at the enemy team to reduce their percentage-based custom HP. Combos increase damage up to a cap. Players regenerate health on a configurable interval, receive additional ammunition each minute, and are eliminated when HP reaches zero. A team wins by eliminating every player on the opposing team (or by having more survivors when the round timer expires).

---

## Table of Contents

1. [Features](#features)
2. [Platform & Versions](#platform--versions)
3. [Building the JAR](#building-the-jar)
4. [Installation](#installation)
5. [First-Time Setup](#first-time-setup)
6. [Commands & Permissions](#commands--permissions)
7. [Configuration](#configuration)
8. [PlaceholderAPI Integration](#placeholderapi-integration)
9. [Statistics](#statistics)
10. [Troubleshooting](#troubleshooting)
11. [Developer Guide](#developer-guide)
12. [Testing](#testing)
13. [Project Structure](#project-structure)
14. [Upgrading](#upgrading)

---

## Features

- **Custom HP system** (0–100%) decoupled from vanilla Minecraft health. Vanilla damage is fully cancelled during a match.
- **Combo damage** — 5 %, 10 %, 15 % (capped) per consecutive enemy hit inside a 3-second window. Tested in `ComboTrackerTest`.
- **Friendly-fire protection** — teammates can never reduce each other's HP and friendly hits never advance the combo.
- **Health regeneration** every 120 s (configurable).
- **Ammunition replenishment** — every minute, every living player gets +1 Water Ball (cap configurable).
- **Custom WaterBall item** identified via `PersistentDataContainer`. Vanilla snowballs never trigger the combat pipeline.
- **Arena state machine**: `DISABLED → WAITING → STARTING → COUNTDOWN → IN_GAME → ENDING → RESETTING`.
- **Lobby system** — global lobby (`/wbz setlobby`) plus per-arena waiting rooms.
- **Team balancing** — always within 1 of each other (Red ≤ Blue + 1 and vice versa).
- **Movement lock during countdown** — cancels move, teleport, sneak, velocity, and food changes.
- **Player state preservation** — inventory, armor, off-hand, gamemode, location, health, food, XP, potion effects, flight, fire ticks, fall distance, air. Restored exactly once even across multiple leave paths.
- **Comprehensive admin commands** with full tab completion and permission filtering.
- **PlaceholderAPI hook** (optional, fails gracefully when absent).
- **SQLite-backed statistics** (matches, wins, losses, draws, eliminations, hits, shots, accuracy, highest combo, damage dealt, red/blue team wins). Writes are async — combat thread is never blocked.
- **Adventure components** for all player-facing text. Legacy `&` color codes are auto-converted to MiniMessage tags.
- **Arena isolation** — every projectile, hit, and statistic is bound to a `matchId` so stale projectiles from a previous round can never affect a new match.
- **Exploit prevention** — block break/place, item drop, item pickup, hand-swap, consume, inventory click/drag of Water Balls.
- **Configurable messages** in `messages.yml`.
- **41 passing unit tests** covering combo damage, custom HP, team balancing, and arena state transitions.

---

## Platform & Versions

| Component             | Version                                |
|-----------------------|----------------------------------------|
| Minecraft             | 1.21.x (tested against 1.21.4)         |
| Paper API             | `1.21.4-R0.1-SNAPSHOT`                 |
| Java                  | 21 (LTS) — required by Paper 1.21      |
| Kotlin                | 2.0.21                                 |
| Gradle                | 8.10.2 (wrapper included)              |
| Adventure             | 4.17.0 (provided by Paper)             |
| PlaceholderAPI        | 2.11.6 (optional, soft-depended)       |
| SQLite JDBC           | 3.46.1.3 (shaded into the JAR)         |
| JUnit 5               | 5.11.3 (test scope only)               |

**Compatibility implications**:
- Java 21 is required on the server host. Paper 1.21 ships with Java 21 bytecode.
- Folia is **not** supported in this release. The `WbzScheduler` is the single point that would need to be re-implemented to add Folia support — see `scheduler/WbzScheduler.kt`.
- The plugin uses only standard Paper API events — no NMS, no packets, no reflection.

---

## Building the JAR

Requirements:
- JDK 21 installed and visible to Gradle (set `JAVA_HOME` or let Gradle auto-detect).
- Internet access for dependency resolution (Paper mc maven, Maven Central).

```bash
git clone https://github.com/seyedaliahmadirouein/Waterball-Plugin.git
cd Waterball-Plugin
./gradlew shadowJar
```

The plugin JAR is written to:

```
build/libs/WaterBallonZ-1.0.0.jar
```

To verify the build with tests:

```bash
./gradlew check
```

---

## Installation

1. Build the JAR (above) or download a release from GitHub Releases.
2. Copy `WaterBallonZ-1.0.0.jar` into your Paper server's `plugins/` directory.
3. Start (or restart) the server. WaterBallonZ will create:
   - `plugins/WaterBallonZ/config.yml`
   - `plugins/WaterBallonZ/messages.yml`
   - `plugins/WaterBallonZ/arenas.yml`
   - `plugins/WaterBallonZ/lobby.yml` (created on first `/wbz setlobby`)
   - `plugins/WaterBallonZ/statistics.db` (SQLite, created on first match end with statistics enabled)
4. Stop the server, edit `config.yml` to taste, then start again.

To install PlaceholderAPI integration (optional):
1. Install [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) on the server.
2. Restart. The placeholder expansion is registered automatically.

---

## First-Time Setup

Open `config.yml` and review the defaults. Then, in-game as an op or with `waterballonz.*`, run:

```
/wbz create waterballonz_1
/wbz setlobby
/wbz setwaitingroom waterballonz_1
/wbz setspawn waterballonz_1 red
/wbz setspawn waterballonz_1 blue
/wbz setspectator waterballonz_1
/wbz setmin waterballonz_1 2
/wbz setmax waterballonz_1 16
/wbz info waterballonz_1
/wbz enable waterballonz_1
/wbz list
```

Players can then `/wbz join waterballonz_1`. Once the minimum player count is reached, the countdown starts automatically.

---

## Commands & Permissions

All commands are accessible via `/waterballonz` or the `/wbz` alias.

### Player commands

| Command                              | Permission                  | Description                                  |
|--------------------------------------|-----------------------------|----------------------------------------------|
| `/wbz help`                           | `waterballonz.play`         | Show help.                                   |
| `/wbz list`                           | `waterballonz.play`         | List all arenas, players, and states.         |
| `/wbz join <arena>`                  | `waterballonz.join`         | Join an arena's waiting room.                 |
| `/wbz leave`                         | `waterballonz.leave`        | Leave your current WaterBallonZ session.     |

### Admin commands

| Command                                          | Permission                          |
|--------------------------------------------------|-------------------------------------|
| `/wbz create <arena>`                            | `waterballonz.admin.arena`          |
| `/wbz delete <arena> confirm`                    | `waterballonz.admin.arena`          |
| `/wbz info <arena>`                              | `waterballonz.admin.arena`          |
| `/wbz enable <arena>`                            | `waterballonz.admin.arena`          |
| `/wbz disable <arena>`                           | `waterballonz.admin.arena`          |
| `/wbz setlobby`                                  | `waterballonz.admin.arena`          |
| `/wbz setwaitingroom <arena>`                    | `waterballonz.admin.arena`          |
| `/wbz setspawn <arena> <red\|blue>`              | `waterballonz.admin.arena`          |
| `/wbz setspectator <arena>`                      | `waterballonz.admin.arena`          |
| `/wbz setmin <arena> <amount>`                   | `waterballonz.admin.arena`          |
| `/wbz setmax <arena> <amount>`                   | `waterballonz.admin.arena`          |
| `/wbz setcountdown <arena> <seconds>`            | `waterballonz.admin.arena`          |
| `/wbz forcestart <arena>`                        | `waterballonz.admin.start`           |
| `/wbz stop <arena>`                              | `waterballonz.admin.stop`            |
| `/wbz reload`                                    | `waterballonz.admin.reload`          |
| `/wbz stats [player]`                            | `waterballonz.admin.stats`           |

### Permission hierarchy

```
waterballonz.* (default: op)
├── waterballonz.play (default: true)
│   ├── waterballonz.join
│   ├── waterballonz.leave
│   └── waterballonz.spectate
├── waterballonz.bypass (default: op)        # Bypass movement restrictions. NEVER bypasses combat validation.
└── waterballonz.admin (default: op)
    ├── waterballonz.admin.arena
    ├── waterballonz.admin.start
    ├── waterballonz.admin.stop
    ├── waterballonz.admin.reload
    └── waterballonz.admin.stats
```

Tab completion is filtered by permission — non-admins never see `create`, `delete`, `forcestart`, etc.

---

## Configuration

Three YAML files are bundled as defaults and copied to `plugins/WaterBallonZ/` on first run:

### `config.yml`

The main settings file. All gameplay and integration values are here. See the inline comments in the file for every key.

Key sections:
- `general.*` — prefix, auto-start, lobby behavior.
- `game.*` — minimum/maximum players, countdown, max duration.
- `health.*` — starting/maximum HP, regeneration rules.
- `waterball.*` — material, display name, lore, custom model data, glint, max held.
- `combat.*` — base/step/max damage, combo window, friendly-fire toggle.
- `effects.*` — sound and particle toggles.
- `hud.*` — action bar / scoreboard toggles.
- `disconnect.*` — eliminate-on-disconnect, reconnect grace.
- `statistics.*` — enabled, backend (`SQLITE` or `NONE`).
- `integrations.*` — PlaceholderAPI on/off.

### `messages.yml`

All player-facing text. Both legacy (`&c`, `&l`) and MiniMessage (`<red>`, `<bold>`) syntaxes are supported in the same string.

### `arenas.yml`

Auto-generated from `/wbz create` and the `set*` commands. **Do not edit by hand while the server is running** — use `/wbz reload` after manual edits.

---

## PlaceholderAPI Integration

When PlaceholderAPI is installed and `integrations.placeholderapi-enabled: true`, the following placeholders are registered:

| Placeholder                       | Description                                  | Returns when not in a match |
|-----------------------------------|----------------------------------------------|------------------------------|
| `%waterballonz_arena%`            | Current arena id                             | `-`                          |
| `%waterballonz_team%`             | `red` or `blue`                              | `-`                          |
| `%waterballonz_hp%`               | Current custom HP (0–100)                    | `-`                          |
| `%waterballonz_ammo%`             | Water Balls in inventory                     | `-`                          |
| `%waterballonz_combo%`            | Current combo count                          | `-`                          |
| `%waterballonz_match_state%`      | `waiting`, `countdown`, `in_game`, etc.      | `-`                          |
| `%waterballonz_time_remaining%`   | e.g. `123s`                                  | `-`                          |
| `%waterballonz_alive_red%`        | Red survivors                                | `-`                          |
| `%waterballonz_alive_blue%`       | Blue survivors                               | `-`                          |
| `%waterballonz_wins%`             | Lifetime wins                                | `0`                          |
| `%waterballonz_losses%`            | Lifetime losses                              | `0`                          |
| `%waterballonz_kills%`            | Eliminations                                 | `0`                          |
| `%waterballonz_hits%`             | Successful enemy hits                        | `0`                          |
| `%waterballonz_shots%`            | Total shots fired                            | `0`                          |

When PlaceholderAPI is **not** installed, the plugin logs an informational message and skips placeholder registration. All other plugin functionality is unaffected.

---

## Statistics

Statistics are **on by default**, using SQLite (`statistics.backend: SQLITE`).

Schema (auto-created):

```sql
CREATE TABLE player_stats (
  player_uuid      TEXT PRIMARY KEY,
  matches_played   INTEGER NOT NULL DEFAULT 0,
  wins              INTEGER NOT NULL DEFAULT 0,
  losses            INTEGER NOT NULL DEFAULT 0,
  draws             INTEGER NOT NULL DEFAULT 0,
  eliminations       INTEGER NOT NULL DEFAULT 0,
  hits              INTEGER NOT NULL DEFAULT 0,
  shots             INTEGER NOT NULL DEFAULT 0,
  highest_combo     INTEGER NOT NULL DEFAULT 0,
  damage_dealt      INTEGER NOT NULL DEFAULT 0,
  red_wins          INTEGER NOT NULL DEFAULT 0,
  blue_wins         INTEGER NOT NULL DEFAULT 0
);
```

All writes are dispatched to a single-writer background executor. Reads (for `/wbz stats` and placeholders) are synchronous and fast (indexed by player_uuid). The statistics subsystem is **idempotent** — `/wbz reload` or a duplicate end-game event cannot duplicate rows.

To disable statistics entirely:
```yaml
statistics:
  enabled: false
```

---

## Troubleshooting

### "Arena has N problem(s)" at startup

Each arena is validated at startup. Common causes:
- `waiting-room location is not set` → run `/wbz setwaitingroom <arena>` while standing where the waiting room should be.
- `red-spawn world 'X' is not loaded` → install the world (e.g. via Multiverse) or remove the arena.
- `maximum-players is below minimum-players` → `/wbz setmax <arena> 16`.

The stored arena configuration is **never** deleted because a world is unavailable — the admin can recover the arena after the world is loaded.

### "Player is already in a WaterBallonZ session"

A player cannot join two arenas simultaneously. Use `/wbz leave` first.

### Stale projectiles

Every projectile is tagged with the match id at launch. When a match ends, all of its projectiles are invalidated in one pass. A projectile from a previous match cannot damage a player in a new match — `CombatService.resolveProjectileHit` checks the match id explicitly.

### Players lose their inventory

The plugin saves a full snapshot when a player joins an arena and restores it (exactly once) when they leave or when the match ends. If the server crashes mid-match, the snapshot is in memory only and will be lost — restart-affected players should be instructed to use `/wbz leave` after the server comes back up.

### Statistics writes are slow

They're not — they're asynchronous on a dedicated single-writer thread. The main server thread is never blocked by JDBC.

### Reload creates duplicate listeners

It doesn't. Listeners are registered exactly once at plugin enable. `/wbz reload` only re-reads the YAML files.

---

## Developer Guide

### Architecture overview

```
WaterBallonZPlugin (lifecycle orchestration)
    └── ServiceRegistry (service locator)
        ├── ConfigManager          → loads config.yml, messages.yml
        ├── MessageService          → Adventure components + legacy color
        ├── WbzScheduler           → wraps Bukkit scheduler (Folia-ready)
        ├── ArenaRepository        → reads/writes arenas.yml
        ├── ArenaValidator         → actionable validation errors
        ├── ArenaService           → public arena API
        ├── LobbyService           → global lobby location
        ├── TeamBalancer           → |red - blue| ≤ 1 invariant
        ├── ComboTracker           → pure-Kotlin combo damage
        ├── GameHealthService      → pure-Kotlin custom HP
        ├── WaterBallItem          → PersistentDataContainer marker
        ├── PlayerSessionService  → per-participant runtime state
        ├── PlayerSnapshotService  → save/restore full player state
        ├── StatisticsService      → SQLite-backed stats (async writes)
        ├── CombatService          → the authoritative hit pipeline
        └── MatchManager           → state machine, countdown, regen, refill
```

### Combat pipeline

```
Projectile Impact
       │
       ▼
Validate Projectile Marker  ←  PersistentDataContainer
       │
       ▼
Resolve Original Shooter     ←  ProjectileInfo (matchId-bound)
       │
       ▼
Resolve Arena and Match      ←  MatchManager.matchOf(arenaId)
       │
       ▼
Validate Match State          ←  must be IN_GAME
       │
       ▼
Validate Shooter Is Alive    ←  Participant.alive
       │
       ▼
Validate Target Is Eligible   ←  Player + alive + same match
       │
       ▼
Check Team Membership        ←  shooter.team == target.team ?
       │                           │
       │   Teammate ────────────►  No Custom Damage, No Combo
       ▼
Calculate Combo Damage       ←  ComboTracker.recordEnemyHit()
       │
       ▼
Apply Custom HP Damage       ←  GameHealthService.applyDamage()
       │
       ▼
Play Effects + Update Stats  ←  async stats writes
       │
       ▼
Check Elimination / Victory  ←  MatchManager.eliminate() / checkWinCondition()
```

### Key design choices

- **Pure-Kotlin combat logic**: `ComboTracker` and `GameHealthService` have zero Bukkit dependencies. They are exhaustively unit-tested.
- **Match-bound projectiles**: every projectile stores its `matchId` in its PDC. Stale projectiles can never leak across matches.
- **Single-writer statistics**: a dedicated `ExecutorService` ensures no contention and never blocks the main thread.
- **Adventure everywhere**: player-facing messages use `Component`. Legacy `&` codes are converted to MiniMessage tags in a single pass.
- **Idempotent snapshots**: `PlayerSnapshotService` tracks a `restored` set so a player cannot receive their items twice even across multiple leave paths.

---

## Testing

Tests live in `src/test/kotlin/`. They cover:

- `ComboTrackerTest` (12 tests) — every combo-damage rule, including the boundary behaviour.
- `GameHealthServiceTest` (10 tests) — HP clamping, regeneration, elimination.
- `TeamBalancerTest` (9 tests) — 1v1, 2v2, 3v2, capacity, removal, no duplicates.
- `ArenaStateTest` (9 tests) — every allowed and disallowed state transition.

Run them:

```bash
./gradlew test
```

Test report: `build/reports/tests/test/index.html`.

---

## Project Structure

```
WaterBallonZ/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/wrapper/
├── README.md
├── LICENSE
├── .gitignore
├── src/
│   ├── main/
│   │   ├── kotlin/com/example/waterballonz/
│   │   │   ├── WaterBallonZPlugin.kt
│   │   │   ├── bootstrap/      # ServiceRegistry
│   │   │   ├── arena/          # ArenaDefinition, ArenaState, ArenaService, ArenaValidator, ArenaBoundary, StoredLocation, repository/
│   │   │   ├── combat/         # ComboTracker, CombatService
│   │   │   ├── commands/       # WaterBallonZCommand, SubCommand, all sub-commands
│   │   │   ├── configuration/  # ConfigManager, GameConfig
│   │   │   ├── health/        # GameHealthService
│   │   │   ├── items/         # WaterBallItem
│   │   │   ├── listeners/     # PlayerLifecycle, Arena, Combat, ExploitPrevention
│   │   │   ├── lobby/          # LobbyService
│   │   │   ├── match/          # Match, MatchManager
│   │   │   ├── messages/      # MessageService
│   │   │   ├── persistence/   # StatisticsService, SqliteStatisticsBackend
│   │   │   ├── placeholders/  # PlaceholderHook (PlaceholderAPI)
│   │   │   ├── player/        # PlayerSession, PlayerSessionService, PlayerSnapshot, PlayerSnapshotService
│   │   │   ├── scheduler/      # WbzScheduler
│   │   │   ├── team/           # Team, TeamBalancer
│   │   │   └── util/           # LegacyColor
│   │   └── resources/
│   │       ├── plugin.yml
│   │       ├── config.yml
│   │       ├── messages.yml
│   │       └── arenas.yml
│   └── test/kotlin/com/example/waterballonz/
│       ├── arena/    # ArenaStateTest
│       ├── combat/   # ComboTrackerTest
│       ├── health/   # GameHealthServiceTest
│       └── team/     # TeamBalancerTest
```

---

## Upgrading

To upgrade the plugin while retaining arena data and statistics:

1. **Back up** `plugins/WaterBallonZ/` (especially `arenas.yml`, `lobby.yml`, `statistics.db`).
2. Stop the server.
3. Replace `plugins/WaterBallonZ.jar` with the new version.
4. Start the server. The plugin will:
   - Auto-migrate the SQLite schema (`ALTER TABLE` for any new columns).
   - Preserve arena definitions and the lobby location.
   - Preserve message customisations only if the new version doesn't introduce new keys (missing keys fall back to the bundled defaults).
5. Run `/wbz reload` to pick up any new config defaults.

If a new config key is added in a release, you can either:
- Delete `config.yml` to regenerate defaults (loses customisations), or
- Manually add the missing key.

---

## License

See [LICENSE](LICENSE).

## Author

seyedaliahmadirouein — [https://github.com/seyedaliahmadirouein/Waterball-Plugin](https://github.com/seyedaliahmadirouein/Waterball-Plugin)
