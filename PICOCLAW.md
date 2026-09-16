# PicoClaw on Foxbridge

PicoClaw is the AI agent shipped in `luckfox-image-minimal`. It talks to the
operator over Telegram (other channels are supported but not enabled by
default), relays to an LLM provider (Anthropic / OpenAI / Gemini / DeepSeek
/ Groq / local Ollama), and — on foxbridge — gets safe hardware access via
the in-tree [`foxbridge-mcp`](meta-luckfox-distro/recipes-support/foxbridge-mcp/)
MCP server.

See [`/etc/picoclaw/README.md`](meta-luckfox-distro/recipes-support/picoclaw/files/README)
on the target for the short operator-facing quick-start; this doc is the
developer-side reference that lives with the layer.

---

## What this is (and isn't)

**OpenClaw** ([openclaw.ai](https://openclaw.ai/)) is a family of
personal AI-assistant agents with a common channel/tool model. The
flagship (Node.js) is desktop-class; a cluster of smaller reimplementations
target constrained hardware:

| Port | Language | Footprint | Notes |
|------|----------|-----------|-------|
| OpenClaw (flagship) | Node.js | desktop | Doesn't fit in 512 MB |
| **PicoClaw** (Sipeed) | **Go** | **<10 MB RAM** | **What we ship on this board** |
| ZeroClaw | Rust | <5 MB RAM, ~3.4 MB binary | Also viable on armv7 |
| espclaw / mimiclaw | ESP32 firmware | MCU-only | Not applicable to RK3506 |
| MeshClaw | — | — | Meshtastic integration, different use case |

We ship **PicoClaw v0.2.6** (sipeed/picoclaw) because it's compact, Go-static
cross-compiles cleanly, and Sipeed ships signed armv7 release tarballs.

Not to be confused with the unrelated 1997 game reimplementation
[pjasicek/OpenClaw](https://github.com/pjasicek/OpenClaw).

---

## Architecture on foxbridge

```
  Telegram Bot API
       ▲
       │
       ▼
  ┌──────────────────────┐     MCP / HTTP     ┌─────────────────────┐
  │ picoclaw             │ ─────────────────▶ │ foxbridge-mcp       │
  │ User=picoclaw        │   127.0.0.1:45521  │ User=fb-mcp         │
  │ Gateway :45520       │       /mcp         │ Groups: gpio, leds  │
  └──────────┬───────────┘                    └──────────┬──────────┘
             │                                           │
             ▼                                           ▼
    LLM provider API                         /dev/gpiochip* (root:gpio 0660)
    (Anthropic / OpenAI / ...)               /sys/class/leds/*/brightness
                                             /sys/class/leds/*/trigger
                                              (root:leds 0664 via udev)
```

Two independent systemd units, separate users, separate hardening profiles.
Picoclaw keeps its tight namespace lockdown (`ProtectKernelTunables=yes`
etc.) and reaches hardware only through the MCP server, which itself runs
unprivileged with group-based access to sysfs/gpiochar. See
[foxbridge-mcp's design notes](meta-luckfox-distro/recipes-support/foxbridge-mcp/foxbridge-mcp_0.1.0.bb)
for the recipe and `meta-luckfox-distro/recipes-support/foxbridge-mcp/files/src/`
for the Go source.

---

## First-time setup

1. **Telegram bot.** `@BotFather` → `/newbot` → save the token.
2. **Your user ID.** `@userinfobot` → note the numeric ID.
3. **Secrets.** `ssh root@foxbridge.local`, then edit `/etc/default/picoclaw`:
   ```
   TELEGRAM_BOT_TOKEN=<bot token from step 1>
   ANTHROPIC_API_KEY=<your Anthropic key>
   ```
   The file is mode 0600 root:root. Systemd reads it as root and passes
   the values as environment to the picoclaw user; the service user
   itself never has direct read access.
4. **Allowlist.** Edit `/etc/picoclaw/config.json.tmpl` →
   `channels.telegram.allow_from`: change `[]` to `[<your numeric ID>]`.
   `[]` is refused at start-time — a public bot on a box that can write
   files is a real exposure.
5. **Start it.** `systemctl restart picoclaw` — watch with
   `journalctl -u picoclaw -f`.

Secrets are substituted into the rendered config at service start
(`/run/picoclaw/config.json`, tmpfs — never written to eMMC).
Flashing wipes `/etc/default/picoclaw` back to CHANGEME, so steps 3–5
must be redone after every reflash.

## Picking a different LLM provider

1. Uncomment the matching env var in `/etc/default/picoclaw`
   (`OPENAI_API_KEY`, `GOOGLE_API_KEY`, `DEEPSEEK_API_KEY`, `GROQ_API_KEY`).
2. Change the `model_list` entry in `/etc/picoclaw/config.json.tmpl`:
   ```json
   { "model_name": "claude",
     "model": "openai/gpt-4o-mini",
     "api_keys": ["${OPENAI_API_KEY}"] }
   ```
   **The `model` field MUST include a `<vendor>/<model>` prefix**
   (`anthropic/`, `openai/`, `gemini/`, `deepseek/`, `groq/`, `ollama/`).
   Without the prefix, picoclaw silently falls back to the OpenAI
   protocol and routes whatever key you supply to `api.openai.com`,
   producing a confusing `"Incorrect API key provided: sk-ant-..."`
   that looks like Anthropic rejected the key.
3. `systemctl restart picoclaw`.

Full list of supported vendors:
<https://github.com/sipeed/picoclaw/blob/v0.2.6/docs/providers.md>

---

## Hardware access via foxbridge-mcp

Picoclaw's template ships with the MCP server registered:

```json
"mcp": {
  "enabled": true,
  "servers": {
    "foxbridge": {
      "enabled": true,
      "type": "http",
      "url": "http://127.0.0.1:45521/mcp"
    }
  }
}
```

On connect, the 8 MCP tools appear to the agent as
`mcp_foxbridge_<tool>` in addition to picoclaw's built-ins. `tools_count`
goes from 11 → 20 (the 8 MCP tools + one auto-registered discovery shim).

| Agent-visible name | Does |
|---|---|
| `mcp_foxbridge_led_list` / `_get` / `_set` | List/read/write `/sys/class/leds/*` |
| `mcp_foxbridge_gpio_list` / `_read` / `_write` | `/dev/gpiochip*` via Linux chardev ABI |
| `mcp_foxbridge_system_info` | uptime, load, memory, CPU temp, kernel |
| `mcp_foxbridge_network_status` | ifaces, state, addrs, default route |

**Agents should use these over `write_file` on `/sys/...`.** Picoclaw's
`write_file` uses atomic-rename (create `.tmp-<pid>-<ns>`, write, rename
over the target) and sysfs rejects the temp-file creation step —
producing a misleading "permission denied".

---

## Known issues

### Anthropic 429 (rate_limit_error) on multi-iteration turns

**Symptom.** Picoclaw journal shows:

```
LLM call failed error="API request failed:
  Status: 429
  Body:   {"error":{"code":"rate_limit_error",
  "message":"This request would exceed your organization's rate limit
   of 30,000 input tokens per minute..."}}"
```

**Root cause.** The Anthropic tier-1 default ITPM is 30,000 tokens per
minute. A multi-tool turn sends every tool schema (~20 tools), the
system prompt, the accumulated message history, and each tool's returned
content on every iteration. A turn with two `web_search` calls (2–4 KB of
returned text each) plus one `web_fetch` (up to the 4096-byte cap set in
config) reaches 30 k+ input tokens by iteration 3–4. Picoclaw's
`summarize_message_threshold` (default 40) doesn't fire mid-turn, so the
history keeps accumulating until the request is rejected.

**Mitigations** (in order of cost):
1. Lower `summarize_message_threshold` from 40 to ~20 in
   `/etc/picoclaw/config.json.tmpl`; consider dropping `max_tool_iterations`
   from 10 to 4. Takes effect on `systemctl restart picoclaw`; no rebuild.
2. Switch the active model to Haiku (`anthropic/claude-haiku-4-5`) —
   same token budget but the cheaper/faster calls finish within the
   per-minute bucket refill.
3. Upgrade the Anthropic org's usage tier (30 k ITPM on tier 1 → 80 k on
   tier 2 → 200 k+ on tier 3).
4. Enable prompt caching if/when a newer picoclaw surfaces the setting —
   v0.2.6 does not appear to.

### Spurious SIGSEGVs in picoclaw v0.2.6 (upstream bug — non-fatal)

**Symptom.** `dmesg` shows repeating page faults, all at the same PC
with a null-deref at offset 12:

```
picoclaw: unhandled page fault (11) at 0x0000000c, code 0x017
CPU: <n> PID: <pid> Comm: picoclaw
PC is at 0xc79008  LR is at 0x182c40
```

Dense burst of ~12 within ~12 s around a 429, then a steady ~1 per 30 s
matching the heartbeat cadence. Process does NOT exit — Go's internal
signal handler catches the fault, a goroutine panics, recovery kicks in.
`systemctl is-active picoclaw` stays `active`, memory stable at
~34 MB peak.

**Observations.**
- Fault address `0x0000000c` → reading a 32-bit struct field 12 bytes
  into a nil pointer (classic Go nil-deref, e.g. a `*Response.Body`
  before the transport populated it).
- PC in picoclaw's own text segment (not our MCP server, not Go runtime).
- The burst timing alongside the 429 strongly hints the bug is in
  picoclaw's 429/error-response handling path.
- `tools/list` MCP discovery poll runs each 30 s → matches the
  steady-state cadence; could be the same path revisited.

**Mitigations.** None strictly required (service is stable). Options:
1. File upstream once repro is clean — sipeed/picoclaw issues.
2. When a new release ships, bump `PV` in
   `meta-luckfox-distro/recipes-support/picoclaw/picoclaw_0.2.6.bb`, update
   the SHA256 from the release's checksum file, rebuild, reflash.
3. If the crash ever turns fatal (process exits, systemd restart loop),
   add `Restart=on-failure RestartSec=5s StartLimitBurst=3` tuning
   — the unit already has `Restart=on-failure`.

---

## Configuration quick reference

| Setting | Location | Purpose |
|---|---|---|
| `TELEGRAM_BOT_TOKEN`, `ANTHROPIC_API_KEY`, … | `/etc/default/picoclaw` | Secrets (root:root 0600). Not on eMMC when rendered. |
| `channels.telegram.allow_from` | `/etc/picoclaw/config.json.tmpl` | Allowlist of numeric Telegram user IDs. `[]` refuses to start. |
| `agents.defaults.summarize_message_threshold` | same | Mid-conversation summarisation trigger. Lower = less drift. |
| `agents.defaults.max_tool_iterations` | same | Per-turn tool-call cap. Lower = fewer 429 risks. |
| `tools.mcp.servers.foxbridge.url` | same | foxbridge-mcp endpoint. Default `http://127.0.0.1:45521/mcp`. |
| `tools.web.private_host_whitelist` | same (if added) | Allow `web_fetch` to hit loopback/RFC1918. **Adding it de-registers `web_fetch` entirely in 0.2.6** — prefer MCP-exposed tools. |
| `heartbeat.interval` | same | **Minutes, not seconds.** Log says `interval_minutes=N`. |

---

## References

- <https://openclaw.ai/> — original flagship
- <https://github.com/sipeed/picoclaw> — PicoClaw upstream
- <https://github.com/Seeed-Projects/awesome-openclaw-hardware-projects> — ecosystem index
- [`/etc/picoclaw/README.md`](meta-luckfox-distro/recipes-support/picoclaw/files/README) — operator quick-start (on-device)
- [foxbridge-mcp recipe](meta-luckfox-distro/recipes-support/foxbridge-mcp/) — MCP server that gives picoclaw hardware access
- [picoclaw recipe](meta-luckfox-distro/recipes-support/picoclaw/) — Yocto recipe + config template + AGENTS.md seed
