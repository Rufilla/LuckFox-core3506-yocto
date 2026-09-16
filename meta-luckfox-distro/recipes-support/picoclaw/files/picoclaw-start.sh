#!/bin/sh
# picoclaw-start: CHANGEME guard + config render, then exec picoclaw gateway.
#
# Secrets are passed in as env vars by systemd (EnvironmentFile=/etc/default/picoclaw).
# This script doesn't read that file directly — systemd reads it as root
# before dropping privileges to User=picoclaw and puts the values in the
# environment. So the env file stays 0600 root:root.
#
# Fails *cleanly* (exit 0) while placeholders are present so a freshly-flashed
# image doesn't spew systemd failures on every boot — same pattern as
# foxbridge-wifi-up.sh.

set -e

CONF_TMPL=/etc/picoclaw/config.json.tmpl
CONF_OUT=/run/picoclaw/config.json

bail() {
    echo "picoclaw: $1" >&2
    echo "picoclaw: see /etc/picoclaw/README.md, then: systemctl restart picoclaw" >&2
    exit 0
}

[ -r "$CONF_TMPL" ] || bail "$CONF_TMPL missing or unreadable"

# 1. Telegram bot token must be set and not a placeholder.
case "${TELEGRAM_BOT_TOKEN:-}" in
    ""|CHANGEME_*)
        bail "TELEGRAM_BOT_TOKEN unset or still CHANGEME_ in /etc/default/picoclaw"
        ;;
esac

# 2. At least one LLM API key must be set and non-placeholder. Concatenate
#    them — if ANY is still CHANGEME_, the joined string contains CHANGEME_.
#    If all are empty, the joined string is empty.
joined="${ANTHROPIC_API_KEY:-}${OPENAI_API_KEY:-}${GOOGLE_API_KEY:-}${DEEPSEEK_API_KEY:-}${GROQ_API_KEY:-}"
case "$joined" in
    "")           bail "no LLM API key set in /etc/default/picoclaw" ;;
    *CHANGEME_*)  bail "an LLM API key in /etc/default/picoclaw still has CHANGEME_ — either set a real key or delete the placeholder line" ;;
esac

# 3. Config template must not still contain CHANGEME_ markers (e.g. if the
#    user pasted another provider's snippet and forgot to fill it in).
if grep -q 'CHANGEME_' "$CONF_TMPL"; then
    bail "$CONF_TMPL still contains CHANGEME_ markers"
fi

# 4. telegram.allow_from must not be the empty array. An open bot on a box
#    that can run shell/edit files is a real exposure, not a theoretical one.
if grep -qE '"allow_from"[[:space:]]*:[[:space:]]*\[[[:space:]]*\]' "$CONF_TMPL"; then
    bail "telegram allow_from is empty — add your Telegram user ID (get it from @userinfobot)"
fi

mkdir -p "$(dirname "$CONF_OUT")"

# Seed AGENTS.md into the workspace on first run only. Ships read-only
# under /usr/share so image updates don't clobber operator edits in
# /var/lib/picoclaw/workspace/AGENTS.md. StateDirectory= creates
# /var/lib/picoclaw; picoclaw itself normally creates the workspace dir,
# but we need it early for the seed copy.
WORKSPACE=/var/lib/picoclaw/workspace
SEED=/usr/share/picoclaw/defaults/AGENTS.md
mkdir -p "$WORKSPACE"
if [ -r "$SEED" ] && [ ! -e "$WORKSPACE/AGENTS.md" ]; then
    cp "$SEED" "$WORKSPACE/AGENTS.md"
fi

# Substitute env vars into the template. Plain POSIX sed — no dependency on
# envsubst/gettext. Each VAR listed here must be populated by systemd from
# /etc/default/picoclaw; unset vars render as empty strings.
sed \
    -e "s|\${TELEGRAM_BOT_TOKEN}|${TELEGRAM_BOT_TOKEN}|g" \
    -e "s|\${ANTHROPIC_API_KEY}|${ANTHROPIC_API_KEY:-}|g" \
    -e "s|\${OPENAI_API_KEY}|${OPENAI_API_KEY:-}|g" \
    -e "s|\${GOOGLE_API_KEY}|${GOOGLE_API_KEY:-}|g" \
    -e "s|\${DEEPSEEK_API_KEY}|${DEEPSEEK_API_KEY:-}|g" \
    -e "s|\${GROQ_API_KEY}|${GROQ_API_KEY:-}|g" \
    "$CONF_TMPL" > "$CONF_OUT"

exec /usr/bin/picoclaw gateway
