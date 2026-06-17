# Nemesis

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/Gh0s777tt/Nemesis?include_prereleases&sort=semver)](https://github.com/Gh0s777tt/Nemesis/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/Gh0s777tt/Nemesis/build.yml?branch=master)](https://github.com/Gh0s777tt/Nemesis/actions/workflows/build.yml)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.19.3-brightgreen.svg)](https://www.minecraft.net)
[![Protocol](https://img.shields.io/badge/protocol-761-informational.svg)](https://wiki.vg/Protocol)
[![Kotlin](https://img.shields.io/badge/Kotlin-JVM-purple.svg)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net)

**Nemesis** is a from-scratch **Minecraft 1.19.3** (protocol **761**) server core written in **Kotlin**, with no Mojang
server code. It is a revived and heavily extended fork of the dormant [Krypton](https://github.com/KryptonMC/Krypton)
project: the dead build was restored, modernized to JDK 21, and then grown — feature by feature — into a server that
implements a broad slice of real vanilla gameplay.

> **Nemesis is a standalone server, not a plugin.** You run it directly with `java -jar`; you do **not** drop it into a
> `plugins/` folder. It speaks the vanilla 1.19.3 protocol, so an unmodified Minecraft 1.19.3 client can connect.

> ⚠️ **Status: experimental (alpha).** Nemesis is a hobby/research project. It is not hardened for production and does
> not yet match Paper/Spigot in completeness or performance. Expect rough edges.

---

## Highlights

Every feature below was implemented and then **verified by an autonomous, protocol-level test bot** — a deliberate
methodology that keeps the project honest about what actually works on the wire (see [CONTRIBUTING.md](CONTRIBUTING.md)).

- **Networking & session** — handshake, status, offline login with packet compression, full PLAY state with registry
  sync, chunk streaming, keep-alive. Stable multiplayer sessions.
- **Core gameplay** — surface spawn, server-validated movement (anti-clip collision), block breaking with **survival
  item-entity drops**, block placing, chat, and complete **inventory interactions** (left/right click, shift quick-move,
  double-click collect, drag-distribute).
- **Containers & automation** — chests / barrels / double-chests (9·27·54 slots), dispensers & droppers, **furnace**
  smelting, **hopper** auto-transfer, and **brewing stands** — all ticking, with persistence across restarts.
- **Crafting & progression** — 3×3 crafting grid with shapeless recipes, **XP & levels** (persisted), eating to restore
  hunger.
- **Mobs, AI & combat** — spawn eggs, on-tick movement, combat & death drops (XP orbs + item entities), item pickup,
  **A\*** pathfinding (hostile mobs chase, passive mobs wander), **natural spawning** by time of day, and
  **player-vs-player** combat with knockback, death screen and full respawn.
- **Redstone** — levers, buttons, pressure plates, **wire propagation** (signal strength 0–15), **repeaters**,
  **comparators** (container readout, compare/subtract modes), **pistons** (push/pull full columns), dispensers, lamps,
  doors and trapdoors — with **cross-chunk** signal propagation.
- **World generation** — **full procedural terrain** from a multi-octave noise heightmap (rolling hills, caves, ore
  veins, trees and lakes) across **four biomes** (plains / desert / forest / snow), plus procedural **structures**:
  houses, wells, **villages** (8-house rings with lamp posts, paths and farms), watchtowers, desert wells and ruins. The
  spawn world is itself generated — no external vanilla world is required.
- **Fluids & farming** — flowing **water** with distance-based levels and bucket pickup, **crop growth** on tick, and
  bone meal.
- **Projectiles & combat items** — a real ballistic-physics projectile engine driving **splash potions**, **fishing**
  (with a loot table), and **primed TNT** entities with explosions.
- **Potions & effects** — drinkable potions with per-NBT effect mapping, effect expiry, and splash delivery.
- **Atmosphere** — the **day-night cycle**, **sleeping** in beds (skip to dawn, sleeping pose, bed spawnpoint), and block
  **sounds + particles** (breaking, placing, doors, pistons, levers, buttons, note blocks).
- **Persistence** — world, player data (inventory / position / gamemode), chest contents and XP all survive a restart.

See the full, versioned breakdown in **[CHANGELOG.md](CHANGELOG.md)** and the forward plan in **[ROADMAP.md](ROADMAP.md)**.

---

## Quick start (running a release)

1. Install a **Java 21** runtime ([Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21) recommended).
2. Download the latest `Nemesis-<version>.jar` from the [Releases](https://github.com/Gh0s777tt/Nemesis/releases) page.
3. Run it:

   ```bash
   java -jar Nemesis-0.1.0.jar
   ```

4. Connect from a **Minecraft 1.19.3** client to `localhost` (the listen port is configurable in `config.conf`,
   default `25565`).

On first run the server creates its configuration and world data next to the jar. Configuration lives in `config.conf`.

## Building from source

Requires **JDK 21**. The Gradle wrapper handles everything else.

```bash
# from the project root
./gradlew :jar:shadowJar
```

The runnable, fat (shaded) server jar is produced at `jar/build/libs/Krypton.jar` (~43 MB). Run it exactly as above.

> Note: the internal Gradle project name is still `krypton` (preserved from upstream to avoid churning module
> coordinates). Release artifacts are published as `Nemesis-<version>.jar`.

---

## Project layout

| Path | What it is |
|------|------------|
| `api/` | The public API (events, scheduling, world/entity model). |
| `server/` | The server implementation — networking, packet handlers, world, entities, redstone, generation. |
| `jar/` | The bootstrap module that assembles the runnable shaded jar. |
| `generators/`, `annotation-processor/`, `internal/` | Build-time code generation and internal annotations. |
| `plugins/` | First-party example plugins (bans, whitelist). |
| `STATUS.md` | The detailed engineering journal (development log). |
| `STYLE_GUIDE.md` | Code style conventions. |

---

## Contributing

Contributions are welcome — please read **[CONTRIBUTING.md](CONTRIBUTING.md)** first. In short: build on JDK 21, follow
the [style guide](STYLE_GUIDE.md), verify behavioral changes with a test bot where practical, and **keep the docs current
in the same change** (every feature lands together with its CHANGELOG entry).

Security issues: see **[SECURITY.md](SECURITY.md)**.

## License

Nemesis is licensed under the **GNU Affero General Public License v3.0** — see [LICENSE](LICENSE).

It is a derivative work of [Krypton](https://github.com/KryptonMC/Krypton) (© 2021–2023 KryptonMC and contributors),
which is licensed under the Apache License 2.0. That upstream license is preserved in
[LICENSE-APACHE](LICENSE-APACHE), and the relationship and attribution are documented in [NOTICE](NOTICE). The Apache 2.0
license is one-way compatible with the AGPL v3.0, so the combined work is distributed under the AGPL while retaining all
upstream notices.

## Credits

- **[KryptonMC / Krypton](https://github.com/KryptonMC/Krypton)** and its contributors — the foundation this project is
  built on.
- **[Minestom](https://minestom.net)** — significant parts of the API and backend (scheduling, events, networking) derive
  from Minestom, as in upstream Krypton.
- **[The Minecraft Coalition (wiki.vg)](https://wiki.vg)** and the **[Minecraft Wiki](https://minecraft.wiki)** — protocol
  and game-mechanics documentation that makes a project like this possible.
