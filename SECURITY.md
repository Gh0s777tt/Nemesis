# Security Policy

## Project status

> ⚠️ **Nemesis is experimental software.** It is a hobby/research project and is **not hardened for production use**.
> Do not expose a Nemesis server to the public internet expecting production-grade security. In particular, the server
> currently runs in **offline mode** (no Mojang/Microsoft authentication and no protocol encryption), so player
> identities are not verified. Run it on a trusted network, or behind a proxy you control.

## Supported versions

Only the latest release line receives fixes.

| Version | Supported |
|---------|-----------|
| 0.1.x   | ✅        |
| < 0.1.0 | ❌        |

## Reporting a vulnerability

**Please do not open a public issue for security vulnerabilities.**

Instead, report privately via GitHub's security advisories:

1. Go to the [Security tab](https://github.com/Gh0s777tt/Nemesis/security/advisories) of the repository.
2. Click **"Report a vulnerability"** and provide:
   - a description of the issue and its impact,
   - steps to reproduce (a minimal proof-of-concept is ideal),
   - affected version(s) and any relevant configuration.

If you cannot use GitHub advisories, open a regular issue that contains **only** a request for a private contact channel
(no vulnerability details).

### What to expect

- An acknowledgement as soon as the report is triaged.
- An assessment of severity and a fix plan for confirmed issues.
- Credit in the release notes once a fix ships, unless you prefer to remain anonymous.

Because this is a volunteer-run project, response times are best-effort. Thank you for helping keep Nemesis safe.

## Scope

In scope: remote crashes, memory/CPU exhaustion reachable from the network, packet-handling vulnerabilities, world/data
corruption, and authentication/authorization flaws in implemented features.

Out of scope: issues that require online mode (not yet implemented), problems inherited from third-party dependencies
that already have an upstream advisory (report those upstream), and anything depending on an attacker who already has
operator/file-system access to the host.
