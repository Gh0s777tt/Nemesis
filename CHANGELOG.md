# Changelog

All notable changes to **Nemesis** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Every behavioral change listed under **Added** was verified by an autonomous, protocol-level test bot before being
recorded here. Versions are dated `YYYY-MM-DD`.

## [Unreleased]

### Added
- **Daylight detector toggle** — right-clicking a daylight detector flips its `inverted` blockstate (day-sensing ↔
  night-sensing) on the same block, completing the redstone right-click family (lever, button, note block, repeater,
  comparator). Verified (`mcbot_daylight.py`): a placed detector toggles state `5909 → 5910 → 5909` over two
  right-clicks (on, then back off).
- **Bone meal on the remaining crops** — bone meal now matures beetroot & sweet berry bush (`age` 0..3) and cocoa
  (0..2), not just the wheat-family (`age` 0..7); restricted by block key so non-crops that share the `age` property
  (e.g. frosted ice) are left untouched. Also added the `beetroot_seeds → beetroots` placement override so beetroot
  can be planted. Verified (`mcbot_bonemeal_beetroot.py`): planted beetroots (state `1532`, age 0) jump to `1535`
  (age 3) on bone meal.

---

## [0.7.0] - 2026-06-17

Toolchain modernization to **Kotlin 2.4.0** and **KSP2**. The shaded server jar builds on the new toolchain, boots,
and was bot-verified against a live server (`mcbot_waterlog.py`: login → spawn → place → waterlog all succeeded).

### Changed
- **Kotlin `2.2.21` → `2.4.0`** and **KSP `2.2.21-2.0.5` → `2.3.9`**. KSP has moved to KSP2, which versions
  independently of the Kotlin release (the old `<kotlin>-<ksp>` scheme is gone), so the catalog now tracks a
  standalone KSP version. This supersedes Dependabot PR #12 (which bumped only Kotlin and would have broken KSP
  resolution on its own).
- `BookMeta.pages` no longer carries `@get:JvmName("pages")`; its JVM getter is now `getPages()`. Kotlin callers
  (`meta.pages`) are unaffected. This removes a JVM-signature collision with the `pages()` method `WrittenBookMeta`
  declares to satisfy Adventure's `Book`, which Kotlin 2.4 now rejects as an accidental override.

### Fixed
- Kotlin 2.4 source compatibility in the internal `downcastApiType` helper: removed the `reified I : A` upper bound.
  Kotlin 2.4 inferred `I` to the intersection `I & A` at call sites and refused to reify it. `I` is now pinned by
  each caller's declared return type, keeping the runtime `this is I` check precise.

---

## [0.6.0] - 2026-06-17

More interactions (pumpkin carving, waterlogging, pig saddling), each implemented and bot-verified against a live server.

### Added
- **Pumpkin carving** — right-clicking an uncarved pumpkin with shears turns it into a carved pumpkin and drops pumpkin
  seeds. Verified (`mcbot_pumpkin.py`): a placed pumpkin becomes a carved pumpkin on shear use and drops a seed item.
- **Waterlogging** — a water bucket on a waterloggable block (slab, stairs, fence, …) fills it with water (`WATERLOGGED`)
  and empties the bucket. Verified (`mcbot_waterlog.py`): an oak slab changes state on water-bucket use and the held item
  becomes an empty bucket. Placement now also forces waterloggable blocks to place dry.
- **Saddle a pig** — right-clicking a pig with a saddle makes it rideable (saddle metadata). Verified
  (`mcbot_saddle.py`): after the interact the pig's metadata sets the saddle flag.

---

## [0.5.0] - 2026-06-17

Block interactions (jukebox, lectern, bell), each implemented and bot-verified against a live server.

### Added
- **Jukebox** — right-clicking an empty jukebox with a music disc loads it (`HAS_RECORD`) and plays the disc. Verified
  (`mcbot_jukebox.py`): a placed jukebox changes state on disc insertion and emits the play-recording world event.
- **Lectern** — right-clicking an empty lectern with a writable or written book places the book on it (`HAS_BOOK`).
  Verified (`mcbot_lectern.py`): a placed lectern changes state when a writable book is placed on it.
- **Bell** — right-clicking a bell rings it (plays the bell sound to nearby players). Verified (`mcbot_bell.py`): the
  bot receives a bell Sound Effect at the bell's position after the interaction.

---

## [0.4.0] - 2026-06-17

More block and tool interactions, each implemented and bot-verified against a live server.

### Added
- **Flower pot** — right-clicking an empty flower pot with a pottable plant (flower, sapling, fern, …) fills it with
  that plant. Verified (`mcbot_flowerpot.py`): a placed flower pot becomes a potted poppy (block state `8389 → 16386`).
- **Cake** — right-clicking a cake takes a bite (the `BITES` state climbs 0→6) and the final bite eats it away.
  Verified (`mcbot_cake.py`): seven right-clicks step the cake through its bite states (`3321 → 3327`) then remove it.
- **Repeater delay** — right-clicking a repeater cycles its delay (1→2→3→4→1 ticks). Verified
  (`mcbot_repeaterdelay.py`): four right-clicks step the repeater through four distinct delay states and wrap back.

---

## [0.3.0] - 2026-06-17

Tool and farming interactions, each implemented and bot-verified against a live server.

### Added
- **Bone meal on grass** — right-clicking a grass block with bone meal scatters short grass (and the occasional flower)
  on the air above nearby grass blocks. Verified (`mcbot_bonemealgrass.py`): bone-mealing a placed grass block grows a
  plant block in the air directly above it.
- **Tilling** — right-clicking grass or dirt with a hoe turns it into farmland. Verified (`mcbot_till.py`): a placed
  grass block becomes farmland (block state `9009 → 7854`) on hoe use.
- **Dirt path** — right-clicking grass or dirt with a shovel turns it into a dirt path. Verified (`mcbot_path.py`): a
  placed grass block becomes a dirt path (block state `9009 → 7279`) on shovel use.
- **Stripping logs** — right-clicking a log, wood, stem or hyphae with an axe converts it to its stripped variant
  (keeping the axis); works for every wood type. Verified (`mcbot_strip.py`): a placed oak log becomes a stripped oak
  log (block state `14199 → 21733`) on axe use.

---

## [0.2.0] - 2026-06-17

Gameplay additions, each implemented and bot-verified against a live server.

### Added
- **Composter** — right-clicking a composter with a compostable item raises its fill level (0 → 8) and plays the fill
  effect; right-clicking a full composter yields **bone meal** and empties it back to level 0. Verified
  (`mcbot_composter.py`): the block state climbs exactly one level per compost and resets on harvest, with the held item
  becoming bone meal.
- **Shearing a sheep** — right-clicking a sheep with shears marks it sheared (entity metadata) and drops 1–3 wool of its
  colour. Verified (`mcbot_shear.py`): after the interact the sheep's flags metadata sets the sheared bit (`0x10`) and a
  wool item entity spawns.
- **Dyeing a sheep** — right-clicking a sheep with any dye recolours its wool to that dye's colour. Verified
  (`mcbot_dye.py`): dyeing a white sheep with red dye sets its flags-metadata colour bits to RED (`14`).
- **Cauldron fill/empty** — right-clicking an empty cauldron with a water bucket fills it (→ water cauldron + empty
  bucket); right-clicking a water cauldron with an empty bucket empties it (→ empty cauldron + water bucket). Verified
  (`mcbot_cauldron.py`): the cauldron's block state round-trips empty → full → empty, with the matching bucket swaps.

---

## [0.1.0] - 2026-06-17

First public release. Nemesis revives the dormant [Krypton](https://github.com/KryptonMC/Krypton) project, modernizes
the build to JDK 21, relicenses the combined work under AGPL-3.0, and implements a broad slice of vanilla Minecraft
1.19.3 (protocol 761) gameplay.

### Added

#### Networking & session
- Handshake, status (server list ping), and offline-mode login with packet compression (Set Compression 256).
- Full transition into the PLAY state with registry/Join-Game sync, chunk streaming, and keep-alive — stable sessions.

#### Core gameplay
- Surface spawn and server-validated movement with no rubber-banding.
- Block breaking, including **survival item-entity drops** (`stone → cobblestone`, etc.) picked up by the drop manager;
  creative correctly drops nothing.
- Block placing (now visible to the client via block-update + acknowledgement packets).
- Chat (with broadcast between players).
- Complete **inventory interactions**: left-click move, right-click split/deposit, shift quick-move (main ↔ hotbar),
  double-click collect-by-type, and drag-distribute.
- Server-side **anti-clip collision**: movement into solid blocks is rejected and the client is resynchronized.

#### Multiplayer
- Two or more simultaneous players, tab list (PlayerInfo updates), and cross-player chat.

#### Containers & automation
- Generalized containers — dispensers/droppers (9), chests/barrels (27), and **double chests** (54, via neighbor
  detection) — with open/transfer/close and contents persisted across restarts (per-chunk block-entity NBT, with a
  `world/krypton-chests.dat` fallback).
- **Furnace**: 3-slot smelting on a scheduled tick (ores → ingots, sand → glass, cobblestone → stone), consuming fuel.
- **Hopper**: auto-transfers one item per tick into the container below.
- **Brewing stand**: tick-based brewing transformations.

#### Crafting & progression
- **Crafting table**: live 3×3 grid with shapeless recipes and result consumption on take.
- **XP & levels**: a new experience packet, orb collection and accumulation, persisted to `world/krypton-xp.dat`.
- **Eating** restores hunger (and updates the client health/food bar).

#### Mobs, entities & AI
- **Spawn eggs**, on-tick mob movement, combat (damage → death/despawn), and death drops (XP orbs + real item entities).
- Item pickup within range, item **dropping** (Q / Ctrl+Q), and drop **despawn** after a timeout.
- **A\* pathfinding**: hostile mobs chase the nearest player; passive mobs wander; friendly/hostile behavior split.
- **Natural spawning** around players by time of day, with a population cap.
- **Entity gravity** with acceleration and **fall damage**.
- **Dropped-item physics**: thrown/dispensed items fly with velocity and friction; nearby stacks of the same type merge.
- **Player-vs-player combat**: 4-damage hits with knockback, a death screen, and a full respawn (health/hunger reset,
  chunks resent).

#### Redstone
- Power sources (lever, button with auto-reset, pressure plate) and **wire propagation** (BFS, strength 0–15).
- Receivers: lamps, doors and trapdoors (two-block doors stay in sync), and **pistons** (extend/retract with a head).
- **Pistons push** (and sticky pistons pull) full block columns, vanilla-style.
- **Repeaters** (configurable delay, chainable) and **comparators** (container readout, compare/subtract modes, analog
  output into wire).
- Powered dispensers/droppers fire their contents.
- **Cross-chunk** signal propagation across chunk boundaries.

#### World generation
- **Full procedural terrain** from a multi-octave noise heightmap: rolling hills, **caves** (3D noise), **ore veins**
  (coal / iron), **trees** (natural per-chunk distribution), and **lakes / water** below sea level.
- **Four biomes** — plains, desert (sand, no trees), forest (dense oak), and snow (snow layer + spruce) — with
  biome-appropriate surface, vegetation, and structures.
- Procedural **structures** injected into generation: houses, wells, watchtowers, desert wells, and ruins.
- **Villages**: an 8-house ring around a central well, with lamp posts, cardinal/diagonal paths, and wheat farms.
- The spawn world is itself procedurally generated — no external vanilla world is required.

#### Fluids & farming
- Flowing **water** from a placed source (BFS spread), with distance-based `LEVEL` (0–15) and full bucket pickup of a
  connected body.
- **Crop growth** on tick (`wheat`, `carrots`, `potatoes`), plus **bone meal** for instant maturation.

#### Projectiles, potions & items
- A reusable **ballistic projectile engine** (the project's first real projectile physics).
- **Splash potions**: thrown as flying entities that burst on impact (timer or block collision) and apply effects in a
  radius.
- **Fishing**: rod cast spawns a bobber; reel catches from a **loot table** (cod / salmon / pufferfish / tropical fish).
- **TNT**: flint & steel primes a fuse; **primed TNT** flies as an entity and explodes, clearing a spherical area.
- **Enchanting**: an enchanting table applies NBT enchantments, consuming lapis lazuli.
- **Drinkable potions** with per-NBT effect mapping (swiftness, leaping, strength, regeneration, poison, …) and timed
  effect expiry, delivered via two newly added effect packets.

#### Animals
- **Breeding**: feeding two animals of the same type triggers love mode → a baby + XP orb; babies grow up over time.
- **Milking**: right-clicking a cow with an empty bucket yields a milk bucket.

#### Atmosphere & persistence
- **Day-night cycle**: authoritative server time is broadcast to clients on an interval.
- **Sleeping**: right-clicking a bed skips to dawn, sets the sleeping pose, and sets the player's bed spawnpoint.
- Block **sounds & particles**: breaking, placing, doors/trapdoors, dispenser fire, pistons, levers, buttons, and a fully
  mechanical **note block** (note cycling with correct pitch).
- **Persistence** across forced restarts: world regions, player data (inventory / position / gamemode), chest contents,
  and XP.

### Changed
- **Relicensed** the combined work from Apache-2.0 to **AGPL-3.0** (upstream Apache-2.0 attribution retained — see
  [NOTICE](NOTICE) and [LICENSE-APACHE](LICENSE-APACHE)).
- Modernized the toolchain to **JDK 21**.
- Revived the build by restoring dependencies (formerly served from the offline `repo.kryptonmc.org`) to a local Maven
  repository.

### Fixed
- New players now spawn at the world spawn with the default gamemode (previously `0,0,0` and always survival).
- Statistics serialization creates its parent directory for a fresh world (was `NoSuchFileException`).
- Block placement and player actions now send the 1.19.3-required acknowledgement packets (placement was invisible to
  the client).
- Chat enabled by disabling secure-profile enforcement for offline mode.
- Repaired two A\* pathfinding integration crashes and a redstone comparator recursion (stack overflow) guard.
- Doors are now placed **closed** (some default states had `OPEN=true`).

[Unreleased]: https://github.com/Gh0s777tt/Nemesis/compare/v0.7.0...HEAD
[0.7.0]: https://github.com/Gh0s777tt/Nemesis/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/Gh0s777tt/Nemesis/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/Gh0s777tt/Nemesis/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/Gh0s777tt/Nemesis/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/Gh0s777tt/Nemesis/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/Gh0s777tt/Nemesis/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Gh0s777tt/Nemesis/releases/tag/v0.1.0
