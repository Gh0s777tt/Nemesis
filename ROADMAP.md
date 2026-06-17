# Roadmap

This is a living document describing where **Nemesis** is headed. It is a hobby/research project, so priorities and
timing may shift — but this captures the current intent. Each release is cut from `master` once its features are
implemented and **bot-verified** (see [CONTRIBUTING.md](CONTRIBUTING.md)).

Legend: ✅ done · 🚧 in progress · 🔭 planned

---

## ✅ 0.1.0 — Foundation _(released 2026-06-17)_

The revival release. Build restored on JDK 21, relicensed to AGPL-3.0, and a broad slice of vanilla 1.19.3 gameplay
implemented: networking/session, core gameplay & inventory, containers (chests/furnace/hopper/brewing), crafting, XP,
mobs with A* AI + natural spawning, redstone (wire/repeater/comparator/pistons, cross-chunk), full procedural world
generation (terrain/caves/ores/trees/lakes/biomes + structures/villages), fluids, crops, projectiles (potions/fishing/
TNT), enchanting, day-night, sleeping, sounds & particles, PvP, and persistence. See [CHANGELOG.md](CHANGELOG.md).

---

## 🔭 0.2.x — Gameplay polish

Filling gaps in systems that already exist.

- Broadcast status effects to nearby observers (not just the affected player); instant (no-duration) potions.
- Fishing "bite" timer and water requirement; richer loot weighting.
- More crafting recipes (shaped recipes, a broader shapeless set) and smelting/brewing tables.
- Additional mob types and mob-specific behaviors/drops.
- Persist furnace/hopper/brewing progress and double-chest/furnace contents in region NBT.
- Multiple simultaneous container windows; drag in container windows; drop-cursor-on-disconnect.
- Weather: verify and surface the existing rain/thunder cycle to clients.

## 🔭 0.3.x — Physics & combat depth

- Swept collision (anti-tunneling) and accurate block shapes (slabs, stairs, fences).
- Player fall damage; basic anti-float / movement sanity checks; spectator no-clip.
- Knockback/armor/damage-type refinement; weapon and tool semantics.
- Redstone logic-gate circuits and observer/target blocks; larger contraptions.

## 🔭 0.4.x — World & lighting

- A real lighting engine (block + sky light propagation).
- Biome blending and smoother transitions; more terrain features and structures.
- Structure variety and loot containers within generated structures.

## 🔭 0.5.x+ — Platform & ecosystem

- **Online mode**: protocol encryption and Mojang/Microsoft authentication.
- Performance & scale work (networking throughput, chunk pipeline, memory).
- Expand and document the **plugin API**; ship more first-party example plugins.
- Investigate support for additional protocol/game versions.

---

## Aspirational / long-term

- Production-grade stability and a configuration/permissions story suitable for real servers.
- A downloads page and automated, versioned release channels.
- Optional Bukkit/Paper-style compatibility shims (large, uncertain — research only).

---

_Have an idea or want to help with one of these? Open an issue or a discussion. Contributions toward roadmap items are
very welcome — see [CONTRIBUTING.md](CONTRIBUTING.md)._
