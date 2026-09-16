# Ship a board-specific /etc/fw_env.config so fw_printenv / fw_setenv read the
# U-Boot environment from the same eMMC location U-Boot uses (OTA A/B slot state).
# Offsets must match rk3506-env-mmc.config. See OTA_SWUPDATE_PLAN.md.

FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

SRC_URI += "file://fw_env.config"

do_install:append() {
    install -d ${D}${sysconfdir}
    install -m 0644 ${WORKDIR}/fw_env.config ${D}${sysconfdir}/fw_env.config
}

# Package the config with the fw_printenv/fw_setenv binaries so it lands whenever
# libubootenv-bin is installed.
FILES:${PN}-bin += "${sysconfdir}/fw_env.config"
CONFFILES:${PN}-bin += "${sysconfdir}/fw_env.config"
