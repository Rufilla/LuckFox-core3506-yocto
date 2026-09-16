SUMMARY = "PicoClaw — lightweight Go-based AI agent, Telegram-capable"
DESCRIPTION = "Sipeed's Go-based personal AI agent runtime. Talks to the user \
over Telegram (and other chat channels) and calls out to an LLM provider \
(Anthropic / OpenAI / Gemini / DeepSeek / Groq / local Ollama). The systemd \
unit is enabled by default but refuses to start until the operator edits \
/etc/default/picoclaw and /etc/picoclaw/config.json.tmpl to remove the \
CHANGEME_ markers — see /etc/picoclaw/README.md on the target."
HOMEPAGE = "https://github.com/sipeed/picoclaw"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://LICENSE;md5=c28798585657e741b4a33d9960659ac2"

# Upstream warns: "PicoClaw is in early rapid development. There may be
# unresolved security issues. Do not deploy to production before v1.0."
# Current pin: v0.2.6 (2026-04-08). Bump only after reviewing upstream release
# notes, and ALWAYS re-run the SHA256 for the new armv7 tarball.

# -- Why prebuilt binaries, not source build --
# PicoClaw's go.mod declares `go 1.25.9`. poky scarthgap ships Go 1.22.12 in
# recipes-devtools/go/go_1.22.12.bb — three minor versions short. Rather than
# backport a newer Go toolchain (non-trivial: go-crosssdk, go-runtime,
# go-binary-native all need paired bumps, and scarthgap's go.bbclass has
# tracked behavioural changes since 1.22), this recipe consumes the upstream
# goreleaser Linux/armv7 release tarball. Binaries are statically linked
# (static Go, no glibc/ld.so dependency), stripped, and checksummed by
# upstream. Trade-off: no source-build provenance on this layer, but the
# SHA256 is pinned and verifiable against the upstream checksums file.

SRC_URI = " \
    https://github.com/sipeed/picoclaw/releases/download/v${PV}/picoclaw_Linux_armv7.tar.gz;subdir=picoclaw-${PV};name=tarball \
    file://picoclaw.service \
    file://picoclaw-start.sh \
    file://config.json.tmpl \
    file://picoclaw.env \
    file://README \
    file://AGENTS.md \
"
SRC_URI[tarball.sha256sum] = "a666ef8206297e02dceb638159e2a7c2e10bebae504f6e1379737cecc4ff3543"

S = "${WORKDIR}/picoclaw-${PV}"

# armv7hf only — the tarball is Go GOARCH=arm GOARM=7, which needs hardware
# VFP. Cortex-A7 (luckfox-core3506 / armv7athf-neon tune) satisfies that.
COMPATIBLE_HOST = "arm.*-linux.*"
COMPATIBLE_MACHINE = "luckfox-core3506"

# The binaries are pre-stripped, pre-built Go binaries. Skip Yocto's QA
# strip + arch/ldflags checks that assume our toolchain produced them.
INHIBIT_PACKAGE_STRIP = "1"
INSANE_SKIP:${PN} += "already-stripped arch ldflags textrel"

inherit systemd useradd

USERADD_PACKAGES = "${PN}"
USERADD_PARAM:${PN} = "--system --home-dir ${localstatedir}/lib/picoclaw \
                       --no-create-home --shell /sbin/nologin \
                       --user-group picoclaw"

SYSTEMD_SERVICE:${PN} = "picoclaw.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${S}/picoclaw              ${D}${bindir}/picoclaw
    install -m 0755 ${S}/picoclaw-launcher     ${D}${bindir}/picoclaw-launcher
    install -m 0755 ${S}/picoclaw-launcher-tui ${D}${bindir}/picoclaw-launcher-tui

    install -d ${D}${libexecdir}
    install -m 0755 ${WORKDIR}/picoclaw-start.sh ${D}${libexecdir}/picoclaw-start

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${WORKDIR}/picoclaw.service ${D}${systemd_system_unitdir}/picoclaw.service

    install -d ${D}${sysconfdir}/picoclaw
    install -m 0644 ${WORKDIR}/config.json.tmpl ${D}${sysconfdir}/picoclaw/config.json.tmpl
    install -m 0644 ${WORKDIR}/README           ${D}${sysconfdir}/picoclaw/README.md

    install -d ${D}${sysconfdir}/default
    install -m 0600 ${WORKDIR}/picoclaw.env ${D}${sysconfdir}/default/picoclaw

    # AGENTS.md seed — picoclaw-start.sh copies it into the workspace on first
    # start if the operator hasn't already created one. Shipped read-only under
    # /usr/share so image updates refresh the seed without clobbering user edits
    # in /var/lib/picoclaw/workspace/.
    install -d ${D}${datadir}/picoclaw/defaults
    install -m 0644 ${WORKDIR}/AGENTS.md ${D}${datadir}/picoclaw/defaults/AGENTS.md
}

# Note: /var/lib/picoclaw is created by systemd at service start via
# StateDirectory=picoclaw (correct User:Group ownership, mode 0750). No need
# to ship the dir.
# /etc/default/picoclaw is 0600 root:root — systemd reads it as root before
# dropping privileges via User=picoclaw, so the service user never needs
# direct read access.

FILES:${PN} = " \
    ${bindir}/picoclaw \
    ${bindir}/picoclaw-launcher \
    ${bindir}/picoclaw-launcher-tui \
    ${libexecdir}/picoclaw-start \
    ${systemd_system_unitdir}/picoclaw.service \
    ${sysconfdir}/picoclaw \
    ${sysconfdir}/default/picoclaw \
    ${datadir}/picoclaw \
"

# The picoclaw binary makes outbound HTTPS calls (Telegram Bot API, LLM
# provider). ca-certificates gives it a trust root.
RDEPENDS:${PN} = "ca-certificates"

CONFFILES:${PN} = " \
    ${sysconfdir}/picoclaw/config.json.tmpl \
    ${sysconfdir}/default/picoclaw \
"
