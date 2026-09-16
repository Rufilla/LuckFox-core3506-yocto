# LuckFox Yocto v1.2.0

**Released:** 2026-04-21
**Target:** LuckFox Core3506-0808 (RK3506B SoC, 512 MB DDR3L, 8 GB eMMC) on Foxbridge Rev A carrier.

Adds on-board AI-agent hardware access: a new MCP server (`foxbridge-mcp`) pairs with the existing PicoClaw agent so the agent can safely read/write LEDs, GPIOs, and system/network state. PicoClaw's recipe is now versioned in-tree alongside the new server.

## What's new since v1.1.0

### foxbridge-mcp — in-tree MCP server for hardware access
- New Go-based MCP Streamable-HTTP server (protocol `2025-03-26`), listens on `127.0.0.1:45521/mcp`.
- Runs as unprivileged `fb-mcp` user with supplementary groups `gpio` + `leds`; kernel file ownership on `/dev/gpiochip*` and `/sys/class/leds/*/{brightness,trigger}` (widened via a shipped udev rule) enforces the access boundary — no capability tricks, no `ProtectKernelTunables` relaxation.
- 8 tools exposed to local MCP clients: `led_list` / `led_get` / `led_set`, `gpio_list` / `gpio_read` / `gpio_write`, `system_info`, `network_status`. Every tool has hardcoded input validation (regex on names, bounds on values).
- Hand-rolled MCP protocol — the official `modelcontextprotocol/go-sdk` requires Go 1.23+ but poky scarthgap ships Go 1.22.12. Only external dep is `github.com/warthog618/go-gpiocdev` (pure-Go GPIO chardev); vendored → offline bitbake build.
- Binary ~7.7 MB, dynamic-PIE. Systemd hardening: `ProtectKernelTunables`, `SystemCallFilter`, `MemoryDenyWriteExecute`, `IPAddressAllow=127.0.0.0/8`.

### PicoClaw recipe moved in-tree
- Previously-untracked `meta-luckfox-distro/recipes-support/picoclaw/` is now versioned at v0.2.6 (upstream armv7 release tarball, SHA256-pinned).
- Config template registers the `foxbridge` MCP server by default.
- `AGENTS.md` seed rewritten to direct the agent at MCP tools instead of sysfs `write_file` (which upstream atomic-rename trips on sysfs).
- CHANGEME-guarded start — operator edits `/etc/default/picoclaw` with real tokens before service comes up.

### Documentation
- New [`PICOCLAW.md`](../../PICOCLAW.md) at repo root: OpenClaw-family context, architecture diagram, first-time setup, provider-swap guide, configuration quick-reference, and a Known-Issues section.
- `README.md` gets a cross-reference line alongside the other top-level docs.

## Known issues

These are captured in full in [`PICOCLAW.md`](../../PICOCLAW.md#known-issues); brief summary:

- **Anthropic 429 rate_limit_error** on multi-iteration tool-heavy turns. Tier-1 ITPM ceiling (30 k/min) is easy to hit with 20 tool schemas + a couple of `web_fetch` results in a 4-iteration turn. Mitigations: lower `summarize_message_threshold`, switch to Haiku, or raise the org's Anthropic usage tier.
- **Spurious SIGSEGV** in picoclaw v0.2.6 on ARM (PC `0xc79008`, null-deref `+0xc`). Kernel logs page faults in `dmesg`; Go's signal handler recovers, process stays active. Looks like an upstream bug in 0.2.6's error-handling path — will be fixed by bumping picoclaw when the next release ships.

## Artifacts

| File | Purpose |
|---|---|
| `luckfox-image-minimal-foxbridge-v1.2.0.wic` | Full eMMC image (~405 MB) |
| `rk3506_spl_loader_v1.2.0.bin` | Combined USB download loader (unchanged from v1.1.0) |
| `SHA256SUMS` | Integrity checksums |

See [FLASHING.md](../../FLASHING.md) for the full flashing procedure.

## Pinned source versions

| Component | Repository | Branch | Commit |
|-----------|-----------|--------|--------|
| rkbin | rockchip-linux/rkbin | master | `74213af1e952c4683d2e35952507133b61394862` |
| Kernel | rockchip-linux/kernel | develop-6.1 | `d2b4477a1df699e6639e83837c7dc45ea1d1d73f` |
| U-Boot | rockchip-linux/u-boot | next-dev | `b14196eade471bbc000c368f8555f2a2a1ecc17d` |
| picoclaw | sipeed/picoclaw | v0.2.6 tarball (armv7) | SHA256 pinned in recipe |
| go-gpiocdev | warthog618/go-gpiocdev | v0.9.1 (vendored) | — |
