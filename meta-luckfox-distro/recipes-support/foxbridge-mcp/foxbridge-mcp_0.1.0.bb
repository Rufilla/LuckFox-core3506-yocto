SUMMARY = "Foxbridge MCP server — safe hardware access for local AI agents"
DESCRIPTION = "A minimal MCP (Model Context Protocol) server exposing LED \
control, GPIO read/write, and read-only system/network info to local AI \
agents such as picoclaw. Runs as an unprivileged system user (fb-mcp) that \
is only a member of the `gpio` and `leds` groups — kernel file ownership on \
/dev/gpiochip* and /sys/class/leds/* enforces the access boundary."
HOMEPAGE = "https://github.com/Rufilla/LuckFox-core3506-yocto"
LICENSE = "MIT"
# The `file://src;subdir=...` fetch places our local `src/` directory *inside*
# the subdir, giving ${S}/src/${GO_IMPORT}/src/... before do_configure runs.
# do_populate_lic fires before do_configure, so the checksum points at the
# pre-flatten path. do_configure:prepend below moves everything up one level
# so go.mod ends up at the module root where go-mod.bbclass expects it.
LIC_FILES_CHKSUM = "file://src/${GO_IMPORT}/src/LICENSE;md5=e3c057987aea00f0d012dc22c9fea253"

# Module path used by Go and by Yocto's go-mod class to place source in
# ${S}/src/${GO_IMPORT}. Not an actual URL — this binary isn't published.
GO_IMPORT = "github.com/rufilla/foxbridge-mcp"

# In-tree sources + vendored deps land under ${WORKDIR}/git/src/${GO_IMPORT}
# so go.bbclass finds them. `subdir=` places the copied tree directly there;
# its contents (go.mod, *.go, vendor/) sit at the module root.
SRC_URI = " \
    file://src;subdir=git/src/${GO_IMPORT} \
    file://foxbridge-mcp.service \
    file://foxbridge-mcp.rules \
"

S = "${WORKDIR}/git"

# armv7hf only — matches the picoclaw recipe's machine constraint.
COMPATIBLE_HOST = "arm.*-linux.*"
COMPATIBLE_MACHINE = "luckfox-core3506"

inherit go-mod useradd systemd

# Force offline/vendor build: all deps are already in vendor/. Prevents any
# accidental network fetch during the bitbake run. GOFLAGS is the standard Go
# env var; oelint's constants db doesn't include it, so suppress the
# mispelled-var lint inline.
# nooelint: oelint.vars.mispell
GOFLAGS = "-mod=vendor"
export GOFLAGS
# GOPROXY=off blocks all module downloads; safe because vendor/ is complete.
export GOPROXY = "off"

# Create the two hardware-access groups and the service user. The user has
# no shell, no home-dir writes — it exists purely to hold the group
# memberships that make /dev/gpiochip* and /sys/class/leds/* accessible.
USERADD_PACKAGES = "${PN}"
GROUPADD_PARAM:${PN} = "--system gpio; --system leds"
USERADD_PARAM:${PN} = "--system --no-create-home --shell /sbin/nologin \
                       --groups gpio,leds --user-group fb-mcp"

SYSTEMD_SERVICE:${PN} = "foxbridge-mcp.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"

do_configure:prepend() {
    # Flatten ${S}/src/${GO_IMPORT}/src/* up to ${S}/src/${GO_IMPORT}/
    # so go.mod, vendor/, and the *.go files land at the module root.
    modroot="${S}/src/${GO_IMPORT}"
    if [ -d "${modroot}/src" ]; then
        (cd "${modroot}/src" && tar -cf - .) | (cd "${modroot}" && tar -xf -)
        rm -rf "${modroot}/src"
    fi
}

do_install:append() {
    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${WORKDIR}/foxbridge-mcp.service \
        ${D}${systemd_system_unitdir}/foxbridge-mcp.service

    # Udev rules: /dev/gpiochipN → root:gpio 0660,
    #             /sys/class/leds/*/{brightness,trigger} → root:leds 0664.
    install -d ${D}${nonarch_base_libdir}/udev/rules.d
    install -m 0644 ${WORKDIR}/foxbridge-mcp.rules \
        ${D}${nonarch_base_libdir}/udev/rules.d/90-foxbridge-mcp.rules
}

FILES:${PN} += "\
    ${systemd_system_unitdir}/foxbridge-mcp.service \
    ${nonarch_base_libdir}/udev/rules.d/90-foxbridge-mcp.rules \
"
