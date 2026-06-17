# Contributing to Nemesis

Thanks for your interest in contributing! Nemesis is a from-scratch Minecraft 1.19.3 server core in Kotlin, revived and
extended from [Krypton](https://github.com/KryptonMC/Krypton). This guide covers how to build it, the standards we hold
changes to, and the one rule that makes this project tick: **every change keeps the docs current**.

**Contents**
- [Prerequisites](#prerequisites)
- [Building & running](#building--running)
- [How we verify changes: test bots](#how-we-verify-changes-test-bots)
- [Keep the docs current](#keep-the-docs-current)
- [Code style](#code-style)
- [Design notes (API & server)](#design-notes-api--server)
- [Commits & pull requests](#commits--pull-requests)
- [Licensing of contributions](#licensing-of-contributions)

## Prerequisites

- **JDK 21** (e.g. [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21)). This is required — the
  project no longer builds on JDK 17.
- **Git**, and some Kotlin familiarity ([Kotlin docs](https://kotlinlang.org/docs/home.html)).
- Gradle is provided via the wrapper (`./gradlew` / `gradlew.bat`) — no separate install needed.

## Building & running

```bash
# Build the runnable fat (shaded) server jar:
./gradlew :jar:shadowJar
# -> jar/build/libs/Krypton.jar  (~43 MB)

# Run it (JDK 21):
java -jar jar/build/libs/Krypton.jar
```

The server listens on the port set in `config.conf` (default `25565`) and creates its world/config next to the jar on
first run. The internal Gradle project name is still `krypton`; released artifacts are named `Nemesis-<version>.jar`.

## How we verify changes: test bots

Nemesis has a strong testing culture: **behavioral features are verified by autonomous, protocol-level test bots**, not
just by eyeballing a client. A bot is a small script that connects as a real client, performs an action (place a block,
fish, throw a potion, power some redstone…), and asserts on the **packets the server sends back** (block updates, entity
spawns, sound effects, slot changes, etc.).

When you add or change behavior:

1. Write or extend a bot that exercises it and asserts on the wire-level result.
2. Run it against a local server and confirm it passes.
3. Mention the bot and what it proves in your PR description.

This keeps claims in the CHANGELOG honest — if it's listed as working, a bot demonstrated it on the protocol.

> Tip: some effects (e.g. client-side rendering) can't be observed over the protocol. Where a result isn't directly
> observable, assert on the closest server-emitted packet (a sound/world-event packet, a state-id change, a log line).

## Keep the docs current

**This is mandatory.** Documentation is part of the change, not a follow-up:

- **`CHANGELOG.md`** — add an entry under `## [Unreleased]` for every user-visible change, in
  [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) style (`Added` / `Changed` / `Fixed` / `Removed`).
- **`README.md`** — update if you add or change a headline feature, a build/run step, or project layout.
- **`ROADMAP.md`** — move an item to "done" (or add a new planned item) when scope shifts.
- **API additions** must be documented (see below). Affected `STATUS.md` notes should be kept accurate too.

A PR that changes behavior without a corresponding docs update is considered incomplete.

## Code style

- Read the **[style guide](STYLE_GUIDE.md)** and match the surrounding code (naming, formatting, idioms).
- The project uses the **Detekt** static analyzer; the build fails if it's violated. Keep changes clean; if a rule
  genuinely needs an exception, use Detekt's `baseline.xml` rather than disabling rules broadly.
- **Documentation:** in the **API**, document everything (public types/functions) — clear and concise, with consistent
  verbs ("Gets…", "Creates a new …", "Checks…"). In the **server**, document anything non-obvious so the next person
  doesn't have to decipher it.

## Design notes (API & server)

- The **API** takes inspiration from Bukkit, Sponge, Velocity and Minestom, and uses [Guice](https://github.com/google/guice)
  for dependency injection — prefer injection over static accessors (except catalogue classes and registries). Design
  APIs to evolve cleanly across Minecraft versions; weigh use cases against maintenance cost.
- The **server** has fewer abstraction constraints than the API, but still: follow the protocol specification carefully,
  don't throw where callers won't expect it, and test thoroughly (see bots above).
- Backwards compatibility is **not** yet a priority for either layer; that comes with a stable API release.

## Commits & pull requests

- Keep commits small and focused — ideally **one logical change per commit** — with a short, descriptive message.
- Open a PR against `master`. Describe **what** changed, **why**, and **how it was verified** (which bot, what it
  asserts). Link any related issue.
- Make sure the project builds (`./gradlew build`) and Detekt passes before requesting review.

## Licensing of contributions

Nemesis is licensed under the **GNU Affero General Public License v3.0** (see [LICENSE](LICENSE)). By submitting a
contribution, you agree that it is licensed under the AGPL-3.0 as part of the project. Do not paste code from
incompatibly licensed sources. Files derived from upstream Krypton remain under their original Apache-2.0 notices — keep
those headers intact (see [NOTICE](NOTICE)).

Thank you for contributing! 🖤
